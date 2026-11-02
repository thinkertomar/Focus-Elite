package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CropRectNormalized
import com.example.data.model.OcrSearchMatch
import com.example.data.model.OcrTextBlockItem
import com.example.data.model.PdfTextEditOperation
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.IndigoPrimary
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Interactive Canvas Overlay layered directly over the rendered PDF Page Image.
 * Draws:
 * 1) Visual bounding-box highlights for ML Kit OCR search matches on the current page.
 * 2) Tappable dashed bounding-box frames for detected text lines when Pencil Edit Mode is active.
 */
@Composable
fun OcrAndEditCanvasOverlay(
    currentPageIndex: Int,
    isOcrSearchOpen: Boolean,
    ocrSearchMatches: List<OcrSearchMatch>,
    activeMatchIndex: Int,
    isPencilEditMode: Boolean,
    pageBlocks: List<OcrTextBlockItem>,
    pendingEdits: List<PdfTextEditOperation>,
    onSelectBlockForEdit: (OcrTextBlockItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeMatch = ocrSearchMatches.getOrNull(activeMatchIndex)
    val matchesOnPage = remember(ocrSearchMatches, currentPageIndex) {
        ocrSearchMatches.filter { it.pageIndex == currentPageIndex }
    }
    val editsOnPage = remember(pendingEdits, currentPageIndex) {
        pendingEdits.filter { it.pageIndex == currentPageIndex }
    }

    Canvas(
        modifier = modifier
            .then(
                if (isPencilEditMode) {
                    Modifier.pointerInput(pageBlocks, currentPageIndex) {
                        detectTapGestures { tapOffset ->
                            val normX = (tapOffset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val normY = (tapOffset.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)

                            // Find tapped text block with generous touch padding
                            val hitBlock = pageBlocks.firstOrNull { block ->
                                val box = block.boundingBox
                                normX >= (box.left - 0.02f) &&
                                    normX <= (box.right + 0.02f) &&
                                    normY >= (box.top - 0.015f) &&
                                    normY <= (box.bottom + 0.015f)
                            }
                            if (hitBlock != null) {
                                onSelectBlockForEdit(hitBlock)
                            }
                        }
                    }
                } else Modifier
            )
            .testTag("ocr_and_edit_canvas_overlay")
    ) {
        val canvasW = size.width
        val canvasH = size.height

        // 1. Draw OCR Search Match Highlights
        if (isOcrSearchOpen && matchesOnPage.isNotEmpty()) {
            matchesOnPage.forEach { match ->
                val isCurrentActive = activeMatch?.id == match.id
                val left = match.boundingBox.left * canvasW
                val top = match.boundingBox.top * canvasH
                val width = (match.boundingBox.right - match.boundingBox.left).coerceAtLeast(0.04f) * canvasW
                val height = (match.boundingBox.bottom - match.boundingBox.top).coerceAtLeast(0.022f) * canvasH

                val fillColor = if (isCurrentActive) {
                    AmberAccent.copy(alpha = 0.42f)
                } else {
                    IndigoPrimary.copy(alpha = 0.25f)
                }
                val strokeColor = if (isCurrentActive) AmberAccent else IndigoPrimary

                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(left - 2.dp.toPx(), top - 2.dp.toPx()),
                    size = Size(width + 4.dp.toPx(), height + 4.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset(left - 2.dp.toPx(), top - 2.dp.toPx()),
                    size = Size(width + 4.dp.toPx(), height + 4.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = if (isCurrentActive) 2.5.dp.toPx() else 1.2.dp.toPx())
                )
            }
        }

        // 2. Draw Interactive Text Block Frames in Pencil Edit Mode
        if (isPencilEditMode) {
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            pageBlocks.forEach { block ->
                val hasPendingEdit = editsOnPage.any { it.id == block.id }
                val left = block.boundingBox.left * canvasW
                val top = block.boundingBox.top * canvasH
                val width = (block.boundingBox.right - block.boundingBox.left).coerceAtLeast(0.08f) * canvasW
                val height = (block.boundingBox.bottom - block.boundingBox.top).coerceAtLeast(0.025f) * canvasH

                val boxColor = if (hasPendingEdit) EmeraldAccent else IndigoPrimary

                drawRoundRect(
                    color = boxColor.copy(alpha = if (hasPendingEdit) 0.22f else 0.10f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                drawRoundRect(
                    color = boxColor.copy(alpha = 0.85f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(
                        width = if (hasPendingEdit) 2.dp.toPx() else 1.3.dp.toPx(),
                        pathEffect = if (hasPendingEdit) null else dashEffect
                    )
                )
            }
        }
    }
}

/**
 * Floating ML Kit OCR Search Bar & Expandable Match Results Drawer for PdfViewerScreen.
 */
@Composable
fun OcrSearchBarOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    matches: List<OcrSearchMatch>,
    activeMatchIndex: Int,
    isIndexing: Boolean,
    indexedPagesCount: Int,
    totalPages: Int,
    onNextMatch: () -> Unit,
    onPrevMatch: () -> Unit,
    onSelectMatch: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMatchesDrawer by remember { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("ocr_search_bar_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp)
        ) {
            // Search Input Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            "Search text via ML Kit OCR...",
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = IndigoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear query", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IndigoPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_ocr_search_query")
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = onPrevMatch,
                    enabled = matches.isNotEmpty(),
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("btn_ocr_prev_match")
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous Match")
                }

                IconButton(
                    onClick = onNextMatch,
                    enabled = matches.isNotEmpty(),
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("btn_ocr_next_match")
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next Match")
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("btn_ocr_close_search")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close OCR Search")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status & Match Summary Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isIndexing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = IndigoPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ML Kit OCR scanning pages ($indexedPagesCount/$totalPages)...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = EmeraldAccent.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "ML Kit OCR Ready • $indexedPagesCount/$totalPages pages indexed",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EmeraldAccent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (query.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (matches.isNotEmpty()) AmberAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .clickable(enabled = matches.isNotEmpty()) {
                                    showMatchesDrawer = !showMatchesDrawer
                                }
                                .testTag("btn_ocr_toggle_snippets")
                        ) {
                            Text(
                                text = if (matches.isNotEmpty()) {
                                    "Match ${activeMatchIndex + 1} of ${matches.size} ${if (showMatchesDrawer) "▲" else "▼"}"
                                } else {
                                    "No matches"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (matches.isNotEmpty()) AmberAccent else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // Expandable All-Matches Snippet Drawer
            AnimatedVisibility(visible = showMatchesDrawer && matches.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .testTag("list_ocr_search_matches"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(matches) { idx, match ->
                            val isSelected = idx == activeMatchIndex
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) IndigoPrimary.copy(alpha = 0.14f) else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectMatch(idx) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) AmberAccent else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "P.${match.pageIndex + 1}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = match.fullLineText,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Interactive Pencil Edit Mode Banner shown in PdfViewerScreen when Pencil Edit is active.
 * Gives one-tap access to In-Place Text Editing, Adding New Text, Document Metadata, and Cropping.
 */
@Composable
fun PencilEditModeBanner(
    currentPageIndex: Int,
    pageBlocks: List<OcrTextBlockItem>,
    pendingEditsCount: Int,
    onAddNewText: () -> Unit,
    onOpenBlockListSheet: () -> Unit,
    onOpenMetadataEditor: () -> Unit,
    onOpenCropTool: () -> Unit,
    onCommitEdits: () -> Unit,
    onCloseEditMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("pencil_edit_mode_banner")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(CrimsonAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            tint = CrimsonAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Pencil PDF Studio • Page ${currentPageIndex + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap any dashed text box on the page to edit or replace text",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onCloseEditMode,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("btn_close_pencil_mode")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Exit Edit Mode", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pendingEditsCount > 0) {
                    Button(
                        onClick = onCommitEdits,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("btn_pencil_apply_edits")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply to PDF ($pendingEditsCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                AssistChip(
                    onClick = onAddNewText,
                    label = { Text("+ Add Text", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("btn_pencil_add_text")
                )

                AssistChip(
                    onClick = onOpenBlockListSheet,
                    label = { Text("Detected Lines (${pageBlocks.size})", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("btn_pencil_block_list")
                )

                AssistChip(
                    onClick = onOpenMetadataEditor,
                    label = { Text("Edit Metadata", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("btn_pencil_metadata")
                )

                AssistChip(
                    onClick = onOpenCropTool,
                    label = { Text("Crop Page", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("btn_pencil_crop")
                )
            }
        }
    }
}

/**
 * Dialog listing all ML Kit detected text blocks on the current page so users can pick any line to edit.
 */
@Composable
fun PageDetectedBlocksListDialog(
    pageIndex: Int,
    blocks: List<OcrTextBlockItem>,
    onSelectBlock: (OcrTextBlockItem) -> Unit,
    onAddNewText: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = IndigoPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Edit Page ${pageIndex + 1} Text Lines",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Select any recognized text line below to modify, replace, or whiteout:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (blocks.isEmpty()) {
                    Text(
                        "No text lines detected on this page yet. Tap '+ Add New Text' to place custom text.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(blocks) { idx, block ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectBlock(block) }
                                    .testTag("detected_text_line_$idx")
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = block.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "Edit line",
                                        tint = IndigoPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAddNewText) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add New Text")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Modal editor for replacing existing PDF text in-place (with automatic whiteout masking)
 * or inserting a new text callout on the page.
 */
@Composable
fun InPlaceTextEditDialog(
    block: OcrTextBlockItem,
    existingEdit: PdfTextEditOperation?,
    isNewTextCallout: Boolean,
    onStageEdit: (PdfTextEditOperation) -> Unit,
    onApplyAndSaveNow: (PdfTextEditOperation) -> Unit,
    onRemoveEdit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editedText by remember(block, existingEdit) {
        mutableStateOf(existingEdit?.newText ?: block.text)
    }
    var fontSizeSp by remember(block, existingEdit) {
        mutableFloatStateOf(existingEdit?.fontSizeSp ?: 14f)
    }
    var textColorHex by remember(block, existingEdit) {
        mutableLongStateOf(existingEdit?.textColorHex ?: 0xFF0F172AL)
    }
    var maskBackground by remember(block, existingEdit, isNewTextCallout) {
        mutableStateOf(existingEdit?.maskBackground ?: !isNewTextCallout)
    }
    var isBold by remember(block, existingEdit) {
        mutableStateOf(existingEdit?.isBold ?: false)
    }
    var verticalPos by remember(block) {
        mutableFloatStateOf(block.boundingBox.top)
    }

    val colorChoices = listOf(
        Pair("Ink Black", 0xFF0F172AL),
        Pair("Royal Navy", 0xFF1E3A8AL),
        Pair("Crimson", 0xFFE11D48L),
        Pair("Emerald", 0xFF047857L)
    )

    fun buildOperation(): PdfTextEditOperation {
        val box = if (isNewTextCallout) {
            val height = (block.boundingBox.bottom - block.boundingBox.top).coerceAtLeast(0.05f)
            block.boundingBox.copy(
                top = verticalPos.coerceIn(0.04f, 0.90f),
                bottom = (verticalPos + height).coerceIn(0.09f, 0.96f)
            )
        } else {
            block.boundingBox
        }
        return PdfTextEditOperation(
            id = block.id,
            pageIndex = block.pageIndex,
            originalText = block.text,
            newText = editedText,
            boundingBox = box,
            fontSizeSp = fontSizeSp,
            textColorHex = textColorHex,
            maskBackground = maskBackground,
            isBold = isBold
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = CrimsonAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isNewTextCallout) "Add Text to Page ${block.pageIndex + 1}" else "Edit PDF Text In-Place",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isNewTextCallout && block.text.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Original Detected Text:",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = block.text,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    label = { Text(if (isNewTextCallout) "Text to insert" else "Replacement text (leave blank to erase/whiteout)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_pdf_edit_text")
                )

                // Whiteout background toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Mask / Whiteout Original Text",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Covers original scanned text cleanly before writing new text",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = maskBackground,
                        onCheckedChange = { maskBackground = it }
                    )
                }

                // Font Size & Bold Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Font Size: ${fontSizeSp.roundToInt()} pt",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    FilterChip(
                        selected = isBold,
                        onClick = { isBold = !isBold },
                        label = { Text("Bold", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
                Slider(
                    value = fontSizeSp,
                    onValueChange = { fontSizeSp = it },
                    valueRange = 10f..28f
                )

                if (isNewTextCallout) {
                    Text(
                        text = "Vertical Page Position: ${(verticalPos * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = verticalPos,
                        onValueChange = { verticalPos = it },
                        valueRange = 0.06f..0.88f
                    )
                }

                // Color Swatches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colorChoices.forEach { (label, hex) ->
                        val selected = textColorHex == hex
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) AmberAccent else Color.Gray.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                                .clickable { textColorHex = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = label,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (existingEdit != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { onRemoveEdit(block.id) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "Revert Edit",
                                tint = CrimsonAccent
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onStageEdit(buildOperation()) },
                    modifier = Modifier.testTag("btn_stage_text_edit")
                ) {
                    Text("Stage", fontSize = 12.sp)
                }
                Button(
                    onClick = { onApplyAndSaveNow(buildOperation()) },
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                    modifier = Modifier.testTag("btn_save_text_edit_now")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply to PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
