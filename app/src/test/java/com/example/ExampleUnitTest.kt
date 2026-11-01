package com.example

import com.example.core.common.isSafeRedirectDestination
import com.example.core.common.normalizeDomain
import com.example.domain.models.DownloadStatus
import com.example.domain.models.DownloadTask
import com.example.domain.models.SourceStatus
import com.example.sourceapi.HtmlImageExtractor
import com.example.sourceapi.SourceRegistry
import com.example.sourceapi.SourceValidator
import com.example.sources.MangalekSource
import com.example.sources.MangatekSource
import com.example.sources.TeamXSource
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun normalizeDomain_cleansAndExtractsValidHost() {
        assertEquals("mangalik.net", normalizeDomain("https://www.mangalik.net/manga/murim/?page=2"))
        assertEquals("olympustaff.com", normalizeDomain("  olympustaff.com/chapters/1  "))
        assertEquals("mangatek.com", normalizeDomain("HTTPS://MANGATEK.COM/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeDomain_rejectsUnsafeHttpScheme() {
        normalizeDomain("http://mangalik.net")
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeDomain_rejectsLocalhostAndInternalNetworks() {
        normalizeDomain("https://192.168.1.10/admin")
    }

    @Test
    fun redirectValidation_allowsSubdomainAndBlocksCrossOrigin() {
        assertTrue(isSafeRedirectDestination("mangalik.net", "https://cdn.mangalik.net/img/01.webp"))
        assertFalse(isSafeRedirectDestination("mangalik.net", "https://malicious-site.org/login"))
    }

    @Test
    fun htmlImageExtractor_supportsLazyAttributesAndResolvesRelativeUrls() {
        val sampleHtml = """
            <div class="reading-content">
                <img class="wp-manga-chapter-img" data-src="/uploads/ch1/001.webp" src="data:image/gif;base64,R0lGOD" />
                <img class="wp-manga-chapter-img" data-lazy-src="https://cdn.mangalik.net/ch1/002.webp" />
                <img class="wp-manga-chapter-img" data-original="//cdn.mangalik.net/ch1/003.webp" />
                <img class="wp-manga-chapter-img" srcset="https://cdn.mangalik.net/ch1/004.webp 1x, https://cdn.mangalik.net/ch1/004_hd.webp 2x" />
            </div>
        """.trimIndent()

        val pages = HtmlImageExtractor.extractChapterPagesFromHtml(sampleHtml, "https://mangalik.net")
        assertEquals(4, pages.size)
        assertEquals("https://mangalik.net/uploads/ch1/001.webp", pages[0].imageUrl)
        assertEquals("https://cdn.mangalik.net/ch1/002.webp", pages[1].imageUrl)
        assertEquals("https://cdn.mangalik.net/ch1/003.webp", pages[2].imageUrl)
        assertEquals("https://cdn.mangalik.net/ch1/004.webp", pages[3].imageUrl)
    }

    @Test
    fun htmlImageExtractor_detectsCloudflareChallengePage() {
        val cfHtml = "<html><head><title>Just a moment...</title></head><body>Cloudflare Turnstile</body></html>"
        assertTrue(HtmlImageExtractor.isSecurityVerificationPage(cfHtml, 403))
        assertFalse(HtmlImageExtractor.isSecurityVerificationPage("<html><body>Normal Content</body></html>", 200))
    }

    @Test
    fun sourceValidator_distinguishesSupportedUnsupportedAndCloudflareSources() = runTest {
        val registry = SourceRegistry(listOf(MangalekSource(), TeamXSource(), MangatekSource()))
        val validator = SourceValidator(registry)

        // 1. Supported source (mangalik.net)
        val mangalekRes = validator.validateDomainCompatibility("https://mangalik.net/").getOrThrow()
        assertEquals(SourceStatus.SUPPORTED, mangalekRes.status)

        // 2. Unsupported source (random-unregistered.com)
        val unsupportedRes = validator.validateDomainCompatibility("random-unregistered.com").getOrThrow()
        assertEquals(SourceStatus.UNSUPPORTED, unsupportedRes.status)

        // 3. Cloudflare source before & after human verification (mangatek.com)
        val beforeVerify = validator.validateDomainCompatibility("mangatek.com").getOrThrow()
        assertEquals(SourceStatus.REQUIRES_VERIFICATION, beforeVerify.status)

        validator.markDomainHumanVerified("mangatek.com")
        val afterVerify = validator.validateDomainCompatibility("mangatek.com").getOrThrow()
        assertEquals(SourceStatus.SUPPORTED, afterVerify.status)
    }

    @Test
    fun downloadTask_calculatesProgressCorrectly() {
        val task = DownloadTask(
            id = "dl_1",
            chapterId = "ch_1",
            mangaId = "m_1",
            mangaTitle = "Murim Psychopath",
            chapterTitle = "الفصل 1",
            coverUrl = null,
            totalPages = 100,
            downloadedPages = 48,
            failedPages = 0,
            status = DownloadStatus.DOWNLOADING
        )
        assertEquals(48, task.progress)
    }
}
