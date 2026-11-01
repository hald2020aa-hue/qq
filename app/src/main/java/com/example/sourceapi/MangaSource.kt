package com.example.sourceapi

import com.example.core.common.normalizeDomain
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.PageResult
import com.example.domain.models.SourceInfo
import com.example.domain.models.SourceStatus
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
 * Section 8: Multi-attribute HTML Image Extractor using Jsoup.
 * Supports: src, data-src, data-lazy-src, data-original, srcset.
 * Resolves relative URLs against baseUri and preserves page order.
 */
object HtmlImageExtractor {

    private val IMAGE_ATTRIBUTES = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "srcset",
        "src"
    )

    fun extractImageUrl(element: Element?, baseUri: String): String? {
        if (element == null) return null
        for (attr in IMAGE_ATTRIBUTES) {
            val raw = element.attr(attr).trim()
            if (raw.isNotEmpty() && !raw.startsWith("data:image/svg") && !raw.startsWith("data:image/gif")) {
                val candidate = if (attr == "srcset") {
                    raw.split(',')
                        .firstOrNull()
                        ?.trim()
                        ?.split(' ')
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                } else {
                    raw
                }
                val resolved = resolveUrl(baseUri, candidate)
                if (resolved != null) return resolved
            }
        }
        return null
    }

    fun extractChapterPagesFromHtml(html: String, baseUri: String, selector: String = ".reading-content img, .page-break img, .chapter-content img, img.wp-manga-chapter-img"): List<MangaPage> {
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

    fun resolveUrl(baseUri: String, relativeOrAbsolute: String): String? {
        val clean = relativeOrAbsolute.trim()
        if (clean.isEmpty()) return null
        return try {
            when {
                clean.startsWith("https://") -> clean
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http://") -> "https://" + clean.removePrefix("http://")
                else -> URI(baseUri).resolve(clean).toString()
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

        // Rule 20: Test Popular, Details, and Chapters before declaring SUPPORTED
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
