package com.example.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.example.core.database.FavoriteEntity
import com.example.core.database.ReadingHistoryEntity
import com.example.domain.models.GridDisplayMode
import com.example.domain.models.MangaSummary
import com.example.domain.models.SortOption
import com.example.domain.models.SourceInfo
import com.example.domain.models.StatusFilter
import com.example.features.CatalogUiState
import com.example.ui.theme.StarYellow

@Composable
fun StorageHomeScreen(
    storedManga: List<MangaSummary>,
    gridDisplayMode: GridDisplayMode,
    onOpenDrawer: () -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenSourceManager: () -> Unit,
    onNavigateDownloads: () -> Unit,
    onNavigateSettings: () -> Unit,
    onChangeGridMode: (GridDisplayMode) -> Unit,
    onImportSampleWork: () -> Unit
) {
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDisplaySubMenu by remember { mutableStateOf(false) }

    val filtered = remember(storedManga, searchQuery) {
        if (searchQuery.isBlank()) storedManga
        else storedManga.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar matching Video 1 & 2 (Menu on right in RTL, Search + 3-dot on left)
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.testTag("open_drawer_button")
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "القائمة")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "التخزين",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { searchActive = !searchActive },
                            modifier = Modifier.testTag("storage_search_button")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "البحث")
                        }
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("storage_overflow_button")
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "المزيد")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("نمط العرض") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showDisplaySubMenu = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("المراجع") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenSourceManager()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("استيراد") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onImportSampleWork()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("التنزيلات") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onNavigateDownloads()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("الاعدادات") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onNavigateSettings()
                                    }
                                )
                            }

                            DropdownMenu(
                                expanded = showDisplaySubMenu,
                                onDismissRequest = { showDisplaySubMenu = false }
                            ) {
                                Text(
                                    text = "نمط العرض",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                                GridDisplayMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = gridDisplayMode == mode,
                                                    onClick = null
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(mode.labelAr)
                                            }
                                        },
                                        onClick = {
                                            onChangeGridMode(mode)
                                            showDisplaySubMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (searchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("ابحث في التخزين...") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                searchQuery = ""
                                searchActive = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "إغلاق البحث")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { }
                    ) {
                        Text(
                            text = "آخر الاضافات",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "ترتيب",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            // Exact Kaomoji Empty State from Video 1 & 2: (`o´) / (-_-) + "لم تحفظ اي عمل في التخزين." + "استيراد"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "( ｀o´ )",
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB0B6C6)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "لم تحفظ اي عمل في التخزين.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = onImportSampleWork,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("storage_import_button")
                    ) {
                        Text(
                            text = "استيراد",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        } else {
            AdaptiveMangaGridOrList(
                items = filtered,
                displayMode = gridDisplayMode,
                onItemClick = { onOpenManga(it.id) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SourceCatalogScreen(
    source: SourceInfo?,
    mangaItems: List<MangaSummary>,
    catalogState: CatalogUiState,
    gridDisplayMode: GridDisplayMode,
    onOpenDrawer: () -> Unit,
    onOpenManga: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onToggleGenre: (String) -> Unit,
    onSelectSort: (SortOption) -> Unit,
    onSelectStatus: (StatusFilter) -> Unit,
    onClearFilters: () -> Unit,
    onChangeGridMode: (GridDisplayMode) -> Unit,
    onRefreshCatalog: (Int) -> Unit,
    onTriggerCloudflareVerify: (String) -> Unit,
    onNavigateDownloads: () -> Unit,
    onNavigateSettings: () -> Unit
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDisplaySubMenu by remember { mutableStateOf(false) }
    var showJumpPageDialog by remember { mutableStateOf(false) }
    var jumpPageInput by remember { mutableStateOf(catalogState.currentPage.toString()) }

    val availableGenres = listOf(
        "اكشن", "فانتازيا", "إيسيكاي", "رومانسي", "دراما", "مغامرة", "كوميدي", "فنون قتالية", "ابراج", "إدارة المناطق", "انتقام", "شوجو"
    )

    val filteredItems = remember(
        mangaItems,
        catalogState.searchQuery,
        catalogState.selectedGenres,
        catalogState.selectedSort,
        catalogState.selectedStatus
    ) {
        var list = mangaItems
        if (catalogState.searchQuery.isNotBlank()) {
            val q = catalogState.searchQuery.trim()
            list = list.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.genres.any { g -> g.contains(q, ignoreCase = true) }
            }
        }
        if (catalogState.selectedGenres.isNotEmpty()) {
            list = list.filter { item ->
                catalogState.selectedGenres.any { sel ->
                    item.genres.any { g -> g.contains(sel, ignoreCase = true) }
                }
            }
        }
        if (catalogState.selectedStatus != StatusFilter.ALL) {
            list = when (catalogState.selectedStatus) {
                StatusFilter.COMPLETED -> list.filter { it.status.equals("Completed", true) }
                StatusFilter.ONGOING -> list.filter { !it.status.equals("Completed", true) }
                else -> list
            }
        }
        when (catalogState.selectedSort) {
            SortOption.ALPHABETICAL -> list.sortedBy { it.title }
            SortOption.RATING -> list.sortedByDescending { it.rating ?: 0f }
            SortOption.MOST_VIEWED, SortOption.POPULAR -> list.sortedByDescending { it.rating ?: 0f }
            else -> list
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header matching Video 2, 3, 4 ("مانجا ليك Mangalek" / "مانجاتك - Mangatek")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "القائمة")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = source?.name ?: "المكتبة",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onRefreshCatalog(catalogState.currentPage) },
                    modifier = Modifier.testTag("catalog_refresh_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                }
                IconButton(
                    onClick = { onToggleSearch(!catalogState.isSearchActive) },
                    modifier = Modifier.testTag("catalog_search_button")
                ) {
                    Icon(Icons.Default.Search, contentDescription = "بحث")
                }
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "المزيد")
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("نمط العرض") },
                            onClick = {
                                showOverflowMenu = false
                                showDisplaySubMenu = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("توجه الى") },
                            onClick = {
                                showOverflowMenu = false
                                showJumpPageDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("التنزيلات") },
                            onClick = {
                                showOverflowMenu = false
                                onNavigateDownloads()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("الاعدادات") },
                            onClick = {
                                showOverflowMenu = false
                                onNavigateSettings()
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showDisplaySubMenu,
                        onDismissRequest = { showDisplaySubMenu = false }
                    ) {
                        GridDisplayMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = gridDisplayMode == mode, onClick = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(mode.labelAr)
                                    }
                                },
                                onClick = {
                                    onChangeGridMode(mode)
                                    showDisplaySubMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (catalogState.isSearchActive) {
            OutlinedTextField(
                value = catalogState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("ابحث داخل ${source?.name ?: "المصدر"}...") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { onToggleSearch(false) }) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        // Filter row & Horizontal Genre Chips Bar (Matching Video 2 & 4)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "صفحة ${catalogState.currentPage}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { showFilterSheet = true }
                    .padding(vertical = 4.dp)
                    .testTag("catalog_filter_button")
            ) {
                Text(
                    text = catalogState.selectedSort.labelAr,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "الفلتر",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = catalogState.selectedGenres.isNotEmpty(),
                onClick = { showFilterSheet = true },
                label = { Text("التصنيفات") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
            availableGenres.forEach { genre ->
                val isSelected = catalogState.selectedGenres.contains(genre)
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggleGenre(genre) },
                    label = { Text(genre) },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }

        // Content Body: Loading / Cloudflare Verification Gate / Adaptive Grid
        when {
            catalogState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            catalogState.requiresCloudflareGate && source != null -> {
                // Exact match to Video 4 (00:05): Robot icon + "عليك فتح الصفحة وإكمال التحقق" + "فتح صفحة الموقع"
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "التحقق الأمني",
                            tint = Color(0xFFB0B8C8),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "عليك فتح الصفحة وإكمال التحقق",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onTriggerCloudflareVerify(source.domain) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("open_site_verification_button")
                        ) {
                            Text(
                                text = "فتح صفحة الموقع",
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            else -> {
                AdaptiveMangaGridOrList(
                    items = filteredItems,
                    displayMode = gridDisplayMode,
                    onItemClick = { onOpenManga(it.id) }
                )
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showFilterSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق")
                        }
                        if (catalogState.selectedGenres.isNotEmpty() || catalogState.selectedStatus != StatusFilter.ALL) {
                            TextButton(onClick = onClearFilters) {
                                Text("مسح التحديد")
                            }
                        }
                    }
                    Text(
                        text = "الفلتر",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "تصفح حسب",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { showSortDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(catalogState.selectedSort.labelAr)
                        Text("▼")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "الحالة",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusFilter.entries.forEach { st ->
                        FilterChip(
                            selected = catalogState.selectedStatus == st,
                            onClick = { onSelectStatus(st) },
                            label = { Text(st.labelAr) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "اي تصنيف من المحددة",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableGenres.forEach { genre ->
                        val selected = catalogState.selectedGenres.contains(genre)
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleGenre(genre) },
                            label = { Text(genre) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("تصفح حسب") },
            text = {
                Column {
                    SortOption.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectSort(option)
                                    showSortDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = catalogState.selectedSort == option,
                                onClick = {
                                    onSelectSort(option)
                                    showSortDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(option.labelAr)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showJumpPageDialog) {
        AlertDialog(
            onDismissRequest = { showJumpPageDialog = false },
            title = { Text("توجه الى الصفحة") },
            text = {
                OutlinedTextField(
                    value = jumpPageInput,
                    onValueChange = { jumpPageInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("رقم الصفحة") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val page = jumpPageInput.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    showJumpPageDialog = false
                    onRefreshCatalog(page)
                }) {
                    Text("إنتقال")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpPageDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
fun AdaptiveMangaGridOrList(
    items: List<MangaSummary>,
    displayMode: GridDisplayMode,
    onItemClick: (MangaSummary) -> Unit
) {
    if (displayMode == GridDisplayMode.LIST) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.id }) { manga ->
                MangaListCardItem(manga = manga, onClick = { onItemClick(manga) })
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = displayMode.minCellWidthDp.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("manga_grid")
        ) {
            items(items, key = { it.id }) { manga ->
                MangaGridCardItem(manga = manga, onClick = { onItemClick(manga) })
            }
        }
    }
}

@Composable
fun MangaGridCardItem(
    manga: MangaSummary,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("manga_card_${manga.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            SubcomposeAsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1E2638), Color(0xFF121622))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = Color(0xFF5A6275),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            )

            if (!manga.latestChapter.isNullOrBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = manga.latestChapter,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = manga.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (manga.genres.isNotEmpty()) {
            Text(
                text = manga.genres.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MangaListCardItem(
    manga: MangaSummary,
    onClick: () -> Unit
) {
    // Matches Video 2 (00:40) List Mode with title, genres, 5 stars, "إقرأ" link, and cover on the right
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .height(122.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                SubcomposeAsyncImage(
                    model = manga.coverUrl,
                    contentDescription = manga.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = manga.genres.joinToString(","),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(5) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = StarYellow,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${manga.rating ?: 4.5f}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "إقرأ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun RecommendationsScreen(
    allManga: List<MangaSummary>,
    gridDisplayMode: GridDisplayMode,
    onOpenDrawer: () -> Unit,
    onOpenManga: (String) -> Unit,
    onBrowseAllSources: () -> Unit
) {
    var browseAllMode by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "القائمة")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "التوصيات",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !browseAllMode,
                onClick = { browseAllMode = false },
                label = { Text("التصنيفات") }
            )
            FilterChip(
                selected = browseAllMode,
                onClick = {
                    browseAllMode = true
                    onBrowseAllSources()
                },
                label = { Text("الكل") }
            )
        }

        if (!browseAllMode || allManga.isEmpty()) {
            // Exact Kaomoji from Video 2 (03:55): ( ⊙д⊙ ) + "لا تتوفر تصنيفات كافية لتوصياتك. يمكنك تصفح «الكل»." + "إعادة المحاولة"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "( ⊙д⊙ )",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB0B6C6)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "لا تتوفر تصنيفات كافية لتوصياتك. يمكنك تصفح «الكل».",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = {
                            browseAllMode = true
                            onBrowseAllSources()
                        },
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("إعادة المحاولة")
                    }
                }
            }
        } else {
            AdaptiveMangaGridOrList(
                items = allManga,
                displayMode = gridDisplayMode,
                onItemClick = { onOpenManga(it.id) }
            )
        }
    }
}

@Composable
fun FavoritesScreen(
    favorites: List<Pair<FavoriteEntity, MangaSummary>>,
    categories: List<String>,
    gridDisplayMode: GridDisplayMode,
    onOpenDrawer: () -> Unit,
    onOpenManga: (String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("الكل") }
    val filtered = remember(favorites, selectedCategory) {
        if (selectedCategory == "الكل") favorites.map { it.second }
        else favorites.filter { it.first.category == selectedCategory }.map { it.second }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "القائمة")
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "المفضلة", style = MaterialTheme.typography.headlineMedium)
        }

        // Category Tabs matching Video 2 (04:00): "الكل 0 | قراءة الآن 0 | قراءة لاحقاً 0"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            categories.forEach { cat ->
                val count = if (cat == "الكل") favorites.size else favorites.count { it.first.category == cat }
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text("$cat ($count)") }
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "(⊙_⊙)",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB0B6C6)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "المفضلة فارغة.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            AdaptiveMangaGridOrList(
                items = filtered,
                displayMode = gridDisplayMode,
                onItemClick = { onOpenManga(it.id) }
            )
        }
    }
}

@Composable
fun HistoryAndUpdatesScreen(
    titleAr: String,
    emptyKaomoji: String,
    emptyMessageAr: String,
    historyItems: List<Pair<ReadingHistoryEntity, MangaSummary>>,
    onOpenDrawer: () -> Unit,
    onOpenManga: (String) -> Unit,
    onResumeChapter: (String, String, Int) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "القائمة")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = titleAr, style = MaterialTheme.typography.headlineMedium)
            }
            if (historyItems.isNotEmpty()) {
                IconButton(onClick = onClearHistory) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "مسح السجل")
                }
            }
        }

        if (historyItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = emptyKaomoji,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB0B6C6)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = emptyMessageAr,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(historyItems, key = { it.first.id }) { (hist, manga) ->
                    Card(
                        onClick = { onOpenManga(manga.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                SubcomposeAsyncImage(
                                    model = manga.coverUrl,
                                    contentDescription = manga.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(56.dp, 76.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = manga.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "الفصل ${hist.chapterTitle} • الصفحة ${hist.pageIndex}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    onResumeChapter(
                                        manga.id,
                                        hist.chapterId,
                                        (hist.pageIndex - 1).coerceAtLeast(0)
                                    )
                                },
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text("إستمر")
                            }
                        }
                    }
                }
            }
        }
    }
}
