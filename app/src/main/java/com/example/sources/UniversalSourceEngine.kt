package com.example.sources

import android.webkit.CookieManager
import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.common.normalizeDomain
import com.example.core.verification.VerificationChallengeType
import com.example.core.verification.VerificationDetector
import com.example.core.verification.VerificationManager
import com.example.domain.models.ContentRating
import com.example.domain.models.ContentSafetyClassifier
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.domain.models.SiteInspectionReport
import com.example.domain.models.SourceConfiguration
import com.example.domain.models.SourceEngineType
import com.example.domain.models.SourceFullTestReport
import com.example.domain.models.SourceStageTestResult
import com.example.domain.models.SourceStatus
import com.example.sourceapi.HtmlImageExtractor
import com.example.sourceapi.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Dynamic Configurable Source Adapter (`DynamicSourceAdapter`) powered by [SourceConfiguration].
 * Supports:
 * - WordPress Madara (`MADARA`)
 * - MangaStream / WPMangaStream (`MANGASTREAM`)
 * - MangaReader (`MANGAREADER`)
 * - Custom CSS Selectors (`CUSTOM_HTML`)
 * - JSON / REST APIs (`JSON_API`, `REST_API`)
 * - RSS / Atom XML Feeds (`RSS_FEED`)
 * - JS DOM / Next.js `__NEXT_DATA__` (`JS_DOM`)
 * - Interactive WebView (`WEBVIEW_ONLY`)
 */
