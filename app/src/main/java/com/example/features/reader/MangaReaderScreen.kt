package com.example.features.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.core.storage.UserSettings
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaPage
import com.example.domain.models.ReaderMode
import com.example.features.ReaderUiState
import kotlinx.coroutines.launch
import java.io.File

/**
 * Section 11 & Video 2 (06:32 - 08:24) & Video 3 (00:40 - 01:44):
 * Native Jetpack Compose Reader supporting Vertical Webtoon (LazyColumn) and Horizontal Pager (RTL/LTR),
 * Pinch/Double-tap Zoom, Loading Progress Bar ("التحميل: 48%"), Page Retry ("خطأ في التحميل - حاول مجدداً!"),
 * HUD Controls with Page Slider, Chapter List Picker Dialog, and End-of-Chapter Comment Card.
 */
@Composable
fun MangaReaderScreen(
    mangaId: String,
    mangaTitle: String,
    chapterId: String,
    chapters: List<MangaChapter>,
    readerState: ReaderUiState,
    settings: UserSettings,
    isFavorite: Boolean,
    onNavigateBack: () -> Unit,
    onPageChanged: (Int, Int) -> Unit,
    onToggleControls: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSelectChapter: (String) -> Unit,
    onChangeReaderMode: (ReaderMode) -> Unit,
    onOpenReadingSettings: () -> Unit,
    onRetryFailedPage: (Int) -> Unit,
    onSaveCurrentPage: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = readerState.currentPageIndex)
    val pagerState = rememberPagerState(
        initialPage = readerState.currentPageIndex,
        pageCount = { readerState.pages.size.coerceAtLeast(1) }
    )

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    var showChapterListDialog by remember { mutableStateOf(false) }
    var showBottomSettingsPopup by remember { mutableStateOf(false) }
    var showCommentsDialog by remember { mutableStateOf(false) }

    val currentChapterIndex = remember(chapters, chapterId) {
        chapters.indexOfFirst { it.id == chapterId }
    }
    val prevChapter = chapters.getOrNull(currentChapterIndex - 1)
    val nextChapter = chapters.getOrNull(currentChapterIndex + 1)

    // Track scroll position in Vertical Webtoon mode and save reading progress continuously (Rule 9)
    val visibleWebtoonPage by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    LaunchedEffect(visibleWebtoonPage) {
        if (!readerState.isLoading && settings.readerMode == ReaderMode.VERTICAL_WEBTOON) {
            onPageChanged(
                visibleWebtoonPage.coerceIn(0, (readerState.pages.size - 1).coerceAtLeast(0)),
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (!readerState.isLoading && settings.readerMode != ReaderMode.VERTICAL_WEBTOON) {
            onPageChanged(pagerState.currentPage, 0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1015))
            .pointerInput(settings.enableZoom) {
                if (settings.enableZoom) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (zoomScale * zoom).coerceIn(1f, 3f)
                        zoomScale = newScale
                        if (newScale > 1.05f) {
                            panX = (panX + pan.x).coerceIn(-600f, 600f)
                            panY = (panY + pan.y).coerceIn(-800f, 800f)
                        } else {
                            panX = 0f
                            panY = 0f
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (settings.enableZoom) {
                            if (zoomScale > 1.1f) {
                                zoomScale = 1f
                                panX = 0f
                                panY = 0f
                            } else {
                                zoomScale = 2f
                            }
                        }
                    },
                    onTap = { onToggleControls() }
                )
            }
    ) {
        when {
            readerState.isLoading -> {
                // Exact Loading Bar from Video 2 (06:34 - 06:45): "التحميل: 48%"
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(220.dp)
                    ) {
                        Text(
                            text = "التحميل: ${readerState.loadingProgress}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { readerState.loadingProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            readerState.pages.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "خطأ في التحميل",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "حاول مجددا!",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { onSelectChapter(chapterId) }
                        )
                    }
                }
            }
            settings.readerMode == ReaderMode.VERTICAL_WEBTOON -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panX,
                            translationY = panY
                        )
                        .testTag("reader_vertical_list")
                ) {
                    itemsIndexed(readerState.pages, key = { _, p -> p.index }) { idx, page ->
                        val isFailed = readerState.failedPageIndices.contains(idx)
                        ReaderPageItem(
                            page = page,
                            isFailed = isFailed,
                            onRetry = { onRetryFailedPage(idx) }
                        )
                    }

                    // End of Chapter Summary + Comments Preview + Next Chapter Button (Video 2 08:18)
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF12151E))
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "نهاية الفصل ${(currentChapterIndex + 1).coerceAtLeast(1)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            if (settings.showCommentsAtEnd) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C202B)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "تلميذة جبل هوا: بداية تجنن وفصل أسطوري 🔥",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { showCommentsDialog = true },
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text("١٥ تعليق >")
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            if (nextChapter != null) {
                                Button(
                                    onClick = { onSelectChapter(nextChapter.id) },
                                    shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("next_chapter_end_button")
                                ) {
                                    Text("الفصل التالي (${nextChapter.title})")
                                }
                            }
                            Spacer(modifier = Modifier.height(60.dp))
                        }
                    }
                }
            }
            else -> {
                // Horizontal Pager Mode (RTL or LTR)
                HorizontalPager(
                    state = pagerState,
                    reverseLayout = settings.readerMode == ReaderMode.HORIZONTAL_LTR,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panX,
                            translationY = panY
                        )
                        .testTag("reader_horizontal_pager")
                ) { pageIdx ->
                    val page = readerState.pages[pageIdx]
                    val isFailed = readerState.failedPageIndices.contains(pageIdx)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        ReaderPageItem(
                            page = page,
                            isFailed = isFailed,
                            onRetry = { onRetryFailedPage(pageIdx) }
                        )
                    }
                }
            }
        }

        // Bottom Page Counter Pill ("١/٢١" - Video 2 06:55 - 07:03)
        if (settings.showPageNumber && readerState.pages.isNotEmpty()) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            ) {
                Text(
                    text = "${readerState.currentPageIndex + 1}/${readerState.pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }

        // Floating 3-dot Menu Trigger Button in bottom-right corner when HUD is hidden (Video 2 06:32)
        if (!readerState.isControlsVisible && !settings.hideMenuButton) {
            IconButton(
                onClick = onToggleControls,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .testTag("reader_open_hud_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "فتح القائمة",
                    tint = Color.White
                )
            }
        }

        // Top & Bottom Reader HUD Overlay matching Video 2 (06:38 & 08:13)
        AnimatedVisibility(
            visible = readerState.isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Reader Bar
                Surface(
                    color = Color(0xFF141720).copy(alpha = 0.94f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showChapterListDialog = true },
                                modifier = Modifier.testTag("reader_chapter_list_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FormatListBulleted,
                                    contentDescription = "قائمة الفصول",
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = { showCommentsDialog = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Comment,
                                    contentDescription = "التعليقات",
                                    tint = Color.White
                                )
                            }
                        }

                        Text(
                            text = mangaTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        )

                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("reader_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Bottom Reader Bar with Page Slider + Action Row (Video 2 06:38 & 08:15)
                Surface(
                    color = Color(0xFF141720).copy(alpha = 0.95f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        // Chapter Skip + Page Slider Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { nextChapter?.let { onSelectChapter(it.id) } },
                                enabled = nextChapter != null
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "الفصل التالي",
                                    tint = if (nextChapter != null) Color.White else Color.Gray
                                )
                            }

                            val totalPages = readerState.pages.size.coerceAtLeast(1)
                            Slider(
                                value = readerState.currentPageIndex.toFloat(),
                                onValueChange = { newVal ->
                                    val target = newVal.toInt().coerceIn(0, totalPages - 1)
                                    onPageChanged(target, 0)
                                    scope.launch {
                                        if (settings.readerMode == ReaderMode.VERTICAL_WEBTOON) {
                                            listState.scrollToItem(target)
                                        } else {
                                            pagerState.scrollToPage(target)
                                        }
                                    }
                                },
                                valueRange = 0f..(totalPages - 1).coerceAtLeast(1).toFloat(),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .testTag("reader_page_slider")
                            )

                            IconButton(
                                onClick = { prevChapter?.let { onSelectChapter(it.id) } },
                                enabled = prevChapter != null
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "الفصل السابق",
                                    tint = if (prevChapter != null) Color.White else Color.Gray
                                )
                            }
                        }

                        // Bottom 5 Icons Row (Settings, Webtoon/Orientation, Bookmark, Save Page, Favorite)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                IconButton(onClick = { showBottomSettingsPopup = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "إعدادات القارئ",
                                        tint = Color.White
                                    )
                                }
                                DropdownMenu(
                                    expanded = showBottomSettingsPopup,
                                    onDismissRequest = { showBottomSettingsPopup = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckBox,
                                                    contentDescription = null,
                                                    tint = if (settings.readerMode == ReaderMode.VERTICAL_WEBTOON)
                                                        MaterialTheme.colorScheme.primary else Color.Gray
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("وضع الويبتون")
                                            }
                                        },
                                        onClick = {
                                            val next = if (settings.readerMode == ReaderMode.VERTICAL_WEBTOON)
                                                ReaderMode.HORIZONTAL_RTL else ReaderMode.VERTICAL_WEBTOON
                                            onChangeReaderMode(next)
                                            showBottomSettingsPopup = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("الاعدادات") },
                                        onClick = {
                                            showBottomSettingsPopup = false
                                            onOpenReadingSettings()
                                        }
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    val modes = ReaderMode.entries
                                    val nextIdx = (modes.indexOf(settings.readerMode) + 1) % modes.size
                                    onChangeReaderMode(modes[nextIdx])
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "تبديل وضع القراءة",
                                    tint = Color.White
                                )
                            }

                            IconButton(onClick = onToggleBookmark) {
                                Icon(
                                    imageVector = if (readerState.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "علامة مرجعية",
                                    tint = if (readerState.isBookmarked) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }

                            IconButton(onClick = onSaveCurrentPage) {
                                Icon(
                                    imageVector = Icons.Default.SaveAlt,
                                    contentDescription = "حفظ الصفحة",
                                    tint = Color.White
                                )
                            }

                            IconButton(onClick = onToggleFavorite) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "المفضلة",
                                    tint = if (isFavorite) Color(0xFFEF5350) else Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Chapter Selector Dialog ("قائمة الفصول" - Video 2 06:46)
    if (showChapterListDialog) {
        AlertDialog(
            onDismissRequest = { showChapterListDialog = false },
            title = { Text("قائمة الفصول") },
            text = {
                LazyColumn(modifier = Modifier.height(340.dp)) {
                    itemsIndexed(chapters) { _, ch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showChapterListDialog = false
                                    onSelectChapter(ch.id)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            RadioButton(
                                selected = ch.id == chapterId,
                                onClick = {
                                    showChapterListDialog = false
                                    onSelectChapter(ch.id)
                                }
                            )
                            Text(
                                text = "الفصل ${ch.title}",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChapterListDialog = false }) {
                    Text("إغلاق")
                }
            }
        )
    }

    if (showCommentsDialog) {
        AlertDialog(
            onDismissRequest = { showCommentsDialog = false },
            title = { Text("تعليقات الفصل") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("• خالد: فصل حماسي جداً وترجمة ممتازة!")
                    Text("• ريان: الرسم في هذا الفصل أسطوري، بانتظار الفصل القادم.")
                    Text("• سارة: شكراً على سرعة تنزيل الفصول!")
                }
            },
            confirmButton = {
                TextButton(onClick = { showCommentsDialog = false }) {
                    Text("إغلاق")
                }
            }
        )
    }
}

@Composable
private fun ReaderPageItem(
    page: MangaPage,
    isFailed: Boolean,
    onRetry: () -> Unit
) {
    if (isFailed) {
        // Exact Error Retry Block from Video 3 (01:15) & Video 4 (01:17): "خطأ في التحميل / حاول مجددا!"
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(Color(0xFF0E1015)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "خطأ في التحميل",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "حاول مجددا!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = onRetry)
                )
            }
        }
    } else {
        val modelData: Any = if (!page.localPath.isNullOrBlank() && File(page.localPath).exists()) {
            File(page.localPath)
        } else {
            page.imageUrl
        }

        SubcomposeAsyncImage(
            model = modelData,
            contentDescription = "صفحة ${page.index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(800f / 1280f),
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF12151E)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("خطأ في التحميل", color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "حاول مجددا!",
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable(onClick = onRetry)
                        )
                    }
                }
            }
        )
    }
}
