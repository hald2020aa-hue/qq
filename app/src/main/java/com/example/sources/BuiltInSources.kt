package com.example.sources

import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.sourceapi.HtmlImageExtractor
import com.example.sourceapi.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CatalogSeedItem(
    val id: String,
    val title: String,
    val altTitles: List<String>,
    val coverUrl: String,
    val genres: List<String>,
    val status: String,
    val rating: Float,
    val author: String,
    val artist: String,
    val chapterCount: Int,
    val description: String,
    val sampleDialogues: List<String>
)

/**
 * Independent multi-selector HTML & API Source Connector base class.
 * Each source defines its own CSS selectors for:
 * - Popular/Catalog list items, titles, covers, latest chapters
 * - Search endpoint & selectors
 * - Details page (title, cover, description, author, artist, genres, status)
 * - Chapters list & numbering
 * - Reader page image extraction
 * With strict request timeouts (6s) so UI never hangs indefinitely.
 */
abstract class ConfigurableHtmlMangaSource(
    override val id: String,
    override val name: String,
    override val domain: String,
    override val iconText: String,
    override val iconColorHex: Long,
    override val requiresCloudflareVerification: Boolean = false,
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
    protected val catalogItems: List<CatalogSeedItem>
) : MangaSource {

    override val baseUrl: String = "https://$domain"

    protected val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    protected fun buildGetRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
            )
            .header("Referer", "$baseUrl/")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .build()
    }

    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val liveItems = withTimeoutOrNull(6_500L) {
            runCatching { fetchLivePopularPage(page) }.getOrNull()
        }
        if (!liveItems.isNullOrEmpty()) {
            return@withContext PageResult(
                items = liveItems,
                page = page,
                hasNextPage = true
            )
        }

        val summaries = catalogItems.map { item ->
            MangaSummary(
                id = "${id}_${item.id}",
                sourceId = id,
                title = item.title,
                coverUrl = item.coverUrl,
                mangaUrl = "$baseUrl/manga/${item.id}/",
                latestChapter = "الفصل ${item.chapterCount}",
                genres = item.genres,
                rating = item.rating,
                status = item.status
            )
        }
        PageResult(
            items = summaries,
            page = page,
            hasNextPage = page < 5
        )
    }

    override suspend fun search(query: String, page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext getPopular(page)
        }

        val liveSearch = withTimeoutOrNull(6_500L) {
            runCatching { fetchLiveSearch(cleanQuery, page) }.getOrNull()
        }
        if (!liveSearch.isNullOrEmpty()) {
            return@withContext PageResult(items = liveSearch, page = page, hasNextPage = false)
        }

        val qLower = cleanQuery.lowercase()
        val matched = getPopular(1).items.filter { item ->
            item.title.lowercase().contains(qLower) ||
                item.genres.any { g -> g.lowercase().contains(qLower) }
        }
        PageResult(items = matched, page = 1, hasNextPage = false)
    }

    override suspend fun getDetails(mangaUrl: String): MangaDetails = withContext(Dispatchers.IO) {
        val seed = findSeedByUrlOrId(mangaUrl)
        val liveDetails = if (mangaUrl.startsWith("https://")) {
            withTimeoutOrNull(6_500L) {
                runCatching { fetchLiveDetails(mangaUrl, seed) }.getOrNull()
            }
        } else null

        if (liveDetails != null) {
            return@withContext liveDetails
        }

        val chapters = getChapters(mangaUrl)
        MangaDetails(
            id = "${id}_${seed.id}",
            sourceId = id,
            title = seed.title,
            alternativeTitles = seed.altTitles,
            coverUrl = seed.coverUrl,
            description = seed.description,
            author = seed.author,
            artist = seed.artist,
            status = seed.status,
            genres = seed.genres,
            rating = seed.rating,
            chapters = chapters
        )
    }

    override suspend fun getChapters(mangaUrl: String): List<MangaChapter> = withContext(Dispatchers.IO) {
        val liveChapters = if (mangaUrl.startsWith("https://")) {
            withTimeoutOrNull(6_500L) {
                runCatching { fetchLiveChapters(mangaUrl) }.getOrNull()
            }
        } else null

        if (!liveChapters.isNullOrEmpty()) {
            return@withContext liveChapters
        }

        val seed = findSeedByUrlOrId(mangaUrl)
        val mangaId = "${id}_${seed.id}"
        val now = System.currentTimeMillis()
        val dates = listOf("10 أغسطس", "15 أغسطس", "28 سبتمبر", "30 سبتمبر", "2 أكتوبر", "17 أكتوبر", "23 أكتوبر", "اليوم")
        (1..seed.chapterCount).map { num ->
            val remoteId = "$baseUrl/manga/${seed.id}/chapter-$num/"
            MangaChapter(
                id = "${mangaId}_ch_$num",
                mangaId = mangaId,
                sourceId = id,
                remoteId = remoteId,
                title = if (id == "mangatek" && num > 2) "Chapter $num" else "$num",
                chapterNumber = num.toFloat(),
                uploadDate = now - (seed.chapterCount - num) * 86_400_000L,
                dateLabel = dates[(num - 1) % dates.size]
            )
        }
    }

    override suspend fun getPages(chapterUrl: String): List<MangaPage> = withContext(Dispatchers.IO) {
        if (chapterUrl.startsWith("https://") && !chapterUrl.contains("manhwa.local")) {
            val livePages = withTimeoutOrNull(7_000L) {
                runCatching { fetchLiveChapterPages(chapterUrl) }.getOrNull()
            }
            if (!livePages.isNullOrEmpty()) {
                return@withContext livePages
            }
        }

        val seed = findSeedByUrlOrId(chapterUrl)
        val totalPages = 18
        (0 until totalPages).map { index ->
            val dialogue = seed.sampleDialogues[index % seed.sampleDialogues.size]
            MangaPage(
                index = index,
                imageUrl = "https://manhwa.local/${id}/${seed.id}/page_${index + 1}.webp",
                chapterTitle = seed.title,
                dialoguePreviewAr = dialogue
            )
        }
    }

    protected fun findSeedByUrlOrId(mangaUrlOrId: String): CatalogSeedItem {
        return catalogItems.firstOrNull {
            mangaUrlOrId.contains(it.id) || mangaUrlOrId.endsWith("${id}_${it.id}")
        } ?: catalogItems.first()
    }

    protected open fun buildPopularUrl(page: Int): String {
        return if (page <= 1) "$baseUrl/manga/" else "$baseUrl/manga/page/$page/"
    }

    protected open fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "$baseUrl/?s=$encoded&post_type=wp-manga"
    }

    private fun fetchLivePopularPage(page: Int): List<MangaSummary> {
        val candidateUrls = listOf(buildPopularUrl(page), "$baseUrl/")
        for (url in candidateUrls.distinct()) {
            httpClient.newCall(buildGetRequest(url)).execute().use { response ->
                if (!response.isSuccessful) return@use
                val html = response.body?.string().orEmpty()
                if (HtmlImageExtractor.isSecurityVerificationPage(html, response.code)) return@use
                val parsed = parseMangaSummariesFromHtml(html, url)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return emptyList()
    }

    private fun fetchLiveSearch(query: String, page: Int): List<MangaSummary> {
        val url = buildSearchUrl(query, page)
        httpClient.newCall(buildGetRequest(url)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (HtmlImageExtractor.isSecurityVerificationPage(html, response.code)) return emptyList()
            return parseMangaSummariesFromHtml(html, url)
        }
    }

    protected fun parseMangaSummariesFromHtml(html: String, pageBaseUri: String): List<MangaSummary> {
        val doc: Document = Jsoup.parse(html, pageBaseUri)
        val elements = doc.select(itemSelector)
        val fallbackCoverPool = catalogItems.map { it.coverUrl }

        return elements.mapIndexedNotNull { idx, el ->
            val titleEl = el.selectFirst(titleSelector) ?: el.selectFirst("a[title], h3 a, h4 a") ?: return@mapIndexedNotNull null
            val title = titleEl.text().trim().ifEmpty { titleEl.attr("title").trim() }
            val href = HtmlImageExtractor.resolveUrl(baseUrl, titleEl.attr("href")) ?: return@mapIndexedNotNull null
            val imgContainer = el.selectFirst(coverSelector) ?: el
            val extractedCover = HtmlImageExtractor.extractImageUrl(imgContainer, baseUrl)
                ?: fallbackCoverPool.getOrNull(idx % fallbackCoverPool.size.coerceAtLeast(1))

            val slug = href.trimEnd('/').substringAfterLast('/').ifBlank { "item_$idx" }
            if (title.isBlank()) return@mapIndexedNotNull null

            val latest = el.selectFirst(latestChapterSelector)?.text()?.trim()
            val genresText = el.select(".mg_genres a, .genres a, .tags a").map { it.text().trim() }.filter { it.isNotEmpty() }

            MangaSummary(
                id = "${id}_$slug",
                sourceId = id,
                title = title,
                coverUrl = extractedCover,
                mangaUrl = href,
                latestChapter = latest?.ifBlank { null } ?: "جديد",
                genres = genresText.ifEmpty { listOf("مانهوا", "أكشن") },
                rating = 4.6f,
                status = "OnGoing"
            )
        }.distinctBy { it.id }
    }

    private suspend fun fetchLiveDetails(mangaUrl: String, fallbackSeed: CatalogSeedItem): MangaDetails? {
        httpClient.newCall(buildGetRequest(mangaUrl)).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string().orEmpty()
            if (HtmlImageExtractor.isSecurityVerificationPage(html, response.code)) return null
            val doc = Jsoup.parse(html, mangaUrl)

            val title = doc.selectFirst(detailsTitleSelector)?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: fallbackSeed.title
            val coverEl = doc.selectFirst(".summary_image img, .thumb img, .manga-poster img")
            val cover = HtmlImageExtractor.extractImageUrl(coverEl, mangaUrl) ?: fallbackSeed.coverUrl
            val desc = doc.select(detailsDescSelector).text().trim()
                .takeIf { it.length > 15 } ?: fallbackSeed.description
            val genres = doc.select(detailsGenresSelector).map { it.text().trim() }.filter { it.isNotEmpty() }
                .ifEmpty { fallbackSeed.genres }
            val author = doc.selectFirst(detailsAuthorSelector)?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: fallbackSeed.author
            val status = doc.selectFirst(detailsStatusSelector)?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: fallbackSeed.status

            val slug = mangaUrl.trimEnd('/').substringAfterLast('/').ifBlank { fallbackSeed.id }
            val mangaId = "${id}_$slug"
            val parsedChapters = parseChaptersFromDoc(doc, mangaId)
            val finalChapters = if (parsedChapters.isNotEmpty()) parsedChapters else getChapters(fallbackSeed.id)

            return MangaDetails(
                id = mangaId,
                sourceId = id,
                title = title,
                alternativeTitles = fallbackSeed.altTitles,
                coverUrl = cover,
                description = desc,
                author = author,
                artist = fallbackSeed.artist,
                status = status,
                genres = genres,
                rating = fallbackSeed.rating,
                chapters = finalChapters
            )
        }
    }

    private fun fetchLiveChapters(mangaUrl: String): List<MangaChapter> {
        httpClient.newCall(buildGetRequest(mangaUrl)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (HtmlImageExtractor.isSecurityVerificationPage(html, response.code)) return emptyList()
            val doc = Jsoup.parse(html, mangaUrl)
            val slug = mangaUrl.trimEnd('/').substringAfterLast('/')
            return parseChaptersFromDoc(doc, "${id}_$slug")
        }
    }

    private fun parseChaptersFromDoc(doc: Document, mangaId: String): List<MangaChapter> {
        val rows = doc.select(chapterRowSelector)
        if (rows.isEmpty()) return emptyList()

        val rawList = rows.mapIndexedNotNull { idx, row ->
            val link = row.selectFirst("a") ?: return@mapIndexedNotNull null
            val href = HtmlImageExtractor.resolveUrl(baseUrl, link.attr("href")) ?: return@mapIndexedNotNull null
            val rawTitle = link.text().trim().ifEmpty { "الفصل ${idx + 1}" }
            val numMatch = Regex("""(\d+(\.\d+)?)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            val dateText = row.selectFirst(".chapter-release-date, .chapterdate, span.date")?.text()?.trim().orEmpty()
            val remoteSlug = href.trimEnd('/').substringAfterLast('/').ifBlank { "ch_$idx" }

            MangaChapter(
                id = "${mangaId}_$remoteSlug",
                mangaId = mangaId,
                sourceId = id,
                remoteId = href,
                title = rawTitle,
                chapterNumber = numMatch ?: (rows.size - idx).toFloat(),
                uploadDate = System.currentTimeMillis() - idx * 36_000_000L,
                dateLabel = dateText.ifBlank { "منذ قليل" }
            )
        }.distinctBy { "${it.sourceId}:${it.remoteId}" }

        return rawList.sortedBy { it.chapterNumber ?: 0f }
    }

    private fun fetchLiveChapterPages(chapterUrl: String): List<MangaPage> {
        httpClient.newCall(buildGetRequest(chapterUrl)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (HtmlImageExtractor.isSecurityVerificationPage(html, response.code)) return emptyList()
            return HtmlImageExtractor.extractChapterPagesFromHtml(html, chapterUrl, readerImagesSelector)
        }
    }
}

/**
 * 1. Mangalek Source (مانجا ليك Mangalek - mangalik.net) — WordPress Madara architecture
 */
class MangalekSource : ConfigurableHtmlMangaSource(
    id = "mangalek",
    name = "مانجا ليك Mangalek",
    domain = "mangalik.net",
    iconText = "M",
    iconColorHex = 0xFFD32F2F,
    requiresCloudflareVerification = false,
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
    catalogItems = listOf(
        CatalogSeedItem(
            id = "murim_psychopath",
            title = "Murim Psychopath",
            altTitles = listOf("مجنون عالم الموريم", "싸이코패스 무림"),
            coverUrl = "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=500&q=80",
            genres = listOf("اكشن", "بطل غير اعتيادي", "دراما", "دموي", "غموض", "فانتازيا", "فنون قتالية"),
            status = "OnGoing",
            rating = 4.7f,
            author = "Tigers Studio",
            artist = "Kirose",
            chapterCount = 50,
            description = "من تأليف الكاتب الذي قدم لكم عمل ومن إنتاج الاستوديو الذي قدم لكم عمل — [مرحباً بك في عالم الموريم الافتراضي، حيث لا بقاء إلا للأقوياء!] وقع عطب في جهازك، وقد أخفقت محاولة تسجيل الخروج! بينما يسعى جاهداً للعودة إلى كنف الواقع الافتراضي، إذا به يجد نفسه في مواجهة قتالية حاسمة.",
            sampleDialogues = listOf(
                "إنه قـــادم!! خذوا مواقعكم!!",
                "مهلاً، هناك من يقترب من البوابة الشمالية.",
                "تنبيه طارئ من النظام: لقد استهلك القراء أجزاء الفصول بمعدل قياسي!",
                "في عالم الموريم لا مكان للتردد... السيف وحده يحكم!"
            )
        ),
        CatalogSeedItem(
            id = "ceres_time_healer",
            title = "Ceres, the Time Healer",
            altTitles = listOf("سيريس معالجة الزمن", "시간치료사 세레스"),
            coverUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=500&q=80",
            genres = listOf("تجسيد", "خارق للطبيعة", "رومانسي", "سحر", "كوميدي", "هوس"),
            status = "OnGoing",
            rating = 4.0f,
            author = "Elysia Novel",
            artist = "Studio Time",
            chapterCount = 33,
            description = "غريس إليسيا، آنسة عائلة إليسيا الكونتية، التي لُقبت بالقديسة والشيطانة في آنٍ واحد بسبب قدرتها الغامضة على إعادة الزمن. رغم أنها أنقذت أعداداً لا تحصى من البشر في هذا العالم، إلا أنها لم تستطع علاج مرضها هي. وفقط قبل أن تلفظ أنفاسها الأخيرة جراء مرضها العضال، كان يساورها قلق أخير واحد!",
            sampleDialogues = listOf(
                "خذوا الأطفال واهربوا! لا تلتفتوا إلى الوراء!",
                "ولكن... أنا متعبة، أريد فقط أن أرتاح بعد كل هذه السنين.",
                "أهذا هو المكان الذي سأعيش فيه من الآن فصاعداً؟",
                "لقد عاد الزمن مرة أخرى... وهذه المرة سأغير المصير!"
            )
        ),
        CatalogSeedItem(
            id = "overgeared",
            title = "Overgeared",
            altTitles = listOf("المدجج بالعتاد", "템빨"),
            coverUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=500&q=80",
            genres = listOf("اكشن", "خيال", "كوميدي", "مغامرة", "ويب تون", "إيسيكاي"),
            status = "OnGoing",
            rating = 4.9f,
            author = "Park Saenal",
            artist = "Redice Studio",
            chapterCount = 64,
            description = "شين يونغوو شاب غير محظوظ في الحياة الواقعية، يجد أثناء مهمة صعبة في لعبة الواقع الافتراضي الشهيرة كتاباً أسطورياً يحوله إلى سليل الحدّاد الأسطوري باجما، ليبدأ بصناعة أقوى الأسلحة والدروع التي تغير توازن العالم.",
            sampleDialogues = listOf(
                "تم تفعيل مهارة الحداد الأسطوري: صناعة نصل التنين!",
                "هل تظن أن درعاً عادياً يمكنه صد هذه الضربة؟",
                "خصائص الأداة الجديدة تجاوزت التصنيف الأسطوري!"
            )
        ),
        CatalogSeedItem(
            id = "after_resignation",
            title = "After Resignation, I Met the Obsessive Male Lead",
            altTitles = listOf("بعد الاستقالة", "퇴사 후 집착 남주를 만났다"),
            coverUrl = "https://images.unsplash.com/photo-1541701494587-cb58502866ab?w=500&q=80",
            genres = listOf("دراما", "رومانسي", "فانتازيا", "شوجو"),
            status = "OnGoing",
            rating = 4.5f,
            author = "Han Seo",
            artist = "Yuna Art",
            chapterCount = 26,
            description = "في حياتها السابقة، كانت موظفة كورية مرهقة. في هذه الحياة، تُعين في قسم هادئ ومريح، على أمل أن تُنجز عملها بسهولة وتتقاضى راتباً دون عناء... هل هذا ما يحدث عندما تأتي موظفة جديدة بخبرة سابقة؟!",
            sampleDialogues = listOf(
                "قدمـت استقالتي للحصول على الراحة، فلماذا يلاحقني القائد الأعلى؟!",
                "هذا التقرير تم إنجازه في عشر دقائق فقط؟ مذهل!",
                "لن أعمل لساعات إضافية مهما كان الثمن!"
            )
        ),
        CatalogSeedItem(
            id = "unexpected_romance",
            title = "Unexpected romance",
            altTitles = listOf("رومانسية غير متوقعة"),
            coverUrl = "https://images.unsplash.com/photo-1516589178581-6cd7833ae3b2?w=500&q=80",
            genres = listOf("دراما", "رومانسي", "الحياة المدرسية", "كوميدي", "ويب تون"),
            status = "OnGoing",
            rating = 4.4f,
            author = "MinJi",
            artist = "Sora",
            chapterCount = 40,
            description = "قصة دافئة تجمع بين طالبين في أكاديمية النخبة يكتشفان أن المصادفات اليومية تخفي وراءها أسراراً قديمة تغير نظرتهما للمستقبل.",
            sampleDialogues = listOf(
                "لم أكن أتوقع رؤيتك في المكتبة في هذا الوقت المتأخر.",
                "هل هذا الدفتر يخصك؟ لقد وجدته قرب القاعة الرئيسية."
            )
        ),
        CatalogSeedItem(
            id = "an_extra_who_wants_to_escape",
            title = "An Extra Who Wants to Escape the Obsessive Male Lead",
            altTitles = listOf("شخصية ثانوية تريد الهروب"),
            coverUrl = "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=500&q=80",
            genres = listOf("دراما", "رومانسي", "زمكاني", "فانتازيا", "مانهوا", "إيسيكاي"),
            status = "OnGoing",
            rating = 4.3f,
            author = "Claire",
            artist = "Luna",
            chapterCount = 51,
            description = "استيقظتُ داخل رواية خيالية كشخصية ثانوية لا يذكرها أحد، وكان هدفي الوحيد الابتعاد عن الأحداث الرئيسية والعيش بسلام في الريف، لكن البطل الرئيسي للرواية بدأ يغير مسار القصة بالكامل!",
            sampleDialogues = listOf(
                "أنا مجرد شخصية ثانوية في هذه الرواية، لماذا تتغير الأحداث؟",
                "عربة السفر جاهزة يا آنستي، علينا المغادرة قبل الفجر."
            )
        ),
        CatalogSeedItem(
            id = "how_to_survive_academy",
            title = "How to Survive at the Academy",
            altTitles = listOf("كيف أنجو في الأكاديمية"),
            coverUrl = "https://images.unsplash.com/photo-1511512578047-dfb367046420?w=500&q=80",
            genres = listOf("اكشن", "فانتازيا", "مغامرة", "إيسيكاي", "إدارة المناطق"),
            status = "OnGoing",
            rating = 4.8f,
            author = "Korakoram",
            artist = "Green Kyrin",
            chapterCount = 75,
            description = "يجد الكاتب نفسه متجسداً في شخصية إد روثتايلور المنبوذ داخل أكاديمية سيلفينيا، وعليه استخدام معرفته الدقيقة بكل أسرار العالم للبقاء على قيد الحياة في الغابة الشمالية والتخرج بسلام.",
            sampleDialogues = listOf(
                "بناء المخيم في الغابة الشمالية هو خطوتي الأولى للبقاء.",
                "الأرواح العنصرية تستجيب لتعويذة الرياح المتقدمة!"
            )
        ),
        CatalogSeedItem(
            id = "surviving_in_another_world",
            title = "Surviving In Another World",
            altTitles = listOf("البقاء في عالم آخر"),
            coverUrl = "https://images.unsplash.com/photo-1563089145-599997674d42?w=500&q=80",
            genres = listOf("إيسيكاي", "اكشن", "مغامرة", "فانتازيا", "ابراج"),
            status = "OnGoing",
            rating = 5.0f,
            author = "Orc Master",
            artist = "Studio Apex",
            chapterCount = 38,
            description = "محارب مخضرم ينتقل إلى عالم موازٍ مليء بالأبراج المحصنة والوحوش الأسطورية، حيث يعتمد على خبرته التكتيكية لتكوين تحالفات غير متوقعة.",
            sampleDialogues = listOf(
                "الطابق العشرون من البرج يفتح أبوابه الآن!",
                "تشكيل الدفاع الدائري فوراً!"
            )
        )
    )
)

/**
 * 2. Team-X Source (olympustaff.com) — Custom MangaStream / Olympus theme selectors
 */
class TeamXSource : ConfigurableHtmlMangaSource(
    id = "teamx",
    name = "Team-X",
    domain = "olympustaff.com",
    iconText = "X",
    iconColorHex = 0xFF1E293B,
    requiresCloudflareVerification = false,
    itemSelector = "div.bsx, div.utao, div.page-item-detail",
    titleSelector = "a[title], .tt, h3 a",
    coverSelector = ".limit, .imgu, .item-thumb",
    latestChapterSelector = ".epxs, .adds .epxs, .chapter a",
    detailsTitleSelector = "h1.entry-title, .author-info-title h1",
    detailsDescSelector = ".entry-content p, .review-content p, .summary__content",
    detailsGenresSelector = ".mgen a, .review-author-info a, .genres-content a",
    detailsAuthorSelector = ".fmed b:contains(المؤلف) + span, .author-content a",
    detailsStatusSelector = ".tsinfo .imptdt:contains(الحالة) i, .post-status",
    chapterRowSelector = "#chapterlist li, .eplister ul li, li.wp-manga-chapter",
    readerImagesSelector = "#readerarea img, .reading-content img, .page-break img",
    catalogItems = listOf(
        CatalogSeedItem(
            id = "took_over_academy_sashimi",
            title = "I Took Over The Academy With a Single Sashimi Knife",
            altTitles = listOf("سيطرت على الأكاديمية بسكين واحد"),
            coverUrl = "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?w=500&q=80",
            genres = listOf("اكشن", "فانتازيا", "فنون قتالية", "إيسيكاي", "الحياة المدرسية"),
            status = "OnGoing",
            rating = 4.9f,
            author = "Tigers Team",
            artist = "Hitbook Studio",
            chapterCount = 45,
            description = "استيقظ كيم ووجين، مطور الألعاب في شركة REW، ليجد نفسه قد أصبح هو جيونغين داخل عالم اللعبة التي كان يصممها! إذا كان السيف هو قدره فسوف يحرك هذا القدر بنفسه ليبقى على قيد الحياة.",
            sampleDialogues = listOf(
                "مهارة التقطيع الدقيق تتجاوز حدود السيوف التقليدية!",
                "مرحباً أعزائي القراء في فصل جديد من ترجمة فريق تايجرز!"
            )
        ),
        CatalogSeedItem(
            id = "return_mount_hua",
            title = "Return of the Mount Hua Sect",
            altTitles = listOf("عودة طائفة جبل هوا"),
            coverUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=500&q=80",
            genres = listOf("اكشن", "فنون قتالية", "كوميدي", "تاريخي", "مغامرة"),
            status = "OnGoing",
            rating = 4.9f,
            author = "Biga",
            artist = "LICO",
            chapterCount = 112,
            description = "تشونغ ميونغ، قديس سيف زهرة البرقوق الثالث عشر، يعود إلى الحياة بعد مئة عام من معركته الأخيرة ليجد طائفة جبل هوا العريقة قد فقدت مجدها، فيقرر إعادة بنائها من الصفر بطريقته الخاصة.",
            sampleDialogues = listOf(
                "تلميذة جبل هوا: بداية تجنن وتدريب لا يرحم!",
                "سيف زهرة البرقوق سيزهر من جديد فوق القمم!"
            )
        ),
        CatalogSeedItem(
            id = "nano_machine",
            title = "Nano Machine",
            altTitles = listOf("آلة النانو"),
            coverUrl = "https://images.unsplash.com/photo-1550745165-9bc0b252726f?w=500&q=80",
            genres = listOf("اكشن", "فنون قتالية", "خيال علمي", "انتقام"),
            status = "OnGoing",
            rating = 4.8f,
            author = "Han-Joong-Wol-Ya",
            artist = "Geum-Gang-Bul-Gae",
            chapterCount = 95,
            description = "بعد تعرضه لمحاولة اغتيال، يتلقى تشون يو-وون تقنية نانو متطورة من أحد أحفاده القادمين من المستقبل، لتندمج مع جسده وتمنحه قدرات تحليل وقتال غير مسبوقة في أكاديمية الموريم.",
            sampleDialogues = listOf(
                "تم تفعيل نظام النانو من الجيل السابع: جاري مسح تقنية الخصم...",
                "معدل التوافق مع الطاقة الداخلية وصل إلى 100%!"
            )
        )
    )
)

/**
 * 3. Mangatek Source (مانجاتك - Mangatek - mangatek.com)
 */
class MangatekSource : ConfigurableHtmlMangaSource(
    id = "mangatek",
    name = "مانجاتك - Mangatek",
    domain = "mangatek.com",
    iconText = "M",
    iconColorHex = 0xFF3949AB,
    requiresCloudflareVerification = true,
    itemSelector = "div.manga-item, div.bsx, div.page-item-detail",
    titleSelector = "h3 a, .post-title a, a[title]",
    coverSelector = ".manga-poster, .limit, .item-thumb",
    latestChapterSelector = ".chapter a, .epxs",
    detailsTitleSelector = "h1.post-title, h1.entry-title",
    detailsDescSelector = ".summary__content, .entry-content p",
    detailsGenresSelector = ".genres-content a, .mgen a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter, #chapterlist li",
    readerImagesSelector = ".reading-content img, #readerarea img",
    catalogItems = listOf(
        CatalogSeedItem(
            id = "what_death_taught_me",
            title = "What Death Taught Me",
            altTitles = listOf("ما علمني إياه الموت"),
            coverUrl = "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=500&q=80",
            genres = listOf("دراما", "رومانسي", "شوجو", "مانهوا", "فانتازيا"),
            status = "Completed",
            rating = 4.3f,
            author = "Dilar Studio",
            artist = "Monarch Team",
            chapterCount = 52,
            description = "قاتل أمي، الذي ترك ندوباً على وجهي إلى الأبد، بل وسرق خطيبي — كاتيا! هذه المرة، سأستعيد كل شيء. لكن فكرة إعادة كتابة الماضي لم تكن أكثر من غطرسة مني... تبدأ معركة لا هوادة فيها.",
            sampleDialogues = listOf(
                "لقد كان حرقهم على التل مشهداً لا يُنسى...",
                "أرغوس، لقد أحضرتُ ما طلبته في الموعد المحدد.",
                "ولكن أولاً، ينبغي علينا أن نبدأ في الاحتفال بعودة الحق!",
                "يا آنستي، حسناً إذاً، يجب أن أذهب الآن..."
            )
        ),
        CatalogSeedItem(
            id = "logging_10000_years",
            title = "Logging 10,000 Years into the Future",
            altTitles = listOf("تسجيل الدخول لعشرة آلاف سنة في المستقبل"),
            coverUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=500&q=80",
            genres = listOf("أكشن", "شوينن", "فانتازيا", "فنون قتالية", "مغامرة"),
            status = "OnGoing",
            rating = 4.7f,
            author = "Ozul Scans",
            artist = "Poxer Studio",
            chapterCount = 372,
            description = "في فجر ثورة في عصر فنون الدفاع عن النفس، تبدأ جحافل من الوحوش المرعبة بغزو العالم، وهزيمة محاربي البشرية واحداً تلو الآخر، حتى يكتشف لو شينغ قدرة الدخول إلى المستقبل لعشرة آلاف عام.",
            sampleDialogues = listOf(
                "فقط لندرك أننا كنا في عالم أوسع مما تخيلنا!",
                "تم نسخ تقنية التنفس القتالي من سجلات المستقبل!",
                "المستوى القتالي الحالي تجاوز تصنيف القائد الأعلى!"
            )
        ),
        CatalogSeedItem(
            id = "mysterious_young_master",
            title = "The Mysterious Young Master",
            altTitles = listOf("السيد الشاب الغامض"),
            coverUrl = "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=500&q=80",
            genres = listOf("أكشن", "إثارة", "فنون قتالية", "تاريخي"),
            status = "OnGoing",
            rating = 4.6f,
            author = "Murim Chronicle",
            artist = "Cloud Ink",
            chapterCount = 37,
            description = "بعد أن فقدت عشيرة ماندوريم مجدها إثر تورطها في الحرب العظمى، يعود إليها حفيد زعيمها الأكبر، شاب يبدو كطالب علم مسالم، لكنه في الحقيقة ملك الجبال بلا منازع، أحد أعظم عشرة خبراء في العالم.",
            sampleDialogues = listOf(
                "هل تتذكر كيف كانت إيل-رين في طفولتها؟",
                "يزعم بعض المحاربين أنه نعمة من السماء، لكن الحقيقة أعمق.",
                "أجل، إنه الندم الوحيد الذي يلازمني منذ تلك المعركة.",
                "غير أن هذه الطاقة تتسبب تدريجياً في إعادة تشكيل المسارات!"
            )
        ),
        CatalogSeedItem(
            id = "solo_max_level_newbie",
            title = "Solo Max-Level Newbie",
            altTitles = listOf("مبتدئ بالمستوى الأقصى"),
            coverUrl = "https://images.unsplash.com/photo-1563089145-599997674d42?w=500&q=80",
            genres = listOf("أكشن", "خيال", "مغامرة", "ابراج"),
            status = "OnGoing",
            rating = 4.8f,
            author = "Maslow",
            artist = "Swingbat",
            chapterCount = 140,
            description = "جينهيوك، اللاعب الوحيد الذي أنهى برج التجارب المستحيل في اللعبة، يجد أن البرج قد أصبح حقيقة واقعة في العالم، ليستخدم معرفته بكل الطوابق السرية للوصول إلى القمة.",
            sampleDialogues = listOf(
                "أنا الوحيد الذي يعرف الممر السري في الطابق الأول!",
                "تم الحصول على المهارة الفريدة بنجاح!"
            )
        ),
        CatalogSeedItem(
            id = "peerless_battle_spirit",
            title = "Peerless Battle Spirit",
            altTitles = listOf("روح القتال المنقطعة النظير"),
            coverUrl = "https://images.unsplash.com/photo-1511512578047-dfb367046420?w=500&q=80",
            genres = listOf("أكشن", "خيال", "فنون قتالية", "مغامرة"),
            status = "OnGoing",
            rating = 4.5f,
            author = "jiuce",
            artist = "jiuce art",
            chapterCount = 88,
            description = "في قارة تسانغلان، حيث تحدد الروح القتالية مصير المحاربين، يوقظ تشين نان روحاً قتالية قادرة على التطور بلا حدود.",
            sampleDialogues = listOf(
                "الروح القتالية الإلهية تستيقظ الآن!",
                "لن يقف أحد في طريق صعودي إلى القمة!"
            )
        )
    )
)

/**
 * 4. Azora Manga Source (ازورا مانجا - Azora Manga - azoramanga.com)
 */
class AzoraMangaSource : ConfigurableHtmlMangaSource(
    id = "azora",
    name = "ازورا مانجا - Azora Manga",
    domain = "azoramanga.com",
    iconText = "A",
    iconColorHex = 0xFFFB8C00,
    requiresCloudflareVerification = false,
    itemSelector = "div.page-item-detail, div.bsx",
    titleSelector = ".post-title h3 a, .post-title a, a[title]",
    coverSelector = ".item-thumb, .limit",
    latestChapterSelector = ".chapter-item:first-child .chapter a, .epxs",
    detailsTitleSelector = ".post-title h1",
    detailsDescSelector = ".summary__content p, .summary__content",
    detailsGenresSelector = ".genres-content a",
    detailsAuthorSelector = ".author-content a",
    detailsStatusSelector = ".post-status .summary-content",
    chapterRowSelector = "li.wp-manga-chapter",
    readerImagesSelector = ".reading-content img, .page-break img",
    catalogItems = listOf(
        CatalogSeedItem(
            id = "magic_emperor",
            title = "Magic Emperor",
            altTitles = listOf("إمبراطور السحر", "مدير عائلة لو"),
            coverUrl = "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=500&q=80",
            genres = listOf("أكشن", "خيال", "فنون قتالية", "بطل غير اعتيادي"),
            status = "OnGoing",
            rating = 4.9f,
            author = "Nightingale",
            artist = "Wuer Manhua",
            chapterCount = 120,
            description = "تشو ييفان، الإمبراطور القديم الذي يمتلك سجل الأباطرة التسعة، يتجسد في جسد كبير خدم عائلة متواضعة، ليقودها بدهائه الاستراتيجي إلى قمة الإمبراطورية.",
            sampleDialogues = listOf(
                "عائلة لو ستصبح القوة الأولى في هذه الإمبراطورية!",
                "خطتك مكشوفة منذ البداية أيها الشيخ!"
            )
        ),
        CatalogSeedItem(
            id = "the_beginning_after_the_end",
            title = "The Beginning After The End",
            altTitles = listOf("البداية بعد النهاية"),
            coverUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=500&q=80",
            genres = listOf("أكشن", "فانتازيا", "إيسيكاي", "سحر", "مغامرة"),
            status = "OnGoing",
            rating = 4.9f,
            author = "TurtleMe",
            artist = "Fuyuki23",
            chapterCount = 175,
            description = "الملك غراي يولد من جديد كآرثر ليوين في عالم قارة ديكاثين المليء بالسحر والتنانين، حاملاً حكمة حياته السابقة لحماية عائلته الجديدة.",
            sampleDialogues = listOf(
                "نواة المانا الخاصة بي وصلت إلى المرحلة الفضية!",
                "سأحمي عائلتي مهما كلف الأمر!"
            )
        )
    )
)

/**
 * 5. Official MangaDex Public REST API v5 Connector (mangadex.org)
 * Uses https://api.mangadex.org/manga?includes[]=cover_art for real JSON API extraction.
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
    catalogItems = listOf(
        CatalogSeedItem(
            id = "omniscient_reader",
            title = "Omniscient Reader's Viewpoint",
            altTitles = listOf("وجهة نظر القارئ الشامل"),
            coverUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=500&q=80",
            genres = listOf("أكشن", "فانتازيا", "مغامرة", "إثارة"),
            status = "OnGoing",
            rating = 4.95f,
            author = "Sing N Song",
            artist = "Sleepy-C",
            chapterCount = 90,
            description = "كيم دوكجا هو القارئ الوحيد الذي أكمل قراءة رواية استمرت عشر سنوات، وعندما تتحول الرواية إلى واقع، يصبح هو الشخص الوحيد الذي يعرف نهاية العالم.",
            sampleDialogues = listOf(
                "تم تفعيل المهارة الحصرية: وجهة نظر القارئ الشامل!",
                "السيناريو الرئيسي الأول يبدأ الآن!"
            )
        )
    )
) {
    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val offset = ((page - 1).coerceAtLeast(0)) * 12
        val apiUrl = "https://api.mangadex.org/manga?limit=12&offset=$offset&includes[]=cover_art&contentRating[]=safe&order[followedCount]=desc"
        val apiItems = withTimeoutOrNull(6_000L) {
            runCatching {
                val req = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "MangaReaderAndroid/2.0")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<MangaSummary>()
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val dataArr = root.optJSONArray("data") ?: return@use emptyList<MangaSummary>()
                    val list = mutableListOf<MangaSummary>()
                    for (i in 0 until dataArr.length()) {
                        val obj = dataArr.getJSONObject(i)
                        val mdId = obj.getString("id")
                        val attrs = obj.getJSONObject("attributes")
                        val titleObj = attrs.optJSONObject("title")
                        val title = titleObj?.optString("en")?.ifBlank { null }
                            ?: titleObj?.keys()?.asSequence()?.firstOrNull()?.let { titleObj.optString(it) }
                            ?: "MangaDex Work"
                        var coverFileName: String? = null
                        val rels = obj.optJSONArray("relationships")
                        if (rels != null) {
                            for (r in 0 until rels.length()) {
                                val rel = rels.getJSONObject(r)
                                if (rel.optString("type") == "cover_art") {
                                    coverFileName = rel.optJSONObject("attributes")?.optString("fileName")
                                }
                            }
                        }
                        val coverUrl = if (!coverFileName.isNullOrBlank()) {
                            "https://uploads.mangadex.org/covers/$mdId/$coverFileName.256.jpg"
                        } else {
                            catalogItems.first().coverUrl
                        }
                        list.add(
                            MangaSummary(
                                id = "mangadex_$mdId",
                                sourceId = id,
                                title = title,
                                coverUrl = coverUrl,
                                mangaUrl = "https://mangadex.org/title/$mdId",
                                latestChapter = "متاح",
                                genres = listOf("مانهوا", "أكشن"),
                                rating = 4.8f,
                                status = attrs.optString("status", "ongoing").replaceFirstChar { it.titlecase(Locale.ROOT) }
                            )
                        )
                    }
                    list
                }
            }.getOrNull()
        }

        if (!apiItems.isNullOrEmpty()) {
            PageResult(items = apiItems, page = page, hasNextPage = true)
        } else {
            super.getPopular(page)
        }
    }
}
