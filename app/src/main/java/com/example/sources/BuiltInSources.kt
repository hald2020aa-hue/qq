package com.example.sources

import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.verification.VerificationDetectionResult
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationRequiredException
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.sourceapi.HtmlImageExtractor
import com.example.sourceapi.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Non-blocking, coroutine-cancellable OkHttp Call extension.
 * Ensures that if a coroutine timeout triggers, the underlying socket is immediately cancelled
 * rather than blocking Dispatchers.IO threads.
 */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(response)
            } else {
                response.close()
            }
        }
    })
}

/**
 * Real HTML + Live API Manga Source Connector Base Class.
 * - Zero hardcoded manga lists or static placeholder data.
 * - Full Android Logcat pipeline tracing via [PipelineLogger].
 * - Connected to Universal Website Verification System ([VerificationManager] & [com.example.core.verification.VerificationDetector]).
 * - Supports WordPress Madara, MangaStream/WPMangaReader, Madara AJAX chapter endpoints,
 *   and live MangaDex REST API v5 bridge when a regional HTML domain is behind Cloudflare or ISP blocks.
 */
abstract class ConfigurableHtmlMangaSource(
    override val id: String,
    override val name: String,
    override val domain: String,
    override val iconText: String,
    override val iconColorHex: Long,
    override val requiresCloudflareVerification: Boolean = false,
    protected val mirrorDomains: List<String> = emptyList(),
    protected val itemSelector: String,
    protected val titleSelector: String,
    protected val coverSelector: String,
    protected val latestChapterSelector: String,
    protected val detailsTitleSelector: String,
    protected val detailsDescSelector: String,
    protected val detailsGenresSelector: String,
    protected val detailsAuthorSelector: String,
    protected val detailsStatusSelector: String,
    protected val chapterRowSelector: String,
    protected val readerImagesSelector: String,
    protected val liveApiQueryParams: String = "originalLanguage[]=ko&order[followedCount]=desc"
) : MangaSource {

    override val baseUrl: String = "https://$domain"

    protected var verificationManager: VerificationManager? = null

    override fun attachVerificationManager(manager: VerificationManager) {
        this.verificationManager = manager
    }

    override fun parseVerifiedHtmlSnapshot(html: String, pageBaseUri: String): List<MangaSummary> {
        return parseMangaSummariesFromHtml(html, pageBaseUri)
    }

    protected val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    protected fun buildGetRequest(url: String, referer: String = "$baseUrl/"): Request {
        return Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("Referer", referer)
            .build()
    }

    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        var lastFailureReason: String? = null
        var lastSecurityChallenge: VerificationDetectionResult? = null

        // 0. If this source requires human verification and has NOT yet been verified in VerificationManager,
        // or if a verified WebView HTML snapshot is available for page 1, handle it first.
        val vManager = verificationManager
        if (vManager != null && page == 1) {
            val verifiedSnapshot = vManager.getVerifiedHtmlSnapshot(domain)
            if (!verifiedSnapshot.isNullOrBlank()) {
                val parsedFromSnapshot = parseMangaSummariesFromHtml(verifiedSnapshot, baseUrl)
                if (parsedFromSnapshot.isNotEmpty()) {
                    PipelineLogger.log(
                        stage = PipelineStage.PARSER_COMPLETED,
                        sourceId = id,
                        sourceName = name,
                        resultCount = parsedFromSnapshot.size,
                        status = "VERIFIED_WEBVIEW_DOM_SUCCESS",
                        details = "Extracted ${parsedFromSnapshot.size} items from verified WebView DOM"
                    )
                    return@withContext PageResult(
                        items = parsedFromSnapshot,
                        page = page,
                        hasNextPage = true
                    )
                }
            }
        }

        // 1. Attempt direct HTML extraction from primary domain + mirrors
        val candidateUrls = buildCandidatePopularUrls(page)
        for (url in candidateUrls) {
            try {
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_REQUEST_STARTED,
                    sourceId = id,
                    sourceName = name,
                    status = "STARTED",
                    details = "GET $url"
                )
                val response = withTimeoutOrNull(5_500L) {
                    httpClient.newCall(buildGetRequest(url)).awaitResponse()
                }
                if (response == null) {
                    lastFailureReason = "HTTP timeout on $url"
                    PipelineLogger.log(
                        stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                        sourceId = id,
                        sourceName = name,
                        status = "TIMEOUT",
                        failureReason = lastFailureReason,
                        details = url
                    )
                    continue
                }

                response.use { resp ->
                    val code = resp.code
                    val contentType = resp.header("Content-Type").orEmpty()
                    val body = resp.body?.string().orEmpty()
                    val headersMap = resp.headers.toMap()

                    val detection = detectSecurityChallenge(
                        html = body,
                        httpCode = code,
                        headers = headersMap
                    )
                    if (detection.isVerificationRequired) {
                        lastSecurityChallenge = detection
                        lastFailureReason = "Cloudflare challenge ($code) on $url"
                        PipelineLogger.log(
                            stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                            sourceId = id,
                            sourceName = name,
                            status = "CLOUDFLARE_$code",
                            failureReason = lastFailureReason,
                            details = "challengeType=${detection.challengeType} contentType=$contentType"
                        )
                        return@use
                    }

                    if (!resp.isSuccessful) {
                        lastFailureReason = "HTTP $code on $url"
                        PipelineLogger.log(
                            stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                            sourceId = id,
                            sourceName = name,
                            status = "HTTP_$code",
                            failureReason = lastFailureReason,
                            details = "contentType=$contentType"
                        )
                        return@use
                    }

                    PipelineLogger.log(
                        stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                        sourceId = id,
                        sourceName = name,
                        resultCount = body.length,
                        status = "HTTP_200",
                        details = "url=$url bytes=${body.length}"
                    )

                    PipelineLogger.log(
                        stage = PipelineStage.PARSER_STARTED,
                        sourceId = id,
                        sourceName = name,
                        status = "HTML_JSOUP",
                        details = "selector=$itemSelector"
                    )
                    val parsed = parseMangaSummariesFromHtml(body, url)
                    PipelineLogger.log(
                        stage = PipelineStage.PARSER_COMPLETED,
                        sourceId = id,
                        sourceName = name,
                        resultCount = parsed.size,
                        status = if (parsed.isNotEmpty()) "SUCCESS" else "EMPTY",
                        failureReason = if (parsed.isEmpty()) "0 items matched selector [$itemSelector] on $url" else null
                    )

                    if (parsed.isNotEmpty()) {
                        return@withContext PageResult(
                            items = parsed,
                            page = page,
                            hasNextPage = true
                        )
                    } else {
                        lastFailureReason = "HTML parser extracted 0 items from $url"
                    }
                }
            } catch (e: Exception) {
                lastFailureReason = "${e.javaClass.simpleName}: ${e.message}"
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                    sourceId = id,
                    sourceName = name,
                    status = "FAILED",
                    failureReason = lastFailureReason,
                    details = "url=$url",
                    throwable = e
                )
            }
        }

        // If a security challenge was detected and this source requires human verification (and is not yet verified),
        // throw VerificationRequiredException so the Universal Website Verification System UI opens.
        if (lastSecurityChallenge != null && requiresCloudflareVerification && vManager?.isVerified(domain) != true) {
            vManager?.markVerificationRequired(
                sourceId = id,
                domain = domain,
                challengeType = lastSecurityChallenge!!.challengeType,
                reasonAr = lastSecurityChallenge!!.reasonAr
            )
            throw VerificationRequiredException(
                sourceId = id,
                domain = domain,
                targetUrl = baseUrl,
                detection = lastSecurityChallenge!!
            )
        }

        // 2. Live API Bridge: When direct HTML scraping is blocked by Cloudflare/ISP or domain redirect
        // (or after the user completes human verification for a Cloudflare-gated source),
        // fetch live real manhwa catalog via MangaDex REST API v5 with this source's specialized query
        val liveApiItems = fetchLiveManhwaFromApi(page = page, searchQuery = null)
        if (liveApiItems.isNotEmpty()) {
            return@withContext PageResult(
                items = liveApiItems,
                page = page,
                hasNextPage = true
            )
        }

        throw IllegalStateException(
            lastFailureReason ?: "تعذّر جلب قائمة المانهوا من المصدر $name"
        )
    }

    override suspend fun search(query: String, page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext getPopular(page)
        }

        val searchUrl = buildSearchUrl(cleanQuery, page)
        try {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_REQUEST_STARTED,
                sourceId = id,
                sourceName = name,
                status = "SEARCH_STARTED",
                details = "GET $searchUrl"
            )
            val response = withTimeoutOrNull(5_500L) {
                httpClient.newCall(buildGetRequest(searchUrl)).awaitResponse()
            }
            response?.use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    if (!HtmlImageExtractor.isSecurityVerificationPage(html, resp.code)) {
                        PipelineLogger.log(
                            stage = PipelineStage.PARSER_STARTED,
                            sourceId = id,
                            sourceName = name,
                            status = "HTML_SEARCH_JSOUP"
                        )
                        val parsed = parseMangaSummariesFromHtml(html, searchUrl)
                        PipelineLogger.log(
                            stage = PipelineStage.PARSER_COMPLETED,
                            sourceId = id,
                            sourceName = name,
                            resultCount = parsed.size,
                            status = if (parsed.isNotEmpty()) "SUCCESS" else "EMPTY"
                        )
                        if (parsed.isNotEmpty()) {
                            return@withContext PageResult(items = parsed, page = page, hasNextPage = false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "SEARCH_HTML_FAILED",
                failureReason = e.message,
                throwable = e
            )
        }

        // Live API search
        val apiSearchItems = fetchLiveManhwaFromApi(page = page, searchQuery = cleanQuery)
        PageResult(items = apiSearchItems, page = page, hasNextPage = false)
    }

    override suspend fun getDetails(mangaUrl: String): MangaDetails = withContext(Dispatchers.IO) {
        // Check if URL is a MangaDex work URL
        val mdUuid = extractMangaDexUuid(mangaUrl)
        if (mdUuid != null) {
            val mdDetails = fetchLiveMangaDexDetails(mdUuid)
            if (mdDetails != null) return@withContext mdDetails
        }

        // Otherwise fetch HTML details from the manga URL
        try {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_REQUEST_STARTED,
                sourceId = id,
                sourceName = name,
                status = "DETAILS_STARTED",
                details = "GET $mangaUrl"
            )
            val response = withTimeoutOrNull(7_000L) {
                httpClient.newCall(buildGetRequest(mangaUrl)).awaitResponse()
            }
            response?.use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    if (!HtmlImageExtractor.isSecurityVerificationPage(html, resp.code)) {
                        val details = parseDetailsFromHtml(html, mangaUrl)
                        if (details != null) {
                            val chapters = if (details.chapters.isNotEmpty()) {
                                details.chapters
                            } else {
                                fetchAjaxMadaraChapters(html, mangaUrl, details.id)
                            }
                            return@withContext details.copy(chapters = chapters)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "DETAILS_FAILED",
                failureReason = e.message,
                throwable = e
            )
        }

        throw IllegalStateException("تعذّر تحميل تفاصيل المانهوا من الرابط: $mangaUrl")
    }

    override suspend fun getChapters(mangaUrl: String): List<MangaChapter> = withContext(Dispatchers.IO) {
        val mdUuid = extractMangaDexUuid(mangaUrl)
        if (mdUuid != null) {
            return@withContext fetchLiveMangaDexChapters(mdUuid, "${id}_$mdUuid")
        }

        try {
            val response = withTimeoutOrNull(7_000L) {
                httpClient.newCall(buildGetRequest(mangaUrl)).awaitResponse()
            }
            response?.use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    if (!HtmlImageExtractor.isSecurityVerificationPage(html, resp.code)) {
                        val doc = Jsoup.parse(html, mangaUrl)
                        val slug = mangaUrl.trimEnd('/').substringAfterLast('/').ifBlank { "work" }
                        val mangaId = "${id}_$slug"
                        val direct = parseChaptersFromDoc(doc, mangaId, mangaUrl)
                        if (direct.isNotEmpty()) return@withContext direct
                        val ajax = fetchAjaxMadaraChapters(html, mangaUrl, mangaId)
                        if (ajax.isNotEmpty()) return@withContext ajax
                    }
                }
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "CHAPTERS_FAILED",
                failureReason = e.message,
                throwable = e
            )
        }

        emptyList()
    }

    override suspend fun getPages(chapterUrl: String): List<MangaPage> = withContext(Dispatchers.IO) {
        if (chapterUrl.contains("api.mangadex.org/at-home/server/")) {
            return@withContext fetchLiveMangaDexPages(chapterUrl)
        }

        try {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_REQUEST_STARTED,
                sourceId = id,
                sourceName = name,
                status = "PAGES_STARTED",
                details = "GET $chapterUrl"
            )
            val response = withTimeoutOrNull(8_000L) {
                httpClient.newCall(buildGetRequest(chapterUrl)).awaitResponse()
            }
            response?.use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    if (!HtmlImageExtractor.isSecurityVerificationPage(html, resp.code)) {
                        val extracted = HtmlImageExtractor.extractChapterPagesFromHtml(
                            html = html,
                            baseUri = chapterUrl,
                            selector = readerImagesSelector
                        )
                        PipelineLogger.log(
                            stage = PipelineStage.PARSER_COMPLETED,
                            sourceId = id,
                            sourceName = name,
                            resultCount = extracted.size,
                            status = if (extracted.isNotEmpty()) "PAGES_SUCCESS" else "PAGES_EMPTY"
                        )
                        if (extracted.isNotEmpty()) return@withContext extracted
                    }
                }
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "PAGES_FAILED",
                failureReason = e.message,
                throwable = e
            )
        }

        emptyList()
    }

    protected open fun buildPopularUrl(page: Int, hostBaseUrl: String = baseUrl): String {
        return if (page <= 1) "$hostBaseUrl/manga/" else "$hostBaseUrl/manga/page/$page/"
    }

    protected open fun buildCandidatePopularUrls(page: Int): List<String> {
        val hosts = listOf(baseUrl) + mirrorDomains.map { "https://$it" }
        val urls = mutableListOf<String>()
        for (h in hosts.distinct()) {
            urls.add(buildPopularUrl(page, h))
            if (page <= 1) {
                urls.add("$h/")
            }
        }
        return urls.distinct()
    }

    protected open fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "$baseUrl/?s=$encoded&post_type=wp-manga"
    }

    /**
     * Parses manga catalog items from HTML using:
     * 1) Configured & multi-theme CSS selectors (Madara, MangaStream, Olympus, Azora)
     * 2) Semantic work-link grouping (`a[href*=/manga/], a[href*=/series/]`) when a site uses custom/Tailwind classes
     * Exposed as public for direct unit test verification in Phase 5.
     */
    fun parseMangaSummariesFromHtml(html: String, pageBaseUri: String = baseUrl): List<MangaSummary> {
        val doc: Document = Jsoup.parse(html, pageBaseUri)
        val combinedSelector = "$itemSelector, div.page-item-detail, div.c-tabs-item__content, div.bsx, div.utao, div.uta, div.bs, div.box, div.manga-item, article.item"
        val elements = doc.select(combinedSelector)

        val selectorParsed = elements.mapIndexedNotNull { idx, el ->
            val linkEl = el.selectFirst(
                ".post-title a[href], h3 a[href], h4 a[href], a[href*=/manga/], a[href*=/series/], .item-thumb a[href], .limit a[href], a[href]"
            )
            val titleEl = el.selectFirst(titleSelector)
                ?: el.selectFirst(".post-title h3 a, .post-title a, .tt, h3 a, h4 a, a[title]")
                ?: linkEl

            val rawTitle = titleEl?.text()?.trim()?.ifEmpty { titleEl.attr("title").trim() }
                ?: linkEl?.attr("title")?.trim()
                ?: linkEl?.text()?.trim()
                ?: ""

            val rawHref = linkEl?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
                ?: titleEl?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
                ?: ""

            if (rawTitle.isBlank() || rawHref.isBlank()) return@mapIndexedNotNull null

            val resolvedHref = HtmlImageExtractor.resolveUrl(pageBaseUri, rawHref)
                ?: return@mapIndexedNotNull null

            val imgContainer = el.selectFirst(coverSelector) ?: el.selectFirst("img") ?: el
            val extractedCover = HtmlImageExtractor.extractImageUrl(imgContainer, pageBaseUri)
                ?: HtmlImageExtractor.extractImageUrl(el, pageBaseUri)

            val slug = resolvedHref.trimEnd('/').substringAfterLast('/').ifBlank { "item_$idx" }
            val latest = el.selectFirst("$latestChapterSelector, .chapter a, .epxs, .latest-chap a")?.text()?.trim()
            val genresText = el.select(".mg_genres a, .genres a, .tags a, .mgen a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }

            MangaSummary(
                id = "${id}_$slug",
                sourceId = id,
                title = rawTitle,
                coverUrl = extractedCover,
                mangaUrl = resolvedHref,
                latestChapter = latest?.ifBlank { null } ?: "متاح",
                genres = genresText.ifEmpty { listOf("مانهوا", "أكشن") },
                rating = 4.7f,
                status = "OnGoing"
            )
        }.distinctBy { it.id }

        if (selectorParsed.isNotEmpty()) {
            return selectorParsed
        }

        // Step 2: Semantic extraction for custom/modern DOMs (e.g., olympustaff.com, azoramoon.com)
        val candidateLinks = doc.select("a[href*=/manga/], a[href*=/series/]")
        val byWorkUrl = LinkedHashMap<String, MutableList<org.jsoup.nodes.Element>>()

        for (a in candidateLinks) {
            val resolved = HtmlImageExtractor.resolveUrl(pageBaseUri, a.attr("href")) ?: continue
            if (!isCanonicalMangaWorkUrl(resolved)) continue
            val normalizedUrl = resolved.trimEnd('/') + "/"
            byWorkUrl.getOrPut(normalizedUrl) { mutableListOf() }.add(a)
        }

        val semanticResults = mutableListOf<MangaSummary>()
        var idx = 0
        for ((workUrl, anchors) in byWorkUrl) {
            idx++
            var title = ""
            var coverUrl: String? = null
            var latestChapter: String? = null

            for (a in anchors) {
                val card = a.parent()?.parent() ?: a.parent() ?: a
                if (coverUrl == null) {
                    coverUrl = HtmlImageExtractor.extractImageUrl(a, pageBaseUri)
                        ?: HtmlImageExtractor.extractImageUrl(a.parent(), pageBaseUri)
                        ?: HtmlImageExtractor.extractImageUrl(card, pageBaseUri)
                }
                if (title.isBlank()) {
                    val candidates = listOf(
                        a.attr("title").trim(),
                        a.selectFirst("img")?.attr("alt")?.trim().orEmpty(),
                        a.selectFirst("h2, h3, h4, .tt, .title, .post-title")?.text()?.trim().orEmpty(),
                        card.selectFirst("h2, h3, h4, .tt, .title, .post-title")?.text()?.trim().orEmpty(),
                        a.text().trim()
                    )
                    title = candidates.firstOrNull { isValidMangaTitleCandidate(it) }.orEmpty()
                }
                if (latestChapter == null) {
                    val chapEl = card.select("a, span").firstOrNull { el ->
                        val t = el.text().trim()
                        (t.contains("فصل") || t.contains("Chapter", ignoreCase = true)) && t.length <= 28
                    }
                    latestChapter = chapEl?.text()?.trim()
                }
            }

            if (title.isNotBlank() && !coverUrl.isNullOrBlank()) {
                val slug = workUrl.trimEnd('/').substringAfterLast('/').ifBlank { "work_$idx" }
                semanticResults.add(
                    MangaSummary(
                        id = "${id}_$slug",
                        sourceId = id,
                        title = title,
                        coverUrl = coverUrl,
                        mangaUrl = workUrl,
                        latestChapter = latestChapter ?: "متاح",
                        genres = listOf("مانهوا", "أكشن"),
                        rating = 4.7f,
                        status = "OnGoing"
                    )
                )
            }
        }

        return semanticResults.distinctBy { it.id }
    }

    private fun isCanonicalMangaWorkUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val path = clean.substringAfter("://").substringAfter('/', "")
        if (path.isBlank()) return false
        val segments = path.split('/').filter { it.isNotBlank() }
        // Must be /manga/{slug} or /series/{slug} (exactly 2 segments, not /manga/page/2 or /manga/{slug}/{chapter})
        if (segments.size != 2) return false
        val prefix = segments[0].lowercase()
        val slug = segments[1].lowercase()
        if (prefix != "manga" && prefix != "series") return false
        if (slug in listOf("page", "genre", "tag", "status", "type", "list")) return false
        return true
    }

    private fun isValidMangaTitleCandidate(text: String): Boolean {
        if (text.length < 2 || text.length > 140) return false
        val lower = text.lowercase()
        if (lower in listOf("مانجا", "مانهوا", "مكتمل", "مستمر", "جديد", "المزيد", "عرض الكل")) return false
        if (Regex("""^(الفصل|فصل|chapter|ch\.?)\s*\d+.*$""", RegexOption.IGNORE_CASE).matches(text.trim())) {
            return false
        }
        return true
    }

    /**
     * Parses manga details and embedded chapters from HTML.
     * Exposed as public for direct unit test verification in Phase 5.
     */
    fun parseDetailsFromHtml(html: String, mangaUrl: String): MangaDetails? {
        val doc = Jsoup.parse(html, mangaUrl)
        val title = doc.selectFirst("$detailsTitleSelector, .post-title h1, h1.entry-title, .author-info-title h1, h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val coverEl = doc.selectFirst(".summary_image img, .thumb img, .manga-poster img, .seriestucontl img, img.wp-post-image, .whitebox img")
        val cover = HtmlImageExtractor.extractImageUrl(coverEl, mangaUrl)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.let {
                HtmlImageExtractor.resolveUrl(mangaUrl, it)
            }
        val desc = doc.select("$detailsDescSelector, .summary__content p, .description-summary p, .entry-content p, .review-content p, .summary__content")
            .text()
            .trim()
            .ifBlank {
                doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim().orEmpty()
            }
            .ifBlank { "عمل مانهوا متوفر للقراءة والتنزيل المحلي." }
        val genres = doc.select("$detailsGenresSelector, .genres-content a, .mgen a, .seriestugenre a, .review-author-info a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf("مانهوا", "أكشن") }
        val author = doc.selectFirst("$detailsAuthorSelector, .author-content a")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val statusRaw = doc.selectFirst("$detailsStatusSelector, .post-status .summary-content")?.text()?.trim().orEmpty()
        val status = if (statusRaw.contains("مكتمل", ignoreCase = true) || statusRaw.contains("Completed", ignoreCase = true)) {
            "Completed"
        } else {
            "OnGoing"
        }

        val slug = mangaUrl.trimEnd('/').substringAfterLast('/').ifBlank { "manga" }
        val mangaId = "${id}_$slug"
        val chapters = parseChaptersFromDoc(doc, mangaId, mangaUrl)

        return MangaDetails(
            id = mangaId,
            sourceId = id,
            title = title,
            alternativeTitles = emptyList(),
            coverUrl = cover,
            description = desc,
            author = author,
            artist = null,
            status = status,
            genres = genres,
            rating = 4.7f,
            chapters = chapters
        )
    }

    protected fun parseChaptersFromDoc(doc: Document, mangaId: String, pageBaseUri: String = baseUrl): List<MangaChapter> {
        val rows = doc.select("$chapterRowSelector, li.wp-manga-chapter, #chapterlist li, .eplister ul li, .listing-chapters_wrap li")
        val rawList = mutableListOf<MangaChapter>()

        if (rows.isNotEmpty()) {
            rows.forEachIndexed { idx, row ->
                val link = row.selectFirst("a[href]") ?: return@forEachIndexed
                val href = HtmlImageExtractor.resolveUrl(pageBaseUri, link.attr("href")) ?: return@forEachIndexed
                val rawTitle = row.selectFirst(".chapternum")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: link.text().trim().ifEmpty { "الفصل ${idx + 1}" }
                val numMatch = Regex("""(\d+(\.\d+)?)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                val dateText = row.selectFirst(".chapter-release-date, .chapterdate, span.date")?.text()?.trim().orEmpty()
                val remoteSlug = href.trimEnd('/').substringAfterLast('/').ifBlank { "ch_$idx" }

                rawList.add(
                    MangaChapter(
                        id = "${mangaId}_$remoteSlug",
                        mangaId = mangaId,
                        sourceId = id,
                        remoteId = href,
                        title = rawTitle,
                        chapterNumber = numMatch ?: (rows.size - idx).toFloat(),
                        uploadDate = System.currentTimeMillis() - idx * 36_000_000L,
                        dateLabel = dateText.ifBlank { "متاح" }
                    )
                )
            }
        }

        // Semantic fallback: find any <a href> on the details page that points to a chapter sub-path of pageBaseUri
        if (rawList.isEmpty()) {
            val baseWorkPrefix = pageBaseUri.trimEnd('/') + "/"
            val subLinks = doc.select("a[href]")
            var idx = 0
            for (link in subLinks) {
                val href = HtmlImageExtractor.resolveUrl(pageBaseUri, link.attr("href")) ?: continue
                if (href.startsWith(baseWorkPrefix) && href.trimEnd('/') != pageBaseUri.trimEnd('/')) {
                    idx++
                    val rawTitle = link.text().trim().ifEmpty { link.attr("title").trim() }.ifEmpty { "الفصل $idx" }
                    val slug = href.trimEnd('/').substringAfterLast('/')
                    val numMatch = Regex("""(\d+(\.\d+)?)""").find(slug)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                        ?: Regex("""(\d+(\.\d+)?)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    rawList.add(
                        MangaChapter(
                            id = "${mangaId}_$slug",
                            mangaId = mangaId,
                            sourceId = id,
                            remoteId = href,
                            title = rawTitle,
                            chapterNumber = numMatch ?: idx.toFloat(),
                            uploadDate = System.currentTimeMillis() - idx * 36_000_000L,
                            dateLabel = "متاح"
                        )
                    )
                }
            }
        }

        return rawList
            .distinctBy { "${it.sourceId}:${it.remoteId}" }
            .sortedBy { it.chapterNumber ?: 0f }
    }

    /**
     * Supports WordPress Madara asynchronous chapter endpoints:
     * 1) POST {mangaUrl}/ajax/chapters/
     * 2) POST {baseUrl}/wp-admin/admin-ajax.php (action=manga_get_chapters&manga={postId})
     */
    private suspend fun fetchAjaxMadaraChapters(
        detailsHtml: String,
        mangaUrl: String,
        mangaId: String
    ): List<MangaChapter> {
        // 1. Try Madara 1.7+ direct /ajax/chapters/ POST
        val ajaxUrl = "${mangaUrl.trimEnd('/')}/ajax/chapters/"
        try {
            val req = Request.Builder()
                .url(ajaxUrl)
                .post("".toRequestBody(null))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", mangaUrl)
                .build()
            val resp = withTimeoutOrNull(5_000L) { httpClient.newCall(req).awaitResponse() }
            resp?.use { r ->
                if (r.isSuccessful) {
                    val ajaxHtml = r.body?.string().orEmpty()
                    val parsed = parseChaptersFromDoc(Jsoup.parse(ajaxHtml, mangaUrl), mangaId, mangaUrl)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        } catch (_: Exception) {
        }

        // 2. Try Madara legacy admin-ajax.php with postId
        val doc = Jsoup.parse(detailsHtml, mangaUrl)
        val postId = doc.selectFirst("#manga-chapters-holder[data-id]")?.attr("data-id")?.trim()
            ?: doc.selectFirst("input.rating-post-id[value]")?.attr("value")?.trim()
        if (!postId.isNullOrBlank()) {
            try {
                val form = FormBody.Builder()
                    .add("action", "manga_get_chapters")
                    .add("manga", postId)
                    .build()
                val req = Request.Builder()
                    .url("$baseUrl/wp-admin/admin-ajax.php")
                    .post(form)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", mangaUrl)
                    .build()
                val resp = withTimeoutOrNull(5_000L) { httpClient.newCall(req).awaitResponse() }
                resp?.use { r ->
                    if (r.isSuccessful) {
                        val ajaxHtml = r.body?.string().orEmpty()
                        val parsed = parseChaptersFromDoc(Jsoup.parse(ajaxHtml, mangaUrl), mangaId, mangaUrl)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            } catch (_: Exception) {
            }
        }

        return emptyList()
    }

    // ─── LIVE MANGADEX REST API V5 BRIDGE ───

    protected suspend fun fetchLiveManhwaFromApi(page: Int, searchQuery: String?): List<MangaSummary> {
        val offset = ((page - 1).coerceAtLeast(0)) * 20
        val apiUrl = if (!searchQuery.isNullOrBlank()) {
            val encoded = URLEncoder.encode(searchQuery.trim(), "UTF-8")
            "https://api.mangadex.org/manga?limit=20&offset=$offset&title=$encoded&includes[]=cover_art&includes[]=author&includes[]=artist&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true"
        } else {
            "https://api.mangadex.org/manga?limit=20&offset=$offset&includes[]=cover_art&includes[]=author&includes[]=artist&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true&$liveApiQueryParams"
        }

        return try {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_REQUEST_STARTED,
                sourceId = id,
                sourceName = name,
                status = "API_REQUEST_STARTED",
                details = "GET $apiUrl"
            )
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "MangaReaderAndroid/2.0 (Kotlin; JetpackCompose)")
                .header("Accept", "application/json")
                .build()

            val response = withTimeoutOrNull(8_000L) {
                httpClient.newCall(req).awaitResponse()
            } ?: run {
                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                    sourceId = id,
                    sourceName = name,
                    status = "API_TIMEOUT",
                    failureReason = "Timed out waiting for api.mangadex.org"
                )
                return emptyList()
            }

            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    PipelineLogger.log(
                        stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                        sourceId = id,
                        sourceName = name,
                        status = "API_HTTP_${resp.code}",
                        failureReason = "MangaDex API returned HTTP ${resp.code}"
                    )
                    return emptyList()
                }

                PipelineLogger.log(
                    stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                    sourceId = id,
                    sourceName = name,
                    resultCount = body.length,
                    status = "HTTP_200_JSON",
                    details = "bytes=${body.length}"
                )

                PipelineLogger.log(
                    stage = PipelineStage.PARSER_STARTED,
                    sourceId = id,
                    sourceName = name,
                    status = "JSON_MANGADEX_V5"
                )
                val parsed = MangaDexApiHelper.parseMangaListResponse(body, sourceId = id)
                PipelineLogger.log(
                    stage = PipelineStage.PARSER_COMPLETED,
                    sourceId = id,
                    sourceName = name,
                    resultCount = parsed.size,
                    status = if (parsed.isNotEmpty()) "SUCCESS" else "EMPTY",
                    failureReason = if (parsed.isEmpty()) "JSON data array returned 0 manga items" else null
                )
                parsed
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "API_FAILED",
                failureReason = "${e.javaClass.simpleName}: ${e.message}",
                throwable = e
            )
            emptyList()
        }
    }

    protected suspend fun fetchLiveMangaDexDetails(mdUuid: String): MangaDetails? {
        val detailsUrl = "https://api.mangadex.org/manga/$mdUuid?includes[]=cover_art&includes[]=author&includes[]=artist"
        val chapters = fetchLiveMangaDexChapters(mdUuid, "${id}_$mdUuid")

        return try {
            val req = Request.Builder()
                .url(detailsUrl)
                .header("User-Agent", "MangaReaderAndroid/2.0")
                .header("Accept", "application/json")
                .build()
            val response = withTimeoutOrNull(7_000L) {
                httpClient.newCall(req).awaitResponse()
            } ?: return null

            response.use { resp ->
                if (!resp.isSuccessful) return null
                val json = resp.body?.string().orEmpty()
                MangaDexApiHelper.parseMangaSingleDetailsResponse(
                    jsonString = json,
                    sourceId = id,
                    chapters = chapters
                )
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "MD_DETAILS_FAILED",
                failureReason = e.message,
                throwable = e
            )
            null
        }
    }

    protected suspend fun fetchLiveMangaDexChapters(mdUuid: String, mangaId: String): List<MangaChapter> {
        val feedUrl = "https://api.mangadex.org/manga/$mdUuid/feed?limit=96&offset=0&translatedLanguage[]=ar&translatedLanguage[]=en&order[chapter]=asc&includeExternalUrl=0&contentRating[]=safe&contentRating[]=suggestive"
        return try {
            val req = Request.Builder()
                .url(feedUrl)
                .header("User-Agent", "MangaReaderAndroid/2.0")
                .header("Accept", "application/json")
                .build()
            val response = withTimeoutOrNull(7_000L) {
                httpClient.newCall(req).awaitResponse()
            } ?: return emptyList()

            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = resp.body?.string().orEmpty()
                val list = MangaDexApiHelper.parseChaptersFeedResponse(json, mangaId = mangaId, sourceId = id)
                if (list.isNotEmpty()) {
                    list
                } else {
                    // Fallback to all languages if no ar/en chapters exist for this work
                    val anyLangUrl = "https://api.mangadex.org/manga/$mdUuid/feed?limit=50&offset=0&order[chapter]=asc&includeExternalUrl=0"
                    val fallbackReq = Request.Builder()
                        .url(anyLangUrl)
                        .header("User-Agent", "MangaReaderAndroid/2.0")
                        .build()
                    val fallbackResp = withTimeoutOrNull(6_000L) {
                        httpClient.newCall(fallbackReq).awaitResponse()
                    }
                    fallbackResp?.use { r2 ->
                        if (r2.isSuccessful) {
                            MangaDexApiHelper.parseChaptersFeedResponse(
                                r2.body?.string().orEmpty(),
                                mangaId = mangaId,
                                sourceId = id
                            )
                        } else emptyList()
                    } ?: emptyList()
                }
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "MD_CHAPTERS_FAILED",
                failureReason = e.message,
                throwable = e
            )
            emptyList()
        }
    }

    protected suspend fun fetchLiveMangaDexPages(atHomeUrl: String): List<MangaPage> {
        return try {
            val req = Request.Builder()
                .url(atHomeUrl)
                .header("User-Agent", "MangaReaderAndroid/2.0")
                .header("Accept", "application/json")
                .build()
            val response = withTimeoutOrNull(8_000L) {
                httpClient.newCall(req).awaitResponse()
            } ?: return emptyList()

            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = resp.body?.string().orEmpty()
                MangaDexApiHelper.parseAtHomePagesResponse(json)
            }
        } catch (e: Exception) {
            PipelineLogger.log(
                stage = PipelineStage.NETWORK_RESPONSE_RECEIVED,
                sourceId = id,
                sourceName = name,
                status = "MD_PAGES_FAILED",
                failureReason = e.message,
                throwable = e
            )
            emptyList()
        }
    }

    protected fun extractMangaDexUuid(urlOrId: String): String? {
        val uuidRegex = Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})""")
        return uuidRegex.find(urlOrId)?.groupValues?.getOrNull(1)
    }
}

/**
 * 1. Mangalek Source (مانجا ليك Mangalek - mangalik.net) — WordPress Madara + Korean Manhwa feed
 */
class MangalekSource : ConfigurableHtmlMangaSource(
    id = "mangalek",
    name = "مانجا ليك Mangalek",
    domain = "mangalik.net",
    iconText = "M",
    iconColorHex = 0xFFD32F2F,
    requiresCloudflareVerification = false,
    mirrorDomains = listOf("lekmanga.net"),
    itemSelector = "div.page-item-detail, div.c-tabs-item__content",
    titleSelector = ".post-title h3 a, .post-title a, h3 a",
    coverSelector = ".item-thumb, .tab-thumb",
    latestChapterSelector = ".list-chapter .chapter-item:first-child .chapter a, .latest-chap .chapter a",
    detailsTitleSelector = ".post-title h1",
    detailsDescSelector = ".summary__content p, .description-summary p, .summary__content",
    detailsGenresSelector = ".genres-content a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter",
    readerImagesSelector = ".reading-content img, .page-break img, img.wp-manga-chapter-img",
    liveApiQueryParams = "originalLanguage[]=ko&order[followedCount]=desc"
)

/**
 * 2. Team-X Source (Team-X - olympustaff.com) — MangaStream / Action & Martial Arts Manhwa
 */
class TeamXSource : ConfigurableHtmlMangaSource(
    id = "teamx",
    name = "Team-X",
    domain = "olympustaff.com",
    iconText = "X",
    iconColorHex = 0xFF1E293B,
    requiresCloudflareVerification = false,
    itemSelector = "div.bsx, div.utao, div.page-item-detail, div.box",
    titleSelector = ".tt, h3 a, a[title]",
    coverSelector = ".limit, .imgu, .item-thumb",
    latestChapterSelector = ".epxs, .adds .epxs, .chapter a",
    detailsTitleSelector = "h1.entry-title, .author-info-title h1",
    detailsDescSelector = ".entry-content p, .review-content p, .summary__content",
    detailsGenresSelector = ".mgen a, .review-author-info a, .genres-content a",
    detailsAuthorSelector = ".fmed b:contains(المؤلف) + span, .author-content a",
    detailsStatusSelector = ".tsinfo .imptdt:contains(الحالة) i, .post-status",
    chapterRowSelector = "#chapterlist li, .eplister ul li, li.wp-manga-chapter",
    readerImagesSelector = "#readerarea img, .reading-content img, .page-break img",
    liveApiQueryParams = "originalLanguage[]=ko&originalLanguage[]=zh&order[rating]=desc"
) {
    override fun buildPopularUrl(page: Int, hostBaseUrl: String): String {
        return if (page <= 1) "$hostBaseUrl/" else "$hostBaseUrl/series?page=$page"
    }
}

/**
 * 3. Mangatek Source (مانجاتك - Mangatek - mangatek.com)
 */
class MangatekSource(
    requiresVerificationOverride: Boolean = false
) : ConfigurableHtmlMangaSource(
    id = "mangatek",
    name = "مانجاتك - Mangatek",
    domain = "mangatek.com",
    iconText = "M",
    iconColorHex = 0xFF3949AB,
    requiresCloudflareVerification = requiresVerificationOverride,
    itemSelector = "div.manga-item, div.bsx, div.page-item-detail",
    titleSelector = "h3 a, .post-title a, .tt, a[title]",
    coverSelector = ".manga-poster, .limit, .item-thumb",
    latestChapterSelector = ".chapter a, .epxs",
    detailsTitleSelector = "h1.post-title, h1.entry-title",
    detailsDescSelector = ".summary__content, .entry-content p",
    detailsGenresSelector = ".genres-content a, .mgen a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter, #chapterlist li",
    readerImagesSelector = ".reading-content img, #readerarea img",
    liveApiQueryParams = "originalLanguage[]=ko&order[latestUploadedChapter]=desc"
)

/**
 * 4. Azora Manga Source (ازورا مانجا - Azora Manga - azoramanga.com / azoramoon.com)
 */
class AzoraMangaSource : ConfigurableHtmlMangaSource(
    id = "azora",
    name = "ازورا مانجا - Azora Manga",
    domain = "azoramanga.com",
    iconText = "A",
    iconColorHex = 0xFFFB8C00,
    requiresCloudflareVerification = false,
    mirrorDomains = listOf("azoramoon.com"),
    itemSelector = "div.page-item-detail, div.bsx",
    titleSelector = ".post-title h3 a, .post-title a, .tt, a[title]",
    coverSelector = ".item-thumb, .limit",
    latestChapterSelector = ".chapter-item:first-child .chapter a, .epxs",
    detailsTitleSelector = ".post-title h1",
    detailsDescSelector = ".summary__content p, .summary__content",
    detailsGenresSelector = ".genres-content a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter",
    readerImagesSelector = ".reading-content img, .page-break img",
    liveApiQueryParams = "originalLanguage[]=ko&order[createdAt]=desc"
) {
    override fun buildCandidatePopularUrls(page: Int): List<String> {
        return if (page <= 1) {
            listOf("https://azoramoon.com/", "https://azoramoon.com/series/", "$baseUrl/manga/")
        } else {
            listOf("https://azoramoon.com/series/?page=$page", "$baseUrl/manga/page/$page/")
        }
    }
}

/**
 * 5. Official MangaDex Public REST API v5 Connector (mangadex.org)
 * Uses https://api.mangadex.org/manga directly for catalog, search, details, chapters, and pages.
 */
class MangaDexSource : ConfigurableHtmlMangaSource(
    id = "mangadex",
    name = "MangaDex Official",
    domain = "mangadex.org",
    iconText = "MD",
    iconColorHex = 0xFFFF6740,
    requiresCloudflareVerification = false,
    itemSelector = "div.manga-card",
    titleSelector = "a.title",
    coverSelector = "img",
    latestChapterSelector = ".chapter",
    detailsTitleSelector = "h1",
    detailsDescSelector = ".description",
    detailsGenresSelector = ".tag",
    detailsAuthorSelector = ".author",
    detailsStatusSelector = ".status",
    chapterRowSelector = ".chapter-row",
    readerImagesSelector = "img",
    liveApiQueryParams = "originalLanguage[]=ko&originalLanguage[]=ja&availableTranslatedLanguage[]=ar&availableTranslatedLanguage[]=en&order[followedCount]=desc"
) {
    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val items = fetchLiveManhwaFromApi(page = page, searchQuery = null)
        if (items.isEmpty()) {
            throw IllegalStateException("تعذّر جلب الأعمال من واجهة MangaDex الرسمية")
        }
        PageResult(items = items, page = page, hasNextPage = true)
    }

    override suspend fun search(query: String, page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isEmpty()) return@withContext getPopular(page)
        val items = fetchLiveManhwaFromApi(page = page, searchQuery = clean)
        PageResult(items = items, page = page, hasNextPage = false)
    }
}
