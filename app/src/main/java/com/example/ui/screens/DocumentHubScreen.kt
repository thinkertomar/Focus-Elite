package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppScreen
import com.example.data.model.HomeWorkspaceTab
import com.example.data.model.PdfFileItem
import com.example.data.model.RecentDocumentEntity
import com.example.data.model.ToolType
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.TealAccent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentHubScreen(
    recentDocuments: List<RecentDocumentEntity>,
    localFiles: List<PdfFileItem> = emptyList(),
    activeDocumentName: String? = null,
    activeDocumentPages: Int = 0,
    homeSearchQuery: String = "",
    onHomeSearchQueryChange: (String) -> Unit = {},
    selectedHomeTab: HomeWorkspaceTab = HomeWorkspaceTab.RECENT,
    onSelectHomeTab: (HomeWorkspaceTab) -> Unit = {},
    onToggleStar: (String) -> Unit = {},
    onOpenUri: (Uri) -> Unit,
    onOpenAndSearchOcr: (String) -> Unit = {},
    onOpenAndEditPdf: (Uri?) -> Unit = {},
    onLoadSample: () -> Unit,
    onNavigateScreen: (AppScreen) -> Unit,
    onNavigateTool: (ToolType) -> Unit,
    onDeleteRecent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // File picker launcher
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onOpenUri(it) }
    }

    val filteredRecents = remember(recentDocuments, homeSearchQuery, selectedHomeTab) {
        val base = if (selectedHomeTab == HomeWorkspaceTab.STARRED) {
            recentDocuments.filter { it.isStarred }
        } else {
            recentDocuments
        }
        if (homeSearchQuery.isBlank()) base
        else base.filter { it.displayName.contains(homeSearchQuery.trim(), ignoreCase = true) }
    }

    val filteredLocalFiles = remember(localFiles, homeSearchQuery) {
        if (homeSearchQuery.isBlank()) localFiles
        else localFiles.filter { it.name.contains(homeSearchQuery.trim(), ignoreCase = true) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("hub_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Executive Brand Header & Adobe/M365 Command Card
        item {
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hero_banner")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = CrimsonAccent,
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "PDF",
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "PdfCraft Studio",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "ML Kit OCR • In-Place PDF Editor",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusBadge(
                                    text = "100% OFFLINE",
                                    backgroundColor = TealAccent.copy(alpha = 0.15f),
                                    textColor = TealAccent,
                                    icon = Icons.Filled.Security
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { onNavigateScreen(AppScreen.SETTINGS) },
                                    modifier = Modifier.testTag("btn_hub_settings")
                                ) {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = "Settings",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Unified Search & Deep OCR Trigger Bar (Adobe / Microsoft 365 Pattern)
                        OutlinedTextField(
                            value = homeSearchQuery,
                            onValueChange = onHomeSearchQueryChange,
                            placeholder = {
                                Text(
                                    text = "Filter PDFs or search text inside active PDF...",
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = IndigoPrimary
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (homeSearchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { onHomeSearchQueryChange("") },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Clear search",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = IndigoPrimary.copy(alpha = 0.14f),
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .clickable { onOpenAndSearchOcr(homeSearchQuery) }
                                            .testTag("btn_hub_launch_ocr_search")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Filled.DocumentScanner,
                                                contentDescription = null,
                                                tint = IndigoPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "OCR Search",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = IndigoPrimary
                                            )
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = IndigoPrimary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_hub_search")
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Primary CTA Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { openDocLauncher.launch(arrayOf("application/pdf")) },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("btn_open_pdf")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open PDF", fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = onLoadSample,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("btn_sample_guide")
                            ) {
                                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sample Guide", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        FilledTonalButton(
                            onClick = { onNavigateScreen(AppScreen.FILES) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("btn_hub_view_all_files")
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Browse All Local PDF Documents", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // 2. Active Workspace Resume Banner (Quick Jump to Viewer, OCR Search, or Pencil Edit)
        if (!activeDocumentName.isNullOrBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, IndigoPrimary.copy(alpha = 0.28f)),
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("active_doc_resume_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(IndigoPrimary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = IndigoPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Active Workspace • $activeDocumentPages pages",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = IndigoPrimary
                                    )
                                    Text(
                                        text = activeDocumentName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = { onNavigateScreen(AppScreen.VIEWER) },
                                label = { Text("Read", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(15.dp))
                                },
                                modifier = Modifier.testTag("btn_resume_read")
                            )

                            AssistChip(
                                onClick = { onOpenAndSearchOcr(homeSearchQuery) },
                                label = { Text("OCR Search", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Search, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(15.dp))
                                },
                                modifier = Modifier.testTag("btn_resume_ocr_search")
                            )

                            AssistChip(
                                onClick = { onOpenAndEditPdf(null) },
                                label = { Text("Edit Text", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Edit, contentDescription = null, tint = CrimsonAccent, modifier = Modifier.size(15.dp))
                                },
                                modifier = Modifier.testTag("btn_resume_pencil_edit")
                            )
                        }
                    }
                }
            }
        }

        // 3. Adobe & Microsoft 365 Style Pro Tool Grid (2x4 Bento Grid)
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quick Document Tools",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Pro Suite",
                        style = MaterialTheme.typography.labelSmall,
                        color = IndigoPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                // Row 1: Smart OCR Search, In-Place Pencil Edit, Crop, Compress
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickToolTile(
                        title = "OCR Search",
                        subtitle = "Find scanned text",
                        icon = Icons.Filled.DocumentScanner,
                        tint = AmberAccent,
                        onClick = { onOpenAndSearchOcr(homeSearchQuery) },
                        modifier = Modifier.weight(1f)
                    )
                    QuickToolTile(
                        title = "Edit Text",
                        subtitle = "Pencil studio",
                        icon = Icons.Filled.Edit,
                        tint = CrimsonAccent,
                        onClick = { onOpenAndEditPdf(null) },
                        modifier = Modifier.weight(1f)
                    )
                    QuickToolTile(
                        title = "Crop",
                        subtitle = "Trim margins",
                        icon = Icons.Filled.Crop,
                        tint = EmeraldAccent,
                        onClick = {
                            onNavigateTool(ToolType.CROP)
                            onNavigateScreen(AppScreen.TOOLS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    QuickToolTile(
                        title = "Compress",
                        subtitle = "Shrink size",
                        icon = Icons.Filled.Compress,
                        tint = IndigoPrimary,
                        onClick = {
                            onNavigateTool(ToolType.COMPRESS)
                            onNavigateScreen(AppScreen.TOOLS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 2: Merge, Split, Images to PDF, Stamp Watermark
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickToolTile(
                        title = "Merge",
                        subtitle = "Combine files",
                        icon = Icons.AutoMirrored.Filled.CallMerge,
                        tint = IndigoPrimary,
                        onClick = {
                            onNavigateTool(ToolType.MERGE)
                            onNavigateScreen(AppScreen.TOOLS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    QuickToolTile(
                        title = "Split",
                        subtitle = "Extract pages",
                        icon = Icons.AutoMirrored.Filled.CallSplit,
                        tint = TealAccent,
                        onClick = {
                            onNavigateTool(ToolType.SPLIT)
                            onNavigateScreen(AppScreen.TOOLS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    QuickToolTile(
                        title = "Images",
                        subtitle = "Photos to PDF",
                        icon = Icons.Filled.Image,
                        tint = CrimsonAccent,
                        onClick = {
                            onNavigateTool(ToolType.IMAGES_TO_PDF)
                            onNavigateScreen(AppScreen.TOOLS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    QuickToolTile(
                        title = "Stamp",
                        subtitle = "Watermark",
                        icon = Icons.Filled.Style,
                        tint = MaterialTheme.colorScheme.secondary,
                        onClick = {
                            onNavigateTool(ToolType.WATERMARK)
                            onNavigateScreen(AppScreen.TOOLS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 3: Password Protection & AES Security + Watermark Overlay Studio Banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickToolTile(
                        title = "Protect",
                        subtitle = "Password PDF",
                        icon = Icons.Filled.Lock,
                        tint = EmeraldAccent,
                        onClick = {
                            onNavigateTool(ToolType.ENCRYPT)
                            onNavigateScreen(AppScreen.TOOLS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    QuickToolTile(
                        title = "Watermark",
                        subtitle = "Text & Image",
                        icon = Icons.Filled.Style,
                        tint = CrimsonAccent,
                        onClick = {
                            onNavigateTool(ToolType.WATERMARK)
                            onNavigateScreen(AppScreen.TOOLS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Segmented Document Hub Tabs (Recent | Starred | On Device)
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Your Documents",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val count = when (selectedHomeTab) {
                        HomeWorkspaceTab.RECENT, HomeWorkspaceTab.STARRED -> filteredRecents.size
                        HomeWorkspaceTab.LOCAL_FILES -> filteredLocalFiles.size
                    }
                    Text(
                        text = "$count files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HomeWorkspaceTab.entries.forEach { tab ->
                        val selected = selectedHomeTab == tab
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectHomeTab(tab) },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            },
                            leadingIcon = if (tab == HomeWorkspaceTab.STARRED) {
                                {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = if (selected) AmberAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            modifier = Modifier.testTag("hub_tab_${tab.name}")
                        )
                    }
                }
            }
        }

        // 5. Document List Based on Active Tab
        if (selectedHomeTab == HomeWorkspaceTab.LOCAL_FILES) {
            if (filteredLocalFiles.isEmpty()) {
                item {
                    EmptyDocumentsPlaceholder(
                        title = "No matching device PDFs found",
                        subtitle = "Open a PDF from your device storage or clear the search filter."
                    )
                }
            } else {
                items(filteredLocalFiles, key = { it.uri.toString() }) { fileItem ->
                    val isStarred = recentDocuments.find { it.uriString == fileItem.uri.toString() }?.isStarred == true
                    RecentDocumentCard(
                        document = RecentDocumentEntity(
                            id = fileItem.uri.toString().hashCode().toLong(),
                            uriString = fileItem.uri.toString(),
                            displayName = fileItem.name,
                            pageCount = fileItem.pageCount,
                            fileSizeFormatted = fileItem.sizeFormatted,
                            lastOpened = fileItem.lastModified,
                            isStarred = isStarred
                        ),
                        onOpen = { onOpenUri(fileItem.uri) },
                        onEditWithPencil = { onOpenAndEditPdf(fileItem.uri) },
                        onToggleStar = { onToggleStar(fileItem.uri.toString()) },
                        onDelete = { onDeleteRecent(fileItem.uri.toString()) }
                    )
                }
            }
        } else {
            if (filteredRecents.isEmpty()) {
                item {
                    EmptyDocumentsPlaceholder(
                        title = if (selectedHomeTab == HomeWorkspaceTab.STARRED) "No starred documents yet" else "No recent documents yet",
                        subtitle = if (selectedHomeTab == HomeWorkspaceTab.STARRED) {
                            "Tap the star icon on any PDF card to pin important files here."
                        } else {
                            "Open a PDF from your storage or explore the sample guide above."
                        }
                    )
                }
            } else {
                items(filteredRecents, key = { it.id }) { doc ->
                    RecentDocumentCard(
                        document = doc,
                        onOpen = { onOpenUri(Uri.parse(doc.uriString)) },
                        onEditWithPencil = { onOpenAndEditPdf(Uri.parse(doc.uriString)) },
                        onToggleStar = { onToggleStar(doc.uriString) },
                        onDelete = { onDeleteRecent(doc.uriString) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDocumentsPlaceholder(
    title: String,
    subtitle: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun QuickToolTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag("tool_tile_$title")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.12f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RecentDocumentCard(
    document: RecentDocumentEntity,
    onOpen: () -> Unit,
    onEditWithPencil: () -> Unit = {},
    onToggleStar: () -> Unit = {},
    onDelete: () -> Unit
) {
    val dateFormatted = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(document.lastOpened))

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("recent_doc_${document.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rendered First Page Thumbnail Preview
            com.example.ui.components.PdfThumbnailPreview(
                uri = Uri.parse(document.uriString),
                pageIndex = 0,
                modifier = Modifier.size(width = 44.dp, height = 58.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${document.pageCount} pages • ${document.fileSizeFormatted} • $dateFormatted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Star / Pin Toggle
            IconButton(
                onClick = onToggleStar,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("btn_star_recent_${document.id}")
            ) {
                Icon(
                    imageVector = if (document.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (document.isStarred) "Unstar document" else "Star document",
                    tint = if (document.isStarred) AmberAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Direct Pencil Edit Action
            IconButton(
                onClick = onEditWithPencil,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("btn_edit_recent_${document.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit PDF Text",
                    tint = IndigoPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("btn_delete_recent_${document.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Delete from history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
