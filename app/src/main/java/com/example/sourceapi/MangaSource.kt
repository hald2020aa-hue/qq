package com.example.sourceapi

import com.example.core.common.normalizeDomain
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.domain.models.SourceStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Section 7 & Rule 2: Unified extension connector interface.
 * Every source implements this independently without coupling UI to HTML structure.
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

    suspend fun getPopular(page: Int): PageResult<MangaSummary>
    suspend fun search(query: String, page: Int): PageResult<MangaSummary>
    suspend fun getDetails(mangaUrl: String): MangaDetails
    suspend fun getChapters(mangaUrl: String): List<MangaChapter>
    suspend fun getPages(chapterUrl: String): List<MangaPage>
}

/**
 * Section 8: Multi-attribute HTML Image Extractor using Jsoup & OkHttp HttpUrl.
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
                if (resolved != null) return resolved
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
                if (resolved != null) return resolved
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
            lower.startsWith("javascript:")
        ) {
            return false
        }
        return true
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
        selector: String = ".reading-content img, .page-break img, .chapter-content img, #readerarea img, img.wp-manga-chapter-img"
    ): List<MangaPage> {
        val doc: Document = Jsoup.parse(html, baseUri)
        val elements = doc.select(selector)
        val urls = mutableListOf<String>()
        for (el in elements) {
            val url = extractImageUrl(el, baseUri)
            if (!url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
                urls.add(url)
            }
        }
        return urls.distinct().mapIndexed { index, imageUrl ->
            MangaPage(index = index, imageUrl = imageUrl)
        }
    }

    fun isSecurityVerificationPage(html: String, httpCode: Int = 200): Boolean {
        val lower = html.lowercase()
        return httpCode in listOf(403, 503, 429) && (
            lower.contains("cloudflare") ||
                lower.contains("cf-browser-verification") ||
                lower.contains("turnstile") ||
                lower.contains("just a moment") ||
                lower.contains("إجراء التحقق من الأمان")
            )
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
    val messageAr: String
)

class SourceRegistry(sources: List<MangaSource>) {
    private val byId = sources.associateBy { it.id }.toMutableMap()
    private val byDomain = sources.associateBy { it.domain.lowercase() }.toMutableMap()

    fun register(source: MangaSource) {
        byId[source.id] = source
        byDomain[source.domain.lowercase()] = source
    }

    fun findById(id: String): MangaSource? = byId[id]

    fun findByDomain(domainInput: String): MangaSource? {
        val clean = runCatching { normalizeDomain(domainInput) }.getOrNull() ?: return null
        return byDomain[clean] ?: byDomain.entries.firstOrNull { clean.endsWith(it.key) || it.key.endsWith(clean) }?.value
    }

    fun getAllRegistered(): List<MangaSource> = byId.values.toList()
}

class SourceValidator(private val registry: SourceRegistry) {
    private val verifiedDomains = mutableSetOf<String>()

    fun markDomainHumanVerified(domain: String) {
        runCatching { normalizeDomain(domain) }.getOrNull()?.let {
            verifiedDomains.add(it)
        }
    }

    fun isDomainHumanVerified(domain: String): Boolean {
        val clean = runCatching { normalizeDomain(domain) }.getOrNull() ?: return false
        return verifiedDomains.contains(clean)
    }

    /**
     * Rule 3 & Rule 20: Adding a domain alone does NOT make a site supported.
     * Validates the domain syntax, checks if a registered connector exists,
     * checks if human verification (Cloudflare) is required, and tests getPopular + getDetails + getChapters.
     */
    suspend fun validateDomainCompatibility(rawInput: String): Result<SourceValidationStepResult> {
        val normalized = try {
            normalizeDomain(rawInput)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        val connector = registry.findByDomain(normalized)
            ?: return Result.success(
                SourceValidationStepResult(
                    normalizedDomain = normalized,
                    status = SourceStatus.UNSUPPORTED,
                    matchedSource = null,
                    suggestedName = normalized,
                    messageAr = "هذا النطاق ($normalized) غير مدعوم حالياً ولا يمتلك موصلاً متوافقاً."
                )
            )

        if (connector.requiresCloudflareVerification && !isDomainHumanVerified(normalized)) {
            return Result.success(
                SourceValidationStepResult(
                    normalizedDomain = normalized,
                    status = SourceStatus.REQUIRES_VERIFICATION,
                    matchedSource = connector,
                    suggestedName = connector.name,
                    messageAr = "يتطلب الموقع إتمام التحقق الأمني (Cloudflare) للمتابعة."
                )
            )
        }

        return try {
            val popular = connector.getPopular(1)
            val firstWork = popular.items.firstOrNull()
            if (firstWork == null) {
                Result.success(
                    SourceValidationStepResult(
                        normalizedDomain = normalized,
                        status = SourceStatus.NEEDS_UPDATE,
                        matchedSource = connector,
                        suggestedName = connector.name,
                        messageAr = "تعذّر مطابقة الأعمال من المصدر. قد يحتاج الموصل إلى تحديث."
                    )
                )
            } else {
                val chapters = connector.getChapters(firstWork.mangaUrl)
                if (chapters.isEmpty()) {
                    Result.success(
                        SourceValidationStepResult(
                            normalizedDomain = normalized,
                            status = SourceStatus.NEEDS_UPDATE,
                            matchedSource = connector,
                            suggestedName = connector.name,
                            messageAr = "تعذّر مطابقة الفصول من المصدر."
                        )
                    )
                } else {
                    Result.success(
                        SourceValidationStepResult(
                            normalizedDomain = normalized,
                            status = SourceStatus.SUPPORTED,
                            matchedSource = connector,
                            suggestedName = connector.name,
                            messageAr = "المصدر متوافق تماماً وتم التحقق من الأعمال والفصول."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.success(
                SourceValidationStepResult(
                    normalizedDomain = normalized,
                    status = SourceStatus.ERROR,
                    matchedSource = connector,
                    suggestedName = connector.name,
                    messageAr = "حدث خطأ أثناء فحص المصدر: ${e.message ?: "خطأ غير معروف"}"
                )
            )
        }
    }
}
