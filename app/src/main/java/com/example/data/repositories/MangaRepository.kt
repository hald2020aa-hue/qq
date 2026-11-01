package com.example.data.repositories

import com.example.core.database.ChapterEntity
import com.example.core.database.FavoriteEntity
import com.example.core.database.MangaDao
import com.example.core.database.MangaEntity
import com.example.core.database.PageEntity
import com.example.core.database.ReadingHistoryEntity
import com.example.core.database.SourceEntity
import com.example.core.downloads.DownloadEngine
import com.example.core.storage.LocalChapterStorage
import com.example.core.storage.StorageStats
import com.example.domain.models.DownloadStatus
import com.example.domain.models.DownloadTask
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.SourceInfo
import com.example.domain.models.SourceStatus
import com.example.sourceapi.SourceRegistry
import com.example.sourceapi.SourceValidationStepResult
import com.example.sourceapi.SourceValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File

class MangaRepository(
    private val dao: MangaDao,
    private val registry: SourceRegistry,
    private val validator: SourceValidator,
    private val storage: LocalChapterStorage,
    private val downloadEngine: DownloadEngine
) {

    // ─── SOURCES MANAGEMENT ───
    val addedSourcesFlow: Flow<List<SourceInfo>> = dao.observeSources().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun validateSiteDomain(rawInput: String): Result<SourceValidationStepResult> {
        return validator.validateDomainCompatibility(rawInput)
    }

    fun markDomainHumanVerified(domain: String) {
        validator.markDomainHumanVerified(domain)
    }

    fun isDomainHumanVerified(domain: String): Boolean {
        return validator.isDomainHumanVerified(domain)
    }

    suspend fun confirmAndAddSource(
        validationResult: SourceValidationStepResult,
        customSiteName: String
    ): Result<SourceInfo> {
        val connector = validationResult.matchedSource
            ?: return Result.failure(IllegalArgumentException("لا يمكن إضافة مصدر غير متوافق."))

        // Prevent duplicate source registration (Rule 10 & Section 7)
        val existing = dao.getSourceByDomain(validationResult.normalizedDomain)
        val finalName = customSiteName.trim().ifEmpty { connector.name }

        val entity = SourceEntity(
            id = existing?.id ?: connector.id,
            name = finalName,
            domain = validationResult.normalizedDomain,
            baseUrl = connector.baseUrl,
            language = "ar",
            iconUrl = null,
            iconText = connector.iconText,
            iconColorHex = connector.iconColorHex,
            isEnabled = true,
            isSupported = true,
            status = SourceStatus.SUPPORTED.name,
            version = 1,
            lastCheckedAt = System.currentTimeMillis()
        )
        dao.upsertSource(entity)

        // Pre-populate catalog into local DB so Unified Library / Recommendations / Search work immediately
        refreshSourceCatalog(connector.id)
        return Result.success(entity.toDomain())
    }

    suspend fun toggleSourceEnabled(sourceId: String, enabled: Boolean) {
        val status = if (enabled) SourceStatus.SUPPORTED.name else SourceStatus.DISABLED.name
        dao.updateSourceEnabled(sourceId, enabled, status)
    }

    suspend fun removeSource(sourceId: String) {
        dao.deleteSource(sourceId)
    }

    // ─── CATALOG & DETAILS ───
    fun observeMangaBySource(sourceId: String): Flow<List<MangaSummary>> {
        return dao.observeMangaBySource(sourceId).map { list ->
            list.map { it.toSummary() }
        }
    }

    val storedMangaFlow: Flow<List<MangaSummary>> = dao.observeStoredManga().map { list ->
        list.map { it.toSummary() }
    }

    val allMangaFlow: Flow<List<MangaSummary>> = dao.observeAllManga().map { list ->
        list.map { it.toSummary() }
    }

    /**
     * Fast catalog sync: fetches popular/latest summaries for [sourceId] and upserts them
     * into Room WITHOUT firing sequential per-item getDetails() requests (which previously
     * caused 60-90s loading spinners). Full details and chapters are fetched on-demand when
     * opening a manga's details screen.
     */
    suspend fun refreshSourceCatalog(sourceId: String, page: Int = 1): Result<List<MangaSummary>> {
        val connector = registry.findById(sourceId)
            ?: return Result.failure(IllegalStateException("المصدر غير موجود"))

        if (connector.requiresCloudflareVerification && !validator.isDomainHumanVerified(connector.domain)) {
            return Result.failure(IllegalStateException("SECURITY_VERIFICATION_REQUIRED"))
        }

        return try {
            val result = connector.getPopular(page)
            val entities = result.items
                .filter { it.title.isNotBlank() && it.mangaUrl.isNotBlank() }
                .map { summary ->
                    val existing = dao.getMangaById(summary.id)
                    MangaEntity(
                        id = summary.id,
                        sourceId = summary.sourceId,
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
                        rating = summary.rating ?: existing?.rating ?: 4.6f,
                        latestChapter = summary.latestChapter ?: existing?.latestChapter,
                        isSavedInStorage = existing?.isSavedInStorage ?: false,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            if (entities.isNotEmpty()) {
                dao.upsertMangaList(entities)
            }
            Result.success(result.items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadMangaDetailsAndSyncChapters(mangaId: String): Result<MangaDetails> {
        val mangaEntity = dao.getMangaById(mangaId)
            ?: return Result.failure(IllegalStateException("لم يتم العثور على المانهوا"))
        val connector = registry.findById(mangaEntity.sourceId)

        return try {
            if (connector == null) {
                val cachedChapters = dao.getChaptersByMangaOnce(mangaId).map { it.toDomain() }
                return Result.success(mangaEntity.toDetails(cachedChapters))
            }

            val remoteDetails = connector.getDetails(mangaEntity.remoteId)

            // Update cached MangaEntity with newly fetched metadata & cover
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
                    coverUrl = updatedEntity.coverUrl,
                    description = updatedEntity.description,
                    chapters = updatedChapters
                )
            )
        } catch (e: Exception) {
            // Offline fallback: return cached Room details & chapters if available
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

    // ─── READER & OFFLINE PAGES (Rule 6, 8, 9, 13) ───
    suspend fun getReaderPages(chapterId: String): Result<List<MangaPage>> {
        val chapter = dao.getChapterById(chapterId)
            ?: return Result.failure(IllegalStateException("الفصل غير موجود"))
        val manga = dao.getMangaById(chapter.mangaId)
        val connector = registry.findById(chapter.sourceId)

        // Check offline downloaded files first (Section 13: Offline reading without unnecessary network calls)
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

        // Materialize local cached page images so native reader renders crisp vertical webtoon art
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
        lastCheckedAt = lastCheckedAt
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
        status = status
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
}
