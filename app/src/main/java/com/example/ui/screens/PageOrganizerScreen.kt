package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PdfPageItem
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.IndigoPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageOrganizerScreen(
    pages: List<PdfPageItem>,
    documentTitle: String,
    onRotatePage: (String, Int) -> Unit,
    onRotateSelected: (Int) -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
    onDeletePage: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onDuplicatePage: (String) -> Unit,
    onResetOrder: () -> Unit,
    onSaveReorganized: (Uri) -> Unit,
    onShare: () -> Unit,
    onCropPage: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val selectedCount = pages.count { it.isSelected }
    val allSelected = pages.isNotEmpty() && selectedCount == pages.size

    // Save As document launcher
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { onSaveReorganized(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("organizer_screen")
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Page Organizer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${pages.size} pages • ${if (selectedCount > 0) "$selectedCount selected" else "Tap page to select"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.testTag("btn_share_organized")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "Share PDF")
                }
                Button(
                    onClick = {
                        val defaultName = documentTitle.removeSuffix(".pdf") + "_edited.pdf"
                        saveAsLauncher.launch(defaultName)
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .testTag("btn_save_organized")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save As", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        // Multi-Action Command Toolbar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Select All / Deselect
                    IconButton(
                        onClick = { onSelectAll(!allSelected) },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (allSelected) Icons.Filled.CheckCircle else Icons.Filled.SelectAll,
                            contentDescription = "Select All",
                            tint = if (allSelected) IndigoPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Rotate CW
                    IconButton(
                        onClick = { onRotateSelected(90) },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("btn_rotate_cw")
                    ) {
                        Icon(Icons.Filled.RotateRight, contentDescription = "Rotate 90 CW", modifier = Modifier.size(20.dp))
                    }

                    // Rotate CCW
                    IconButton(
                        onClick = { onRotateSelected(-90) },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("btn_rotate_ccw")
                    ) {
                        Icon(Icons.Filled.RotateLeft, contentDescription = "Rotate 90 CCW", modifier = Modifier.size(20.dp))
                    }

                    // Flip 180
                    IconButton(
                        onClick = { onRotateSelected(180) },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("btn_rotate_180")
                    ) {
                        Icon(Icons.Filled.ScreenRotation, contentDescription = "Flip 180", modifier = Modifier.size(19.dp))
                    }

                    // Delete Selected
                    if (selectedCount > 0) {
                        IconButton(
                            onClick = onDeleteSelected,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("btn_delete_selected")
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = CrimsonAccent, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Reset Button
                OutlinedButton(
                    onClick = onResetOrder,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("btn_reset_order")
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset", fontSize = 11.sp)
                }
            }
        }

        // Pages Grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("pages_grid")
        ) {
            itemsIndexed(pages, key = { _, item -> item.id }) { index, item ->
                PageGridTile(
                    pageItem = item,
                    displayIndex = index + 1,
                    canMoveLeft = index > 0,
                    canMoveRight = index < pages.size - 1,
                    onToggleSelect = { onToggleSelect(item.id) },
                    onRotate = { onRotatePage(item.id, 90) },
                    onCrop = { onCropPage(index) },
                    onMoveLeft = { onMoveLeft(index) },
                    onMoveRight = { onMoveRight(index) },
                    onDuplicate = { onDuplicatePage(item.id) },
                    onDelete = { onDeletePage(item.id) }
                )
            }
        }
    }
}

@Composable
fun PageGridTile(
    pageItem: PdfPageItem,
    displayIndex: Int,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onToggleSelect: () -> Unit,
    onRotate: () -> Unit,
    onCrop: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (pageItem.isSelected) 6.dp else 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (pageItem.isSelected) 2.5.dp else 1.dp,
                color = if (pageItem.isSelected) IndigoPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("tile_page_$displayIndex")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Tile Header: Page Badge & Selection Checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(2.dp)
                ) {
                    Text(
                        text = "Page $displayIndex",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (pageItem.rotationDegrees % 360 != 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = AmberAccent.copy(alpha = 0.2f),
                        modifier = Modifier.padding(2.dp)
                    ) {
                        Text(
                            text = "${(pageItem.rotationDegrees % 360 + 360) % 360}°",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = AmberAccent,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onToggleSelect,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (pageItem.isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = "Select page",
                        tint = if (pageItem.isSelected) IndigoPrimary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Thumbnail Preview Container with Rotation
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(pageItem.aspectRatio.coerceIn(0.6f, 1.4f))
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleSelect)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (pageItem.thumbnail != null) {
                        Image(
                            bitmap = pageItem.thumbnail.asImageBitmap(),
                            contentDescription = "Thumbnail $displayIndex",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .rotate(pageItem.rotationDegrees.toFloat())
                        )
                    } else {
                        Text("Page $displayIndex", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Actions row: Move Left, Move Right, Rotate, Duplicate, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onMoveLeft,
                    enabled = canMoveLeft,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Move Left", modifier = Modifier.size(15.dp))
                }

                IconButton(
                    onClick = onRotate,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.RotateRight, contentDescription = "Rotate", modifier = Modifier.size(16.dp))
                }

                IconButton(
                    onClick = onCrop,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.Crop, contentDescription = "Crop Page", modifier = Modifier.size(15.dp))
                }

                IconButton(
                    onClick = onDuplicate,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(15.dp))
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = CrimsonAccent, modifier = Modifier.size(15.dp))
                }

                IconButton(
                    onClick = onMoveRight,
                    enabled = canMoveRight,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.ArrowForward, contentDescription = "Move Right", modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
