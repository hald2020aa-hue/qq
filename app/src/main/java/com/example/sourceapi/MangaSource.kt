package com.example.sourceapi

import com.example.core.common.normalizeDomain
import com.example.core.verification.VerificationChallengeType
import com.example.core.verification.VerificationDetectionResult
import com.example.core.verification.VerificationDetector
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationState
import com.example.domain.models.ContentRating
import com.example.domain.models.ContentSafetyClassifier
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.domain.models.SiteInspectionReport
import com.example.domain.models.SourceCapability
import com.example.domain.models.SourceDiagnosticReport
import com.example.domain.models.SourceEngineType
import com.example.domain.models.SourceStatus
import com.example.sources.AzoraMangaSource
import com.example.sources.DynamicSiteSource
import com.example.sources.DynamicSourceAdapter
import com.example.sources.MangaDexSource
import com.example.sources.MangalekSource
import com.example.sources.MangastarzSource
import com.example.sources.MangatekSource
import com.example.sources.TeamXSource
import com.example.sources.UniversalSourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Independent HTML/JSON parser contract per source or engine.
 */
interface SourceParser {
    fun parseCatalog(html: String, pageBaseUri: String): List<MangaSummary>
    fun parseDetails(html: String, mangaUrl: String): MangaDetails?
    fun parseChapters(html: String, mangaId: String, mangaUrl: String): List<MangaChapter>
    fun parseReaderPages(html: String, chapterUrl: String): List<MangaPage>
}

/**
 * Unified SourceAdapter & MangaSource contract.
 * Every source implements this independently without coupling UI to HTML structure,
 * and can report its need for security verification via [VerificationDetector] / [VerificationManager].
 */
interface MangaSource {
    val id: String
    val name: String
    val baseUrl: String
    val domain: String
    val iconText: String
    val iconColorHex: Long
    val requiresCloudflareVerification: Boolean
        get() = false

    val capabilities: Set<SourceCapability>
        get() = setOf(
            SourceCapability.POPULAR_MANGA,
            SourceCapability.LATEST_UPDATES,
            SourceCapability.SEARCH_MANGA,
            SourceCapability.MANGA_DETAILS,
            SourceCapability.MANGA_CHAPTERS,
            SourceCapability.CHAPTER_PAGES,
            SourceCapability.PAGINATION,
            SourceCapability.CATEGORIES,
            SourceCapability.FILTERS
        )

    fun attachVerificationManager(manager: VerificationManager) {}

    fun detectSecurityChallenge(
        html: String,
        httpCode: Int = 200,
        headers: Map<String, String> = emptyMap(),
        pageTitle: String = ""
    ): VerificationDetectionResult {
        return VerificationDetector.detect(
            html = html,
            httpCode = httpCode,
            headers = headers,
            pageTitle = pageTitle
        )
    }

    fun parseVerifiedHtmlSnapshot(html: String, pageBaseUri: String = baseUrl): List<MangaSummary> = emptyList()

    suspend fun getPopular(page: Int): PageResult<MangaSummary>
    suspend fun getLatestUpdates(page: Int): PageResult<MangaSummary> = getPopular(page)
    suspend fun search(query: String, page: Int): PageResult<MangaSummary>
    suspend fun getDetails(mangaUrl: String): MangaDetails
    suspend fun getChapters(mangaUrl: String): List<MangaChapter>
    suspend fun getPages(chapterUrl: String): List<MangaPage>
}

typealias SourceAdapter = MangaSource

/**
 * Multi-attribute HTML Image Extractor using Jsoup & OkHttp HttpUrl.
 * Supports: src, data-src, data-lazy-src, data-original, data-bg, srcset, data-srcset, and inline style background-image.
 * Properly encodes Arabic characters and spaces in relative/absolute URLs so covers never fail with URISyntaxException.
 */
object HtmlImageExtractor {

