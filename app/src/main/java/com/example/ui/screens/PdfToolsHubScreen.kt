package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material3.RadioButton
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.model.BuiltInImageStamp
import com.example.data.model.CompressionPreset
import com.example.data.model.EncryptionKeyStrength
import com.example.data.model.EncryptionMode
import com.example.data.model.MergeDocumentItem
import com.example.data.model.PdfEncryptionConfig
import com.example.data.model.ToolType
import com.example.data.model.WatermarkConfig
import com.example.data.model.WatermarkFont
import com.example.data.model.WatermarkPlacement
import com.example.data.model.WatermarkType
import com.example.ui.components.PdfSecurityToolSection
import com.example.ui.components.WatermarkToolSection
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.TealAccent
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsHubScreen(
    activeTool: ToolType,
    onSelectTool: (ToolType) -> Unit,
    currentDocName: String,
    currentDocPages: Int,
    // Crop & Compress
    compressionPreset: CompressionPreset = CompressionPreset.BALANCED,
    onCompressionPresetChange: (CompressionPreset) -> Unit = {},
    onExecuteCompress: (CompressionPreset, Uri) -> Unit = { _, _ -> },
    onOpenCropTool: () -> Unit = {},
    // Merge
    mergeQueue: List<MergeDocumentItem>,
    onAddMergeFiles: (List<Uri>) -> Unit,
    onRemoveMergeItem: (Int) -> Unit,
    onMoveMergeUp: (Int) -> Unit,
    onMoveMergeDown: (Int) -> Unit,
    onExecuteMerge: (Uri) -> Unit,
    // Split
    splitRangeInput: String,
    onSplitRangeChange: (String) -> Unit,
    onExecuteSplit: (Uri) -> Unit,
    // Images to PDF
    selectedImageUris: List<Uri>,
    onSelectImages: (List<Uri>) -> Unit,
    onExecuteImagesToPdf: (Uri) -> Unit,
    // PDF to Images
    onExecutePdfToImages: ((List<File>) -> Unit) -> Unit,
    // Watermark
    watermarkConfig: WatermarkConfig,
    onWatermarkTextChange: (String) -> Unit,
    onWatermarkOpacityChange: (Float) -> Unit,
    onWatermarkDiagonalChange: (Boolean) -> Unit,
    onWatermarkPageNumbersChange: (Boolean) -> Unit,
    onWatermarkTypeChange: (WatermarkType) -> Unit = {},
    onWatermarkImageUriChange: (Uri?) -> Unit = {},
    onWatermarkBuiltInStampChange: (BuiltInImageStamp) -> Unit = {},
    onWatermarkFontChange: (WatermarkFont) -> Unit = {},
    onWatermarkRotationChange: (Float) -> Unit = {},
    onWatermarkScaleChange: (Float) -> Unit = {},
    onWatermarkPlacementChange: (WatermarkPlacement) -> Unit = {},
    onWatermarkCustomOffsetChange: (Float, Float) -> Unit = { _, _ -> },
    onWatermarkColorChange: (Long) -> Unit = {},
    onApplyWatermarkActive: () -> Unit = {},
    onExecuteWatermark: (Uri) -> Unit,
    // PDF Encryption & Password Protection
    encryptionConfig: PdfEncryptionConfig = PdfEncryptionConfig(),
    onEncryptionModeChange: (EncryptionMode) -> Unit = {},
    onEncryptUserPasswordChange: (String) -> Unit = {},
    onEncryptConfirmPasswordChange: (String) -> Unit = {},
    onEncryptOwnerPasswordChange: (String) -> Unit = {},
    onEncryptKeyStrengthChange: (EncryptionKeyStrength) -> Unit = {},
    onEncryptAllowPrintingChange: (Boolean) -> Unit = {},
    onEncryptAllowCopyingChange: (Boolean) -> Unit = {},
    onEncryptAllowModifyingChange: (Boolean) -> Unit = {},
    onUnlockPasswordChange: (String) -> Unit = {},
    onGenerateStrongPassword: () -> Unit = {},
    onEncryptActivePdf: () -> Unit = {},
    onExecuteEncrypt: (Uri) -> Unit = {},
    onUnlockActivePdf: () -> Unit = {},
    onExecuteDecrypt: (Uri) -> Unit = {},
    onShareProtectedFile: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val toolTabs = ToolType.values()

    // SAF Launchers
    val mergeAddLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onAddMergeFiles(uris)
    }

    val mergeSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { onExecuteMerge(it) }
    }

    val splitSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { onExecuteSplit(it) }
    }

    val imagesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onSelectImages(uris)
    }

    val imagesPdfSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { onExecuteImagesToPdf(it) }
    }

    val watermarkSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { onExecuteWatermark(it) }
    }

    val watermarkImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onWatermarkImageUriChange(it) }
    }

    val compressSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { onExecuteCompress(compressionPreset, it) }
    }

    val encryptSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { onExecuteEncrypt(it) }
    }

    val decryptSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { onExecuteDecrypt(it) }
    }

    var exportedImageFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("tools_hub_screen")
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "PDF Tool Suite",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        // Scrollable Tool Category Tabs
        ScrollableTabRow(
            selectedTabIndex = activeTool.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            edgePadding = 16.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            toolTabs.forEach { tool ->
                Tab(
                    selected = activeTool == tool,
                    onClick = { onSelectTool(tool) },
                    text = {
                        Text(
                            text = tool.title,
                            fontWeight = if (activeTool == tool) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier.testTag("tab_${tool.name}")
                )
            }
        }

        // Active Tool Content
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (activeTool) {
                ToolType.CROP -> {
                    item {
                        ToolDescriptionCard(
                            title = "Visual Page Cropping & Border Trimming",
                            description = "Trim scanner borders, remove unwanted headers/footers, or crop custom zones with interactive corner pins. Apply to the current page or batch-crop all pages."
                        )
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Active Document", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(currentDocName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("$currentDocPages total pages available for cropping", style = MaterialTheme.typography.bodySmall, color = IndigoPrimary)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Crop, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                Text("Precision Margin Presets:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                        Text("Trim Margins (5%)", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                        Text("Header/Footer (10%)", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                        Text("Custom Touch Box", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.height(18.dp))
                                Button(
                                    onClick = onOpenCropTool,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("btn_open_crop_editor")
                                ) {
                                    Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Launch Visual Crop Studio", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                ToolType.COMPRESS -> {
                    item {
                        ToolDescriptionCard(
                            title = "Document Compression & Size Optimizer",
                            description = "Reduce large PDF file sizes by optimizing resolution (DPI) and image compression quality. Ideal for email attachments, messaging, or conserving storage."
                        )
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Compression Presets",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Target DPI & quality balance for: $currentDocName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                CompressionPreset.values().forEach { preset ->
                                    val isSelected = preset == compressionPreset
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) IndigoPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) IndigoPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable { onCompressionPresetChange(preset) }
                                            .testTag("preset_${preset.name}")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = { onCompressionPresetChange(preset) }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(preset.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                                Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        val suggestedName = "Compressed_" + currentDocName
                                        compressSaveLauncher.launch(suggestedName)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("btn_execute_compress")
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Compress & Save PDF", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                ToolType.MERGE -> {
                    item {
                        ToolDescriptionCard(
                            title = "Combine Multiple PDFs",
                            description = "Add 2 or more PDF documents. Arrange their sequence using the move buttons and merge them into a single master PDF."
                        )
                    }

                    item {
                        Button(
                            onClick = { mergeAddLauncher.launch(arrayOf("application/pdf")) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_add_merge_files")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select PDFs to Merge")
                        }
                    }

                    if (mergeQueue.isEmpty()) {
                        item {
                            EmptyToolState("No documents added to the merge queue yet.")
                        }
                    } else {
                        itemsIndexed(mergeQueue) { index, item ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = IndigoPrimary.copy(alpha = 0.12f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                fontWeight = FontWeight.Bold,
                                                color = IndigoPrimary
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.pageCount} pages • ${item.sizeFormatted}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    IconButton(
                                        onClick = { onMoveMergeUp(index) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                                    }

                                    IconButton(
                                        onClick = { onMoveMergeDown(index) },
                                        enabled = index < mergeQueue.size - 1,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                                    }

                                    IconButton(
                                        onClick = { onRemoveMergeItem(index) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = CrimsonAccent, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        item {
                            val totalPages = mergeQueue.sumOf { it.pageCount }
                            Button(
                                onClick = { mergeSaveLauncher.launch("Merged_Document.pdf") },
                                enabled = mergeQueue.size >= 2,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("btn_execute_merge")
                            ) {
                                Icon(Icons.Filled.CallMerge, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Merge ${mergeQueue.size} Files ($totalPages pages)")
                            }
                        }
                    }
                }

                ToolType.SPLIT -> {
                    item {
                        ToolDescriptionCard(
                            title = "Extract Custom Page Ranges",
                            description = "Split the active document '$currentDocName' ($currentDocPages pages) into a new, smaller document by specifying page numbers."
                        )
                    }

                    item {
                        ElevatedCard(
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Target Page Range",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = splitRangeInput,
                                    onValueChange = onSplitRangeChange,
                                    label = { Text("e.g. 1-2 or 1,3,4") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("input_split_range")
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Quick presets:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = splitRangeInput == "1-1",
                                        onClick = { onSplitRangeChange("1-1") },
                                        label = { Text("Page 1") }
                                    )
                                    if (currentDocPages >= 2) {
                                        FilterChip(
                                            selected = splitRangeInput == "1-2",
                                            onClick = { onSplitRangeChange("1-2") },
                                            label = { Text("Pages 1-2") }
                                        )
                                    }
                                    if (currentDocPages >= 3) {
                                        FilterChip(
                                            selected = splitRangeInput == "2-$currentDocPages",
                                            onClick = { onSplitRangeChange("2-$currentDocPages") },
                                            label = { Text("Rest of doc") }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { splitSaveLauncher.launch("Split_$currentDocName") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("btn_execute_split")
                                ) {
                                    Icon(Icons.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Split & Save New PDF")
                                }
                            }
                        }
                    }
                }

                ToolType.IMAGES_TO_PDF -> {
                    item {
                        ToolDescriptionCard(
                            title = "Convert Photos / Scans to PDF",
                            description = "Pick photos, receipts, or notes from your gallery to compile into a crisp A4 PDF document."
                        )
                    }

                    item {
                        Button(
                            onClick = {
                                imagesPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_pick_images")
                        ) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Photos from Gallery")
                        }
                    }

                    if (selectedImageUris.isEmpty()) {
                        item {
                            EmptyToolState("No images chosen yet.")
                        }
                    } else {
                        item {
                            Text(
                                text = "${selectedImageUris.size} photos selected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        item {
                            Button(
                                onClick = { imagesPdfSaveLauncher.launch("Scanned_Photos.pdf") },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("btn_execute_img_to_pdf")
                            ) {
                                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Convert ${selectedImageUris.size} Photos to PDF")
                            }
                        }
                    }
                }

                ToolType.PDF_TO_IMAGES -> {
                    item {
                        ToolDescriptionCard(
                            title = "Export PDF Pages as Images",
                            description = "Extract all pages of '$currentDocName' ($currentDocPages pages) into high-resolution PNG image files."
                        )
                    }

                    item {
                        Button(
                            onClick = {
                                onExecutePdfToImages { files ->
                                    exportedImageFiles = files
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_execute_pdf_to_images")
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export Pages as PNG")
                        }
                    }

                    if (exportedImageFiles.isNotEmpty()) {
                        item {
                            Text(
                                text = "Exported ${exportedImageFiles.size} Page Images:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(exportedImageFiles) { file ->
                            Card(
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = file.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(text = "${file.length() / 1024} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }

                                    IconButton(
                                        onClick = {
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share Page Image"))
                                        }
                                    ) {
                                        Icon(Icons.Filled.Share, contentDescription = "Share Image", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                ToolType.WATERMARK -> {
                    item {
                        ToolDescriptionCard(
                            title = "Watermark & Overlay Studio",
                            description = "Overlay custom text or image stamps onto '$currentDocName' with interactive drag-to-place controls, 9-point placement presets, 3×4 tiled grids, and opacity sliders."
                        )
                    }

                    item {
                        WatermarkToolSection(
                            currentDocName = currentDocName,
                            currentDocPages = currentDocPages,
                            watermarkConfig = watermarkConfig,
                            onWatermarkTextChange = onWatermarkTextChange,
                            onWatermarkOpacityChange = onWatermarkOpacityChange,
                            onWatermarkPageNumbersChange = onWatermarkPageNumbersChange,
                            onWatermarkTypeChange = onWatermarkTypeChange,
                            onWatermarkImageUriChange = onWatermarkImageUriChange,
                            onWatermarkBuiltInStampChange = onWatermarkBuiltInStampChange,
                            onWatermarkFontChange = onWatermarkFontChange,
                            onWatermarkRotationChange = onWatermarkRotationChange,
                            onWatermarkScaleChange = onWatermarkScaleChange,
                            onWatermarkPlacementChange = onWatermarkPlacementChange,
                            onWatermarkCustomOffsetChange = onWatermarkCustomOffsetChange,
                            onWatermarkColorChange = onWatermarkColorChange,
                            onPickGalleryImage = {
                                watermarkImagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onApplyWatermarkToActive = onApplyWatermarkActive,
                            onExportWatermarkedCopy = {
                                watermarkSaveLauncher.launch("Stamped_$currentDocName")
                            }
                        )
                    }
                }

                ToolType.ENCRYPT -> {
                    item {
                        ToolDescriptionCard(
                            title = "Password Protection & AES Security",
                            description = "Encrypt '$currentDocName' with AES-256 or 128-bit password security and granular printing/copying permissions, or unlock password-protected PDFs."
                        )
                    }

                    item {
                        PdfSecurityToolSection(
                            currentDocName = currentDocName,
                            currentDocPages = currentDocPages,
                            encryptionConfig = encryptionConfig,
                            onModeChange = onEncryptionModeChange,
                            onUserPasswordChange = onEncryptUserPasswordChange,
                            onConfirmPasswordChange = onEncryptConfirmPasswordChange,
                            onOwnerPasswordChange = onEncryptOwnerPasswordChange,
                            onKeyStrengthChange = onEncryptKeyStrengthChange,
                            onAllowPrintingChange = onEncryptAllowPrintingChange,
                            onAllowCopyingChange = onEncryptAllowCopyingChange,
                            onAllowModifyingChange = onEncryptAllowModifyingChange,
                            onUnlockPasswordChange = onUnlockPasswordChange,
                            onGenerateStrongPassword = onGenerateStrongPassword,
                            onEncryptActivePdf = onEncryptActivePdf,
                            onExportEncryptedPdf = {
                                encryptSaveLauncher.launch("Protected_$currentDocName")
                            },
                            onUnlockActivePdf = onUnlockActivePdf,
                            onExportUnlockedPdf = {
                                decryptSaveLauncher.launch("Unlocked_$currentDocName")
                            },
                            onShareProtectedFile = onShareProtectedFile
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolDescriptionCard(title: String, description: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        }
    }
}

@Composable
fun EmptyToolState(message: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