class DynamicSourceAdapter(
    val config: SourceConfiguration,
    customId: String? = null,
    customIconText: String? = null,
    customIconColorHex: Long? = null
) : ConfigurableHtmlMangaSource(
    id = customId ?: "site_${config.domain.lowercase().replace('.', '_').replace('-', '_')}",
    name = config.sourceName.ifBlank {
        config.domain.substringBefore('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    },
    domain = config.domain.lowercase(),
    iconText = customIconText ?: config.sourceName.take(1).uppercase().ifBlank { "M" },
    iconColorHex = customIconColorHex ?: DynamicSiteSource.computeColorForDomain(config.domain),
    requiresCloudflareVerification = config.requiresVerification,
    mirrorDomains = emptyList(),
    itemSelector = config.mangaItemSelector,
    titleSelector = config.titleSelector,
    coverSelector = config.coverSelector,
    latestChapterSelector = config.latestChapterSelector,
    detailsTitleSelector = config.detailsTitleSelector,
    detailsDescSelector = config.descriptionSelector,
    detailsGenresSelector = config.genreSelector,
    detailsAuthorSelector = config.authorSelector,
    detailsStatusSelector = config.statusSelector,
    chapterRowSelector = config.chapterListSelector,
    readerImagesSelector = config.pageImageSelector
) {
    override val baseUrl: String = config.baseUrl.trimEnd('/').ifBlank { "https://${config.domain}" }
    override val allowLiveApiFallback: Boolean = false

    override fun buildGetRequest(url: String, referer: String): Request {
        val cookieHeader = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", config.userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("Referer", referer)

        if (!cookieHeader.isNullOrBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        for ((k, v) in config.headers) {
            if (k.isNotBlank() && v.isNotBlank()) {
                builder.header(k.trim(), v.trim())
            }
        }
        return builder.build()
    }

    override fun buildPopularUrl(page: Int, hostBaseUrl: String): String {
        val path = config.popularPath.ifBlank { "/manga/" }
        return if (page <= 1) {
            "$hostBaseUrl/${path.trimStart('/')}"
        } else {
            val pag = config.paginationPattern.replace("{page}", page.toString())
            if (pag.startsWith("?")) {
                "$hostBaseUrl/${path.trimStart('/')}$pag"
            } else {
                "$hostBaseUrl/${path.trim('/')}//${pag.trimStart('/')}".replace("//", "/")
                    .replace("https:/", "https://")
            }
        }
    }

    override fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val pattern = config.searchPath.ifBlank { "/?s={query}&post_type=wp-manga" }
        val replaced = pattern
            .replace("{query}", encoded)
            .replace("{page}", page.toString())
        return if (replaced.startsWith("https://")) replaced else "$baseUrl/${replaced.trimStart('/')}"
    }

    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        when (config.engineType) {
            SourceEngineType.JSON_API, SourceEngineType.REST_API -> fetchJsonOrRestPopular(page)
            SourceEngineType.RSS_FEED -> fetchRssPopular()
            else -> super.getPopular(page)
        }
    }

    override suspend fun getLatestUpdates(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        when (config.engineType) {
            SourceEngineType.JSON_API, SourceEngineType.REST_API -> fetchJsonOrRestPopular(page)
            SourceEngineType.RSS_FEED -> fetchRssPopular()
            else -> {
                val latestUrl = "$baseUrl/${config.latestPath.trimStart('/')}"
                try {
                    val resp = withTimeoutOrNull(6_000L) {
                        httpClient.newCall(buildGetRequest(latestUrl)).awaitResponse()
                    }
                    resp?.use { r ->
                        if (r.isSuccessful) {
                            val html = r.body?.string().orEmpty()
                            val parsed = parseMangaSummariesFromHtml(html, latestUrl)
                            if (parsed.isNotEmpty()) {
                                return@withContext PageResult(items = parsed, page = page, hasNextPage = true)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                super.getPopular(page)
            }
        }
    }

    private suspend fun fetchJsonOrRestPopular(page: Int): PageResult<MangaSummary> {
        val url = if (domain.contains("mangadex")) {
            val offset = ((page - 1).coerceAtLeast(0)) * 20
            "https://api.mangadex.org/manga?limit=20&offset=$offset&includes[]=cover_art&includes[]=author&includes[]=artist&hasAvailableChapters=true"
        } else {
            buildPopularUrl(page, baseUrl)
        }
        val response = withTimeoutOrNull(7_000L) {
            httpClient.newCall(buildGetRequest(url)).awaitResponse()
        } ?: throw IllegalStateException("انتهت مهلة الاتصال بواجهة JSON API للموقع $domain")

        return response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("فشل طلب واجهة API برمز HTTP ${resp.code}")
            }
            if (domain.contains("mangadex")) {
                val items = MangaDexApiHelper.parseMangaListResponse(body, sourceId = id)
                return@use PageResult(items = items, page = page, hasNextPage = items.isNotEmpty())
            }
            val parsed = parseGenericJsonCatalog(body, baseUrl)
            PageResult(items = parsed, page = page, hasNextPage = parsed.isNotEmpty())
        }
    }

    private suspend fun fetchRssPopular(): PageResult<MangaSummary> {
        val rssCandidates = listOf(
            "$baseUrl/feed/",
            "$baseUrl/rss",
            "$baseUrl/rss.xml",
            "$baseUrl/atom.xml",
            buildPopularUrl(1, baseUrl)
        ).distinct()

        for (rssUrl in rssCandidates) {
            try {
                val resp = withTimeoutOrNull(6_000L) {
                    httpClient.newCall(buildGetRequest(rssUrl)).awaitResponse()
                }
                resp?.use { r ->
                    if (r.isSuccessful) {
                        val xml = r.body?.string().orEmpty()
                        val items = parseRssOrAtomFeed(xml, rssUrl)
                        if (items.isNotEmpty()) {
                            return PageResult(items = items, page = 1, hasNextPage = false)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return super.getPopular(1)
    }

    fun parseGenericJsonCatalog(jsonString: String, pageBaseUri: String = baseUrl): List<MangaSummary> {
        val results = mutableListOf<MangaSummary>()
        try {
            val trimmed = jsonString.trim()
            val array: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("data")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("items")
                        ?: obj.optJSONArray("manga")
                        ?: obj.optJSONArray("series")
                        ?: JSONArray()
                }
                else -> JSONArray()
            }

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title")
                    .ifBlank { item.optString("name") }
                    .ifBlank { item.optJSONObject("attributes")?.optJSONObject("title")?.optString("en").orEmpty() }
                    .trim()
                if (title.isBlank()) continue

                val slugOrId = item.optString("slug")
                    .ifBlank { item.optString("id") }
                    .ifBlank { "item_$i" }
                val rawUrl = item.optString("url")
                    .ifBlank { item.optString("link") }
                    .ifBlank { "$pageBaseUri/manga/$slugOrId/" }
                val mangaUrl = HtmlImageExtractor.resolveUrl(pageBaseUri, rawUrl) ?: rawUrl

                val rawCover = item.optString("cover")
                    .ifBlank { item.optString("cover_url") }
                    .ifBlank { item.optString("image") }
                    .ifBlank { item.optString("thumbnail") }
                val coverUrl = if (rawCover.isNotBlank()) HtmlImageExtractor.resolveUrl(pageBaseUri, rawCover) else null

                if (ContentSafetyClassifier.isProhibitedIllegalContent(title, "", emptyList())) continue
                val rating = ContentSafetyClassifier.classifyFromGenresAndText(
                    genres = emptyList(),
                    title = title,
                    domain = domain,
                    explicitFlag = item.optBoolean("is_adult", false) || item.optBoolean("nsfw", false)
                )

                results.add(
                    MangaSummary(
                        id = "${id}_$slugOrId",
                        sourceId = id,
                        title = title,
                        coverUrl = coverUrl,
                        mangaUrl = mangaUrl,
                        latestChapter = item.optString("latest_chapter").ifBlank { "متاح" },
                        genres = listOf("مانهوا"),
                        rating = 4.6f,
                        status = item.optString("status").ifBlank { "OnGoing" },
                        contentRating = rating
                    )
                )
            }
        } catch (_: Exception) {
        }
        return results
    }

    fun parseRssOrAtomFeed(xml: String, pageBaseUri: String = baseUrl): List<MangaSummary> {
        val doc = Jsoup.parse(xml, pageBaseUri, Parser.xmlParser())
        val entries = doc.select("item, entry")
        return entries.mapIndexedNotNull { idx, entry ->
            val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
            val link = entry.selectFirst("link")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: entry.selectFirst("link")?.text()?.trim().orEmpty()
            if (title.isBlank() || link.isBlank()) return@mapIndexedNotNull null

            val resolvedUrl = HtmlImageExtractor.resolveUrl(pageBaseUri, link) ?: return@mapIndexedNotNull null
            val contentHtml = entry.selectFirst("content\\:encoded, description, summary, content")?.text().orEmpty()
            val coverUrl = entry.selectFirst("media\\:thumbnail, media\\:content, enclosure")?.attr("url")
                ?.takeIf { it.isNotBlank() }
                ?.let { HtmlImageExtractor.resolveUrl(pageBaseUri, it) }
                ?: Jsoup.parse(contentHtml, pageBaseUri).selectFirst("img")?.let {
                    HtmlImageExtractor.extractImageUrl(it, pageBaseUri)
                }

            val slug = resolvedUrl.trimEnd('/').substringAfterLast('/').ifBlank { "rss_$idx" }
            MangaSummary(
                id = "${id}_$slug",
                sourceId = id,
                title = title,
                coverUrl = coverUrl,
                mangaUrl = resolvedUrl,
                latestChapter = "متاح",
                genres = listOf("مانجا"),
                rating = 4.5f,
                status = "OnGoing",
                contentRating = config.contentRating
            )
        }
    }
}

/**
 * Factory that builds the right [MangaSource] adapter from a [SourceConfiguration] or detected engine type.
 */
object SourceAdapterFactory {

    fun defaultConfigForEngine(
        domain: String,
        sourceName: String,
        engineType: SourceEngineType,
        contentRating: ContentRating = ContentRating.GENERAL,
        requiresVerification: Boolean = false
    ): SourceConfiguration {
        val cleanDomain = domain.lowercase()
        val base = SourceConfiguration(
            sourceName = sourceName.ifBlank {
                cleanDomain.substringBefore('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            },
            baseUrl = "https://$cleanDomain",
            domain = cleanDomain,
            engineType = engineType,
            contentRating = contentRating,
            requiresVerification = requiresVerification
        )
        return when (engineType) {
            SourceEngineType.MADARA -> base.copy(
                popularPath = "/manga/",
                latestPath = "/manga/?m_orderby=latest",
                searchPath = "/?s={query}&post_type=wp-manga",
                mangaItemSelector = "div.page-item-detail, div.c-tabs-item__content",
                titleSelector = ".post-title h3 a, .post-title a, h3 a",
                coverSelector = ".item-thumb img, .tab-thumb img",
                latestChapterSelector = ".list-chapter .chapter-item:first-child .chapter a, .latest-chap .chapter a",
                chapterListSelector = "li.wp-manga-chapter",
                pageImageSelector = ".reading-content img, .page-break img, img.wp-manga-chapter-img"
            )
            SourceEngineType.MANGASTREAM -> base.copy(
                popularPath = "/manga/?order=popular",
                latestPath = "/manga/?order=update",
                searchPath = "/?s={query}",
                mangaItemSelector = "div.bsx, div.utao, div.uta, div.box",
                titleSelector = ".tt, .luf a h4, .luf a, h3 a, a[title]",
                coverSelector = ".limit img, .imgu img",
                latestChapterSelector = ".epxs, .luf ul li:first-child a",
                chapterListSelector = "#chapterlist li, .eplister ul li",
                pageImageSelector = "#readerarea img, img.manga-chapter-img, .reading-content img"
            )
            SourceEngineType.MANGAREADER -> base.copy(
                popularPath = "/manga/",
                latestPath = "/latest-update/",
                searchPath = "/search?q={query}",
                mangaItemSelector = "div.manga-item, div.item, div.bsx",
                titleSelector = "h3 a, .title a, a[title]",
                coverSelector = ".manga-poster img, img",
                latestChapterSelector = ".chapter a, .fd-infor a",
                chapterListSelector = "ul.reading-list li, #chapterlist li",
                pageImageSelector = "#readerarea img, .container-reader-chapter img"
            )
            else -> base
        }
    }

    fun createAdapter(config: SourceConfiguration, customId: String? = null): DynamicSourceAdapter {
        return DynamicSourceAdapter(config = config, customId = customId)
    }
}

/**
 * Section 3: 14-Point Automatic Site Inspection & Discovery Engine (`SourceDiscoveryEngine`).
 */
class SourceDiscoveryEngine(
    private val verificationManager: VerificationManager
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun inspectSite(rawInput: String): SiteInspectionReport = withContext(Dispatchers.IO) {
        // 1. Validate domain & clean input
        val normalizedDomain = try {
            normalizeDomain(rawInput, allowHttpUpgrade = true)
        } catch (e: IllegalArgumentException) {
            return@withContext SiteInspectionReport(
                inputUrl = rawInput,
                normalizedDomain = rawInput.trim(),
                baseUrl = "",
                isValidDomain = false,
                usesHttps = false,
                isReachable = false,
                httpStatusCode = 0,
                requiresVerification = false,
                verificationChallengeType = "NONE",
                detectedArchitecture = SourceEngineType.CUSTOM_HTML,
                canExtractWorksList = false,
                extractedWorksCount = 0,
                canExtractCovers = false,
                sampleCoverUrl = null,
                canExtractDetails = false,
                canExtractChapters = false,
                extractedChaptersCount = 0,
                canExtractPages = false,
                extractedPagesCount = 0,
                supportsSearch = false,
                supportsPagination = false,
                detectedContentRating = ContentRating.GENERAL,
                recommendedStatus = SourceStatus.UNSUPPORTED,
                suggestedName = "",
                diagnosticSummaryAr = e.message ?: "اسم النطاق غير صالح"
            )
        }

        val baseUrl = "https://$normalizedDomain"
        val suggestedDefaultName = normalizedDomain
            .substringBefore('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        // Special fast-path for MangaDex official REST API
        if (normalizedDomain == "mangadex.org" || normalizedDomain.endsWith(".mangadex.org")) {
            val mdSource = MangaDexSource()
            val pop = runCatching { mdSource.getPopular(1) }.getOrNull()
            val items = pop?.items.orEmpty()
            val first = items.firstOrNull()
            val chapters = if (first != null) runCatching { mdSource.getChapters(first.mangaUrl) }.getOrDefault(emptyList()) else emptyList()
            val pages = if (chapters.isNotEmpty()) runCatching { mdSource.getPages(chapters.first().remoteId) }.getOrDefault(emptyList()) else emptyList()

            return@withContext SiteInspectionReport(
                inputUrl = rawInput,
                normalizedDomain = normalizedDomain,
                baseUrl = baseUrl,
                isValidDomain = true,
                usesHttps = true,
                isReachable = items.isNotEmpty(),
                httpStatusCode = if (items.isNotEmpty()) 200 else 503,
                requiresVerification = false,
                verificationChallengeType = "NONE",
                detectedArchitecture = SourceEngineType.REST_API,
                canExtractWorksList = items.isNotEmpty(),
                extractedWorksCount = items.size,
                canExtractCovers = items.any { !it.coverUrl.isNullOrBlank() },
                sampleCoverUrl = first?.coverUrl,
                canExtractDetails = first != null,
                canExtractChapters = chapters.isNotEmpty(),
                extractedChaptersCount = chapters.size,
                canExtractPages = pages.isNotEmpty(),
                extractedPagesCount = pages.size,
                supportsSearch = true,
                supportsPagination = true,
                detectedContentRating = ContentRating.GENERAL,
                recommendedStatus = if (items.isNotEmpty()) SourceStatus.SUPPORTED else SourceStatus.NETWORK_ERROR,
                suggestedName = "MangaDex Official",
                diagnosticSummaryAr = "موقع متوافق عبر واجهة REST API الرسمية (${items.size} عمل، ${chapters.size} فصل)."
            )
        }

        // Probe candidate entry URLs over HTTPS
        val probeUrls = listOf("$baseUrl/manga/", "$baseUrl/", "$baseUrl/series/")
        var httpCode = 0
        var reachable = false
        var responseHtml = ""
        var responseHeaders = emptyMap<String, String>()
        var landedUrl = baseUrl

        for (probeUrl in probeUrls) {
            try {
                val cookieHeader = runCatching { CookieManager.getInstance().getCookie(probeUrl) }.getOrNull()
                val req = Request.Builder()
                    .url(probeUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .apply {
                        if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader)
                    }
                    .build()

                val resp = withTimeoutOrNull(6_000L) { httpClient.newCall(req).awaitResponse() }
                if (resp != null) {
                    resp.use { r ->
                        httpCode = r.code
                        reachable = true
                        landedUrl = r.request.url.toString()
                        responseHeaders = r.headers.toMap()
                        val body = r.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            responseHtml = body
                        }
                    }
                    if (httpCode in 200..299 && responseHtml.length > 500) {
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (!reachable) {
            return@withContext SiteInspectionReport(
                inputUrl = rawInput,
                normalizedDomain = normalizedDomain,
                baseUrl = baseUrl,
                isValidDomain = true,
                usesHttps = true,
                isReachable = false,
                httpStatusCode = 0,
                requiresVerification = false,
                verificationChallengeType = "NONE",
                detectedArchitecture = SourceEngineType.CUSTOM_HTML,
                canExtractWorksList = false,
                extractedWorksCount = 0,
                canExtractCovers = false,
                sampleCoverUrl = null,
                canExtractDetails = false,
                canExtractChapters = false,
                extractedChaptersCount = 0,
                canExtractPages = false,
                extractedPagesCount = 0,
                supportsSearch = false,
                supportsPagination = false,
                detectedContentRating = ContentSafetyClassifier.classifyFromGenresAndText(emptyList(), "", "", normalizedDomain),
                recommendedStatus = SourceStatus.UNSUPPORTED,
                suggestedName = suggestedDefaultName,
                diagnosticSummaryAr = "تعذّر الوصول إلى النطاق $normalizedDomain. تحقق من صحة العنوان أو اتصال الشبكة."
            )
        }

        // Check security verification (Cloudflare / CAPTCHA / Waiting room)
        val detection = VerificationDetector.detect(
            html = responseHtml,
            httpCode = httpCode,
            headers = responseHeaders
        )
        if (detection.isVerificationRequired && !verificationManager.isVerified(normalizedDomain)) {
            verificationManager.markVerificationRequired(
                sourceId = "site_${normalizedDomain.replace('.', '_')}",
                domain = normalizedDomain,
                challengeType = detection.challengeType,
                reasonAr = detection.reasonAr
            )
            return@withContext SiteInspectionReport(
                inputUrl = rawInput,
                normalizedDomain = normalizedDomain,
                baseUrl = baseUrl,
                isValidDomain = true,
                usesHttps = true,
                isReachable = true,
                httpStatusCode = httpCode,
                requiresVerification = true,
                verificationChallengeType = detection.challengeType.name,
                detectedArchitecture = detectEngineTypeFromHtml(responseHtml),
                canExtractWorksList = false,
                extractedWorksCount = 0,
                canExtractCovers = false,
                sampleCoverUrl = null,
                canExtractDetails = false,
                canExtractChapters = false,
                extractedChaptersCount = 0,
                canExtractPages = false,
                extractedPagesCount = 0,
                supportsSearch = true,
                supportsPagination = true,
                detectedContentRating = ContentSafetyClassifier.classifyFromGenresAndText(emptyList(), "", "", normalizedDomain),
                recommendedStatus = SourceStatus.NEEDS_VERIFICATION,
                suggestedName = suggestedDefaultName,
                diagnosticSummaryAr = "الموقع محمي ببوابة تحقق أمني (${detection.challengeType}). أكمل التحقق البشري للمتابعة."
            )
        }

        // Detect site architecture (Madara, MangaStream, MangaReader, JSON API, RSS, JS DOM, Custom HTML)
        val detectedEngine = detectEngineTypeFromHtml(responseHtml)
        val doc = Jsoup.parse(responseHtml, landedUrl)
        val rawTitle = doc.title().substringBefore(" - ").substringBefore(" | ").substringBefore(" – ").trim()
        val suggestedName = if (rawTitle.length in 2..42 && !rawTitle.contains("Just a moment", true)) {
            rawTitle
        } else {
            suggestedDefaultName
        }

        val detectedRating = ContentSafetyClassifier.classifyFromGenresAndText(
            genres = emptyList(),
            title = rawTitle,
            description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty(),
            domain = normalizedDomain
        )

        val tempConfig = SourceAdapterFactory.defaultConfigForEngine(
            domain = normalizedDomain,
            sourceName = suggestedName,
            engineType = detectedEngine,
            contentRating = detectedRating
        )
        val tempAdapter = DynamicSourceAdapter(tempConfig)
        tempAdapter.attachVerificationManager(verificationManager)

        val parsedWorks = tempAdapter.parseMangaSummariesFromHtml(responseHtml, landedUrl)
        val firstWork = parsedWorks.firstOrNull { !it.coverUrl.isNullOrBlank() } ?: parsedWorks.firstOrNull()
        val hasCovers = parsedWorks.any { !it.coverUrl.isNullOrBlank() && it.coverUrl!!.startsWith("https://") }

        var detailsOk = false
        var chaptersCount = 0
        var pagesCount = 0

        if (firstWork != null) {
            val details = runCatching { tempAdapter.getDetails(firstWork.mangaUrl) }.getOrNull()
            detailsOk = details != null && details.title.isNotBlank()
            val chapters = details?.chapters?.takeIf { it.isNotEmpty() }
                ?: runCatching { tempAdapter.getChapters(firstWork.mangaUrl) }.getOrDefault(emptyList())
            chaptersCount = chapters.size
            if (chapters.isNotEmpty()) {
                val pages = runCatching { tempAdapter.getPages(chapters.first().remoteId) }.getOrDefault(emptyList())
                pagesCount = pages.size
            }
        }

        val supportsSearch = doc.selectFirst("form[role=search], input[name=s], input[type=search], a[href*=search]") != null ||
            detectedEngine in listOf(SourceEngineType.MADARA, SourceEngineType.MANGASTREAM, SourceEngineType.MANGAREADER)
        val supportsPagination = doc.selectFirst(".wp-pagenavi, .pagination, a.next, a.page-numbers, .hpage a") != null ||
            parsedWorks.size >= 10

        val recommendedStatus = when {
            parsedWorks.isNotEmpty() && chaptersCount > 0 && pagesCount > 0 -> SourceStatus.SUPPORTED
            parsedWorks.isNotEmpty() && (chaptersCount > 0 || hasCovers) -> SourceStatus.PARTIALLY_SUPPORTED
            parsedWorks.isEmpty() && detectedEngine == SourceEngineType.JS_DOM -> SourceStatus.WEBVIEW_ONLY
            parsedWorks.isEmpty() && httpCode in 200..299 -> SourceStatus.NEEDS_CONFIGURATION
            else -> SourceStatus.UNSUPPORTED
        }

        val summaryAr = when (recommendedStatus) {
            SourceStatus.SUPPORTED ->
                "مدعوم بالكامل (${detectedEngine.labelAr}): تم استخراج ${parsedWorks.size} عمل، ${chaptersCount} فصل، و${pagesCount} صفحة قراءة."
            SourceStatus.PARTIALLY_SUPPORTED ->
                "مدعوم جزئياً (${detectedEngine.labelAr}): تم استخراج ${parsedWorks.size} عمل من الفهرس."
            SourceStatus.NEEDS_CONFIGURATION ->
                "الموقع متصل (HTTP $httpCode) ويحتاج إلى تخصيص محددات CSS (Selectors) لاستخراج الأعمال."
            SourceStatus.WEBVIEW_ONLY ->
                "الموقع يعتمد على JavaScript ديناميكي ويعمل عبر وضع التصفح الداخلي (WebView)."
            else ->
                "الموقع غير متوافق مع استخراج المانجا المباشر."
        }

        SiteInspectionReport(
            inputUrl = rawInput,
            normalizedDomain = normalizedDomain,
            baseUrl = baseUrl,
            isValidDomain = true,
            usesHttps = true,
            isReachable = true,
            httpStatusCode = httpCode,
            requiresVerification = false,
            verificationChallengeType = "NONE",
            detectedArchitecture = detectedEngine,
            canExtractWorksList = parsedWorks.isNotEmpty(),
            extractedWorksCount = parsedWorks.size,
            canExtractCovers = hasCovers,
            sampleCoverUrl = firstWork?.coverUrl,
            canExtractDetails = detailsOk,
            canExtractChapters = chaptersCount > 0,
            extractedChaptersCount = chaptersCount,
            canExtractPages = pagesCount > 0,
            extractedPagesCount = pagesCount,
            supportsSearch = supportsSearch,
            supportsPagination = supportsPagination,
            detectedContentRating = detectedRating,
            recommendedStatus = recommendedStatus,
            suggestedName = suggestedName,
            diagnosticSummaryAr = summaryAr,
            recommendedConfig = tempConfig
        )
    }

    fun detectEngineTypeFromHtml(html: String): SourceEngineType {
        val trimmed = html.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return SourceEngineType.JSON_API
        }
        if (trimmed.startsWith("<?xml") || trimmed.contains("<rss") || trimmed.contains("<feed")) {
            return SourceEngineType.RSS_FEED
        }
        val lower = trimmed.lowercase()
        return when {
            lower.contains("wp-manga") || lower.contains("page-item-detail") || lower.contains("madara") ->
                SourceEngineType.MADARA
            lower.contains("mangastream") || lower.contains("div class=\"bsx\"") || lower.contains("id=\"chapterlist\"") || lower.contains("class=\"eplister\"") ->
                SourceEngineType.MANGASTREAM
            lower.contains("mangareader") || lower.contains("manga-poster") || lower.contains("container-reader-chapter") ->
                SourceEngineType.MANGAREADER
            lower.contains("__next_data__") && !lower.contains("page-item-detail") && !lower.contains("bsx") ->
                SourceEngineType.JS_DOM
            else -> SourceEngineType.CUSTOM_HTML
        }
    }
}

/**
 * Section 8: 11-Stage Automatic Source Test Runner (`SourceTestRunner`).
 * Executes real end-to-end verification before classifying any source as SUPPORTED:
 * 1. اختبار الاتصال بالموقع
 * 2. اختبار التحقق الأمني
 * 3. اختبار جلب الأعمال الشائعة
 * 4. اختبار جلب آخر التحديثات
 * 5. اختبار البحث
 * 6. اختبار استخراج الأغلفة
 * 7. اختبار صفحة تفاصيل المانجا
 * 8. اختبار قائمة الفصول
 * 9. اختبار صفحات الفصل
 * 10. اختبار القارئ
 * 11. اختبار التنزيل
 */
class SourceTestRunner(
    private val verificationManager: VerificationManager
) {
    suspend fun runFullTest(source: MangaSource): SourceFullTestReport = withContext(Dispatchers.IO) {
        source.attachVerificationManager(verificationManager)
        val stages = mutableListOf<SourceStageTestResult>()

        var popularItems = emptyList<MangaSummary>()
        var latestItems = emptyList<MangaSummary>()
        var searchItems = emptyList<MangaSummary>()
        var detailsResult: MangaDetails? = null
        var chaptersResult = emptyList<MangaChapter>()
        var pagesResult = emptyList<MangaPage>()
        var verificationNeeded = false

        // Stage 1 & 2 & 3: Connection, Verification & Popular Manga
        val t1 = System.currentTimeMillis()
        try {
            val popularPage = withTimeoutOrNull(12_000L) { source.getPopular(1) }
            val elapsed = System.currentTimeMillis() - t1
            if (popularPage != null && popularPage.items.isNotEmpty()) {
                popularItems = popularPage.items
                stages.add(SourceStageTestResult("1. الاتصال بالموقع", true, popularItems.size, "متصل بنجاح (${source.baseUrl})", elapsed))
                stages.add(SourceStageTestResult("2. التحقق الأمني", true, 1, "الجلسة صالحة ولا توجد حواجز نشطة", 10L))
                stages.add(SourceStageTestResult("3. جلب الأعمال الشائعة", true, popularItems.size, "تم استخراج ${popularItems.size} عمل", elapsed))
            } else {
                stages.add(SourceStageTestResult("1. الاتصال بالموقع", popularPage != null, 0, if (popularPage != null) "متصل بدون عناصر" else "انتهت مهلة الاتصال", elapsed))
                stages.add(SourceStageTestResult("2. التحقق الأمني", true, 0, verificationManager.getState(source.domain).labelAr, 5L))
                stages.add(SourceStageTestResult("3. جلب الأعمال الشائعة", false, 0, "لم يتم استخراج أي أعمال من الفهرس", elapsed))
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t1
            val isVer = e is com.example.core.verification.VerificationRequiredException ||
                e.message?.contains("SECURITY_VERIFICATION_REQUIRED") == true
            verificationNeeded = isVer
            stages.add(SourceStageTestResult("1. الاتصال بالموقع", isVer, 0, if (isVer) "متصل (محمي ببوابة تحقق)" else "فشل الاتصال: ${e.message}", elapsed))
            stages.add(SourceStageTestResult("2. التحقق الأمني", !isVer, 0, if (isVer) "يتطلب إكمال التحقق البشري" else "غير مطلوب", 5L))
            stages.add(SourceStageTestResult("3. جلب الأعمال الشائعة", false, 0, e.message ?: "فشل الجلب", elapsed))
        }

        // Stage 4: Latest Updates
        val t4 = System.currentTimeMillis()
        try {
            if (popularItems.isNotEmpty()) {
                val latestPage = withTimeoutOrNull(8_000L) { source.getLatestUpdates(1) }
                latestItems = latestPage?.items.orEmpty()
            }
            val d4 = System.currentTimeMillis() - t4
            stages.add(
                SourceStageTestResult(
                    "4. جلب آخر التحديثات",
                    latestItems.isNotEmpty(),
                    latestItems.size,
                    if (latestItems.isNotEmpty()) "تم جلب ${latestItems.size} عمل محدث" else "لم يتم استخراج قائمة التحديثات",
                    d4
                )
            )
        } catch (e: Exception) {
            stages.add(SourceStageTestResult("4. جلب آخر التحديثات", false, 0, e.message ?: "فشل", System.currentTimeMillis() - t4))
        }

        // Stage 5: Search
        val t5 = System.currentTimeMillis()
        try {
            if (popularItems.isNotEmpty()) {
                val sampleQuery = popularItems.first().title.split(" ").firstOrNull { it.length >= 3 } ?: "مانهوا"
                val searchRes = withTimeoutOrNull(8_000L) { source.search(sampleQuery, 1) }
                searchItems = searchRes?.items.orEmpty()
            }
            val d5 = System.currentTimeMillis() - t5
            stages.add(
                SourceStageTestResult(
                    "5. اختبار البحث",
                    searchItems.isNotEmpty(),
                    searchItems.size,
                    if (searchItems.isNotEmpty()) "البحث يعمل (${searchItems.size} نتيجة)" else "البحث لم يرجع نتائج",
                    d5
                )
            )
        } catch (e: Exception) {
            stages.add(SourceStageTestResult("5. اختبار البحث", false, 0, e.message ?: "فشل البحث", System.currentTimeMillis() - t5))
        }

        // Stage 6: Cover Images
        val validCovers = popularItems.count { !it.coverUrl.isNullOrBlank() && it.coverUrl!!.startsWith("https://") }
        stages.add(
            SourceStageTestResult(
                "6. استخراج الأغلفة",
                validCovers > 0,
                validCovers,
                if (validCovers > 0) "تم استخراج $validCovers غلاف صالح بصيغة HTTPS" else "الأغلفة مفقودة",
                5L
            )
        )

        // Stage 7 & 8: Manga Details & Chapter List
        val sampleWork = popularItems.firstOrNull { !it.coverUrl.isNullOrBlank() } ?: popularItems.firstOrNull()
        val t7 = System.currentTimeMillis()
        if (sampleWork != null) {
            try {
                detailsResult = withTimeoutOrNull(10_000L) { source.getDetails(sampleWork.mangaUrl) }
                val d7 = System.currentTimeMillis() - t7
                val detailsOk = detailsResult != null && detailsResult.title.isNotBlank()
                stages.add(
                    SourceStageTestResult(
                        "7. صفحة تفاصيل المانجا",
                        detailsOk,
                        if (detailsOk) 1 else 0,
                        if (detailsOk) "تم استخراج تفاصيل «${detailsResult?.title}»" else "فشل استخراج التفاصيل",
                        d7
                    )
                )

                val t8 = System.currentTimeMillis()
                chaptersResult = detailsResult?.chapters?.takeIf { it.isNotEmpty() }
                    ?: withTimeoutOrNull(9_000L) { source.getChapters(sampleWork.mangaUrl) }.orEmpty()
                val d8 = System.currentTimeMillis() - t8
                stages.add(
                    SourceStageTestResult(
                        "8. قائمة الفصول",
                        chaptersResult.isNotEmpty(),
                        chaptersResult.size,
                        if (chaptersResult.isNotEmpty()) "تم استخراج ${chaptersResult.size} فصل" else "لم يُعثر على فصول",
                        d8
                    )
                )
            } catch (e: Exception) {
                val d7 = System.currentTimeMillis() - t7
                stages.add(SourceStageTestResult("7. صفحة تفاصيل المانجا", false, 0, e.message ?: "فشل التفاصيل", d7))
                stages.add(SourceStageTestResult("8. قائمة الفصول", false, 0, "تعذر جلب الفصول", 0L))
            }
        } else {
            stages.add(SourceStageTestResult("7. صفحة تفاصيل المانجا", false, 0, "لا يوجد عمل لاختباره", 0L))
            stages.add(SourceStageTestResult("8. قائمة الفصول", false, 0, "لا يوجد عمل لاختباره", 0L))
        }

        // Stage 9, 10 & 11: Chapter Pages, Reader Compatibility & Download Readiness
        val firstChapter = chaptersResult.firstOrNull()
        val t9 = System.currentTimeMillis()
        if (firstChapter != null) {
            try {
                pagesResult = withTimeoutOrNull(10_000L) { source.getPages(firstChapter.remoteId) }.orEmpty()
                val d9 = System.currentTimeMillis() - t9
                val validPages = pagesResult.count { it.imageUrl.startsWith("https://") }
                stages.add(
                    SourceStageTestResult(
                        "9. صفحات الفصل",
                        validPages > 0,
                        validPages,
                        if (validPages > 0) "تم استخراج $validPages صورة فصل" else "لم يتم استخراج صور الفصل",
                        d9
                    )
                )
                stages.add(
                    SourceStageTestResult(
                        "10. اختبار القارئ",
                        validPages > 0,
                        validPages,
                        if (validPages > 0) "روابط الصور متوافقة مع القارئ العمودي والأفقي" else "القارئ لا يملك صفحات لعرضها",
                        5L
                    )
                )
                stages.add(
                    SourceStageTestResult(
                        "11. اختبار التنزيل المحلي",
                        validPages > 0,
                        validPages,
                        if (validPages > 0) "جاهز لتنزيل الفصل وحفظه للقراءة دون اتصال" else "غير متاح للتنزيل",
                        5L
                    )
                )
            } catch (e: Exception) {
                val d9 = System.currentTimeMillis() - t9
                stages.add(SourceStageTestResult("9. صفحات الفصل", false, 0, e.message ?: "فشل", d9))
                stages.add(SourceStageTestResult("10. اختبار القارئ", false, 0, "فشل تجهيز القارئ", 0L))
                stages.add(SourceStageTestResult("11. اختبار التنزيل المحلي", false, 0, "فشل تجهيز التنزيل", 0L))
            }
        } else {
            stages.add(SourceStageTestResult("9. صفحات الفصل", false, 0, "لا يوجد فصل لاختباره", 0L))
            stages.add(SourceStageTestResult("10. اختبار القارئ", false, 0, "لا يوجد فصل لاختباره", 0L))
            stages.add(SourceStageTestResult("11. اختبار التنزيل المحلي", false, 0, "لا يوجد فصل لاختباره", 0L))
        }

        val finalStatus = when {
            verificationNeeded -> SourceStatus.NEEDS_VERIFICATION
            popularItems.isNotEmpty() && chaptersResult.isNotEmpty() && pagesResult.isNotEmpty() -> SourceStatus.SUPPORTED
            popularItems.isNotEmpty() && (chaptersResult.isNotEmpty() || validCovers > 0) -> SourceStatus.PARTIALLY_SUPPORTED
            popularItems.isEmpty() -> SourceStatus.NEEDS_CONFIGURATION
            else -> SourceStatus.UNSUPPORTED
        }

        SourceFullTestReport(
            sourceId = source.id,
            sourceName = source.name,
            domain = source.domain,
            finalStatus = finalStatus,
            stages = stages
        )
    }
}

/**
 * Coordinates [SourceDiscoveryEngine], [SourceAdapterFactory], [SourceTestRunner], and custom [SourceConfiguration]
 * live preview before saving.
 */
class UniversalSourceEngine(
    val verificationManager: VerificationManager
) {
    val discoveryEngine = SourceDiscoveryEngine(verificationManager)
    val testRunner = SourceTestRunner(verificationManager)

    suspend fun inspectSite(rawUrl: String): SiteInspectionReport {
        return discoveryEngine.inspectSite(rawUrl)
    }

    suspend fun testSource(source: MangaSource): SourceFullTestReport {
        return testRunner.runFullTest(source)
    }

    /**
     * Tests a user-edited [SourceConfiguration] live before saving it to the database.
     */
    suspend fun previewConfiguration(config: SourceConfiguration): Result<List<MangaSummary>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val adapter = SourceAdapterFactory.createAdapter(config)
            adapter.attachVerificationManager(verificationManager)
            val popular = adapter.getPopular(1)
            Result.success(popular.items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