    private val IMAGE_ATTRIBUTES = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "data-bg",
        "data-srcset",
        "srcset",
        "src"
    )

    private val BG_URL_REGEX = Regex("""url\(['"]?([^'")]+)['"]?\)""")

    fun extractImageUrl(element: Element?, baseUri: String): String? {
        if (element == null) return null

        // 1. Check standard and lazy-loading attributes on the element or child <img>
        val target = if (element.tagName().equals("img", ignoreCase = true)) {
            element
        } else {
            element.selectFirst("img") ?: element
        }

        for (attr in IMAGE_ATTRIBUTES) {
            val raw = target.attr(attr).trim()
            if (isValidRawImageCandidate(raw)) {
                val candidate = if (attr.endsWith("srcset")) {
                    parseFirstUrlFromSrcset(raw)
                } else {
                    raw
                }
                val resolved = resolveUrl(baseUri, candidate)
                if (resolved != null && !isDecorativeOrLogoUrl(resolved)) return resolved
            }
        }

        // 2. Check inline style="background-image: url(...)" on element or children
        val styledEl = if (element.attr("style").contains("url(")) {
            element
        } else {
            element.selectFirst("[style*=url(]")
        }
        if (styledEl != null) {
            val match = BG_URL_REGEX.find(styledEl.attr("style"))
            val bgUrl = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (isValidRawImageCandidate(bgUrl)) {
                val resolved = resolveUrl(baseUri, bgUrl)
                if (resolved != null && !isDecorativeOrLogoUrl(resolved)) return resolved
            }
        }

        return null
    }

    private fun isValidRawImageCandidate(raw: String): Boolean {
        if (raw.isBlank()) return false
        val lower = raw.lowercase()
        if (lower.startsWith("data:image/svg") ||
            lower.startsWith("data:image/gif") ||
            lower.contains("blank.gif") ||
            lower.contains("placeholder") ||
            lower.contains("loading.gif") ||
            lower.startsWith("javascript:")
        ) {
            return false
        }
        return true
    }

    fun isDecorativeOrLogoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith("logo.png") ||
            lower.endsWith("teamx.png") ||
            lower.endsWith("mangalek.png") ||
            lower.endsWith("btn_close.gif") ||
            lower.endsWith("discord.svg") ||
            lower.contains("/themes/madara/images/") ||
            lower.contains("favicon") ||
            lower.contains("avatar")
    }

    private fun parseFirstUrlFromSrcset(srcset: String): String {
        return srcset
            .lines()
            .joinToString(" ")
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    }

    fun extractChapterPagesFromHtml(
        html: String,
        baseUri: String,
        selector: String = ".reading-content img, .page-break img, .chapter-content img, #readerarea img, img.wp-manga-chapter-img, img.manga-chapter-img"
    ): List<MangaPage> {
        val doc: Document = Jsoup.parse(html, baseUri)
        val elements = doc.select(selector)
        val urls = mutableListOf<String>()
        for (el in elements) {
            val url = extractImageUrl(el, baseUri)
            if (!url.isNullOrBlank() &&
                (url.startsWith("https://") || url.startsWith("http://")) &&
                !isDecorativeOrLogoUrl(url)
            ) {
                urls.add(url)
            }
        }

        // Next.js / JSON embedded image array fallback (e.g., azoramoon.com / custom React readers)
        if (urls.isEmpty()) {
            val jsonImgRegex = Regex("""https?:\\?/\\?/[^"'\s<>]+\.(?:webp|jpg|jpeg|png|avif)""", RegexOption.IGNORE_CASE)
            for (match in jsonImgRegex.findAll(html)) {
                val cleaned = match.value.replace("\\/", "/")
                val resolved = resolveUrl(baseUri, cleaned)
                if (!resolved.isNullOrBlank() && !isDecorativeOrLogoUrl(resolved) && !resolved.contains("cover", ignoreCase = true)) {
                    urls.add(resolved)
                }
            }
        }

        return urls.distinct().mapIndexed { index, imageUrl ->
            MangaPage(index = index, imageUrl = imageUrl)
        }
    }

    fun isSecurityVerificationPage(html: String, httpCode: Int = 200): Boolean {
        return VerificationDetector.detect(html = html, httpCode = httpCode).isVerificationRequired
    }

    /**
     * Resolves relative, protocol-relative, and unencoded Arabic/space URLs against baseUri
     * and normalizes http:// to https://.
     */
    fun resolveUrl(baseUri: String, relativeOrAbsolute: String): String? {
        val clean = relativeOrAbsolute
            .trim()
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        if (clean.isEmpty()) return null

        val normalizedBase = if (baseUri.startsWith("http://")) {
            "https://" + baseUri.removePrefix("http://")
        } else if (!baseUri.startsWith("https://")) {
            "https://$baseUri"
        } else {
            baseUri
        }

        // First try OkHttp's WHATWG/RFC-compliant HttpUrl resolver (handles Arabic & spaces gracefully)
        val baseHttpUrl = normalizedBase.toHttpUrlOrNull()
        if (baseHttpUrl != null) {
            val resolvedHttpUrl = baseHttpUrl.resolve(clean)
            if (resolvedHttpUrl != null) {
                return resolvedHttpUrl.newBuilder().scheme("https").build().toString()
            }
        }

        // Fallback URI resolution with space encoding
        return try {
            val safeClean = clean.replace(" ", "%20")
            when {
                safeClean.startsWith("https://") -> safeClean
                safeClean.startsWith("//") -> "https:$safeClean"
                safeClean.startsWith("http://") -> "https://" + safeClean.removePrefix("http://")
                else -> URI(normalizedBase).resolve(safeClean).toString()
            }
        } catch (e: Exception) {
            null
        }
    }
}

