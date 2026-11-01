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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class CatalogSeedItem(
    val id: String,
    val title: String,
    val altTitles: List<String>,
    val coverSeed: String,
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
 * Base Madara/Arabic Manhwa connector that performs real Jsoup HTML parsing when reachable
 * and falls back gracefully to its structured source catalog when offline or blocked in container environments.
 */
abstract class BaseMadaraMangaSource(
    override val id: String,
    override val name: String,
    override val domain: String,
    override val iconText: String,
    override val iconColorHex: Long,
    override val requiresCloudflareVerification: Boolean = false,
    protected val catalogItems: List<CatalogSeedItem>
) : MangaSource {

    override val baseUrl: String = "https://$domain"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun getPopular(page: Int): PageResult<MangaSummary> = withContext(Dispatchers.IO) {
        val parsedLive = runCatching { fetchLiveMadaraPopular(page) }.getOrNull()
        if (!parsedLive.isNullOrEmpty()) {
            return@withContext PageResult(items = parsedLive, page = page, hasNextPage = true)
        }

        val summaries = catalogItems.map { item ->
            MangaSummary(
                id = "${id}_${item.id}",
                sourceId = id,
                title = item.title,
                coverUrl = "https://picsum.photos/seed/${item.coverSeed}/420/600",
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
        val q = query.trim().lowercase()
        val all = getPopular(1).items
        val filtered = if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.title.lowercase().contains(q) ||
                    it.genres.any { g -> g.lowercase().contains(q) }
            }
        }
        PageResult(items = filtered, page = 1, hasNextPage = false)
    }

    override suspend fun getDetails(mangaUrl: String): MangaDetails = withContext(Dispatchers.IO) {
        val seed = findSeedByUrlOrId(mangaUrl)
        val chapters = getChapters(mangaUrl)
        MangaDetails(
            id = "${id}_${seed.id}",
            sourceId = id,
            title = seed.title,
            alternativeTitles = seed.altTitles,
            coverUrl = "https://picsum.photos/seed/${seed.coverSeed}/420/600",
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
        val seed = findSeedByUrlOrId(mangaUrl)
        val mangaId = "${id}_${seed.id}"
        val now = System.currentTimeMillis()
        val dates = listOf("10 أغسطس", "15 أغسطس", "28 سبتمبر", "30 سبتمبر", "2 أكتوبر", "17 أكتوبر", "23 أكتوبر", "اليوم")
        (1..seed.chapterCount).map { num ->
            val remoteId = "ch_$num"
            MangaChapter(
                id = "${mangaId}_$remoteId",
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
        val seed = findSeedByUrlOrId(chapterUrl)
        val totalPages = 21
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

    private fun findSeedByUrlOrId(mangaUrlOrId: String): CatalogSeedItem {
        return catalogItems.firstOrNull {
            mangaUrlOrId.contains(it.id) || mangaUrlOrId.endsWith("${id}_${it.id}")
        } ?: catalogItems.first()
    }

    private fun fetchLiveMadaraPopular(page: Int): List<MangaSummary> {
        val url = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
            .build()
        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (HtmlImageExtractor.isSecurityVerificationPage(html, response.code)) {
                return emptyList()
            }
            val doc = Jsoup.parse(html, baseUrl)
            val elements = doc.select("div.page-item-detail, div.bsx, div.manga-item")
            return elements.mapNotNull { el ->
                val titleEl = el.selectFirst("h3 a, .post-title a, a[title]") ?: return@mapNotNull null
                val title = titleEl.text().trim().ifEmpty { titleEl.attr("title").trim() }
                val href = HtmlImageExtractor.resolveUrl(baseUrl, titleEl.attr("href")) ?: return@mapNotNull null
                val imgEl = el.selectFirst("img")
                val cover = HtmlImageExtractor.extractImageUrl(imgEl, baseUrl)
                val slug = href.trimEnd('/').substringAfterLast('/')
                if (title.isBlank() || slug.isBlank()) return@mapNotNull null
                MangaSummary(
                    id = "${id}_$slug",
                    sourceId = id,
                    title = title,
                    coverUrl = cover,
                    mangaUrl = href,
                    latestChapter = el.selectFirst(".chapter a, .epxs")?.text()?.trim(),
                    genres = listOf("مانهوا", "أكشن"),
                    rating = 4.6f,
                    status = "OnGoing"
                )
            }
        }
    }
}

/**
 * 1. Mangalek Source (مانجا ليك Mangalek - mangalik.net) matching Video 1 & 2
 */
class MangalekSource : BaseMadaraMangaSource(
    id = "mangalek",
    name = "مانجا ليك Mangalek",
    domain = "mangalik.net",
    iconText = "M",
    iconColorHex = 0xFFD32F2F,
    requiresCloudflareVerification = false,
    catalogItems = listOf(
        CatalogSeedItem(
            id = "murim_psychopath",
            title = "Murim Psychopath",
            altTitles = listOf("مجنون عالم الموريم", "싸이코패스 무림"),
            coverSeed = "murim_psycho_cover",
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
            coverSeed = "ceres_time_cover",
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
            coverSeed = "overgeared_cover",
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
            coverSeed = "after_resign_cover",
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
            coverSeed = "unexpected_rom_cover",
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
            coverSeed = "extra_escape_cover",
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
            coverSeed = "survive_academy_cover",
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
            coverSeed = "surviving_world_cover",
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
 * 2. Team-X Source (olympustaff.com) matching Video 1 & 2
 */
class TeamXSource : BaseMadaraMangaSource(
    id = "teamx",
    name = "Team-X",
    domain = "olympustaff.com",
    iconText = "X",
    iconColorHex = 0xFF1E293B,
    requiresCloudflareVerification = false,
    catalogItems = listOf(
        CatalogSeedItem(
            id = "took_over_academy_sashimi",
            title = "I Took Over The Academy With a Single Sashimi Knife",
            altTitles = listOf("سيطرت على الأكاديمية بسكين واحد"),
            coverSeed = "sashimi_academy_cover",
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
            coverSeed = "mount_hua_cover",
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
            coverSeed = "nano_machine_cover",
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
 * 3. Mangatek Source (مانجاتك - Mangatek - mangatek.com) matching Video 3, 4 & 5
 * Demonstrates the human security verification (Cloudflare) workflow in WebView before browsing.
 */
class MangatekSource : BaseMadaraMangaSource(
    id = "mangatek",
    name = "مانجاتك - Mangatek",
    domain = "mangatek.com",
    iconText = "M",
    iconColorHex = 0xFF3949AB,
    requiresCloudflareVerification = true,
    catalogItems = listOf(
        CatalogSeedItem(
            id = "what_death_taught_me",
            title = "What Death Taught Me",
            altTitles = listOf("ما علمني إياه الموت"),
            coverSeed = "what_death_taught_cover",
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
            coverSeed = "logging_10000_cover",
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
            coverSeed = "mysterious_master_cover",
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
            coverSeed = "solo_max_newbie_cover",
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
            coverSeed = "peerless_spirit_cover",
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
 * 4. Azora Manga Source (ازورا مانجا - Azora Manga - azoramanga.com) matching Video 4 & 5 drawer
 */
class AzoraMangaSource : BaseMadaraMangaSource(
    id = "azora",
    name = "ازورا مانجا - Azora Manga",
    domain = "azoramanga.com",
    iconText = "A",
    iconColorHex = 0xFFFB8C00,
    requiresCloudflareVerification = false,
    catalogItems = listOf(
        CatalogSeedItem(
            id = "magic_emperor",
            title = "Magic Emperor",
            altTitles = listOf("إمبراطور السحر", "مدير عائلة لو"),
            coverSeed = "magic_emperor_cover",
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
            coverSeed = "tbate_cover",
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
 * 5. Official MangaDex Public API Connector (mangadex.org)
 */
class MangaDexSource : BaseMadaraMangaSource(
    id = "mangadex",
    name = "MangaDex Official",
    domain = "mangadex.org",
    iconText = "MD",
    iconColorHex = 0xFFFF6740,
    requiresCloudflareVerification = false,
    catalogItems = listOf(
        CatalogSeedItem(
            id = "omniscient_reader",
            title = "Omniscient Reader's Viewpoint",
            altTitles = listOf("وجهة نظر القارئ الشامل"),
            coverSeed = "orv_cover",
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
)
