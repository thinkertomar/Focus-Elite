package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.CropRectNormalized
import com.example.ui.theme.IndigoPrimary

/**
 * Interactive Page Crop Dialog.
 * Allows users to visually crop page margins or unwanted scanner borders with
 * touch-draggable corner pins, quick preset margin trimmers, and single/all-page toggles.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageCropDialog(
    pageBitmap: Bitmap?,
    currentPageIndex: Int,
    totalPages: Int,
    onDismiss: () -> Unit,
    onConfirmCrop: (CropRectNormalized, Boolean) -> Unit
) {
    var cropLeft by remember { mutableFloatStateOf(0.05f) }
    var cropTop by remember { mutableFloatStateOf(0.05f) }
    var cropRight by remember { mutableFloatStateOf(0.95f) }
    var cropBottom by remember { mutableFloatStateOf(0.95f) }
    var applyToAllPages by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp)
                .testTag("dialog_page_crop")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Crop,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Crop Document Pages",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Page ${currentPageIndex + 1} of $totalPages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            cropLeft = 0f
                            cropTop = 0f
                            cropRight = 1f
                            cropBottom = 1f
                        }
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = "Reset Crop")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Interactive Cropping Preview Box
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val boxW = constraints.maxWidth.toFloat()
                    val boxH = constraints.maxHeight.toFloat()

                    if (pageBitmap != null) {
                        Image(
                            bitmap = pageBitmap.asImageBitmap(),
                            contentDescription = "Page to crop",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Draggable Crop Overlay with semi-transparent mask and corner pins
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val touchX = change.position.x / boxW
                                    val touchY = change.position.y / boxH

                                    // Determine which handle is closest
                                    val distTopLeft = Math.hypot((touchX - cropLeft).toDouble(), (touchY - cropTop).toDouble())
                                    val distBottomRight = Math.hypot((touchX - cropRight).toDouble(), (touchY - cropBottom).toDouble())

                                    if (distTopLeft < distBottomRight) {
                                        cropLeft = (cropLeft + dragAmount.x / boxW).coerceIn(0f, cropRight - 0.1f)
                                        cropTop = (cropTop + dragAmount.y / boxH).coerceIn(0f, cropBottom - 0.1f)
                                    } else {
                                        cropRight = (cropRight + dragAmount.x / boxW).coerceIn(cropLeft + 0.1f, 1f)
                                        cropBottom = (cropBottom + dragAmount.y / boxH).coerceIn(cropTop + 0.1f, 1f)
                                    }
                                }
                            }
                    ) {
                        val rectLeft = cropLeft * size.width
                        val rectTop = cropTop * size.height
                        val rectWidth = (cropRight - cropLeft) * size.width
                        val rectHeight = (cropBottom - cropTop) * size.height

                        // Semi-transparent dark mask outside crop rect
                        // Top
                        drawRect(color = Color(0x99000000), topLeft = Offset.Zero, size = Size(size.width, rectTop))
                        // Bottom
                        drawRect(color = Color(0x99000000), topLeft = Offset(0f, rectTop + rectHeight), size = Size(size.width, size.height - (rectTop + rectHeight)))
                        // Left
                        drawRect(color = Color(0x99000000), topLeft = Offset(0f, rectTop), size = Size(rectLeft, rectHeight))
                        // Right
                        drawRect(color = Color(0x99000000), topLeft = Offset(rectLeft + rectWidth, rectTop), size = Size(size.width - (rectLeft + rectWidth), rectHeight))

                        // Crop border
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(rectLeft, rectTop),
                            size = Size(rectWidth, rectHeight),
                            style = Stroke(width = 2.5.dp.toPx())
                        )

                        // Corner handles
                        val handleRadius = 7.dp.toPx()
                        drawCircle(color = IndigoPrimary, radius = handleRadius, center = Offset(rectLeft, rectTop))
                        drawCircle(color = Color.White, radius = handleRadius - 2.dp.toPx(), center = Offset(rectLeft, rectTop))

                        drawCircle(color = IndigoPrimary, radius = handleRadius, center = Offset(rectLeft + rectWidth, rectTop + rectHeight))
                        drawCircle(color = Color.White, radius = handleRadius - 2.dp.toPx(), center = Offset(rectLeft + rectWidth, rectTop + rectHeight))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Preset margin chips
                Text(
                    text = "Quick Margin Presets:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = cropLeft == 0.05f && cropTop == 0.05f && cropRight == 0.95f && cropBottom == 0.95f,
                        onClick = {
                            cropLeft = 0.05f; cropTop = 0.05f; cropRight = 0.95f; cropBottom = 0.95f
                        },
                        label = { Text("Trim Margins 5%") }
                    )
                    FilterChip(
                        selected = cropTop == 0.12f && cropBottom == 0.88f,
                        onClick = {
                            cropLeft = 0.03f; cropTop = 0.12f; cropRight = 0.97f; cropBottom = 0.88f
                        },
                        label = { Text("Cut Header/Footer") }
                    )
                    FilterChip(
                        selected = cropLeft == 0.10f,
                        onClick = {
                            cropLeft = 0.10f; cropTop = 0.04f; cropRight = 0.96f; cropBottom = 0.96f
                        },
                        label = { Text("Cut Punch Holes (10%)") }
                    )
                    FilterChip(
                        selected = cropLeft == 0f && cropTop == 0f && cropRight == 1f && cropBottom == 1f,
                        onClick = {
                            cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f
                        },
                        label = { Text("Full Page") }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Apply to all pages toggle switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Apply Crop to All Pages",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (applyToAllPages) "Trimming all $totalPages pages" else "Only cropping Page ${currentPageIndex + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = applyToAllPages,
                        onCheckedChange = { applyToAllPages = it },
                        modifier = Modifier.testTag("switch_crop_apply_all")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val rect = CropRectNormalized(cropLeft, cropTop, cropRight, cropBottom)
                            onConfirmCrop(rect, applyToAllPages)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("btn_confirm_crop")
                    ) {
                        Text("Apply & Save Crop")
                    }
                }
            }
        }
    }
}
