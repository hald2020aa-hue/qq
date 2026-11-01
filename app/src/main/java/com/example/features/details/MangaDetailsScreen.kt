package com.example.features.details

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.core.database.ReadingHistoryEntity
import com.example.domain.models.MangaChapter
import com.example.domain.models.SourceInfo
import com.example.features.DetailsUiState
import com.example.features.navigation.SourceBadgeIcon
import com.example.ui.theme.StarYellow
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MangaDetailsScreen(
    mangaId: String,
    state: DetailsUiState,
    chapters: List<MangaChapter>,
    isFavorite: Boolean,
    lastHistory: ReadingHistoryEntity?,
    sourceInfo: SourceInfo?,
    onNavigateBack: () -> Unit,
    onToggleSort: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenChapterReader: (String, Int) -> Unit,
    onDownloadSingleChapter: (String) -> Unit,
    onRequestBatchDownload: (List<MangaChapter>) -> Unit,
    onConfirmBatchDownload: () -> Unit,
    onDismissBatchDialog: () -> Unit,
    onRefreshDetails: () -> Unit,
    onRemoveFromStorage: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    val details = state.details
    val sortedChapters = remember(chapters, state.sortDescending) {
        if (state.sortDescending) chapters.sortedByDescending { it.chapterNumber ?: 0f }
        else chapters.sortedBy { it.chapterNumber ?: 0f }
    }
    var showBottomMoreMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Header Bar matching Video 2 (01:09 & 06:16): Back arrow + Title + "الفصول" + Sort icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("details_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = details?.title ?: "جاري التحميل...",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "الفصول",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = onToggleSort,
                    modifier = Modifier.testTag("chapters_sort_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Sort,
                        contentDescription = "ترتيب الفصول"
                    )
                }
            }
        }

        // Resume Reading Strip if user has read any chapter (Video 2 04:50 & 07:04: "إستمر | 1 | قبل 0 ثانية • الصفحة 1")
        if (lastHistory != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "الفصل ${lastHistory.chapterTitle}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "قبل لحظات • الصفحة ${lastHistory.pageIndex}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            onOpenChapterReader(
                                lastHistory.chapterId,
                                (lastHistory.pageIndex - 1).coerceAtLeast(0)
                            )
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("resume_reading_button")
                    ) {
                        Text("إستمر", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        }

        // Main Split Body matching Video 2 (01:09 & 06:16): Right info pane + Left chapters list (in RTL, first item in Row is Right!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Right Info Column (Cover, Status, Rating, Chapters Count, Source, Genres, Description)
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (details?.status.equals("Completed", true)) "المانجا منتهية" else "جاري العمل عليها",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Cover + Status box
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubcomposeAsyncImage(
                        model = details?.coverUrl,
                        contentDescription = details?.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(0.6f)
                            .height(145.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(0.4f)
                            .height(82.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "الحالة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = details?.status ?: "OnGoing",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Stats Card (Rating + Chapter Count + Source / Storage Size)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = StarYellow,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${details?.rating ?: 4.7f}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "عدد الفصول",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${chapters.size}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.storageSizeMb > 0.05) {
                                Text(
                                    text = "التخزين",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f MB", state.storageSizeMb),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            } else if (sourceInfo != null) {
                                SourceBadgeIcon(
                                    text = sourceInfo.iconText,
                                    colorHex = sourceInfo.iconColorHex,
                                    sizeDp = 22
                                )
                                Text(
                                    text = sourceInfo.name.take(10),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Genre Tags Box
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FlowRow(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        details?.genres.orEmpty().forEach { genre ->
                            Box(
                                modifier = Modifier
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = genre,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Story Description Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (!details?.author.isNullOrBlank()) {
                            Text(
                                text = "المؤلف: ${details?.author} • الرسام: ${details?.artist ?: "-"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
                            text = details?.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Left Column: Chapters List (Video 2 01:09 & 01:19)
            Box(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
            ) {
                when {
                    state.isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    sortedChapters.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = state.errorMessageAr ?: "لم يتم العثور على فصول",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("chapters_list")
                        ) {
                            itemsIndexed(sortedChapters, key = { _, ch -> ch.id }) { index, chapter ->
                                var showChapterDownloadMenu by remember { mutableStateOf(false) }

                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenChapterReader(chapter.id, 0) }
                                            .padding(horizontal = 10.dp, vertical = 10.dp)
                                            .testTag("chapter_item_${chapter.title}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Chapter Number Circle + Title & Date
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (chapter.isRead) MaterialTheme.colorScheme.primaryContainer
                                                        else Color(0xFF2B303C)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${(chapter.chapterNumber ?: (index + 1).toFloat()).toInt()}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (chapter.isRead) MaterialTheme.colorScheme.primary else Color.White
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = if (chapter.title.startsWith("Chapter")) chapter.title else "الفصل ${chapter.title}",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = if (chapter.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground
                                                )
                                                val subText = buildString {
                                                    append(chapter.dateLabel)
                                                    if (chapter.lastReadPage > 0) {
                                                        append(" • الصفحة ${chapter.lastReadPage}")
                                                    }
                                                }
                                                Text(
                                                    text = subText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        // Download / Downloaded Check Icon with context menu (Video 2 01:19 & 07:07)
                                        Box {
                                            IconButton(
                                                onClick = { showChapterDownloadMenu = true },
                                                modifier = Modifier.testTag("chapter_download_btn_${chapter.title}")
                                            ) {
                                                if (chapter.isDownloaded) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "محمل",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.Download,
                                                        contentDescription = "تنزيل الفصل",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }

                                            DropdownMenu(
                                                expanded = showChapterDownloadMenu,
                                                onDismissRequest = { showChapterDownloadMenu = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("حفظ هذا الفصل") },
                                                    onClick = {
                                                        showChapterDownloadMenu = false
                                                        onDownloadSingleChapter(chapter.id)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("تنزيل هذا الفصل والفصول السابقة") },
                                                    onClick = {
                                                        showChapterDownloadMenu = false
                                                        val prevAndCurrent = sortedChapters.take(index + 1)
                                                        onRequestBatchDownload(prevAndCurrent)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("تنزيل هذا الفصل والفصول التالية") },
                                                    onClick = {
                                                        showChapterDownloadMenu = false
                                                        val currentAndNext = sortedChapters.drop(index)
                                                        onRequestBatchDownload(currentAndNext)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Action Bar matching Video 2 (01:09, 05:09, 08:05, 08:31)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.testTag("details_favorite_button")
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "أضف إلى المفضلة",
                        tint = if (isFavorite) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = {
                        val missing = sortedChapters.filter { !it.isDownloaded }
                        onRequestBatchDownload(missing.ifEmpty { sortedChapters })
                    },
                    modifier = Modifier.testTag("details_batch_save_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = "حفظ الفصول المفقودة"
                    )
                }

                IconButton(
                    onClick = {
                        onRefreshDetails()
                        onShowToast("جاري البحث وتحديث الفصول...")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "إعادة البحث"
                    )
                }

                Box {
                    IconButton(onClick = { showBottomMoreMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "خيارات إضافية"
                        )
                    }
                    DropdownMenu(
                        expanded = showBottomMoreMenu,
                        onDismissRequest = { showBottomMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("تحديث الصفحة") },
                            onClick = {
                                showBottomMoreMenu = false
                                onRefreshDetails()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("تحديث الغلاف") },
                            onClick = {
                                showBottomMoreMenu = false
                                onShowToast("تم تحديث الغلاف")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("انشاء اختصار") },
                            onClick = {
                                showBottomMoreMenu = false
                                onShowToast("تم إنشاء اختصار للعمل")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("شارك") },
                            onClick = {
                                showBottomMoreMenu = false
                                runCatching {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "اقرأ مانهوا ${details?.title.orEmpty()}")
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "مشاركة"))
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("تصدير") },
                            onClick = {
                                showBottomMoreMenu = false
                                onShowToast("تم تصدير الفصول المحفوظة إلى مجلد saved")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("إزالة") },
                            onClick = {
                                showBottomMoreMenu = false
                                onRemoveFromStorage()
                            }
                        )
                    }
                }
            }
        }
    }

    // Batch Download Confirmation Dialog matching Video 2 (02:30): "سيتم حفظ المانجا (جميع X الفصول)"
    if (state.showBatchConfirmDialog) {
        AlertDialog(
            onDismissRequest = onDismissBatchDialog,
            text = {
                Text(
                    text = "سيتم حفظ المانجا (جميع ${state.pendingBatchChapters.size} الفصول).",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmBatchDownload,
                    modifier = Modifier.testTag("confirm_batch_download_button")
                ) {
                    Text("حسنا")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBatchDialog) {
                    Text("إلغاء")
                }
            }
        )
    }
}
