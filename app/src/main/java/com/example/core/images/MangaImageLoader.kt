package com.example.core.images

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File
import java.net.URI

/**
 * Builds an HTTP-header-aware Coil ImageRequest that:
 * - Resolves local file paths or remote HTTPS URLs
 * - Attaches standard mobile browser User-Agent, Origin, Referer, and Cookie headers
 *   so hotlink-protected manga CDNs do not reject cover/page requests with HTTP 403
 * - Enables disk and memory caching
 * - Supports bounded retry via retryAttempt key
 */
object MangaImageRequestBuilder {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    fun create(
        context: Context,
        imageUrlOrPath: String?,
        refererUrl: String? = null,
        retryAttempt: Int = 0
    ): ImageRequest {
        val clean = imageUrlOrPath?.trim().orEmpty()
        val dataModel: Any? = when {
            clean.isEmpty() -> null
            clean.startsWith("/") && !clean.startsWith("//") -> File(clean)
            clean.startsWith("file://") -> File(clean.removePrefix("file://"))
            clean.startsWith("//") -> "https:$clean"
            retryAttempt > 0 && clean.startsWith("https://") -> {
                val sep = if (clean.contains("?")) "&" else "?"
                "$clean${sep}_retry=$retryAttempt"
            }
            else -> clean
        }

        val computedReferer = refererUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: extractOriginReferer(clean)
        val cookieHeader = resolveCookieHeader(clean, computedReferer)

        return ImageRequest.Builder(context)
            .data(dataModel)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (clean.startsWith("https://") || clean.startsWith("http://") || clean.startsWith("//")) {
                    addHeader("User-Agent", DEFAULT_USER_AGENT)
                    addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    if (!computedReferer.isNullOrBlank()) {
                        addHeader("Referer", computedReferer)
                        addHeader("Origin", computedReferer.trimEnd('/'))
                    }
                    if (!cookieHeader.isNullOrBlank()) {
                        addHeader("Cookie", cookieHeader)
                    }
                }
            }
            .build()
    }

    fun build(
        context: Context,
        data: Any?,
        refererUrl: String? = null,
        crossfade: Boolean = false
    ): ImageRequest {
        val urlStr = (data as? String)?.trim().orEmpty()
        val computedReferer = refererUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: extractOriginReferer(urlStr)
        val cookieHeader = resolveCookieHeader(urlStr, computedReferer)
        return ImageRequest.Builder(context)
            .data(data)
            .crossfade(crossfade)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (urlStr.startsWith("https://") || urlStr.startsWith("http://")) {
                    addHeader("User-Agent", DEFAULT_USER_AGENT)
                    addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    if (!computedReferer.isNullOrBlank()) {
                        addHeader("Referer", computedReferer)
                        addHeader("Origin", computedReferer.trimEnd('/'))
                    }
                    if (!cookieHeader.isNullOrBlank()) {
                        addHeader("Cookie", cookieHeader)
                    }
                }
            }
            .build()
    }

    private fun resolveCookieHeader(imageUrl: String, refererUrl: String?): String? {
        return try {
            val cm = CookieManager.getInstance()
            val c1 = if (imageUrl.startsWith("http")) cm.getCookie(imageUrl) else null
            val c2 = if (!refererUrl.isNullOrBlank()) cm.getCookie(refererUrl) else null
            c1?.takeIf { it.isNotBlank() } ?: c2?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractOriginReferer(url: String): String? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return try {
            val uri = URI(url)
            val host = uri.host ?: return null
            "https://$host/"
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Unified Cover Image Component with automatic bounded retry, optional sensitive cover blur
 * (+18 / Mature privacy protection), and a styled poster fallback.
 */
@Composable
fun MangaCoverImage(
    coverUrl: String? = null,
    imageUrl: String? = null,
    title: String = "",
    modifier: Modifier = Modifier,
    refererUrl: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    allowManualRetry: Boolean = false,
    blurSensitive: Boolean = false,
    ratingBadgeText: String? = null
) {
    val resolvedUrl = coverUrl ?: imageUrl
    val context = LocalContext.current
    var retryCount by remember(resolvedUrl) { mutableIntStateOf(0) }
    var isBlurRevealed by remember(resolvedUrl, blurSensitive) { mutableStateOf(false) }
    val shouldBlurNow = blurSensitive && !isBlurRevealed

    val palette = remember(title) {
        val hash = kotlin.math.abs(title.hashCode())
        val gradients = listOf(
            listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF1E293B)),
            listOf(Color(0xFF3B0764), Color(0xFF4C1D95), Color(0xFF1E1B4B)),
            listOf(Color(0xFF14532D), Color(0xFF065F46), Color(0xFF0F172A)),
            listOf(Color(0xFF450A0A), Color(0xFF7F1D1D), Color(0xFF18181B)),
            listOf(Color(0xFF0C4A6E), Color(0xFF1E3A8A), Color(0xFF0F172A))
        )
        gradients[hash % gradients.size]
    }

    Box(modifier = modifier) {
        if (resolvedUrl.isNullOrBlank()) {
            StylizedCoverFallback(
                title = title,
                gradientColors = palette,
                showRetryBadge = false,
                onRetry = {},
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (shouldBlurNow) Modifier.blur(18.dp) else Modifier)
            )
        } else {
            val request = remember(resolvedUrl, refererUrl, retryCount) {
                MangaImageRequestBuilder.create(
                    context = context,
                    imageUrlOrPath = resolvedUrl,
                    refererUrl = refererUrl,
                    retryAttempt = retryCount
                )
            }

            SubcomposeAsyncImage(
                model = request,
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (shouldBlurNow) Modifier.blur(18.dp) else Modifier),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(palette)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                error = {
                    if (retryCount < 1) {
                        retryCount += 1
                    }
                    StylizedCoverFallback(
                        title = title,
                        gradientColors = palette,
                        showRetryBadge = allowManualRetry && retryCount < 3,
                        onRetry = { if (retryCount < 3) retryCount += 1 },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }

        if (!ratingBadgeText.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFB91C1C).copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            ) {
                Text(
                    text = ratingBadgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        if (shouldBlurNow) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { isBlurRevealed = true },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = "غلاف حساس مموّه",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "غلاف +18 مموّه\nانقر للإظهار",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun StylizedCoverFallback(
    title: String,
    gradientColors: List<Color>,
    showRetryBadge: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Brush.verticalGradient(gradientColors))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (showRetryBadge) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.clickable(onClick = onRetry)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "إعادة تحميل الغلاف",
                        tint = Color.White,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(16.dp)
                    )
                }
            }
        }
    }
}