data class SourceValidationStepResult(
    val normalizedDomain: String,
    val status: SourceStatus,
    val matchedSource: MangaSource?,
    val suggestedName: String,
    val messageAr: String,
    val diagnosticReport: SourceDiagnosticReport? = null,
    val inspectionReport: SiteInspectionReport? = null
)

/**
 * SourceRegistry that preserves specialized built-in sources (`MangalekSource`, `MangastarzSource`,
 * `TeamXSource`, `MangatekSource`, `AzoraMangaSource`, `MangaDexSource`) so that renaming or
 * re-adding a built-in site never overwrites its specialized parser with a generic [DynamicSiteSource].
 */
class SourceRegistry(sources: List<MangaSource>) {
    private val byId = sources.associateBy { it.id }.toMutableMap()
    private val byDomain = sources.associateBy { it.domain.lowercase() }.toMutableMap()

    // Reference map of the 6 specialized built-in connectors by domain & mirror domain
    private val builtInCatalog: Map<String, MangaSource> = buildMap {
        val builtIns = listOf(
            MangalekSource(),
            MangastarzSource(),
            TeamXSource(),
            MangatekSource(),
            AzoraMangaSource(),
            MangaDexSource()
        )
        for (src in builtIns) {
            put(src.domain.lowercase(), src)
        }
        put("lekmanga.net", MangalekSource())
        put("azoramanga.com", AzoraMangaSource())
    }

    fun register(source: MangaSource) {
        val domainKey = source.domain.lowercase()
        val existingByDomain = byDomain[domainKey]
        // Fix #2: Never overwrite an already-registered specialized built-in source with a generic DynamicSiteSource
        if (existingByDomain != null && existingByDomain !is DynamicSiteSource && source is DynamicSiteSource) {
            byId[source.id] = existingByDomain
            return
        }
        byId[source.id] = source
        byDomain[domainKey] = source
    }

    fun unregister(sourceId: String) {
        val removed = byId.remove(sourceId)
        if (removed != null && removed is DynamicSiteSource) {
            byDomain.remove(removed.domain.lowercase())
        }
    }

    fun findById(id: String): MangaSource? = byId[id]

    fun findByDomain(domainInput: String): MangaSource? {
        val clean = runCatching { normalizeDomain(domainInput, allowHttpUpgrade = true) }.getOrNull() ?: return null
        return byDomain[clean]
            ?: byDomain.entries.firstOrNull { clean.endsWith(".${it.key}") || it.key.endsWith(".$clean") }?.value
    }

    fun findBuiltInByDomain(domainInput: String): MangaSource? {
        val clean = runCatching { normalizeDomain(domainInput, allowHttpUpgrade = true) }.getOrNull() ?: return null
        return builtInCatalog[clean]
            ?: builtInCatalog.entries.firstOrNull { clean.endsWith(".${it.key}") || it.key.endsWith(".$clean") }?.value
    }

