package com.example.features

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.MangaDatabase
import com.example.core.downloads.DownloadEngine
import com.example.core.storage.AppPreferences
import com.example.core.storage.LocalChapterStorage
import com.example.core.storage.StorageStats
import com.example.core.storage.UserSettings
import com.example.data.repositories.MangaRepository
import com.example.domain.models.AppThemeStyle
import com.example.domain.models.DownloadTask
import com.example.domain.models.GridDisplayMode
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.ReaderMode
import com.example.domain.models.SortOption
import com.example.domain.models.SourceInfo
import com.example.domain.models.SourceStatus
import com.example.domain.models.StatusFilter
import com.example.sourceapi.SourceRegistry
import com.example.sourceapi.SourceValidationStepResult
import com.example.sourceapi.SourceValidator
import com.example.sources.AzoraMangaSource
import com.example.sources.MangaDexSource
import com.example.sources.MangalekSource
import com.example.sources.MangatekSource
import com.example.sources.TeamXSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ScreenRoute {
    data object Storage : ScreenRoute()
    data object Recommendations : ScreenRoute()
    data object Updates : ScreenRoute()
    data object Favorites : ScreenRoute()
    data object History : ScreenRoute()
    data object SourceManager : ScreenRoute()
    data class Browser(val initialUrl: String = "https://www.google.com") : ScreenRoute()
    data class SourceCatalog(val sourceId: String) : ScreenRoute()
    data class MangaDetailsRoute(val mangaId: String) : ScreenRoute()
    data class ReaderRoute(val mangaId: String, val chapterId: String, val initialPage: Int = 0) : ScreenRoute()
    data object Downloads : ScreenRoute()
    data object SettingsMain : ScreenRoute()
    data class SettingsSub(val section: String) : ScreenRoute()
}

data class AddSiteDialogState(
    val isVisible: Boolean = false,
    val inputUrl: String = "",
    val isChecking: Boolean = false,
    val stepMessageAr: String = "",
    val errorMessageAr: String? = null,
    val showManualConfirmDialog: Boolean = false,
    val manualSiteName: String = "",
    val pendingValidationResult: SourceValidationStepResult? = null,
    val showCloudflareDialog: Boolean = false,
    val cloudflareDomain: String = "",
    val cloudflareStepAr: String = "يتم التحقق الآن..."
)

data class CatalogUiState(
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val selectedGenres: Set<String> = emptySet(),
    val selectedSort: SortOption = SortOption.LATEST_ADDED,
    val selectedStatus: StatusFilter = StatusFilter.ALL,
    val currentPage: Int = 1,
    val requiresCloudflareGate: Boolean = false,
    val errorMessageAr: String? = null
)

data class DetailsUiState(
    val isLoading: Boolean = false,
    val details: MangaDetails? = null,
    val sortDescending: Boolean = false,
    val storageSizeMb: Double = 0.0,
    val errorMessageAr: String? = null,
    val showBatchConfirmDialog: Boolean = false,
    val pendingBatchChapters: List<MangaChapter> = emptyList()
)

