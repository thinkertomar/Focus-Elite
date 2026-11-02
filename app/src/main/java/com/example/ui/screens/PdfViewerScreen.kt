package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.example.data.model.OcrResult
import com.example.ui.theme.EmeraldAccent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import com.example.data.model.AppScreen
import com.example.data.model.CropRectNormalized
import com.example.data.model.DocumentMetadata
import com.example.data.model.OcrSearchMatch
import com.example.data.model.OcrTextBlockItem
import com.example.data.model.PdfPageItem
import com.example.data.model.PdfTextEditOperation
import com.example.ui.components.InPlaceTextEditDialog
import com.example.ui.components.OcrAndEditCanvasOverlay
import com.example.ui.components.OcrSearchBarOverlay
import com.example.ui.components.PageCropDialog
import com.example.ui.components.PageDetectedBlocksListDialog
import com.example.ui.components.PencilEditModeBanner
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.IndigoPrimary
import java.util.UUID
import kotlin.math.roundToInt

/**
 * High-performance PDF Reader & In-Place Editor Screen.
 * Features ML Kit OCR Search with visual bounding-box highlights, interactive Pencil Edit Mode
 * for editing written/scanned text and metadata, quick 90° rotation, and pinch-to-zoom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    metadata: DocumentMetadata?,
    pages: List<PdfPageItem>,
    currentPageIndex: Int,
    highResBitmap: Bitmap?,
    isLoading: Boolean,
    isNightMode: Boolean = false,
    onToggleNightMode: () -> Unit = {},
    onOpenCrop: () -> Unit = {},
    onQuickRotatePage: () -> Unit = {},
    onPageSelected: (Int) -> Unit,
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
    onNavigateScreen: (AppScreen) -> Unit,
    onShare: () -> Unit,
    onSaveMetadata: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    // ML Kit Full-Document OCR Search state & callbacks
    isOcrSearchOpen: Boolean = false,
    ocrSearchQuery: String = "",
    ocrSearchMatches: List<OcrSearchMatch> = emptyList(),
    activeMatchIndex: Int = 0,
    isOcrIndexing: Boolean = false,
    ocrIndexedPagesCount: Int = 0,
    onOpenOcrSearch: () -> Unit = {},
    onCloseOcrSearch: () -> Unit = {},
    onOcrSearchQueryChange: (String) -> Unit = {},
    onNextOcrMatch: () -> Unit = {},
    onPrevOcrMatch: () -> Unit = {},
    onSelectOcrMatch: (Int) -> Unit = {},
    // Interactive Pencil Edit Mode state & callbacks
    isPencilEditMode: Boolean = false,
    currentPageOcrBlocks: List<OcrTextBlockItem> = emptyList(),
    selectedEditBlock: OcrTextBlockItem? = null,
    pendingTextEdits: List<PdfTextEditOperation> = emptyList(),
    onTogglePencilEditMode: () -> Unit = {},
    onSelectBlockForEdit: (OcrTextBlockItem?) -> Unit = {},
    onStageTextEdit: (PdfTextEditOperation) -> Unit = {},
    onRemoveTextEdit: (String) -> Unit = {},
    onCommitTextEdits: () -> Unit = {},
    // Legacy single-page OCR dialog support
    ocrResult: OcrResult? = null,
    isOcrRunning: Boolean = false,
    showOcrDialog: Boolean = false,
    onRunOcr: () -> Unit = {},
    onCloseOcr: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    smoothTransitions: Boolean = true,
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val invertedColorMatrix = remember {
        ColorMatrix(
            floatArrayOf(
                -1f,  0f,  0f, 0f, 255f,
                 0f, -1f,  0f, 0f, 255f,
                 0f,  0f, -1f, 0f, 255f,
                 0f,  0f,  0f, 1f,   0f
            )
        )
    }

    // Full-screen and overlay state
    var isFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showThumbnailStrip by remember { mutableStateOf(false) }
    var fitWidthMode by remember { mutableStateOf(false) }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpTargetInput by remember { mutableStateOf("") }
    var showDetectedBlocksSheet by remember { mutableStateOf(false) }
    var isAddingNewTextCallout by remember { mutableStateOf(false) }

    val totalPages = pages.size

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isFullscreen) Color.Black else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .testTag("viewer_screen")
    ) {
        // Main Document Rendering Layer with Pinch-to-Zoom, Pan & Tap Gestures
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentPageIndex, isFullscreen) {
                    detectTapGestures(
                        onTap = {
                            if (isFullscreen) {
                                showControls = !showControls
                            }
                        },
                        onDoubleTap = {
                            if (scale > 1.1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(currentPageIndex) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5.0f)
                        if (scale > 1.02f) {
                            val maxPanX = (size.width * (scale - 1f)) / 2f
                            val maxPanY = (size.height * (scale - 1f)) / 2f
                            offsetX = (offsetX + pan.x).coerceIn(-maxPanX, maxPanX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxPanY, maxPanY)
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                            // Swipe page threshold
                            if (pan.x < -40f && currentPageIndex < totalPages - 1) {
                                onNextPage()
                            } else if (pan.x > 40f && currentPageIndex > 0) {
                                onPrevPage()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading && highResBitmap == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Rendering Page ${currentPageIndex + 1}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (highResBitmap != null) {
                Card(
                    shape = RoundedCornerShape(if (isFullscreen) 0.dp else 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isFullscreen) 0.dp else 6.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isNightMode) Color.Black else Color.White),
                    modifier = Modifier
                        .padding(if (isFullscreen) 0.dp else 12.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                ) {
                    Box {
                        Image(
                            bitmap = highResBitmap.asImageBitmap(),
                            contentDescription = "PDF Page ${currentPageIndex + 1}",
                            modifier = Modifier
                                .then(if (fitWidthMode) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.96f))
                                .testTag("rendered_pdf_page"),
                            contentScale = if (fitWidthMode) ContentScale.FillWidth else ContentScale.Fit,
                            colorFilter = if (isNightMode) ColorFilter.colorMatrix(invertedColorMatrix) else null
                        )

                        // Interactive OCR Search Highlights & Pencil Edit Text Bounding Boxes
                        if (isOcrSearchOpen || isPencilEditMode) {
                            OcrAndEditCanvasOverlay(
                                currentPageIndex = currentPageIndex,
                                isOcrSearchOpen = isOcrSearchOpen,
                                ocrSearchMatches = ocrSearchMatches,
                                activeMatchIndex = activeMatchIndex,
                                isPencilEditMode = isPencilEditMode,
                                pageBlocks = currentPageOcrBlocks,
                                pendingEdits = pendingTextEdits,
                                onSelectBlockForEdit = { block ->
                                    isAddingNewTextCallout = false
                                    onSelectBlockForEdit(block)
                                },
                                modifier = Modifier.matchParentSize()
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "No document loaded",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Overlay: Top Navigation Bar + Floating OCR Search Bar / Pencil Edit Banner
        AnimatedVisibility(
            visible = (!isFullscreen || showControls),
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = metadata?.fileName ?: "PdfCraft Viewer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (totalPages > 0) "Page ${currentPageIndex + 1} of $totalPages • ${metadata?.fileSizeFormatted ?: ""}" else "No document",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        // 1. ML Kit OCR Search Toggle
                        IconButton(
                            onClick = {
                                if (isOcrSearchOpen) onCloseOcrSearch() else onOpenOcrSearch()
                            },
                            modifier = Modifier.testTag("btn_viewer_ocr_search")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "OCR Search in PDF",
                                tint = if (isOcrSearchOpen) AmberAccent else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 2. Pencil Icon: Interactive PDF Text Editor & Metadata Studio
                        IconButton(
                            onClick = onTogglePencilEditMode,
                            modifier = Modifier.testTag("btn_viewer_pencil_edit")
                        ) {
                            if (pendingTextEdits.isNotEmpty()) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = EmeraldAccent) {
                                            Text("${pendingTextEdits.size}", color = Color.White, fontSize = 9.sp)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "Edit PDF Text & Metadata",
                                        tint = if (isPencilEditMode) CrimsonAccent else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit PDF Text & Metadata",
                                    tint = if (isPencilEditMode) CrimsonAccent else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 3. Quick 90° Page Rotate
                        IconButton(
                            onClick = onQuickRotatePage,
                            modifier = Modifier.testTag("btn_viewer_quick_rotate")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.RotateRight,
                                contentDescription = "Rotate Page 90°"
                            )
                        }

                        // 4. Night Inverted Reading Mode
                        IconButton(
                            onClick = onToggleNightMode,
                            modifier = Modifier.testTag("btn_toggle_night_mode")
                        ) {
                            Icon(
                                imageVector = if (isNightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                contentDescription = "Night Inverted Reading Mode",
                                tint = if (isNightMode) AmberAccent else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 5. Fullscreen Toggle
                        IconButton(
                            onClick = {
                                isFullscreen = !isFullscreen
                                if (isFullscreen) showControls = false
                            },
                            modifier = Modifier.testTag("btn_toggle_fullscreen")
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = if (isFullscreen) "Exit Fullscreen" else "Enter Fullscreen"
                            )
                        }

                        // 6. Share PDF
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier.testTag("btn_share_doc")
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Share PDF")
                        }

                        // 7. Page Organizer
                        FilledTonalButton(
                            onClick = { onNavigateScreen(AppScreen.ORGANIZER) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("btn_jump_organizer")
                        ) {
                            Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Organize", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    )
                )

                // Floating ML Kit OCR Search Bar
                AnimatedVisibility(
                    visible = isOcrSearchOpen,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    OcrSearchBarOverlay(
                        query = ocrSearchQuery,
                        onQueryChange = onOcrSearchQueryChange,
                        matches = ocrSearchMatches,
                        activeMatchIndex = activeMatchIndex,
                        isIndexing = isOcrIndexing,
                        indexedPagesCount = ocrIndexedPagesCount,
                        totalPages = totalPages,
                        onNextMatch = onNextOcrMatch,
                        onPrevMatch = onPrevOcrMatch,
                        onSelectMatch = onSelectOcrMatch,
                        onClose = onCloseOcrSearch
                    )
                }

                // Floating Interactive Pencil Edit Mode Banner
                AnimatedVisibility(
                    visible = isPencilEditMode,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    PencilEditModeBanner(
                        currentPageIndex = currentPageIndex,
                        pageBlocks = currentPageOcrBlocks,
                        pendingEditsCount = pendingTextEdits.size,
                        onAddNewText = {
                            isAddingNewTextCallout = true
                            onSelectBlockForEdit(
                                OcrTextBlockItem(
                                    id = "custom_text_${UUID.randomUUID().toString().take(6)}",
                                    pageIndex = currentPageIndex,
                                    text = "",
                                    boundingBox = CropRectNormalized(
                                        left = 0.10f,
                                        top = 0.18f,
                                        right = 0.85f,
                                        bottom = 0.25f
                                    )
                                )
                            )
                        },
                        onOpenBlockListSheet = { showDetectedBlocksSheet = true },
                        onOpenMetadataEditor = { showInfoDialog = true },
                        onOpenCropTool = onOpenCrop,
                        onCommitEdits = onCommitTextEdits,
                        onCloseEditMode = onTogglePencilEditMode
                    )
                }
            }
        }

        // Overlay: Bottom Controls (Scrubber, Thumbnails & Floating Page Navigation Pill)
        AnimatedVisibility(
            visible = (!isFullscreen || showControls),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            ) {
                // Expandable Thumbnail Strip
                if (showThumbnailStrip && pages.isNotEmpty()) {
                    Surface(
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(86.dp)
                            .padding(top = 4.dp)
                    ) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            itemsIndexed(pages) { idx, pageItem ->
                                val isCurrent = idx == currentPageIndex
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.White,
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = if (isCurrent) 2.5.dp else 1.dp,
                                        color = if (isCurrent) IndigoPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .aspectRatio(pageItem.aspectRatio.coerceIn(0.6f, 1.4f))
                                        .clickable {
                                            scale = 1f; offsetX = 0f; offsetY = 0f
                                            onPageSelected(idx)
                                        }
                                        .testTag("strip_thumb_$idx")
                                ) {
                                    Box(contentAlignment = Alignment.BottomCenter) {
                                        if (pageItem.thumbnail != null) {
                                            Image(
                                                bitmap = pageItem.thumbnail.asImageBitmap(),
                                                contentDescription = "Page ${idx + 1}",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Surface(
                                            color = if (isCurrent) IndigoPrimary else Color.Black.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Page Scrubber Slider (when totalPages > 1)
                if (totalPages > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "1",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = (currentPageIndex + 1).toFloat(),
                            onValueChange = { newVal ->
                                val target = newVal.roundToInt().coerceIn(1, totalPages)
                                if (target - 1 != currentPageIndex) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                    onPageSelected(target - 1)
                                }
                            },
                            valueRange = 1f..totalPages.toFloat(),
                            steps = (totalPages - 2).coerceAtLeast(0),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .testTag("slider_page_scrubber")
                        )
                        Text(
                            text = "$totalPages",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Bottom Bar Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Zoom & View Mode Controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                scale = (scale - 0.35f).coerceAtLeast(1f)
                                if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom Out", modifier = Modifier.size(19.dp))
                        }

                        if (scale > 1.05f) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .clickable {
                                        scale = 1f; offsetX = 0f; offsetY = 0f
                                    }
                                    .padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = "${(scale * 100).roundToInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { scale = (scale + 0.35f).coerceAtMost(5.0f) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom In", modifier = Modifier.size(19.dp))
                        }

                        IconButton(
                            onClick = { fitWidthMode = !fitWidthMode },
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("btn_toggle_fit_width")
                        ) {
                            Icon(
                                imageVector = if (fitWidthMode) Icons.Filled.FitScreen else Icons.Filled.AspectRatio,
                                contentDescription = if (fitWidthMode) "Fit Screen" else "Fit Width",
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(
                            onClick = { showThumbnailStrip = !showThumbnailStrip },
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("btn_toggle_thumbnails")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ViewCarousel,
                                contentDescription = "Thumbnail Carousel",
                                tint = if (showThumbnailStrip) IndigoPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(
                            onClick = onOpenCrop,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("btn_crop_current_page")
                        ) {
                            Icon(Icons.Filled.Crop, contentDescription = "Crop Page", modifier = Modifier.size(19.dp))
                        }

                        IconButton(
                            onClick = { showInfoDialog = true },
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("btn_doc_info")
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = "Document Info", modifier = Modifier.size(19.dp))
                        }
                    }

                    // Navigation Arrows & Jump Pill
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                                onPrevPage()
                            },
                            enabled = currentPageIndex > 0,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("btn_prev_page")
                        ) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous Page")
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clickable {
                                    jumpTargetInput = (currentPageIndex + 1).toString()
                                    showJumpDialog = true
                                }
                                .testTag("btn_jump_page")
                        ) {
                            Text(
                                text = "${currentPageIndex + 1} / $totalPages",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                                onNextPage()
                            },
                            enabled = currentPageIndex < totalPages - 1,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("btn_next_page")
                        ) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Next Page")
                        }
                    }
                }
            }
        }
    }

    // Jump to Page Dialog
    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("Jump to Page") },
            text = {
                Column {
                    Text("Enter page number (1 to $totalPages):", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = jumpTargetInput,
                        onValueChange = { jumpTargetInput = it.filter { char -> char.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = jumpTargetInput.toIntOrNull()
                        if (target != null && target in 1..totalPages) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                            onPageSelected(target - 1)
                            showJumpDialog = false
                        }
                    }
                ) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Document Details & Metadata Editor Dialog
    if (showInfoDialog && metadata != null) {
        var editTitle by remember(metadata) { mutableStateOf(metadata.title) }
        var editAuthor by remember(metadata) { mutableStateOf(metadata.author) }
        var editSubject by remember(metadata) { mutableStateOf(metadata.subject) }
        var editKeywords by remember(metadata) { mutableStateOf(metadata.keywords) }
        var activeTab by remember { mutableStateOf(0) } // 0: Edit Metadata, 1: Properties

        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = IndigoPrimary)
                    Text("Document Details & Metadata", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabRow(
                        selectedTabIndex = activeTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = IndigoPrimary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Tab(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0 },
                            text = { Text("Edit Metadata", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1 },
                            text = { Text("Properties", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }

                    if (activeTab == 0) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Edit standard PDF information header fields:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = editTitle,
                                onValueChange = { editTitle = it },
                                label = { Text("Document Title") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_meta_title")
                            )

                            OutlinedTextField(
                                value = editAuthor,
                                onValueChange = { editAuthor = it },
                                label = { Text("Author / Organization") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_meta_author")
                            )

                            OutlinedTextField(
                                value = editSubject,
                                onValueChange = { editSubject = it },
                                label = { Text("Subject / Abstract") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_meta_subject")
                            )

                            OutlinedTextField(
                                value = editKeywords,
                                onValueChange = { editKeywords = it },
                                label = { Text("Keywords (comma separated)") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_meta_keywords")
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            InfoRow(label = "Filename", value = metadata.fileName)
                            InfoRow(label = "Page Count", value = "${metadata.pageCount} pages")
                            InfoRow(label = "File Size", value = metadata.fileSizeFormatted)
                            InfoRow(label = "Current Title", value = metadata.title.ifBlank { "None set" })
                            InfoRow(label = "Current Author", value = metadata.author.ifBlank { "None set" })
                            InfoRow(label = "Current Subject", value = metadata.subject.ifBlank { "None set" })
                            InfoRow(label = "Engine", value = "Apache PDFBox + Android PdfRenderer")
                            InfoRow(label = "Security / Privacy", value = "100% On-Device / Offline")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveMetadata(editTitle, editAuthor, editSubject, editKeywords)
                        showInfoDialog = false
                    },
                    modifier = Modifier.testTag("btn_save_metadata")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Metadata")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Detected Page Text Lines Sheet (when user clicks "Detected Lines" in Pencil Edit Banner)
    if (showDetectedBlocksSheet) {
        PageDetectedBlocksListDialog(
            pageIndex = currentPageIndex,
            blocks = currentPageOcrBlocks,
            onSelectBlock = { block ->
                showDetectedBlocksSheet = false
                isAddingNewTextCallout = false
                onSelectBlockForEdit(block)
            },
            onAddNewText = {
                showDetectedBlocksSheet = false
                isAddingNewTextCallout = true
                onSelectBlockForEdit(
                    OcrTextBlockItem(
                        id = "custom_text_${UUID.randomUUID().toString().take(6)}",
                        pageIndex = currentPageIndex,
                        text = "",
                        boundingBox = CropRectNormalized(
                            left = 0.10f,
                            top = 0.18f,
                            right = 0.85f,
                            bottom = 0.25f
                        )
                    )
                )
            },
            onDismiss = { showDetectedBlocksSheet = false }
        )
    }

    // In-Place PDF Text Edit Modal (when user taps any recognized text block or adds new text)
    if (selectedEditBlock != null) {
        val existingEdit = pendingTextEdits.find { it.id == selectedEditBlock.id }
        InPlaceTextEditDialog(
            block = selectedEditBlock,
            existingEdit = existingEdit,
            isNewTextCallout = isAddingNewTextCallout,
            onStageEdit = { op ->
                onStageTextEdit(op)
            },
            onApplyAndSaveNow = { op ->
                onStageTextEdit(op)
                onCommitTextEdits()
            },
            onRemoveEdit = { editId ->
                onRemoveTextEdit(editId)
            },
            onDismiss = { onSelectBlockForEdit(null) }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