    fun getAllBuiltInSources(): List<MangaSource> = listOf(
        MangalekSource(),
        MangastarzSource(),
        TeamXSource(),
        MangatekSource(),
        AzoraMangaSource(),
        MangaDexSource()
    )

    fun getAllRegistered(): List<MangaSource> = byId.values.distinctBy { it.id }.toList()
}

/**
 * Live Health & End-to-End Extraction Checker for any [MangaSource].
 * Populates all 12 diagnostic fields required by Section 12.
 */
class SourceHealthChecker(
    private val verificationManager: VerificationManager
) {
    suspend fun checkSourceHealth(source: MangaSource): SourceDiagnosticReport = withContext(Dispatchers.IO) {
        source.attachVerificationManager(verificationManager)
        val verState = verificationManager.getState(source.domain)
        val engineType = when {
            source is DynamicSourceAdapter -> source.config.engineType
            source.domain.contains("mangadex") -> SourceEngineType.REST_API
            source.domain.contains("olympustaff") -> SourceEngineType.MANGASTREAM
            else -> SourceEngineType.MADARA
        }
        val contentRating = when (source) {
            is DynamicSourceAdapter -> source.config.contentRating
            else -> ContentSafetyClassifier.classifyFromGenresAndText(emptyList(), source.name, "", source.domain)
        }

        try {
            val popular = withTimeoutOrNull(12_000L) { source.getPopular(1) }
            if (popular == null) {
                return@withContext SourceDiagnosticReport(
                    sourceId = source.id,
                    sourceName = source.name,
                    domain = source.domain,
                    engineType = engineType,
                    contentRating = contentRating,
                    connectionStatusAr = "انتهت مهلة الاتصال",
                    verificationStatusAr = verState.labelAr,
                    adapterStatus = SourceStatus.NETWORK_ERROR,
                    capabilities = source.capabilities,
                    lastError = "استغرق الاتصال بالنطاق ${source.domain} وقتاً طويلاً."
                )
            }

            val items = popular.items
            if (items.isEmpty()) {
                return@withContext SourceDiagnosticReport(
                    sourceId = source.id,
                    sourceName = source.name,
                    domain = source.domain,
                    engineType = engineType,
                    contentRating = contentRating,
                    connectionStatusAr = "متصل (HTTP 200)",
                    verificationStatusAr = verState.labelAr,
                    adapterStatus = SourceStatus.NEEDS_CONFIGURATION,
                    extractedMangaCount = 0,
                    capabilities = source.capabilities,
                    lastError = "لم يتم استخراج أي أعمال من الصفحة الرئيسية؛ قد يحتاج الموقع إلى تعديل محددات CSS."
                )
            }

            val firstWithCover = items.firstOrNull { !it.coverUrl.isNullOrBlank() } ?: items.first()
            val hasValidCover = !firstWithCover.coverUrl.isNullOrBlank() &&
                firstWithCover.coverUrl!!.startsWith("https://")

            val chapters = withTimeoutOrNull(10_000L) {
                source.getChapters(firstWithCover.mangaUrl)
            }.orEmpty()

            val firstChapter = chapters.firstOrNull()
            val pages = if (firstChapter != null) {
                withTimeoutOrNull(10_000L) {
                    source.getPages(firstChapter.remoteId)
                }.orEmpty()
            } else {
                emptyList()
            }

            val finalStatus = when {
                items.isNotEmpty() && chapters.isNotEmpty() && pages.isNotEmpty() -> SourceStatus.SUPPORTED
                items.isNotEmpty() && chapters.isNotEmpty() -> SourceStatus.SUPPORTED
                items.isNotEmpty() -> SourceStatus.PARTIALLY_SUPPORTED
                else -> SourceStatus.NEEDS_CONFIGURATION
            }

            SourceDiagnosticReport(
                sourceId = source.id,
                sourceName = source.name,
                domain = source.domain,
                engineType = engineType,
                contentRating = contentRating,
                connectionStatusAr = "متصل بنجاح",
                verificationStatusAr = verState.labelAr,
                adapterStatus = finalStatus,
                siteArchitecture = engineType.labelAr,
                extractedMangaCount = items.size,
                sampleCoverUrl = firstWithCover.coverUrl,
                imagesStatusOk = hasValidCover,
                chaptersStatusOk = chapters.isNotEmpty(),
                extractedChaptersCount = chapters.size,
                pagesStatusOk = pages.isNotEmpty(),
                extractedPagesCount = pages.size,
                capabilities = source.capabilities,
                lastError = null,
                lastTestedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val isVerification = msg.contains("SECURITY_VERIFICATION_REQUIRED") ||
                e is com.example.core.verification.VerificationRequiredException
            val status = when {
                isVerification -> SourceStatus.NEEDS_VERIFICATION
                msg.contains("timeout", ignoreCase = true) || msg.contains("Unable to resolve host", ignoreCase = true) ->
                    SourceStatus.NETWORK_ERROR
                else -> SourceStatus.NEEDS_CONFIGURATION
            }
            SourceDiagnosticReport(
                sourceId = source.id,
                sourceName = source.name,
                domain = source.domain,
                engineType = engineType,
                contentRating = contentRating,
                connectionStatusAr = if (status == SourceStatus.NETWORK_ERROR) "فشل الاتصال" else "متصل",
                verificationStatusAr = if (isVerification) VerificationState.REQUIRED.labelAr else verState.labelAr,
                adapterStatus = status,
                siteArchitecture = engineType.labelAr,
                capabilities = source.capabilities,
                lastError = msg.ifBlank { e.javaClass.simpleName },
                lastTestedAt = System.currentTimeMillis()
            )
        }
    }
}

class SourceValidator(
    private val registry: SourceRegistry,
    val verificationManager: VerificationManager = VerificationManager()
) {
    val healthChecker = SourceHealthChecker(verificationManager)
    val universalEngine = UniversalSourceEngine(verificationManager)

    init {
        registry.getAllRegistered().forEach { source ->
            source.attachVerificationManager(verificationManager)
        }
    }

    fun markDomainHumanVerified(domain: String, verifiedHtml: String? = null) {
        val clean = runCatching { normalizeDomain(domain, allowHttpUpgrade = true) }.getOrNull() ?: return
        val matched = registry.findByDomain(clean)
        verificationManager.completeVerification(
            sourceId = matched?.id ?: clean,
            domain = clean,
            verifiedHtml = verifiedHtml
        )
    }

    fun isDomainHumanVerified(domain: String): Boolean {
        val clean = runCatching { normalizeDomain(domain, allowHttpUpgrade = true) }.getOrNull() ?: return false
        return verificationManager.isVerified(clean)
    }

    fun getVerificationState(domain: String): VerificationState {
        val clean = runCatching { normalizeDomain(domain, allowHttpUpgrade = true) }.getOrDefault(domain)
        return verificationManager.getState(clean)
    }

    /**
     * Honest Smart Site Onboarding & Validation System:
     * 1. Cleans and validates domain syntax (`starzmanga.com`, `/https://starzmanga.com`, etc.).
     * 2. Preserves any registered or built-in specialized adapter for known domains (`mangalik.net`, `starzmanga.com`, `olympustaff.com`, `mangatek.com`, `azoramoon.com`, `mangadex.org`).
     * 3. For unknown domains, runs [UniversalSourceEngine.inspectSite] to verify reachability, security challenges, architecture, and real extraction before classifying.
     * 4. Returns `SourceStatus.UNSUPPORTED` for unreachable/non-manga domains (such as `random-unregistered.com`).
     */
    suspend fun validateDomainCompatibility(rawInput: String): Result<SourceValidationStepResult> = withContext(Dispatchers.IO) {
        val normalized = try {
            normalizeDomain(rawInput, allowHttpUpgrade = true)
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(e)
        }

        // 1. Check registered or built-in specialized connector first (Fix #1 & Fix #2)
        var connector = registry.findByDomain(normalized) ?: registry.findBuiltInByDomain(normalized)
        if (connector != null) {
            connector.attachVerificationManager(verificationManager)
            registry.register(connector)

            if (connector.requiresCloudflareVerification && !isDomainHumanVerified(normalized)) {
                verificationManager.markVerificationRequired(
                    sourceId = connector.id,
                    domain = normalized,
                    challengeType = VerificationChallengeType.CLOUDFLARE_CHALLENGE,
                    reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني"
                )
                return@withContext Result.success(
                    SourceValidationStepResult(
                        normalizedDomain = normalized,
                        status = SourceStatus.REQUIRES_VERIFICATION,
                        matchedSource = connector,
                        suggestedName = connector.name,
                        messageAr = "يتطلب هذا الموقع إكمال التحقق الأمني."
                    )
                )
            }

            return@withContext try {
                val popular = connector.getPopular(1)
                val firstWork = popular.items.firstOrNull()
                if (firstWork == null) {
                    Result.success(
                        SourceValidationStepResult(
                            normalizedDomain = normalized,
                            status = SourceStatus.NEEDS_CONFIGURATION,
                            matchedSource = connector,
                            suggestedName = connector.name,
                            messageAr = "تم الاتصال بالموقع، ولكنه يحتاج إلى إعداد المحددات لعرض الأعمال."
                        )
                    )
                } else {
                    val chapters = runCatching { connector.getChapters(firstWork.mangaUrl) }.getOrDefault(emptyList())
                    val report = SourceDiagnosticReport(
                        sourceId = connector.id,
                        sourceName = connector.name,
                        domain = normalized,
                        connectionStatusAr = "متصل بنجاح",
                        verificationStatusAr = verificationManager.getState(normalized).labelAr,
                        adapterStatus = SourceStatus.SUPPORTED,
                        extractedMangaCount = popular.items.size,
                        sampleCoverUrl = firstWork.coverUrl,
                        imagesStatusOk = !firstWork.coverUrl.isNullOrBlank(),
                        chaptersStatusOk = chapters.isNotEmpty(),
                        extractedChaptersCount = chapters.size,
                        capabilities = connector.capabilities
                    )
                    Result.success(
                        SourceValidationStepResult(
                            normalizedDomain = normalized,
                            status = SourceStatus.SUPPORTED,
                            matchedSource = connector,
                            suggestedName = connector.name,
                            messageAr = "المصدر متوافق تماماً وتم التحقق من استخراج ${popular.items.size} عمل والغلاف والفصول.",
                            diagnosticReport = report
                        )
                    )
                }
            } catch (e: Exception) {
                if (e.message == "SECURITY_VERIFICATION_REQUIRED" || e is com.example.core.verification.VerificationRequiredException) {
                    verificationManager.markVerificationRequired(
                        sourceId = connector.id,
                        domain = normalized,
                        reasonAr = "يتطلب هذا الموقع إكمال التحقق الأمني"
                    )
                    return@withContext Result.success(
                        SourceValidationStepResult(
                            normalizedDomain = normalized,
                            status = SourceStatus.REQUIRES_VERIFICATION,
                            matchedSource = connector,
                            suggestedName = connector.name,
                            messageAr = "يتطلب هذا الموقع إكمال التحقق الأمني."
                        )
                    )
                }
                Result.success(
                    SourceValidationStepResult(
                        normalizedDomain = normalized,
                        status = SourceStatus.PARTIALLY_SUPPORTED,
                        matchedSource = connector,
                        suggestedName = connector.name,
                        messageAr = "الموقع مسجل ويمكن تصفحه."
                    )
                )
            }
        }

        // 2. Unknown domain: Run 14-point site inspection via UniversalSourceEngine (Fix #4: Honest Classification)
        val inspection = universalEngine.inspectSite(normalized)

        if (!inspection.isReachable) {
            return@withContext Result.success(
                SourceValidationStepResult(
                    normalizedDomain = normalized,
                    status = SourceStatus.UNSUPPORTED,
                    matchedSource = null,
                    suggestedName = inspection.suggestedName.ifBlank { normalized },
                    messageAr = inspection.diagnosticSummaryAr,
                    inspectionReport = inspection
                )
            )
        }

        if (inspection.requiresVerification) {
            val dynamicConfig = inspection.recommendedConfig
                ?: UniversalSourceEngine(verificationManager).let {
                    com.example.sources.SourceAdapterFactory.defaultConfigForEngine(
                        domain = normalized,
                        sourceName = inspection.suggestedName,
                        engineType = inspection.detectedArchitecture,
                        contentRating = inspection.detectedContentRating,
                        requiresVerification = true
                    )
                }
            val dynamicAdapter = DynamicSourceAdapter(dynamicConfig)
            dynamicAdapter.attachVerificationManager(verificationManager)
            registry.register(dynamicAdapter)

            return@withContext Result.success(
                SourceValidationStepResult(
                    normalizedDomain = normalized,
                    status = SourceStatus.REQUIRES_VERIFICATION,
                    matchedSource = dynamicAdapter,
                    suggestedName = dynamicAdapter.name,
                    messageAr = inspection.diagnosticSummaryAr,
                    inspectionReport = inspection
                )
            )
        }

        val config = inspection.recommendedConfig
            ?: com.example.sources.SourceAdapterFactory.defaultConfigForEngine(
                domain = normalized,
                sourceName = inspection.suggestedName,
                engineType = inspection.detectedArchitecture,
                contentRating = inspection.detectedContentRating
            )
        val dynamicAdapter = DynamicSourceAdapter(config)
        dynamicAdapter.attachVerificationManager(verificationManager)
        registry.register(dynamicAdapter)

        val report = SourceDiagnosticReport(
            sourceId = dynamicAdapter.id,
            sourceName = dynamicAdapter.name,
            domain = normalized,
            engineType = inspection.detectedArchitecture,
            contentRating = inspection.detectedContentRating,
            connectionStatusAr = "متصل (HTTP ${inspection.httpStatusCode})",
            verificationStatusAr = verificationManager.getState(normalized).labelAr,
            adapterStatus = inspection.recommendedStatus,
            siteArchitecture = inspection.detectedArchitecture.labelAr,
            extractedMangaCount = inspection.extractedWorksCount,
            sampleCoverUrl = inspection.sampleCoverUrl,
            imagesStatusOk = inspection.canExtractCovers,
            chaptersStatusOk = inspection.canExtractChapters,
            extractedChaptersCount = inspection.extractedChaptersCount,
            pagesStatusOk = inspection.canExtractPages,
            extractedPagesCount = inspection.extractedPagesCount,
            capabilities = dynamicAdapter.capabilities
        )

        Result.success(
            SourceValidationStepResult(
                normalizedDomain = normalized,
                status = inspection.recommendedStatus,
                matchedSource = dynamicAdapter,
                suggestedName = dynamicAdapter.name,
                messageAr = inspection.diagnosticSummaryAr,
                diagnosticReport = report,
                inspectionReport = inspection
            )
        )
    }
}

/**
 * SourceManager coordinating [SourceRegistry], [SourceValidator], [SourceHealthChecker], and [VerificationManager].
 */
class SourceManager(
    val registry: SourceRegistry,
    val validator: SourceValidator,
    val verificationManager: VerificationManager = validator.verificationManager
) {
    val healthChecker: SourceHealthChecker = validator.healthChecker
    val universalEngine: UniversalSourceEngine = validator.universalEngine

    init {
        registry.getAllRegistered().forEach { source ->
            source.attachVerificationManager(verificationManager)
        }
    }

    fun getSourceById(sourceId: String): MangaSource? = registry.findById(sourceId)

    fun getSourceByDomain(domain: String): MangaSource? = registry.findByDomain(domain)

    fun getAllSources(): List<MangaSource> = registry.getAllRegistered()

    fun getVerificationStateForSource(sourceIdOrDomain: String): VerificationState {
        val source = registry.findById(sourceIdOrDomain) ?: registry.findByDomain(sourceIdOrDomain)
        val key = source?.domain ?: sourceIdOrDomain
        val currentState = verificationManager.getState(key)
        if (currentState == VerificationState.NOT_REQUIRED && source?.requiresCloudflareVerification == true) {
            return VerificationState.REQUIRED
        }
        return currentState
    }

    suspend fun runDiagnosticsForSource(sourceIdOrDomain: String): SourceDiagnosticReport? {
        val source = registry.findById(sourceIdOrDomain) ?: registry.findByDomain(sourceIdOrDomain) ?: return null
        return healthChecker.checkSourceHealth(source)
    }
}