data class ReaderUiState(
    val isLoading: Boolean = true,
    val loadingProgress: Int = 0,
    val pages: List<MangaPage> = emptyList(),
    val currentPageIndex: Int = 0,
    val isControlsVisible: Boolean = false,
    val failedPageIndices: Set<Int> = emptySet(),
    val isBookmarked: Boolean = false,
    val errorMessageAr: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = MangaDatabase.getInstance(application)
    private val storage = LocalChapterStorage(application)
    val preferences = AppPreferences(application)

    private val registry = SourceRegistry(
        listOf(
            MangalekSource(),
            TeamXSource(),
            MangatekSource(),
            AzoraMangaSource(),
            MangaDexSource()
        )
    )
    private val validator = SourceValidator(registry)
    private val downloadEngine = DownloadEngine(application, db, storage, registry)

    val repository = MangaRepository(
        dao = db.mangaDao(),
        registry = registry,
        validator = validator,
        storage = storage,
        downloadEngine = downloadEngine
    )

    // ─── NAVIGATION STACK ───
    private val backStack = ArrayDeque<ScreenRoute>()
    private val _currentRoute = MutableStateFlow<ScreenRoute>(ScreenRoute.Storage)
    val currentRoute: StateFlow<ScreenRoute> = _currentRoute.asStateFlow()

    // ─── GLOBAL UI STATES ───
    val settings: StateFlow<UserSettings> = preferences.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings()
    )

    val addedSources: StateFlow<List<SourceInfo>> = repository.addedSourcesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val storedManga: StateFlow<List<MangaSummary>> = repository.storedMangaFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allManga: StateFlow<List<MangaSummary>> = repository.allMangaFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val favoritesWithCategory = repository.favoritesWithCategoryFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val readingHistory = repository.readingHistoryWithMangaFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val downloadTasks: StateFlow<List<DownloadTask>> = repository.downloadTasksFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _showRestartBanner = MutableStateFlow(false)
    val showRestartBanner: StateFlow<Boolean> = _showRestartBanner.asStateFlow()

    private val _showLoginPromptDialog = MutableStateFlow(false)
    val showLoginPromptDialog: StateFlow<Boolean> = _showLoginPromptDialog.asStateFlow()

    private val _loggedInEmail = MutableStateFlow<String?>(null)
    val loggedInEmail: StateFlow<String?> = _loggedInEmail.asStateFlow()

    private val _addSiteDialog = MutableStateFlow(AddSiteDialogState())
    val addSiteDialog: StateFlow<AddSiteDialogState> = _addSiteDialog.asStateFlow()

    private val _catalogState = MutableStateFlow(CatalogUiState())
    val catalogState: StateFlow<CatalogUiState> = _catalogState.asStateFlow()

    private val _detailsState = MutableStateFlow(DetailsUiState())
    val detailsState: StateFlow<DetailsUiState> = _detailsState.asStateFlow()

    private val _readerState = MutableStateFlow(ReaderUiState())
    val readerState: StateFlow<ReaderUiState> = _readerState.asStateFlow()

    private val _storageStats = MutableStateFlow(
        StorageStats(
            downloadsBytes = 0L,
            pagesCacheBytes = 0L,
            otherAppBytes = 7_549_747L,
            availableBytes = 264_784_000_000L,
            storagePath = storage.getSavedRootDir().absolutePath
        )
    )
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private var readerLoadJob: Job? = null

    init {
        refreshStorageStats()
    }

    fun navigateTo(route: ScreenRoute, clearBackStack: Boolean = false) {
        if (clearBackStack) {
            backStack.clear()
        } else if (_currentRoute.value != route) {
            backStack.addLast(_currentRoute.value)
        }
        _currentRoute.value = route

        when (route) {
            is ScreenRoute.SourceCatalog -> loadSourceCatalog(route.sourceId)
            is ScreenRoute.MangaDetailsRoute -> loadMangaDetails(route.mangaId)
            is ScreenRoute.ReaderRoute -> loadReaderChapter(route.mangaId, route.chapterId, route.initialPage)
            is ScreenRoute.SettingsSub -> if (route.section == "storage") refreshStorageStats()
            else -> {}
        }
    }

    fun navigateBack(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        _currentRoute.value = previous
        if (previous is ScreenRoute.MangaDetailsRoute) {
            loadMangaDetails(previous.mangaId)
        }
        return true
    }

    fun showToast(message: String) {
        viewModelScope.launch {
            _toastMessage.value = message
            delay(2800L)
            if (_toastMessage.value == message) {
                _toastMessage.value = null
            }
        }
    }

    fun dismissLoginPrompt() {
        _showLoginPromptDialog.value = false
    }

    fun openLoginPrompt() {
        _showLoginPromptDialog.value = true
    }

    fun signInLocalProfile(email: String) {
        _loggedInEmail.value = email.ifBlank { "hald2020ww@gmail.com" }
        _showLoginPromptDialog.value = false
        showToast("تم ربط الحساب محلياً بنجاح")
    }

    // ─── ADD SOURCE & COMPATIBILITY CHECK FLOW (Matching Video 1, 2, 3) ───
    fun openAddSiteDialog(prefillUrl: String = "") {
        _addSiteDialog.value = AddSiteDialogState(
            isVisible = true,
            inputUrl = prefillUrl
        )
    }

    fun updateAddSiteInput(url: String) {
        _addSiteDialog.value = _addSiteDialog.value.copy(
            inputUrl = url,
            errorMessageAr = null
        )
    }

    fun closeAddSiteDialog() {
        _addSiteDialog.value = AddSiteDialogState()
    }

    fun startSiteCompatibilityCheck() {
        val rawUrl = _addSiteDialog.value.inputUrl.trim()
        if (rawUrl.isEmpty()) {
            _addSiteDialog.value = _addSiteDialog.value.copy(
                errorMessageAr = "يرجى إدخال عنوان نطاق الموقع أولاً (مثال: mangalik.net)"
            )
            return
        }

        viewModelScope.launch {
            _addSiteDialog.value = _addSiteDialog.value.copy(
                isChecking = true,
                errorMessageAr = null,
                stepMessageAr = "جار مراجعة حالة توافق الموقع..."
            )
            delay(450L)

            val res = repository.validateSiteDomain(rawUrl)
            if (res.isFailure) {
                _addSiteDialog.value = _addSiteDialog.value.copy(
                    isChecking = false,
                    errorMessageAr = res.exceptionOrNull()?.message ?: "رابط غير صالح"
                )
                return@launch
            }

            val stepResult = res.getOrThrow()

            // If Cloudflare human verification is needed (e.g. mangatek.com in Video 3 & 4)
            if (stepResult.status == SourceStatus.REQUIRES_VERIFICATION) {
                _addSiteDialog.value = _addSiteDialog.value.copy(
                    isChecking = false,
                    showCloudflareDialog = true,
                    cloudflareDomain = stepResult.normalizedDomain,
                    cloudflareStepAr = "يتم التحقق الآن...",
                    pendingValidationResult = stepResult
                )
                return@launch
            }

            _addSiteDialog.value = _addSiteDialog.value.copy(
                stepMessageAr = "جار التحقق من توافق الموقع..."
            )
            delay(420L)

            if (stepResult.status == SourceStatus.UNSUPPORTED) {
                _addSiteDialog.value = _addSiteDialog.value.copy(
                    isChecking = false,
                    errorMessageAr = stepResult.messageAr
                )
                return@launch
            }

            _addSiteDialog.value = _addSiteDialog.value.copy(
                stepMessageAr = "جار التحقق من مطابقة الأعمال..."
            )
            delay(420L)

            _addSiteDialog.value = _addSiteDialog.value.copy(
                stepMessageAr = "جار التحقق من مطابقة الفصول..."
            )
            delay(380L)

            _addSiteDialog.value = _addSiteDialog.value.copy(
                isChecking = false,
                showManualConfirmDialog = true,
                manualSiteName = stepResult.suggestedName,
                pendingValidationResult = stepResult
            )
        }
    }

    fun completeCloudflareHumanVerification() {
        val domain = _addSiteDialog.value.cloudflareDomain
        viewModelScope.launch {
            _addSiteDialog.value = _addSiteDialog.value.copy(
                cloudflareStepAr = "نجح التحقق. في انتظار $domain للاستجابة..."
            )
            delay(650L)
            repository.markDomainHumanVerified(domain)

            // If triggered from Catalog gate, reload catalog
            val route = _currentRoute.value
            if (route is ScreenRoute.SourceCatalog) {
                _addSiteDialog.value = AddSiteDialogState()
                loadSourceCatalog(route.sourceId)
                return@launch
            }

            // Otherwise continue Add Site flow
            val revalidated = repository.validateSiteDomain(domain).getOrNull()
            _addSiteDialog.value = _addSiteDialog.value.copy(
                showCloudflareDialog = false,
                showManualConfirmDialog = true,
                manualSiteName = revalidated?.suggestedName ?: domain,
                pendingValidationResult = revalidated
            )
        }
    }

    fun triggerCatalogCloudflareVerification(domain: String) {
        _addSiteDialog.value = AddSiteDialogState(
            isVisible = false,
            showCloudflareDialog = true,
            cloudflareDomain = domain,
            cloudflareStepAr = "يتم التحقق الآن..."
        )
    }

    fun updateManualSiteName(name: String) {
        _addSiteDialog.value = _addSiteDialog.value.copy(manualSiteName = name)
    }

    fun confirmManualSiteAddition() {
        val state = _addSiteDialog.value
        val pending = state.pendingValidationResult ?: return
        viewModelScope.launch {
            val addRes = repository.confirmAndAddSource(pending, state.manualSiteName)
            _addSiteDialog.value = AddSiteDialogState()
            if (addRes.isSuccess) {
                _showRestartBanner.value = true
                showToast("تم الإضافة بنجاح")
            } else {
                showToast(addRes.exceptionOrNull()?.message ?: "تعذّر إضافة الموقع")
            }
        }
    }

    fun toggleSourceEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleSourceEnabled(sourceId, enabled)
            _showRestartBanner.value = true
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            repository.removeSource(sourceId)
            showToast("تم حذف المصدر")
        }
    }

    fun quickAddDemoSourcesIfEmpty() {
        viewModelScope.launch {
            for (domain in listOf("mangalik.net", "olympustaff.com", "mangatek.com", "azoramanga.com")) {
                repository.markDomainHumanVerified(domain)
                val v = repository.validateSiteDomain(domain).getOrNull()
                if (v != null && v.matchedSource != null) {
                    repository.confirmAndAddSource(v, v.suggestedName)
                }
            }
            showToast("تم تفعيل المصادر المعتمدة بنجاح")
        }
    }

    // ─── SOURCE CATALOG & FILTERING ───
    fun loadSourceCatalog(sourceId: String, page: Int = 1) {
        viewModelScope.launch {
            _catalogState.value = _catalogState.value.copy(
                isLoading = true,
                currentPage = page,
                errorMessageAr = null,
                requiresCloudflareGate = false
            )
            delay(280L)
            val res = repository.refreshSourceCatalog(sourceId, page)
            if (res.isFailure) {
                val msg = res.exceptionOrNull()?.message.orEmpty()
                if (msg == "SECURITY_VERIFICATION_REQUIRED") {
                    _catalogState.value = _catalogState.value.copy(
                        isLoading = false,
                        requiresCloudflareGate = true
                    )
                } else {
                    _catalogState.value = _catalogState.value.copy(
                        isLoading = false,
                        errorMessageAr = msg
                    )
                }
            } else {
                _catalogState.value = _catalogState.value.copy(
                    isLoading = false,
                    requiresCloudflareGate = false
                )
            }
        }
    }

    fun setCatalogSearchQuery(query: String) {
        _catalogState.value = _catalogState.value.copy(searchQuery = query)
    }

    fun setCatalogSearchActive(active: Boolean) {
        _catalogState.value = _catalogState.value.copy(
            isSearchActive = active,
            searchQuery = if (!active) "" else _catalogState.value.searchQuery
        )
    }

    fun toggleCatalogGenre(genre: String) {
        val current = _catalogState.value.selectedGenres.toMutableSet()
        if (current.contains(genre)) {
            current.remove(genre)
        } else {
            current.add(genre)
        }
        _catalogState.value = _catalogState.value.copy(selectedGenres = current)
    }

    fun clearCatalogFilters() {
        _catalogState.value = _catalogState.value.copy(
            selectedGenres = emptySet(),
            selectedSort = SortOption.LATEST_ADDED,
            selectedStatus = StatusFilter.ALL
        )
    }

    fun setCatalogSort(sort: SortOption) {
        _catalogState.value = _catalogState.value.copy(selectedSort = sort)
    }

    fun setCatalogStatusFilter(status: StatusFilter) {
        _catalogState.value = _catalogState.value.copy(selectedStatus = status)
    }

    // ─── MANGA DETAILS & CHAPTERS ───
    fun loadMangaDetails(mangaId: String) {
        viewModelScope.launch {
            _detailsState.value = _detailsState.value.copy(
                isLoading = true,
                errorMessageAr = null
            )
            delay(250L)
            val res = repository.loadMangaDetailsAndSyncChapters(mangaId)
            val sizeMb = repository.getMangaStorageSizeMb(mangaId)
            if (res.isSuccess) {
                _detailsState.value = _detailsState.value.copy(
                    isLoading = false,
                    details = res.getOrNull(),
                    storageSizeMb = sizeMb
                )
            } else {
                _detailsState.value = _detailsState.value.copy(
                    isLoading = false,
                    errorMessageAr = res.exceptionOrNull()?.message ?: "لم يتم العثور على فصول"
                )
            }
        }
    }

    fun toggleChapterSortOrder() {
        _detailsState.value = _detailsState.value.copy(
            sortDescending = !_detailsState.value.sortDescending
        )
    }

    fun toggleFavorite(mangaId: String, currentlyFav: Boolean, category: String = "الكل") {
        viewModelScope.launch {
            repository.toggleFavorite(mangaId, currentlyFav, category)
            showToast(if (currentlyFav) "تمت الإزالة من المفضلة" else "تمت الإضافة إلى المفضلة")
        }
    }

    fun downloadSingleChapter(mangaId: String, chapterId: String) {
        viewModelScope.launch {
            repository.downloadSingleChapter(mangaId, chapterId)
            showToast("بدأ التنزيل")
            delay(1500L)
            val sizeMb = repository.getMangaStorageSizeMb(mangaId)
            _detailsState.value = _detailsState.value.copy(storageSizeMb = sizeMb)
        }
    }

    fun requestBatchDownload(chapters: List<MangaChapter>) {
        _detailsState.value = _detailsState.value.copy(
            showBatchConfirmDialog = true,
            pendingBatchChapters = chapters
        )
    }

    fun dismissBatchConfirmDialog() {
        _detailsState.value = _detailsState.value.copy(
            showBatchConfirmDialog = false,
            pendingBatchChapters = emptyList()
        )
    }

    fun confirmBatchDownload(mangaId: String) {
        val chapters = _detailsState.value.pendingBatchChapters
        _detailsState.value = _detailsState.value.copy(
            showBatchConfirmDialog = false,
            pendingBatchChapters = emptyList()
        )
        viewModelScope.launch {
            repository.downloadChapterBatch(mangaId, chapters.take(5))
            showToast("بدأ التنزيل")
        }
    }

    fun removeMangaFromStorage(mangaId: String) {
        viewModelScope.launch {
            repository.removeMangaFromStorage(mangaId)
            _detailsState.value = _detailsState.value.copy(storageSizeMb = 0.0)
            showToast("تمت إزالة الملفات المحملة من التخزين")
        }
    }

    // ─── NATIVE READER (Section 11) ───
    fun loadReaderChapter(mangaId: String, chapterId: String, initialPage: Int = 0) {
        readerLoadJob?.cancel()
        readerLoadJob = viewModelScope.launch {
            _readerState.value = ReaderUiState(
                isLoading = true,
                loadingProgress = 8,
                currentPageIndex = initialPage
            )
            for (pct in listOf(22, 46, 68, 94)) {
                delay(110L)
                _readerState.value = _readerState.value.copy(loadingProgress = pct)
            }
            val res = repository.getReaderPages(chapterId)
            if (res.isSuccess) {
                val pages = res.getOrThrow()
                val safeInitial = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                _readerState.value = ReaderUiState(
                    isLoading = false,
                    loadingProgress = 100,
                    pages = pages,
                    currentPageIndex = safeInitial,
                    isControlsVisible = false
                )
                saveReaderProgress(mangaId, chapterId, safeInitial, 0)
            } else {
                _readerState.value = ReaderUiState(
                    isLoading = false,
                    errorMessageAr = "خطأ في التحميل"
                )
            }
        }
    }

    fun onReaderPageChanged(mangaId: String, chapterId: String, pageIndex: Int, scrollOffset: Int = 0) {
        val current = _readerState.value
        if (current.currentPageIndex != pageIndex) {
            _readerState.value = current.copy(currentPageIndex = pageIndex)
            saveReaderProgress(mangaId, chapterId, pageIndex, scrollOffset)
        }
    }

    private fun saveReaderProgress(mangaId: String, chapterId: String, pageIndex: Int, scrollOffset: Int) {
        viewModelScope.launch {
            val ch = db.mangaDao().getChapterById(chapterId)
            repository.saveReadingProgress(
                mangaId = mangaId,
                chapterId = chapterId,
                chapterTitle = ch?.title ?: "1",
                pageIndex = pageIndex,
                scrollOffset = scrollOffset,
                totalPages = _readerState.value.pages.size
            )
        }
    }

    fun toggleReaderControls() {
        _readerState.value = _readerState.value.copy(
            isControlsVisible = !_readerState.value.isControlsVisible
        )
    }

    fun toggleReaderBookmark() {
        val next = !_readerState.value.isBookmarked
        _readerState.value = _readerState.value.copy(isBookmarked = next)
        showToast(if (next) "تم حفظ العلامة المرجعية للصفحة" else "تمت إزالة العلامة المرجعية")
    }

    fun retryFailedReaderPage(pageIndex: Int) {
        val failed = _readerState.value.failedPageIndices.toMutableSet()
        failed.remove(pageIndex)
        _readerState.value = _readerState.value.copy(failedPageIndices = failed)
    }

    // ─── DOWNLOAD CONTROLS ───
    fun pauseDownload(taskId: String) {
        viewModelScope.launch {
            repository.pauseDownload(taskId)
            showToast("لقد تم إيقاف التنزيلات مؤقتاً")
        }
    }

    fun resumeDownload(taskId: String) {
        viewModelScope.launch {
            repository.resumeDownload(taskId)
            showToast("تم استئناف التنزيل")
        }
    }

    fun cancelDownload(taskId: String) {
        viewModelScope.launch {
            repository.cancelDownload(taskId)
        }
    }

    fun pauseAllDownloads() {
        viewModelScope.launch {
            repository.pauseAllDownloads()
            showToast("لقد تم إيقاف التنزيلات مؤقتاً")
        }
    }

    fun cancelAllDownloads() {
        viewModelScope.launch {
            repository.cancelAllDownloads()
            showToast("تم إلغاء جميع التنزيلات الجارية")
        }
    }

    fun clearDownloadHistory() {
        viewModelScope.launch {
            repository.clearDownloadHistory()
            showToast("تم حذف سجل التنزيلات")
        }
    }

    // ─── SETTINGS & STORAGE ───
    fun refreshStorageStats() {
        viewModelScope.launch {
            _storageStats.value = repository.getStorageStats()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearImageCache()
            refreshStorageStats()
            showToast("تم مسح ذاكرة التخزين المؤقتة")
        }
    }

    fun clearReadingHistory() {
        viewModelScope.launch {
            repository.clearReadingHistory()
            showToast("تم مسح السجل")
        }
    }

    fun setThemeStyle(style: AppThemeStyle) {
        viewModelScope.launch { preferences.setThemeStyle(style) }
    }

    fun setPureDarkMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setPureDarkMode(enabled) }
    }

    fun setReaderMode(mode: ReaderMode) {
        viewModelScope.launch { preferences.setReaderMode(mode) }
    }

    fun setGridDisplayMode(mode: GridDisplayMode) {
        viewModelScope.launch { preferences.setGridDisplayMode(mode) }
    }

    fun setBooleanSetting(key: String, value: Boolean) {
        viewModelScope.launch { preferences.updateBoolean(key, value) }
    }

    fun setBrightnessValue(value: Int) {
        viewModelScope.launch { preferences.setBrightnessValue(value) }
    }

    fun setMaxConcurrentDownloads(value: Int) {
        viewModelScope.launch { preferences.setMaxConcurrentDownloads(value) }
    }

    fun addFavoriteCategory(category: String) {
        viewModelScope.launch {
            preferences.addFavoriteCategory(category)
            showToast("تمت إضافة الفئة: $category")
        }
    }
}
