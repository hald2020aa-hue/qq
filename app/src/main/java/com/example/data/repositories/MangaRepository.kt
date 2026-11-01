package com.example.data.repositories

import com.example.core.common.DomainNormalizer
import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.database.ChapterEntity
import com.example.core.database.FavoriteEntity
import com.example.core.database.MangaDao
import com.example.core.database.MangaEntity
import com.example.core.database.PageEntity
import com.example.core.database.ReadingHistoryEntity
import com.example.core.database.SourceConfigEntity
import com.example.core.database.SourceEntity
import com.example.core.downloads.DownloadEngine
import com.example.core.storage.LocalChapterStorage
import com.example.core.storage.StorageStats
import com.example.core.verification.VerificationChallengeType
import com.example.core.verification.VerificationDetectionResult
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationRequiredException
import com.example.core.verification.VerificationSession
import com.example.core.verification.VerificationState
import com.example.domain.models.ContentRating
import com.example.domain.models.ContentSafetyClassifier
import com.example.domain.models.DownloadStatus
import com.example.domain.models.DownloadTask
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.SiteInspectionReport
import com.example.domain.models.SourceConfiguration
import com.example.domain.models.SourceEngineType
import com.example.domain.models.SourceFullTestReport
import com.example.domain.models.SourceInfo
import com.example.domain.models.SourceStatus
import com.example.sourceapi.MangaSource
import com.example.sourceapi.SourceManager
import com.example.sourceapi.SourceRegistry
import com.example.sourceapi.SourceValidationStepResult
import com.example.sourceapi.SourceValidator
import com.example.sources.DynamicSiteSource
import com.example.sources.DynamicSourceAdapter
import com.example.sources.SourceAdapterFactory
import com.example.sources.UniversalSourceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Section 6: SourceRepository contract managing source registration, domain validation,
 * and security verification states across [SourceManager], [SourceRegistry], and [VerificationManager].
 */
interface SourceRepository {
    val addedSourcesFlow: Flow<List<SourceInfo>>
    val verificationSessionsFlow: StateFlow<Map<String, VerificationSession>>
    suspend fun ensureDefaultSourcesRegistered()
    suspend fun clearAllSources()
    suspend fun resolveConnector(sourceIdOrDomain: String): MangaSource?
    suspend fun validateSiteDomain(rawInput: String): Result<SourceValidationStepResult>
    fun getSourceVerificationState(sourceIdOrDomain: String): VerificationState
    fun startSourceVerification(sourceId: String, domain: String): VerificationSession
    fun completeSourceVerification(sourceId: String, domain: String, verifiedHtml: String? = null, pageTitle: String = ""): VerificationSession
    fun cancelSourceVerification(sourceId: String, domain: String): VerificationSession
    fun failSourceVerification(sourceId: String, domain: String, reasonAr: String): VerificationSession
}

