package com.example.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BuiltInImageStamp
import com.example.data.model.EncryptionKeyStrength
import com.example.data.model.EncryptionMode
import com.example.data.model.PdfEncryptionConfig
import com.example.data.model.WatermarkConfig
import com.example.data.model.WatermarkFont
import com.example.data.model.WatermarkPlacement
import com.example.data.model.WatermarkType
import com.example.engine.PdfEngine
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.TealAccent
import kotlin.math.roundToInt

/**
 * Comprehensive Watermarking Studio with Text & Image overlays, interactive drag-to-place live preview,
 * 9-point placement matrix + 3x4 tiled grid, adjustable opacity, scale, rotation, and page numbering.
 */
@Composable
fun WatermarkToolSection(
    currentDocName: String,
    currentDocPages: Int,
    watermarkConfig: WatermarkConfig,
    onWatermarkTextChange: (String) -> Unit,
    onWatermarkOpacityChange: (Float) -> Unit,
    onWatermarkPageNumbersChange: (Boolean) -> Unit,
    onWatermarkTypeChange: (WatermarkType) -> Unit,
    onWatermarkImageUriChange: (Uri?) -> Unit,
    onWatermarkBuiltInStampChange: (BuiltInImageStamp) -> Unit,
    onWatermarkFontChange: (WatermarkFont) -> Unit,
    onWatermarkRotationChange: (Float) -> Unit,
    onWatermarkScaleChange: (Float) -> Unit,
    onWatermarkPlacementChange: (WatermarkPlacement) -> Unit,
    onWatermarkCustomOffsetChange: (Float, Float) -> Unit,
    onWatermarkColorChange: (Long) -> Unit,
    onPickGalleryImage: () -> Unit,
    onApplyWatermarkToActive: () -> Unit,
    onExportWatermarkedCopy: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Resolve preview bitmap for Image Watermark mode (either custom gallery image or built-in stamp)
    val previewImageBitmap = remember(watermarkConfig.imageUri, watermarkConfig.builtInStamp) {
        val fromUri = watermarkConfig.imageUri?.let { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                null
            }
        }
        fromUri ?: if (watermarkConfig.builtInStamp != BuiltInImageStamp.NONE) {
            PdfEngine.generateBuiltInStampBitmap(watermarkConfig.builtInStamp)
        } else {
            PdfEngine.generateBuiltInStampBitmap(BuiltInImageStamp.CONFIDENTIAL_SEAL)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. Interactive Live Preview & Drag-to-Place Canvas
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.OpenWith,
                            contentDescription = null,
                            tint = CrimsonAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Interactive Live Preview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CrimsonAccent.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = if (watermarkConfig.placement == WatermarkPlacement.TILED_GRID) {
                                "Tiled 3×4 Grid"
                            } else {
                                "Tap or drag on page to place (${(watermarkConfig.offsetXNormalized * 100).roundToInt()}%, ${(watermarkConfig.offsetYNormalized * 100).roundToInt()}%)"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CrimsonAccent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Interactive Page Canvas
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .testTag("watermark_live_preview_canvas")
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(watermarkConfig.placement) {
                                detectTapGestures { tapOffset ->
                                    if (size.width > 0 && size.height > 0) {
                                        val normX = (tapOffset.x / size.width).coerceIn(0.08f, 0.92f)
                                        val normY = (tapOffset.y / size.height).coerceIn(0.08f, 0.92f)
                                        onWatermarkCustomOffsetChange(normX, normY)
                                    }
                                }
                            }
                            .pointerInput(watermarkConfig.placement) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    if (size.width > 0 && size.height > 0) {
                                        val normX = (change.position.x / size.width).coerceIn(0.08f, 0.92f)
                                        val normY = (change.position.y / size.height).coerceIn(0.08f, 0.92f)
                                        onWatermarkCustomOffsetChange(normX, normY)
                                    }
                                }
                            }
                    ) {
                        val canvasWidthPx = with(density) { maxWidth.toPx() }
                        val canvasHeightPx = with(density) { maxHeight.toPx() }

                        // Simulated document content lines in background
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.48f)
                                    .height(7.dp)
                                    .background(Color(0xFFCBD5E1), RoundedCornerShape(3.dp))
                            )
                            repeat(8) { idx ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(if (idx % 3 == 2) 0.68f else 0.96f)
                                        .height(4.dp)
                                        .background(Color(0xFFE2E8F0), RoundedCornerShape(2.dp))
                                )
                            }
                        }

                        // Render Watermark Overlay(s) at configured coordinates
                        val previewAlpha = watermarkConfig.opacity.coerceIn(0.05f, 1f)
                        val coordinates = PdfEngine.resolveWatermarkCoordinates(watermarkConfig)
                        val isTiled = watermarkConfig.placement == WatermarkPlacement.TILED_GRID
                        val previewScale = watermarkConfig.scaleFactor.coerceIn(0.4f, 2.0f) * (if (isTiled) 0.55f else 1f)

                        coordinates.forEach { (normX, normY) ->
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            x = ((normX - 0.5f) * canvasWidthPx).roundToInt(),
                                            y = ((normY - 0.5f) * canvasHeightPx).roundToInt()
                                        )
                                    }
                                    .align(Alignment.Center),
                                contentAlignment = Alignment.Center
                            ) {
                                if (watermarkConfig.type == WatermarkType.IMAGE && previewImageBitmap != null) {
                                    Image(
                                        bitmap = previewImageBitmap.asImageBitmap(),
                                        contentDescription = "Watermark Image Preview",
                                        alpha = previewAlpha,
                                        modifier = Modifier
                                            .width((120 * previewScale).dp)
                                            .height((56 * previewScale).dp)
                                            .rotate(watermarkConfig.rotationDegrees)
                                    )
                                } else {
                                    Text(
                                        text = watermarkConfig.text.ifBlank { "WATERMARK" },
                                        color = Color(watermarkConfig.colorHex).copy(alpha = previewAlpha),
                                        fontSize = (22f * previewScale).coerceIn(9f, 44f).sp,
                                        fontWeight = if (watermarkConfig.font == WatermarkFont.BOLD_HEADING) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        fontFamily = when (watermarkConfig.font) {
                                            WatermarkFont.SANS_SERIF -> FontFamily.SansSerif
                                            WatermarkFont.SERIF -> FontFamily.Serif
                                            WatermarkFont.MONOSPACE -> FontFamily.Monospace
                                            WatermarkFont.BOLD_HEADING -> FontFamily.SansSerif
                                        },
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.rotate(watermarkConfig.rotationDegrees)
                                    )
                                }
                            }
                        }

                        // Stamped footer page numbers preview
                        if (watermarkConfig.includePageNumbers) {
                            Text(
                                text = "Page 1 of $currentDocPages — PdfCraft",
                                fontSize = 9.sp,
                                color = Color(0xFF475569),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Watermark Type & Content Card (Text vs Image/Stamp Overlay)
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Overlay Type & Content",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = watermarkConfig.type == WatermarkType.TEXT,
                        onClick = { onWatermarkTypeChange(WatermarkType.TEXT) },
                        label = { Text("Text Overlay", fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chip_watermark_text")
                    )

                    FilterChip(
                        selected = watermarkConfig.type == WatermarkType.IMAGE,
                        onClick = { onWatermarkTypeChange(WatermarkType.IMAGE) },
                        label = { Text("Image / Stamp", fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chip_watermark_image")
                    )
                }

                if (watermarkConfig.type == WatermarkType.TEXT) {
                    OutlinedTextField(
                        value = watermarkConfig.text,
                        onValueChange = onWatermarkTextChange,
                        label = { Text("Custom Watermark Text") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_watermark_text")
                    )

                    // Quick Text Presets
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Quick Stamps",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("CONFIDENTIAL", "DRAFT", "DO NOT COPY", "APPROVED").forEach { preset ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (watermarkConfig.text == preset) CrimsonAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .clickable { onWatermarkTextChange(preset) }
                                        .testTag("preset_wm_$preset")
                                ) {
                                    Text(
                                        text = preset,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        color = if (watermarkConfig.text == preset) CrimsonAccent else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Font Family Selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Typography Style",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            WatermarkFont.entries.forEach { font ->
                                val fontName = when (font) {
                                    WatermarkFont.SANS_SERIF -> "Sans"
                                    WatermarkFont.SERIF -> "Serif"
                                    WatermarkFont.MONOSPACE -> "Mono"
                                    WatermarkFont.BOLD_HEADING -> "Bold"
                                }
                                FilterChip(
                                    selected = watermarkConfig.font == font,
                                    onClick = { onWatermarkFontChange(font) },
                                    label = {
                                        Text(
                                            text = fontName,
                                            fontSize = 12.sp,
                                            fontFamily = when (font) {
                                                WatermarkFont.SANS_SERIF -> FontFamily.SansSerif
                                                WatermarkFont.SERIF -> FontFamily.Serif
                                                WatermarkFont.MONOSPACE -> FontFamily.Monospace
                                                WatermarkFont.BOLD_HEADING -> FontFamily.SansSerif
                                            },
                                            fontWeight = if (font == WatermarkFont.BOLD_HEADING) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    modifier = Modifier.testTag("chip_font_${font.name.lowercase()}")
                                )
                            }
                        }
                    }

                    // Color Palette
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Ink Color",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        val colors = listOf(
                            Pair("Crimson Red", 0xFFE11D48),
                            Pair("Slate Dark", 0xFF334155),
                            Pair("Ocean Navy", 0xFF1E3A8A),
                            Pair("Forest Green", 0xFF059669),
                            Pair("Amber Gold", 0xFFD97706),
                            Pair("Pure Black", 0xFF0F172A)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            colors.forEach { (name, hex) ->
                                val isSelected = watermarkConfig.colorHex == hex
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(hex))
                                        .clickable { onWatermarkColorChange(hex) }
                                        .then(
                                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = name,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Image / Graphic Stamp Overlay Selector
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Built-In Graphic Stamp Seals",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                BuiltInImageStamp.CONFIDENTIAL_SEAL,
                                BuiltInImageStamp.APPROVED_STAMP,
                                BuiltInImageStamp.DRAFT_BADGE,
                                BuiltInImageStamp.OFFICIAL_SEAL
                            ).forEach { stamp ->
                                val selected = watermarkConfig.imageUri == null && watermarkConfig.builtInStamp == stamp
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        onWatermarkImageUriChange(null)
                                        onWatermarkBuiltInStampChange(stamp)
                                    },
                                    label = {
                                        Text(
                                            text = stamp.badgeTitle.lowercase().replaceFirstChar { it.uppercase() },
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("chip_stamp_${stamp.name.lowercase()}")
                                )
                            }
                        }

                        HorizontalDivider()

                        Text(
                            text = "Or Upload Custom Logo / Image from Device",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (watermarkConfig.imageUri != null && previewImageBitmap != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Image(
                                        bitmap = previewImageBitmap.asImageBitmap(),
                                        contentDescription = "Selected Watermark Logo",
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Custom Image Selected",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "${previewImageBitmap.width} × ${previewImageBitmap.height} px",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onWatermarkImageUriChange(null) },
                                        modifier = Modifier.testTag("btn_remove_watermark_image")
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove logo",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onPickGalleryImage,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_pick_watermark_image")
                        ) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (watermarkConfig.imageUri != null) "Change Custom Gallery Image" else "Pick Custom Image / Logo (PNG/JPEG)")
                        }
                    }
                }
            }
        }

        // 3. Placement & Position Controls Card (9-Point Matrix + Tiled Grid + X/Y Fine Sliders)
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Placement & Position Controls",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = watermarkConfig.placement.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = IndigoPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 3x3 Visual Anchor Grid + Tiled Pattern button
                val gridRows = listOf(
                    listOf(WatermarkPlacement.TOP_LEFT, WatermarkPlacement.TOP_CENTER, WatermarkPlacement.TOP_RIGHT),
                    listOf(WatermarkPlacement.CENTER_LEFT, WatermarkPlacement.CENTER, WatermarkPlacement.CENTER_RIGHT),
                    listOf(WatermarkPlacement.BOTTOM_LEFT, WatermarkPlacement.BOTTOM_CENTER, WatermarkPlacement.BOTTOM_RIGHT)
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    gridRows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { pos ->
                                val isSelected = watermarkConfig.placement == pos
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) IndigoPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clickable { onWatermarkPlacementChange(pos) }
                                        .testTag("placement_${pos.name.lowercase()}")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = pos.label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Tiled Repeat Pattern Option
                FilterChip(
                    selected = watermarkConfig.placement == WatermarkPlacement.TILED_GRID,
                    onClick = { onWatermarkPlacementChange(WatermarkPlacement.TILED_GRID) },
                    label = { Text("Repeat Tiled Pattern Across Entire Page (3×4 Grid)", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("placement_tiled_grid")
                )

                // Fine X / Y Offset Sliders (active when not in Tiled mode)
                if (watermarkConfig.placement != WatermarkPlacement.TILED_GRID) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Horizontal Position (X)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("${(watermarkConfig.offsetXNormalized * 100).roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = IndigoPrimary)
                        }
                        Slider(
                            value = watermarkConfig.offsetXNormalized,
                            onValueChange = { newX ->
                                onWatermarkCustomOffsetChange(newX, watermarkConfig.offsetYNormalized)
                            },
                            valueRange = 0.08f..0.92f,
                            modifier = Modifier.testTag("slider_watermark_x")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Vertical Position (Y)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("${(watermarkConfig.offsetYNormalized * 100).roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = IndigoPrimary)
                        }
                        Slider(
                            value = watermarkConfig.offsetYNormalized,
                            onValueChange = { newY ->
                                onWatermarkCustomOffsetChange(watermarkConfig.offsetXNormalized, newY)
                            },
                            valueRange = 0.08f..0.92f,
                            modifier = Modifier.testTag("slider_watermark_y")
                        )
                    }
                }
            }
        }

        // 4. Opacity, Scale, Rotation & Page Number Controls Card
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Opacity Slider + Quick Presets
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Overlay Opacity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${(watermarkConfig.opacity * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = CrimsonAccent)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            Pair("15% Subtle", 0.15f),
                            Pair("30% Standard", 0.30f),
                            Pair("50% Medium", 0.50f),
                            Pair("80% Bold", 0.80f)
                        ).forEach { (label, alphaVal) ->
                            FilterChip(
                                selected = (watermarkConfig.opacity * 100).roundToInt() == (alphaVal * 100).roundToInt(),
                                onClick = { onWatermarkOpacityChange(alphaVal) },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.testTag("chip_opacity_${(alphaVal * 100).roundToInt()}")
                            )
                        }
                    }
                    Slider(
                        value = watermarkConfig.opacity,
                        onValueChange = onWatermarkOpacityChange,
                        valueRange = 0.05f..0.95f,
                        modifier = Modifier.testTag("slider_watermark_opacity")
                    )
                }

                // Scale / Size Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Overlay Size / Scale", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${(watermarkConfig.scaleFactor * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = CrimsonAccent)
                    }
                    Slider(
                        value = watermarkConfig.scaleFactor,
                        onValueChange = onWatermarkScaleChange,
                        valueRange = 0.40f..2.00f,
                        modifier = Modifier.testTag("slider_watermark_scale")
                    )
                }

                // Rotation Angle Slider + Quick Presets
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Rotation Angle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${watermarkConfig.rotationDegrees.roundToInt()}°", fontWeight = FontWeight.Bold, color = CrimsonAccent)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            Pair("-45° Diag", -45f),
                            Pair("0° Flat", 0f),
                            Pair("45° Diag", 45f),
                            Pair("90° Vert", 90f)
                        ).forEach { (label, angle) ->
                            FilterChip(
                                selected = watermarkConfig.rotationDegrees == angle,
                                onClick = { onWatermarkRotationChange(angle) },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.testTag("chip_rot_${angle.toInt()}")
                            )
                        }
                    }
                    Slider(
                        value = watermarkConfig.rotationDegrees,
                        onValueChange = onWatermarkRotationChange,
                        valueRange = -90f..90f,
                        modifier = Modifier.testTag("slider_watermark_rotation")
                    )
                }

                // Stamped Page Numbers Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stamped Page Numbers", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Add 'Page X of Y — PdfCraft' to bottom margin", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = watermarkConfig.includePageNumbers,
                        onCheckedChange = onWatermarkPageNumbersChange,
                        modifier = Modifier.testTag("switch_page_numbers")
                    )
                }

                // Dual Action Buttons: Apply Directly to Active PDF + Export Copy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onApplyWatermarkToActive,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonAccent),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_apply_watermark_active")
                    ) {
                        Icon(Icons.Filled.Style, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply to PDF", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onExportWatermarkedCopy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_execute_watermark")
                    ) {
                        Text("Save Copy As...", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * PDF Encryption & Password Protection Studio (`ToolType.ENCRYPT`).
 * Allows encrypting sensitive PDF files with AES-256 / 128-bit passwords and granular access permissions,
 * or unlocking existing password-protected PDFs.
 */
@Composable
fun PdfSecurityToolSection(
    currentDocName: String,
    currentDocPages: Int,
    encryptionConfig: PdfEncryptionConfig,
    onModeChange: (EncryptionMode) -> Unit,
    onUserPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onOwnerPasswordChange: (String) -> Unit,
    onKeyStrengthChange: (EncryptionKeyStrength) -> Unit,
    onAllowPrintingChange: (Boolean) -> Unit,
    onAllowCopyingChange: (Boolean) -> Unit,
    onAllowModifyingChange: (Boolean) -> Unit,
    onUnlockPasswordChange: (String) -> Unit,
    onGenerateStrongPassword: () -> Unit,
    onEncryptActivePdf: () -> Unit,
    onExportEncryptedPdf: () -> Unit,
    onUnlockActivePdf: () -> Unit,
    onExportUnlockedPdf: () -> Unit,
    onShareProtectedFile: (String) -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    var showUnlockPassword by remember { mutableStateOf(false) }

    val passwordLen = encryptionConfig.userPassword.length
    val hasMixedCase = encryptionConfig.userPassword.any { it.isUpperCase() } && encryptionConfig.userPassword.any { it.isLowerCase() }
    val hasDigitsOrSymbols = encryptionConfig.userPassword.any { it.isDigit() || !it.isLetterOrDigit() }

    val (strengthFraction, strengthLabel, strengthColor) = when {
        passwordLen == 0 -> Triple(0.05f, "Enter a password", MaterialTheme.colorScheme.outline)
        passwordLen < 4 -> Triple(0.28f, "Weak (Too short)", CrimsonAccent)
        passwordLen < 8 -> Triple(0.58f, "Moderate Security", AmberAccent)
        hasMixedCase && hasDigitsOrSymbols && passwordLen >= 10 -> Triple(1.0f, "Vault-Grade AES Security", EmeraldAccent)
        else -> Triple(0.82f, "Strong Password", TealAccent)
    }

    val passwordsMatch = encryptionConfig.confirmPassword.isEmpty() ||
        encryptionConfig.userPassword == encryptionConfig.confirmPassword

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Mode Switcher: Encrypt & Lock vs Unlock / Remove Password
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = encryptionConfig.mode == EncryptionMode.ENCRYPT_LOCK,
                        onClick = { onModeChange(EncryptionMode.ENCRYPT_LOCK) },
                        label = { Text("Encrypt & Lock PDF", fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chip_mode_encrypt")
                    )

                    FilterChip(
                        selected = encryptionConfig.mode == EncryptionMode.DECRYPT_UNLOCK,
                        onClick = { onModeChange(EncryptionMode.DECRYPT_UNLOCK) },
                        label = { Text("Unlock PDF", fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chip_mode_decrypt")
                    )
                }

                // Active Document Security Summary Banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = IndigoPrimary.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Filled.Security,
                                contentDescription = null,
                                tint = IndigoPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = currentDocName,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$currentDocPages pages • On-Device PDFBox Cryptographic Engine",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = IndigoPrimary
                        ) {
                            Text(
                                text = encryptionConfig.keyStrength.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        if (encryptionConfig.mode == EncryptionMode.ENCRYPT_LOCK) {
            // Password Configuration Card
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Password Protection",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        FilledTonalButton(
                            onClick = {
                                showPassword = true
                                onGenerateStrongPassword()
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("btn_generate_password")
                        ) {
                            Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Generate Strong", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedTextField(
                        value = encryptionConfig.userPassword,
                        onValueChange = onUserPasswordChange,
                        label = { Text("Document Open Password (Required)") },
                        placeholder = { Text("Enter password required to open PDF") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(Icons.Filled.Key, contentDescription = null, tint = IndigoPrimary)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_encrypt_password")
                    )

                    // Password Strength Meter
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Password Strength",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = strengthLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = strengthColor
                            )
                        }
                        LinearProgressIndicator(
                            progress = { strengthFraction },
                            color = strengthColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }

                    OutlinedTextField(
                        value = encryptionConfig.confirmPassword,
                        onValueChange = onConfirmPasswordChange,
                        label = { Text("Confirm Open Password") },
                        placeholder = { Text("Re-enter password to verify") },
                        singleLine = true,
                        isError = !passwordsMatch,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        supportingText = if (!passwordsMatch) {
                            { Text("Passwords do not match", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_encrypt_confirm_password")
                    )

                    OutlinedTextField(
                        value = encryptionConfig.ownerPassword,
                        onValueChange = onOwnerPasswordChange,
                        label = { Text("Owner / Permissions Password (Optional)") },
                        placeholder = { Text("Defaults to Open Password if left blank") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_encrypt_owner_password")
                    )
                }
            }

            // Cipher Strength & Access Permissions Card
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Encryption Cipher & Access Permissions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EncryptionKeyStrength.entries.forEach { cipher ->
                            FilterChip(
                                selected = encryptionConfig.keyStrength == cipher,
                                onClick = { onKeyStrengthChange(cipher) },
                                label = { Text(cipher.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chip_cipher_${cipher.keyLengthBits}")
                            )
                        }
                    }

                    HorizontalDivider()

                    // Granular Permission Switches
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow Document Printing", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Permit recipients to print physical copies", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = encryptionConfig.allowPrinting,
                            onCheckedChange = onAllowPrintingChange,
                            modifier = Modifier.testTag("switch_perm_print")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow Content Copying", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Permit copying text and extracting images", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = encryptionConfig.allowCopying,
                            onCheckedChange = onAllowCopyingChange,
                            modifier = Modifier.testTag("switch_perm_copy")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow Document Modification", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Permit editing pages, annotations, and form fields", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = encryptionConfig.allowModifying,
                            onCheckedChange = onAllowModifyingChange,
                            modifier = Modifier.testTag("switch_perm_modify")
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Action Buttons
                    val canExecuteEncrypt = encryptionConfig.userPassword.isNotBlank() && passwordsMatch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onEncryptActivePdf,
                            enabled = canExecuteEncrypt,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("btn_encrypt_active_pdf")
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Encrypt PDF", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onExportEncryptedPdf,
                            enabled = canExecuteEncrypt,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("btn_execute_encrypt")
                        ) {
                            Text("Save Protected As...", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Confirmation card when an encrypted PDF has been generated in app storage
            if (!encryptionConfig.lastProtectedFilePath.isNullOrBlank()) {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = EmeraldAccent.copy(alpha = 0.10f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_encrypted_result")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldAccent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Encrypted PDF Secured & Verified",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = EmeraldAccent
                                )
                                Text(
                                    text = encryptionConfig.lastProtectedFileName ?: "Protected_Document.pdf",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onShareProtectedFile(encryptionConfig.lastProtectedFilePath) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_share_encrypted_pdf")
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share Password-Protected PDF", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            // Unlock / Decrypt Password-Protected PDF Card
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Unlock & Remove PDF Password",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Enter the document's current password to permanently remove encryption and save an unlocked copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = encryptionConfig.unlockPassword,
                        onValueChange = onUnlockPasswordChange,
                        label = { Text("Current PDF Password") },
                        singleLine = true,
                        visualTransformation = if (showUnlockPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(Icons.Filled.Key, contentDescription = null, tint = TealAccent)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showUnlockPassword = !showUnlockPassword }) {
                                Icon(
                                    imageVector = if (showUnlockPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showUnlockPassword) "Hide password" else "Show password"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_unlock_password")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onUnlockActivePdf,
                            enabled = encryptionConfig.unlockPassword.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("btn_unlock_active_pdf")
                        ) {
                            Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Unlock PDF", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onExportUnlockedPdf,
                            enabled = encryptionConfig.unlockPassword.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("btn_execute_decrypt")
                        ) {
                            Text("Save Unlocked As...", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog shown automatically when a user attempts to open a password-protected PDF file,
 * allowing them to enter the password and unlock the document for viewing and editing.
 */
@Composable
fun UnlockProtectedPdfDialog(
    fileName: String,
    errorMessage: String?,
    onUnlock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = IndigoPrimary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Password-Protected PDF",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "'$fileName' is encrypted. Enter the document password to unlock and open it in PdfCraft.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Document Password") },
                    singleLine = true,
                    isError = !errorMessage.isNullOrBlank(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    supportingText = if (!errorMessage.isNullOrBlank()) {
                        { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_dialog_unlock_password")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onUnlock(password) },
                enabled = password.isNotBlank(),
                modifier = Modifier.testTag("btn_dialog_unlock_confirm")
            ) {
                Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Unlock & Open")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_dialog_unlock_cancel")
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.testTag("dialog_unlock_pdf")
    )
}