class MangaRepository(
    private val dao: MangaDao,
    private val registry: SourceRegistry,
    private val validator: SourceValidator,
    private val storage: LocalChapterStorage,
    private val downloadEngine: DownloadEngine,
    val verificationManager: VerificationManager = validator.verificationManager,
    val sourceManager: SourceManager = SourceManager(registry, validator, verificationManager)
) : SourceRepository {

    override val verificationSessionsFlow: StateFlow<Map<String, VerificationSession>> =
        verificationManager.sessionsFlow

    // ─── SOURCES MANAGEMENT ───
    override val addedSourcesFlow: Flow<List<SourceInfo>> = dao.observeSources().map { list ->
        list.map { it.toDomain() }
    }

    /**
     * Fix #1 & Fix #3:
     * Restores saved sources from Room into [SourceRegistry] without duplication, linking known domains
     * to their specialized built-in [MangaSource] adapters (or custom [DynamicSourceAdapter] when configured).
     * When Room is empty and [SourceRegistry] was initialized with built-in sources, seeds those built-in
     * sources into Room so all 6 built-in sources are ready immediately on startup.
     */
    override suspend fun ensureDefaultSourcesRegistered() {
        val existingSources = dao.getAllSourcesOnce()
        val existingConfigs = dao.getAllSourceConfigsOnce().associateBy { it.sourceId }

        if (existingSources.isEmpty()) {
            val preRegistered = registry.getAllRegistered()
            for (src in preRegistered) {
                src.attachVerificationManager(verificationManager)
                val status = if (src.requiresCloudflareVerification && !validator.isDomainHumanVerified(src.domain)) {
                    SourceStatus.REQUIRES_VERIFICATION
                } else {
                    SourceStatus.SUPPORTED
                }
                val engineType = if (src.id == "mangadex") SourceEngineType.REST_API else SourceEngineType.MADARA
                dao.upsertSource(
                    SourceEntity(
                        id = src.id,
                        name = src.name,
                        domain = src.domain,
                        baseUrl = src.baseUrl,
                        language = "ar",
                        iconUrl = null,
                        iconText = src.iconText,
                        iconColorHex = src.iconColorHex,
                        isEnabled = true,
                        isSupported = true,
                        status = status.name,
                        engineType = engineType.name,
                        contentRating = ContentRating.GENERAL.name,
                        version = 1,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
            }
            return
        }

        val seenDomains = mutableSetOf<String>()
        for (dbSource in existingSources) {
            val cleanDomain = dbSource.domain.lowercase().removePrefix("www.")
            seenDomains.add(cleanDomain)

            val savedConfigEntity = existingConfigs[dbSource.id]
            val builtIn = registry.findBuiltInByDomain(dbSource.domain)
                ?: registry.findById(dbSource.id)
                ?: registry.findByDomain(dbSource.domain)

            val connector: MangaSource = when {
                savedConfigEntity != null && savedConfigEntity.toDomain().hasCustomSelectors() -> {
                    SourceAdapterFactory.createFromConfig(savedConfigEntity.toDomain())
                }
                builtIn != null && builtIn !is DynamicSiteSource && builtIn !is DynamicSourceAdapter -> {
                    builtIn
                }
                savedConfigEntity != null -> {
                    SourceAdapterFactory.createFromConfig(savedConfigEntity.toDomain())
                }
                else -> {
                    DynamicSiteSource(
                        domain = dbSource.domain,
                        customId = dbSource.id,
                        customName = dbSource.name,
                        customIconText = dbSource.iconText,
                        customIconColorHex = dbSource.iconColorHex
                    )
                }
            }
            connector.attachVerificationManager(verificationManager)
            registry.register(connector)
        }

        // Ensure any built-in sources registered in SourceRegistry that aren't in Room yet are also persisted
        for (builtIn in registry.getAllRegistered()) {
            val cleanDomain = builtIn.domain.lowercase().removePrefix("www.")
            if (cleanDomain !in seenDomains && dao.getSourceById(builtIn.id) == null) {
                builtIn.attachVerificationManager(verificationManager)
                val engineType = if (builtIn.id == "mangadex") SourceEngineType.REST_API else SourceEngineType.MADARA
                dao.upsertSource(
                    SourceEntity(
                        id = builtIn.id,
                        name = builtIn.name,
                        domain = builtIn.domain,
                        baseUrl = builtIn.baseUrl,
                        language = "ar",
                        iconUrl = null,
                        iconText = builtIn.iconText,
                        iconColorHex = builtIn.iconColorHex,
                        isEnabled = true,
                        isSupported = true,
                        status = SourceStatus.SUPPORTED.name,
                        engineType = engineType.name,
                        contentRating = ContentRating.GENERAL.name,
                        version = 1,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    override suspend fun clearAllSources() {
        dao.clearAllSources()
    }

    override suspend fun resolveConnector(sourceIdOrDomain: String): MangaSource? {
        val savedConfig = dao.getSourceConfigById(sourceIdOrDomain)?.toDomain()
        if (savedConfig != null && savedConfig.hasCustomSelectors()) {
            val customAdapter = SourceAdapterFactory.createFromConfig(savedConfig)
            customAdapter.attachVerificationManager(verificationManager)
            registry.register(customAdapter)
            return customAdapter
        }

        registry.findById(sourceIdOrDomain)?.let {
            it.attachVerificationManager(verificationManager)
            return it
        }
        registry.findByDomain(sourceIdOrDomain)?.let {
            it.attachVerificationManager(verificationManager)
            return it
        }
        registry.findBuiltInByDomain(sourceIdOrDomain)?.let {
            it.attachVerificationManager(verificationManager)
            registry.register(it)
            return it
        }

        val dbSource = dao.getSourceById(sourceIdOrDomain) ?: dao.getSourceByDomain(sourceIdOrDomain)
        if (dbSource != null) {
            val configByDbId = dao.getSourceConfigById(dbSource.id)?.toDomain()
                ?: dao.getSourceConfigByDomain(dbSource.domain)?.toDomain()
            if (configByDbId != null && configByDbId.hasCustomSelectors()) {
                val customAdapter = SourceAdapterFactory.createFromConfig(configByDbId)
                customAdapter.attachVerificationManager(verificationManager)
                registry.register(customAdapter)
                return customAdapter
            }

            registry.findBuiltInByDomain(dbSource.domain)?.let { builtIn ->
                builtIn.attachVerificationManager(verificationManager)
                registry.register(builtIn)
                return builtIn
            }
            registry.findById(dbSource.id)?.let { return it }
            registry.findByDomain(dbSource.domain)?.let { return it }

            val adapter: MangaSource = if (configByDbId != null) {
                SourceAdapterFactory.createFromConfig(configByDbId)
            } else {
                DynamicSiteSource(
                    domain = dbSource.domain,
                    customId = dbSource.id,
                    customName = dbSource.name,
                    customIconText = dbSource.iconText,
                    customIconColorHex = dbSource.iconColorHex
                )
            }
            adapter.attachVerificationManager(verificationManager)
            registry.register(adapter)
            return adapter
        }
        return null
    }

    override suspend fun validateSiteDomain(rawInput: String): Result<SourceValidationStepResult> {
        return validator.validateDomainCompatibility(rawInput)
    }

    override fun getSourceVerificationState(sourceIdOrDomain: String): VerificationState {
        return sourceManager.getVerificationStateForSource(sourceIdOrDomain)
    }

    override fun startSourceVerification(sourceId: String, domain: String): VerificationSession {
        return verificationManager.startVerification(sourceId, domain)
    }

    override fun completeSourceVerification(
        sourceId: String,
        domain: String,
        verifiedHtml: String?,
        pageTitle: String
    ): VerificationSession {
        return verificationManager.completeVerification(
            sourceId = sourceId,
            domain = domain,
            verifiedHtml = verifiedHtml,
            pageTitle = pageTitle
        )
    }

    override fun cancelSourceVerification(sourceId: String, domain: String): VerificationSession {
        return verificationManager.cancelVerification(sourceId, domain)
    }

    override fun failSourceVerification(sourceId: String, domain: String, reasonAr: String): VerificationSession {
        return verificationManager.failVerification(sourceId, domain, reasonAr)
    }

    fun markDomainHumanVerified(domain: String, verifiedHtml: String? = null) {
        validator.markDomainHumanVerified(domain, verifiedHtml)
    }

    fun isDomainHumanVerified(domain: String): Boolean {
        return validator.isDomainHumanVerified(domain)
    }

    /**
     * Fix #2 & Fix #4:
     * - Preserves specialized built-in [MangaSource] adapters even when the user customizes the site name.
     * - Assigns the honest [SourceStatus] from [validationResult] instead of marking every URL as `SUPPORTED`.
     * - Saves [SourceConfigEntity] so custom selectors and engine settings survive restarts.
     */
    suspend fun confirmAndAddSource(
        validationResult: SourceValidationStepResult,
        customSiteName: String,
        overrideRating: ContentRating? = null
    ): Result<SourceInfo> {
        if (ContentSafetyClassifier.isProhibitedIllegalContent(customSiteName, validationResult.normalizedDomain)) {
            return Result.failure(IllegalArgumentException("هذا الموقع مخالف لسياسات المحتوى القانوني ولا يمكن إضافته"))
        }

        val existing = dao.getSourceByDomain(validationResult.normalizedDomain)
        val builtInSource = registry.findBuiltInByDomain(validationResult.normalizedDomain)
            ?: validationResult.matchedSource?.takeIf { it !is DynamicSiteSource && it !is DynamicSourceAdapter }

        val finalName = customSiteName.trim().ifEmpty {
            builtInSource?.name ?: validationResult.suggestedName
        }
        val sourceId = existing?.id ?: builtInSource?.id ?: validationResult.matchedSource?.id
            ?: "dynamic_${validationResult.normalizedDomain.replace('.', '_').replace('-', '_')}"

        val effectiveRating = overrideRating
            ?: validationResult.inspectionReport?.detectedContentRating
            ?: ContentSafetyClassifier.classifySource(validationResult.normalizedDomain, finalName)

        val effectiveEngineType = when {
            builtInSource?.id == "mangadex" -> SourceEngineType.REST_API
            builtInSource != null -> SourceEngineType.MADARA
            validationResult.inspectionReport != null -> validationResult.inspectionReport.detectedEngineType
            else -> SourceEngineType.GENERIC_HTML
        }

        val registeredAdapter: MangaSource = when {
            builtInSource != null -> builtInSource
            validationResult.generatedConfig != null -> {
                val cfg = validationResult.generatedConfig.copy(
                    sourceId = sourceId,
                    sourceName = finalName,
                    contentRating = effectiveRating,
                    engineType = effectiveEngineType
                )
                dao.upsertSourceConfig(cfg.toEntity())
                SourceAdapterFactory.createFromConfig(cfg)
            }
            validationResult.matchedSource != null -> validationResult.matchedSource
            else -> DynamicSiteSource(
                domain = validationResult.normalizedDomain,
                customId = sourceId,
                customName = finalName
            )
        }

        registeredAdapter.attachVerificationManager(verificationManager)
        registry.register(registeredAdapter)

        val honestStatus = when {
            builtInSource != null && !builtInSource.requiresCloudflareVerification -> SourceStatus.SUPPORTED
            builtInSource != null && validator.isDomainHumanVerified(builtInSource.domain) -> SourceStatus.SUPPORTED
            validationResult.status == SourceStatus.UNSUPPORTED -> SourceStatus.NEEDS_CONFIGURATION
            else -> validationResult.status
        }

        val extractedCount = validationResult.inspectionReport?.extractedMangaCount ?: existing?.extractedMangaCount ?: 0
        val extractedChCount = validationResult.inspectionReport?.extractedChaptersCount ?: existing?.extractedChaptersCount ?: 0
        val extractedPgCount = validationResult.inspectionReport?.extractedPagesCount ?: existing?.extractedPagesCount ?: 0

        val entity = SourceEntity(
            id = sourceId,
            name = finalName,
            domain = validationResult.normalizedDomain,
            baseUrl = registeredAdapter.baseUrl,
            language = validationResult.generatedConfig?.language ?: "ar",
            iconUrl = null,
            iconText = registeredAdapter.iconText,
            iconColorHex = registeredAdapter.iconColorHex,
            isEnabled = true,
            isSupported = honestStatus.isUsable,
            status = honestStatus.name,
            engineType = effectiveEngineType.name,
            contentRating = effectiveRating.name,
            extractedMangaCount = extractedCount,
            extractedChaptersCount = extractedChCount,
            extractedPagesCount = extractedPgCount,
            lastError = validationResult.inspectionReport?.errorMessageAr,
            hasCustomConfig = validationResult.generatedConfig != null,
            version = (existing?.version ?: 0) + 1,
            lastCheckedAt = System.currentTimeMillis()
        )
        dao.upsertSource(entity)

        // Pre-populate catalog into local DB if the source is usable
        if (honestStatus in listOf(SourceStatus.SUPPORTED, SourceStatus.PARTIALLY_SUPPORTED)) {
            refreshSourceCatalog(sourceId, 1)
        }
        return Result.success(dao.getSourceById(sourceId)?.toDomain() ?: entity.toDomain())
    }

    // ─── UNIVERSAL SOURCE ENGINE OPERATIONS (Sections 2, 3, 4, 5, 7, 8, 12) ───

    /**
     * Runs the 14-point Site Inspection Engine on any user-provided URL or domain.
     */
    suspend fun inspectSiteUrl(rawUrl: String): Result<SiteInspectionReport> {
        return UniversalSourceEngine.inspectSite(rawUrl, verificationManager)
    }

    /**
     * Runs the 11-stage Source Test Runner on [sourceId], updates the source's status and counts in Room,
     * and returns the full stage-by-stage report.
     */
    suspend fun runFullSourceTest(sourceId: String): Result<SourceFullTestReport> {
        val connector = resolveConnector(sourceId)
            ?: return Result.failure(IllegalStateException("المصدر غير موجود في السجل"))
        val report = UniversalSourceEngine.runFullTest(connector, verificationManager)
        dao.updateSourceDiagnostics(
            sourceId = sourceId,
            status = report.finalStatus.name,
            mangaCount = report.extractedMangaCount,
            chaptersCount = report.extractedChaptersCount,
            pagesCount = report.extractedPagesCount,
            lastError = report.summaryAr,
            checkedAt = System.currentTimeMillis()
        )
        return Result.success(report)
    }

    suspend fun getSourceConfiguration(sourceId: String): SourceConfiguration {
        dao.getSourceConfigById(sourceId)?.let { return it.toDomain() }
        val dbSource = dao.getSourceById(sourceId)
        val connector = resolveConnector(sourceId)
        val domain = dbSource?.domain ?: connector?.domain ?: sourceId
        return SourceAdapterFactory.defaultConfigForEngine(
            domain = domain,
            name = dbSource?.name ?: connector?.name ?: domain,
            engineType = dbSource?.engineType?.let {
                runCatching { SourceEngineType.valueOf(it) }.getOrNull()
            } ?: SourceEngineType.MADARA,
            contentRating = dbSource?.contentRating?.let {
                runCatching { ContentRating.valueOf(it) }.getOrNull()
            } ?: ContentRating.GENERAL
        ).copy(
            sourceId = sourceId,
            baseUrl = dbSource?.baseUrl ?: connector?.baseUrl ?: "https://$domain",
            language = dbSource?.language ?: "ar"
        )
    }

    suspend fun saveSourceConfiguration(
        sourceId: String,
        config: SourceConfiguration
    ): Result<SourceInfo> {
        if (ContentSafetyClassifier.isProhibitedIllegalContent(config.sourceName, config.domain)) {
            return Result.failure(IllegalArgumentException("إعدادات الموقع تحتوي على محتوى محظور قانونياً"))
        }
        val normalizedConfig = config.copy(sourceId = sourceId)
        dao.upsertSourceConfig(normalizedConfig.toEntity())

        val adapter = UniversalSourceEngine.createOrUpgradeAdapter(
            domain = normalizedConfig.domain,
            customId = sourceId,
            customName = normalizedConfig.sourceName,
            config = normalizedConfig,
            builtInFallback = if (normalizedConfig.hasCustomSelectors()) null else registry.findBuiltInByDomain(normalizedConfig.domain),
            verificationManager = verificationManager
        )
        registry.register(adapter)

        val existing = dao.getSourceById(sourceId)
        val updatedEntity = SourceEntity(
            id = sourceId,
            name = normalizedConfig.sourceName,
            domain = normalizedConfig.domain,
            baseUrl = normalizedConfig.baseUrl,
            language = normalizedConfig.language,
            iconUrl = existing?.iconUrl,
            iconText = adapter.iconText,
            iconColorHex = adapter.iconColorHex,
            isEnabled = existing?.isEnabled ?: true,
            isSupported = true,
            status = existing?.status ?: SourceStatus.SUPPORTED.name,
            engineType = normalizedConfig.engineType.name,
            contentRating = normalizedConfig.contentRating.name,
            extractedMangaCount = existing?.extractedMangaCount ?: 0,
            extractedChaptersCount = existing?.extractedChaptersCount ?: 0,
            extractedPagesCount = existing?.extractedPagesCount ?: 0,
            lastError = null,
            hasCustomConfig = true,
            version = (existing?.version ?: 1) + 1,
            lastCheckedAt = System.currentTimeMillis()
        )
        dao.upsertSource(updatedEntity)
        return Result.success(updatedEntity.toDomain())
    }

    suspend fun previewSourceConfiguration(config: SourceConfiguration): Result<List<MangaSummary>> {
        return UniversalSourceEngine.previewConfiguration(config, verificationManager)
    }

    suspend fun exportSourceConfigJson(sourceId: String): String {
        val config = getSourceConfiguration(sourceId)
        return SourceConfiguration.toJsonString(config)
    }

    suspend fun importSourceConfigJson(jsonString: String): Result<SourceInfo> {
        val configRes = SourceConfiguration.fromJsonString(jsonString)
        if (configRes.isFailure) {
            return Result.failure(configRes.exceptionOrNull() ?: IllegalArgumentException("ملف JSON غير صالح"))
        }
        val config = configRes.getOrThrow()
        if (ContentSafetyClassifier.isProhibitedIllegalContent(config.sourceName, config.domain)) {
            return Result.failure(IllegalArgumentException("المصدر المستورد مخالف للسياسات القانونية"))
        }
        return saveSourceConfiguration(config.sourceId, config)
    }

    suspend fun updateSourceMetadata(
        sourceId: String,
        newName: String,
        newBaseUrl: String,
        language: String,
        contentRating: ContentRating,
        engineType: SourceEngineType
    ): Result<SourceInfo> {
        val existing = dao.getSourceById(sourceId)
            ?: return Result.failure(IllegalStateException("المصدر غير موجود"))
        val cleanDomain = DomainNormalizer.normalizeDomain(newBaseUrl, allowHttpUpgrade = true)
            .getOrDefault(existing.domain)

        val currentConfig = getSourceConfiguration(sourceId).copy(
            sourceName = newName.trim().ifEmpty { existing.name },
            domain = cleanDomain,
            baseUrl = newBaseUrl.trim().trimEnd('/').ifEmpty { existing.baseUrl },
            language = language.trim().ifEmpty { "ar" },
            contentRating = contentRating,
            engineType = engineType
        )
        return saveSourceConfiguration(sourceId, currentConfig)
    }

    suspend fun toggleSourceEnabled(sourceId: String, enabled: Boolean) {
        val existing = dao.getSourceById(sourceId)
        val status = if (enabled) {
            existing?.status?.takeIf { it != SourceStatus.DISABLED.name } ?: SourceStatus.SUPPORTED.name
        } else {
            SourceStatus.DISABLED.name
        }
        dao.updateSourceEnabled(sourceId, enabled, status)
    }

    suspend fun removeSource(sourceId: String) {
        dao.deleteSourceConfig(sourceId)
        dao.deleteSource(sourceId)
    }

    // ─── CATALOG & DETAILS ───
    fun observeMangaBySource(sourceId: String): Flow<List<MangaSummary>> {
        return dao.observeMangaBySource(sourceId).map { list ->
            list.map { it.toSummary() }
        }
    }

    suspend fun getMangaBySourceOnce(sourceId: String): List<MangaSummary> {
        return dao.getMangaBySourceOnce(sourceId).map { it.toSummary() }
    }

    val storedMangaFlow: Flow<List<MangaSummary>> = dao.observeStoredManga().map { list ->
        list.map { it.toSummary() }
    }

    val allMangaFlow: Flow<List<MangaSummary>> = dao.observeAllManga().map { list ->
        list.map { it.toSummary() }
    }

    /**
     * Parses and persists manga items captured directly from an authenticated/verified WebView DOM.
     */
    suspend fun ingestVerifiedWebViewHtml(
        sourceId: String,
        html: String,
        baseUrl: String
    ): List<MangaSummary> {
        val connector = resolveConnector(sourceId) ?: return emptyList()
        val sourceEntity = dao.getSourceById(sourceId)
        val sourceRating = sourceEntity?.contentRating?.let {
            runCatching { ContentRating.valueOf(it) }.getOrNull()
        } ?: ContentRating.GENERAL

        val parsed = connector.parseVerifiedHtmlSnapshot(html, baseUrl)
            .filter { it.title.isNotBlank() && it.mangaUrl.isNotBlank() }
            .filterNot { ContentSafetyClassifier.isProhibitedIllegalContent(it.title, it.genres.joinToString(" ")) }
            .map {
                val itemRating = ContentSafetyClassifier.classifyManga(
                    title = it.title,
                    description = "",
                    genres = it.genres,
                    sourceRating = sourceRating
                )
                it.copy(sourceId = sourceId, contentRating = itemRating)
            }
        if (parsed.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val entities = parsed.mapIndexed { idx, summary ->
            val existing = dao.getMangaById(summary.id)
            MangaEntity(
                id = summary.id,
                sourceId = sourceId,
                remoteId = summary.mangaUrl,
                title = summary.title.trim(),
                coverUrl = summary.coverUrl?.takeIf { it.isNotBlank() } ?: existing?.coverUrl,
                description = existing?.description.orEmpty(),
                status = summary.status ?: existing?.status ?: "OnGoing",
                author = existing?.author,
                artist = existing?.artist,
                genresCsv = if (summary.genres.isNotEmpty()) {
                    summary.genres.joinToString(",")
                } else {
                    existing?.genresCsv.orEmpty()
                },
                rating = summary.rating ?: existing?.rating ?: 4.7f,
                latestChapter = summary.latestChapter ?: existing?.latestChapter,
                contentRating = summary.contentRating.name,
                isSavedInStorage = existing?.isSavedInStorage ?: false,
                updatedAt = now - idx * 10L
            )
        }
        dao.upsertMangaList(entities)
        dao.updateSourceDiagnostics(
            sourceId = sourceId,
            status = SourceStatus.SUPPORTED.name,
            mangaCount = entities.size,
            chaptersCount = sourceEntity?.extractedChaptersCount ?: 0,
            pagesCount = sourceEntity?.extractedPagesCount ?: 0,
            lastError = null,
            checkedAt = now
        )
        verificationManager.markRequiresWebViewFallback(sourceId, connector.domain, false)
        return getMangaBySourceOnce(sourceId).ifEmpty { parsed }
    }

    /**
     * Fetches popular/latest summaries for [sourceId] from the registered [MangaSource] connector,
     * logs every pipeline stage via [PipelineLogger], upserts the results into Room, and returns
     * the full list of [MangaSummary] items for immediate ViewModel state update.
     */
    suspend fun refreshSourceCatalog(sourceId: String, page: Int = 1): Result<List<MangaSummary>> {
        val connector = resolveConnector(sourceId)
        if (connector == null) {
            PipelineLogger.log(
                stage = PipelineStage.SOURCE_FOUND_IN_REGISTRY,
                sourceId = sourceId,
                sourceName = sourceId,
                resultCount = 0,
                status = "FAILED",
                failureReason = "Source '$sourceId' not found in SourceRegistry"
            )
            return Result.failure(IllegalStateException("المصدر ($sourceId) غير مسجل في النظام"))
        }

        connector.attachVerificationManager(verificationManager)

        PipelineLogger.log(
            stage = PipelineStage.SOURCE_FOUND_IN_REGISTRY,
            sourceId = sourceId,
            sourceName = connector.name,
            resultCount = 1,
            status = "FOUND",
            details = "connectorId=${connector.id} domain=${connector.domain}"
        )

        if (connector.requiresCloudflareVerification && !validator.isDomainHumanVerified(connector.domain)) {
            val currentVerificationState = verificationManager.getState(connector.domain)
            if (currentVerificationState == VerificationState.NOT_REQUIRED) {
                verificationManager.markVerificationRequired(
                    sourceId = sourceId,
                    domain = connector.domain,
                    challengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                    reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني"
                )
            }
            PipelineLogger.log(
                stage = PipelineStage.GET_POPULAR_CALLED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = 0,
                status = "BLOCKED_CLOUDFLARE",
                failureReason = "SECURITY_VERIFICATION_REQUIRED (${verificationManager.getState(connector.domain)}) for ${connector.domain}"
            )
            return Result.failure(
                VerificationRequiredException(
                    sourceId = sourceId,
                    domain = connector.domain,
                    targetUrl = connector.baseUrl,
                    detection = VerificationDetectionResult(
                        isVerificationRequired = true,
                        challengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                        httpCode = 403,
                        reasonAr = verificationManager.getState(connector.domain).labelAr
                    )
                )
            )
        }

        return try {
            PipelineLogger.log(
                stage = PipelineStage.GET_POPULAR_CALLED,
                sourceId = sourceId,
                sourceName = connector.name,
                status = "CALLED",
                details = "page=$page"
            )

            val sourceEntity = dao.getSourceById(sourceId)
            val sourceRating = sourceEntity?.contentRating?.let {
                runCatching { ContentRating.valueOf(it) }.getOrNull()
            } ?: ContentRating.GENERAL

            val result = connector.getPopular(page)
            val validItems = result.items
                .filter { it.title.isNotBlank() && it.mangaUrl.isNotBlank() }
                .filterNot { ContentSafetyClassifier.isProhibitedIllegalContent(it.title, it.genres.joinToString(" ")) }
                .map {
                    val itemRating = ContentSafetyClassifier.classifyManga(
                        title = it.title,
                        description = "",
                        genres = it.genres,
                        sourceRating = if (it.contentRating != ContentRating.GENERAL) it.contentRating else sourceRating
                    )
                    it.copy(sourceId = sourceId, contentRating = itemRating)
                }

            PipelineLogger.log(
                stage = PipelineStage.MANGA_RESULTS_RECEIVED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = validItems.size,
                status = if (validItems.isNotEmpty()) "SUCCESS" else "EMPTY",
                failureReason = if (validItems.isEmpty()) "getPopular($page) returned 0 valid items" else null
            )

            val now = System.currentTimeMillis()
            val entities = validItems.mapIndexed { idx, summary ->
                val existing = dao.getMangaById(summary.id)
                MangaEntity(
                    id = summary.id,
                    sourceId = sourceId,
                    remoteId = summary.mangaUrl,
                    title = summary.title.trim(),
                    coverUrl = summary.coverUrl?.takeIf { it.isNotBlank() } ?: existing?.coverUrl,
                    description = existing?.description.orEmpty(),
                    status = summary.status ?: existing?.status ?: "OnGoing",
                    author = existing?.author,
                    artist = existing?.artist,
                    genresCsv = if (summary.genres.isNotEmpty()) {
                        summary.genres.joinToString(",")
                    } else {
                        existing?.genresCsv.orEmpty()
                    },
                    rating = summary.rating ?: existing?.rating ?: 4.7f,
                    latestChapter = summary.latestChapter ?: existing?.latestChapter,
                    contentRating = summary.contentRating.name,
                    isSavedInStorage = existing?.isSavedInStorage ?: false,
                    updatedAt = now - idx * 10L
                )
            }

            if (entities.isNotEmpty()) {
                dao.upsertMangaList(entities)
            }

            val allStoredForSource = getMangaBySourceOnce(sourceId)
            val finalItems = if (allStoredForSource.isNotEmpty()) allStoredForSource else validItems

            if (finalItems.isNotEmpty()) {
                dao.updateSourceDiagnostics(
                    sourceId = sourceId,
                    status = SourceStatus.SUPPORTED.name,
                    mangaCount = finalItems.size,
                    chaptersCount = sourceEntity?.extractedChaptersCount ?: 0,
                    pagesCount = sourceEntity?.extractedPagesCount ?: 0,
                    lastError = null,
                    checkedAt = now
                )
            }

            PipelineLogger.log(
                stage = PipelineStage.REPOSITORY_UPDATED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = finalItems.size,
                status = if (finalItems.isNotEmpty()) "UPSERTED" else "EMPTY",
                details = "newlyUpserted=${entities.size} totalInDb=${allStoredForSource.size}"
            )

            if (finalItems.isEmpty()) {
                if (verificationManager.isVerified(connector.domain)) {
                    verificationManager.markRequiresWebViewFallback(sourceId, connector.domain, true)
                    Result.failure(IllegalStateException("NO_CONTENT_AFTER_VERIFICATION"))
                } else {
                    Result.failure(IllegalStateException("لم يُعثر على أي أعمال من المصدر ${connector.name}"))
                }
            } else {
                verificationManager.markRequiresWebViewFallback(sourceId, connector.domain, false)
                Result.success(finalItems)
            }
        } catch (e: VerificationRequiredException) {
            PipelineLogger.log(
                stage = PipelineStage.REPOSITORY_UPDATED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = 0,
                status = "VERIFICATION_REQUIRED",
                failureReason = e.detection.reasonAr,
                throwable = e
            )
            Result.failure(e)
        } catch (e: Exception) {
            val cached = getMangaBySourceOnce(sourceId)
            PipelineLogger.log(
                stage = PipelineStage.REPOSITORY_UPDATED,
                sourceId = sourceId,
                sourceName = connector.name,
                resultCount = cached.size,
                status = if (cached.isNotEmpty()) "CACHED_FALLBACK" else "FAILED",
                failureReason = "${e.javaClass.simpleName}: ${e.message}",
                throwable = e
            )
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun loadMangaDetailsAndSyncChapters(mangaId: String): Result<MangaDetails> {
        val mangaEntity = dao.getMangaById(mangaId)
            ?: return Result.failure(IllegalStateException("لم يتم العثور على المانهوا"))
        val connector = resolveConnector(mangaEntity.sourceId)

        return try {
            if (connector == null) {
                val cachedChapters = dao.getChaptersByMangaOnce(mangaId).map { it.toDomain() }
                return Result.success(mangaEntity.toDetails(cachedChapters))
            }

            val remoteDetails = connector.getDetails(mangaEntity.remoteId)
            val existingRating = runCatching { ContentRating.valueOf(mangaEntity.contentRating) }
                .getOrDefault(ContentRating.GENERAL)
            val resolvedRating = ContentSafetyClassifier.classifyManga(
                title = remoteDetails.title.ifBlank { mangaEntity.title },
                description = remoteDetails.description,
                genres = remoteDetails.genres,
                sourceRating = if (remoteDetails.contentRating != ContentRating.GENERAL) remoteDetails.contentRating else existingRating
            )

            val updatedEntity = mangaEntity.copy(
                title = remoteDetails.title.ifBlank { mangaEntity.title },
                coverUrl = remoteDetails.coverUrl?.takeIf { it.isNotBlank() } ?: mangaEntity.coverUrl,
                description = remoteDetails.description.ifBlank { mangaEntity.description },
                status = remoteDetails.status?.takeIf { it.isNotBlank() } ?: mangaEntity.status,
                author = remoteDetails.author ?: mangaEntity.author,
                artist = remoteDetails.artist ?: mangaEntity.artist,
                genresCsv = if (remoteDetails.genres.isNotEmpty()) {
                    remoteDetails.genres.joinToString(",")
                } else {
                    mangaEntity.genresCsv
                },
                rating = remoteDetails.rating ?: mangaEntity.rating,
                latestChapter = remoteDetails.chapters.maxByOrNull { it.chapterNumber ?: 0f }?.let { "فصل ${it.title}" }
                    ?: mangaEntity.latestChapter,
                contentRating = resolvedRating.name,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertManga(updatedEntity)

            val chapterEntities = remoteDetails.chapters.map { ch ->
                ChapterEntity(
                    id = ch.id,
                    mangaId = mangaId,
                    sourceId = mangaEntity.sourceId,
                    remoteId = ch.remoteId,
                    title = ch.title,
                    chapterNumber = ch.chapterNumber,
                    uploadDate = ch.uploadDate,
                    dateLabel = ch.dateLabel,
                    isRead = ch.isRead,
                    isDownloaded = ch.isDownloaded
                )
            }
            if (chapterEntities.isNotEmpty()) {
                dao.syncChaptersPreservingState(mangaId, chapterEntities)
            }

            val updatedChapters = dao.getChaptersByMangaOnce(mangaId).map { it.toDomain() }
            Result.success(
                remoteDetails.copy(
                    id = mangaId,
                    sourceId = mangaEntity.sourceId,
                    coverUrl = updatedEntity.coverUrl,
                    description = updatedEntity.description,
                    contentRating = resolvedRating,
                    chapters = updatedChapters
                )
            )
        } catch (e: Exception) {
            val cachedChapters = dao.getChaptersByMangaOnce(mangaId).map { it.toDomain() }
            if (cachedChapters.isNotEmpty() || mangaEntity.description.isNotBlank()) {
                Result.success(mangaEntity.toDetails(cachedChapters))
            } else {
                Result.failure(e)
            }
        }
    }

    fun observeChaptersForManga(mangaId: String): Flow<List<MangaChapter>> {
        return dao.observeChaptersByManga(mangaId).map { list ->
            list.map { it.toDomain() }
        }
    }

    fun observeMangaEntity(mangaId: String): Flow<MangaEntity?> = dao.observeMangaById(mangaId)

    // ─── READER & OFFLINE PAGES ───
    suspend fun getReaderPages(chapterId: String): Result<List<MangaPage>> {
        val chapter = dao.getChapterById(chapterId)
            ?: return Result.failure(IllegalStateException("الفصل غير موجود"))
        val manga = dao.getMangaById(chapter.mangaId)
        val connector = resolveConnector(chapter.sourceId)

        // Check offline downloaded files first
        val cachedPages = dao.getPagesForChapter(chapterId)
        if (cachedPages.isNotEmpty() && cachedPages.all { !it.localPath.isNullOrBlank() && File(it.localPath).exists() }) {
            return Result.success(
                cachedPages.map {
                    MangaPage(
                        index = it.pageIndex,
                        imageUrl = it.imageUrl,
                        localPath = it.localPath,
                        chapterTitle = "الفصل ${chapter.title}",
                        dialoguePreviewAr = it.dialoguePreviewAr
                    )
                }
            )
        }

        val remotePages = connector?.getPages(chapter.remoteId).orEmpty()
        if (remotePages.isEmpty()) {
            return Result.failure(IllegalStateException("CHAPTER_EMPTY"))
        }

        val resolvedPages = remotePages.map { p ->
            val fileRes = storage.downloadAndSavePage(
                sourceId = chapter.sourceId,
                mangaId = chapter.mangaId,
                chapterNumber = chapter.chapterNumber,
                pageIndex = p.index,
                imageUrl = p.imageUrl,
                mangaTitle = manga?.title ?: p.chapterTitle,
                chapterTitle = "الفصل ${chapter.title}",
                dialogueAr = p.dialoguePreviewAr,
                refererUrl = connector?.baseUrl
            )
            val path = fileRes.getOrNull()?.absolutePath
            MangaPage(
                index = p.index,
                imageUrl = p.imageUrl,
                localPath = path,
                chapterTitle = "الفصل ${chapter.title}",
                dialoguePreviewAr = p.dialoguePreviewAr
            )
        }

        dao.upsertPages(
            resolvedPages.map { p ->
                PageEntity(
                    id = "${chapterId}_p_${p.index}",
                    chapterId = chapterId,
                    pageIndex = p.index,
                    imageUrl = p.imageUrl,
                    localPath = p.localPath,
                    dialoguePreviewAr = p.dialoguePreviewAr
                )
            }
        )
        return Result.success(resolvedPages)
    }

    suspend fun saveReadingProgress(
        mangaId: String,
        chapterId: String,
        chapterTitle: String,
        pageIndex: Int,
        scrollOffset: Int,
        totalPages: Int
    ) {
        dao.updateChapterReadProgress(
            chapterId = chapterId,
            isRead = true,
            lastPage = pageIndex + 1
        )
        dao.upsertReadingHistory(
            ReadingHistoryEntity(
                id = "hist_$mangaId",
                mangaId = mangaId,
                chapterId = chapterId,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex + 1,
                scrollOffset = scrollOffset,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun observeHistoryForManga(mangaId: String): Flow<ReadingHistoryEntity?> =
        dao.observeHistoryForManga(mangaId)

    val readingHistoryWithMangaFlow: Flow<List<Pair<ReadingHistoryEntity, MangaSummary>>> =
        combine(dao.observeReadingHistory(), dao.observeAllManga()) { histList, mangaList ->
            val byId = mangaList.associateBy { it.id }
            histList.mapNotNull { h ->
                val m = byId[h.mangaId] ?: return@mapNotNull null
                h to m.toSummary()
            }
        }

    suspend fun clearReadingHistory() = dao.clearReadingHistory()

    // ─── FAVORITES ───
    val favoritesWithCategoryFlow: Flow<List<Pair<FavoriteEntity, MangaSummary>>> =
        combine(dao.observeFavorites(), dao.observeAllManga()) { favs, mangaList ->
            val byId = mangaList.associateBy { it.id }
            favs.mapNotNull { f ->
                val m = byId[f.mangaId] ?: return@mapNotNull null
                f to m.toSummary()
            }
        }

    fun observeIsFavorite(mangaId: String): Flow<Boolean> = dao.observeIsFavorite(mangaId)

    suspend fun toggleFavorite(mangaId: String, isCurrentlyFav: Boolean, category: String = "الكل") {
        if (isCurrentlyFav) {
            dao.removeFavorite(mangaId)
        } else {
            dao.addFavorite(FavoriteEntity(mangaId = mangaId, category = category))
        }
    }

    // ─── DOWNLOADS & STORAGE ───
    val downloadTasksFlow: Flow<List<DownloadTask>> = dao.observeDownloadTasks().map { list ->
        list.map {
            DownloadTask(
                id = it.id,
                chapterId = it.chapterId,
                mangaId = it.mangaId,
                mangaTitle = it.mangaTitle,
                chapterTitle = it.chapterTitle,
                coverUrl = it.coverUrl,
                totalPages = it.totalPages,
                downloadedPages = it.downloadedPages,
                failedPages = it.failedPages,
                status = runCatching { DownloadStatus.valueOf(it.status) }.getOrDefault(DownloadStatus.WAITING),
                createdAt = it.createdAt
            )
        }
    }

    suspend fun downloadSingleChapter(mangaId: String, chapterId: String) {
        val manga = dao.getMangaById(mangaId) ?: return
        val chapter = dao.getChapterById(chapterId) ?: return
        downloadEngine.enqueueChapterDownload(
            mangaId = mangaId,
            mangaTitle = manga.title,
            coverUrl = manga.coverUrl,
            chapter = chapter
        )
    }

    suspend fun downloadChapterBatch(mangaId: String, chapters: List<MangaChapter>) {
        val manga = dao.getMangaById(mangaId) ?: return
        val dbChapters = dao.getChaptersByMangaOnce(mangaId).associateBy { it.id }
        for (ch in chapters) {
            val entity = dbChapters[ch.id] ?: continue
            downloadEngine.enqueueChapterDownload(
                mangaId = mangaId,
                mangaTitle = manga.title,
                coverUrl = manga.coverUrl,
                chapter = entity
            )
        }
    }

    suspend fun pauseDownload(taskId: String) = downloadEngine.pauseTask(taskId)
    suspend fun resumeDownload(taskId: String) = downloadEngine.resumeTask(taskId)
    suspend fun cancelDownload(taskId: String) = downloadEngine.cancelTask(taskId)
    suspend fun pauseAllDownloads() = downloadEngine.pauseAllActive()
    suspend fun cancelAllDownloads() = downloadEngine.cancelAllActive()
    suspend fun clearDownloadHistory() = downloadEngine.clearHistory()

    suspend fun removeMangaFromStorage(mangaId: String) {
        val manga = dao.getMangaById(mangaId) ?: return
        val chapters = dao.getChaptersByMangaOnce(mangaId)
        for (ch in chapters) {
            storage.deleteChapterFiles(manga.sourceId, mangaId, ch.chapterNumber)
            dao.updateChapterDownloaded(ch.id, false)
        }
        dao.setMangaSavedInStorage(mangaId, false)
    }

    suspend fun getMangaStorageSizeMb(mangaId: String): Double {
        val manga = dao.getMangaById(mangaId) ?: return 0.0
        return storage.getMangaStorageSizeMb(manga.sourceId, mangaId)
    }

    suspend fun getStorageStats(): StorageStats = storage.computeStorageStats()
    suspend fun clearImageCache(): Long = storage.clearCache()

    private fun SourceEntity.toDomain() = SourceInfo(
        id = id,
        name = name,
        domain = domain,
        baseUrl = baseUrl,
        language = language,
        iconText = iconText,
        iconColorHex = iconColorHex,
        isEnabled = isEnabled,
        status = runCatching { SourceStatus.valueOf(status) }.getOrDefault(SourceStatus.SUPPORTED),
        version = version,
        lastCheckedAt = lastCheckedAt,
        engineType = runCatching { SourceEngineType.valueOf(engineType) }.getOrDefault(SourceEngineType.MADARA),
        contentRating = runCatching { ContentRating.valueOf(contentRating) }.getOrDefault(ContentRating.GENERAL),
        extractedMangaCount = extractedMangaCount,
        extractedChaptersCount = extractedChaptersCount,
        extractedPagesCount = extractedPagesCount,
        lastError = lastError,
        hasCustomConfig = hasCustomConfig
    )

    private fun MangaEntity.toSummary() = MangaSummary(
        id = id,
        sourceId = sourceId,
        title = title,
        coverUrl = coverUrl,
        mangaUrl = remoteId,
        latestChapter = latestChapter,
        genres = genresCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        rating = rating,
        status = status,
        contentRating = runCatching { ContentRating.valueOf(contentRating) }.getOrDefault(ContentRating.GENERAL)
    )

    private fun MangaEntity.toDetails(chapters: List<MangaChapter>) = MangaDetails(
        id = id,
        sourceId = sourceId,
        title = title,
        alternativeTitles = emptyList(),
        coverUrl = coverUrl,
        description = description.ifBlank { "قصة مانهوا مشوّقة متوفرة للقراءة والتنزيل المحلي." },
        author = author,
        artist = artist,
        status = status,
        genres = genresCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        rating = rating,
        contentRating = runCatching { ContentRating.valueOf(contentRating) }.getOrDefault(ContentRating.GENERAL),
        chapters = chapters
    )

    private fun ChapterEntity.toDomain() = MangaChapter(
        id = id,
        mangaId = mangaId,
        sourceId = sourceId,
        remoteId = remoteId,
        title = title,
        chapterNumber = chapterNumber,
        uploadDate = uploadDate,
        dateLabel = dateLabel,
        isRead = isRead,
        isDownloaded = isDownloaded,
        lastReadPage = lastReadPage
    )

    private fun SourceConfigEntity.toDomain() = SourceConfiguration(
        sourceId = sourceId,
        sourceName = sourceName,
        domain = domain,
        baseUrl = baseUrl,
        language = language,
        engineType = runCatching { SourceEngineType.valueOf(engineType) }.getOrDefault(SourceEngineType.MADARA),
        contentRating = runCatching { ContentRating.valueOf(contentRating) }.getOrDefault(ContentRating.GENERAL),
        popularPath = popularPath,
        latestPath = latestPath,
        searchPathTemplate = searchPathTemplate,
        mangaListSelector = mangaListSelector,
        mangaTitleSelector = mangaTitleSelector,
        mangaTitleAttr = mangaTitleAttr,
        mangaUrlSelector = mangaUrlSelector,
        mangaUrlAttr = mangaUrlAttr,
        mangaCoverSelector = mangaCoverSelector,
        mangaCoverAttr = mangaCoverAttr,
        detailsTitleSelector = detailsTitleSelector,
        detailsDescriptionSelector = detailsDescriptionSelector,
        detailsCoverSelector = detailsCoverSelector,
        detailsAuthorSelector = detailsAuthorSelector,
        detailsStatusSelector = detailsStatusSelector,
        detailsGenresSelector = detailsGenresSelector,
        chapterListSelector = chapterListSelector,
        chapterTitleSelector = chapterTitleSelector,
        chapterUrlSelector = chapterUrlSelector,
        chapterDateSelector = chapterDateSelector,
        pageListSelector = pageListSelector,
        pageImageAttr = pageImageAttr,
        customUserAgent = customUserAgent,
        refererUrl = refererUrl,
        extraHeadersJson = extraHeadersJson,
        requiresVerification = requiresVerification,
        useWebViewFallback = useWebViewFallback,
        updatedAt = updatedAt
    )

    private fun SourceConfiguration.toEntity() = SourceConfigEntity(
        sourceId = sourceId,
        sourceName = sourceName,
        domain = domain,
        baseUrl = baseUrl,
        language = language,
        engineType = engineType.name,
        contentRating = contentRating.name,
        popularPath = popularPath,
        latestPath = latestPath,
        searchPathTemplate = searchPathTemplate,
        mangaListSelector = mangaListSelector,
        mangaTitleSelector = mangaTitleSelector,
        mangaTitleAttr = mangaTitleAttr,
        mangaUrlSelector = mangaUrlSelector,
        mangaUrlAttr = mangaUrlAttr,
        mangaCoverSelector = mangaCoverSelector,
        mangaCoverAttr = mangaCoverAttr,
        detailsTitleSelector = detailsTitleSelector,
        detailsDescriptionSelector = detailsDescriptionSelector,
        detailsCoverSelector = detailsCoverSelector,
        detailsAuthorSelector = detailsAuthorSelector,
        detailsStatusSelector = detailsStatusSelector,
        detailsGenresSelector = detailsGenresSelector,
        chapterListSelector = chapterListSelector,
        chapterTitleSelector = chapterTitleSelector,
        chapterUrlSelector = chapterUrlSelector,
        chapterDateSelector = chapterDateSelector,
        pageListSelector = pageListSelector,
        pageImageAttr = pageImageAttr,
        customUserAgent = customUserAgent,
        refererUrl = refererUrl,
        extraHeadersJson = extraHeadersJson,
        requiresVerification = requiresVerification,
        useWebViewFallback = useWebViewFallback,
        updatedAt = System.currentTimeMillis()
    )
}
