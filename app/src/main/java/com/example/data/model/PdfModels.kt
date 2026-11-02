package com.example.data.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single page in the active PDF document or organizer.
 */
data class PdfPageItem(
    val id: String,
    val sourcePageIndex: Int,
    val rotationDegrees: Int = 0,
    val thumbnail: Bitmap? = null,
    val width: Int = 595,
    val height: Int = 842,
    val isSelected: Boolean = false
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 0.707f
}

/**
 * Metadata summary of a PDF document.
 */
data class DocumentMetadata(
    val fileName: String,
    val pageCount: Int,
    val fileSizeFormatted: String,
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "PdfCraft for Android",
    val uri: Uri? = null,
    val isEncrypted: Boolean = false
)

/**
 * Room Entity for caching recent documents locally.
 */
@Entity(tableName = "recent_documents")
data class RecentDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    val displayName: String,
    val pageCount: Int,
    val fileSizeFormatted: String,
    val lastOpened: Long = System.currentTimeMillis(),
    val isStarred: Boolean = false
)

/**
 * Representation of a local PDF document file on device storage.
 */
data class PdfFileItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val pageCount: Int,
    val lastModified: Long = System.currentTimeMillis(),
    val isSample: Boolean = false,
    val isInternalFile: Boolean = false
)

/**
 * Navigation views in the PdfCraft studio.
 */
enum class AppScreen {
    HUB,
    FILES,
    VIEWER,
    ORGANIZER,
    TOOLS,
    SETTINGS
}

/**
 * Tabs on the redesigned Adobe/Microsoft-style Home Hub screen.
 */
enum class HomeWorkspaceTab(val label: String) {
    RECENT("Recent"),
    STARRED("Starred"),
    LOCAL_FILES("On Device")
}

/**
 * A recognized text line or block with normalized page coordinates (0f..1f).
 */
data class OcrTextBlockItem(
    val id: String,
    val pageIndex: Int,
    val text: String,
    val boundingBox: CropRectNormalized
)

/**
 * A single OCR search match within a document page, including normalized coordinates for visual highlighting.
 */
data class OcrSearchMatch(
    val id: String,
    val pageIndex: Int,
    val matchedQuery: String,
    val fullLineText: String,
    val boundingBox: CropRectNormalized
)

/**
 * Represents an in-place PDF text edit or addition created via the Viewer Pencil Edit Studio.
 */
data class PdfTextEditOperation(
    val id: String,
    val pageIndex: Int,
    val originalText: String,
    val newText: String,
    val boundingBox: CropRectNormalized,
    val fontSizeSp: Float = 14f,
    val textColorHex: Long = 0xFF0F172A,
    val maskBackground: Boolean = true,
    val isBold: Boolean = false
)

/**
 * Result of on-device OCR text extraction using Google ML Kit.
 */
data class OcrResult(
    val text: String = "",
    val blockCount: Int = 0,
    val lineCount: Int = 0,
    val characterCount: Int = 0,
    val executionTimeMs: Long = 0L,
    val isSuccess: Boolean = true,
    val engineName: String = "Google ML Kit Neural Vision (On-Device)",
    val blocks: List<OcrTextBlockItem> = emptyList()
)

/**
 * Cloud storage and synchronization configuration for Google Drive.
 */
data class CloudSyncSettings(
    val providerName: String = "Google Drive",
    val isConnected: Boolean = true,
    val accountEmail: String = "thinkertomar@gmail.com",
    val targetFolder: String = "My Drive/PdfCraft/Documents",
    val autoBackupOnEdit: Boolean = false,
    val wifiOnly: Boolean = true,
    val lastSyncTimestamp: Long = System.currentTimeMillis() - 7200000L,
    val lastSyncDocument: String = "Welcome_Guide.pdf",
    val lastSyncStatus: String = "Synchronized with Google Drive"
)

/**
 * Normalized crop bounding box (values 0f to 1f relative to page dimensions).
 */
data class CropRectNormalized(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float get() = (right - left).coerceIn(0.05f, 1f)
    val height: Float get() = (bottom - top).coerceIn(0.05f, 1f)
}

/**
 * Compression presets for document optimization.
 */
enum class CompressionPreset(
    val label: String,
    val targetDpi: Int,
    val jpegQuality: Int,
    val description: String
) {
    HIGH_QUALITY("High Quality", 180, 85, "Crisp clarity (180 DPI)"),
    BALANCED("Balanced", 130, 72, "Recommended for sharing (~60% smaller)"),
    MAX_COMPRESSION("Maximum", 90, 55, "Smallest size for email (~80% smaller)")
}

/**
 * Dedicated PDF manipulation tools.
 */
enum class ToolType(val title: String, val description: String) {
    CROP("Crop Pages", "Trim margins, remove headers/footers, or crop custom zones"),
    COMPRESS("Compress PDF", "Optimize and reduce document file size for easy sharing"),
    MERGE("Merge PDFs", "Combine multiple PDF documents into one seamless file"),
    SPLIT("Split PDF", "Extract custom page ranges or separate pages"),
    IMAGES_TO_PDF("Images to PDF", "Convert gallery photos or scans into a high-res PDF"),
    PDF_TO_IMAGES("PDF to Images", "Export pages as PNG/JPEG images"),
    WATERMARK("Watermark", "Overlay custom text or image stamps with opacity and placement controls"),
    ENCRYPT("Protect PDF", "Encrypt PDF files with password protection and access permissions")
}

/**
 * Item in the merge document queue.
 */
data class MergeDocumentItem(
    val uri: Uri,
    val name: String,
    val pageCount: Int,
    val sizeFormatted: String
)

/**
 * Watermark type: text overlay or image stamp.
 */
enum class WatermarkType(val label: String) {
    TEXT("Text Overlay"),
    IMAGE("Image / Stamp Overlay")
}

/**
 * Font options for custom text watermarks.
 */
enum class WatermarkFont(val label: String) {
    SANS_SERIF("Sans-Serif (Modern)"),
    SERIF("Serif (Classic)"),
    MONOSPACE("Monospace (Typewriter)"),
    BOLD_HEADING("Bold Sans (Heavy)")
}

/**
 * Placement presets and custom coordinate modes for watermarks.
 */
enum class WatermarkPlacement(val label: String, val shortLabel: String, val normX: Float, val normY: Float) {
    TOP_LEFT("Top Left", "TL", 0.22f, 0.14f),
    TOP_CENTER("Top Header", "TC", 0.50f, 0.12f),
    TOP_RIGHT("Top Right", "TR", 0.78f, 0.14f),
    CENTER_LEFT("Middle Left", "ML", 0.22f, 0.50f),
    CENTER("Center", "C", 0.50f, 0.50f),
    CENTER_RIGHT("Middle Right", "MR", 0.78f, 0.50f),
    BOTTOM_LEFT("Bottom Left", "BL", 0.22f, 0.86f),
    BOTTOM_CENTER("Bottom Footer", "BC", 0.50f, 0.88f),
    BOTTOM_RIGHT("Bottom Right", "BR", 0.78f, 0.86f),
    TILED_GRID("Tiled Pattern (3×4)", "Tile", 0.50f, 0.50f),
    CUSTOM("Custom Position", "XY", 0.50f, 0.50f)
}

/**
 * Built-in high-resolution graphic stamp templates for Image Watermark mode when no external gallery photo is used.
 */
enum class BuiltInImageStamp(
    val label: String,
    val badgeTitle: String,
    val subTitle: String,
    val colorHex: Long
) {
    NONE("Custom Gallery Image", "", "", 0xFFE11D48),
    CONFIDENTIAL_SEAL("Confidential Seal", "CONFIDENTIAL", "AUTHORIZED EYES ONLY", 0xFFE11D48),
    APPROVED_STAMP("Approved Stamp", "APPROVED", "VERIFIED & SIGNED", 0xFF059669),
    DRAFT_BADGE("Draft Stamp", "DRAFT COPY", "DO NOT DISTRIBUTE", 0xFFD97706),
    OFFICIAL_SEAL("Official Seal", "OFFICIAL", "CERTIFIED DOCUMENT", 0xFF1E3A8A)
}

/**
 * Watermark configuration supporting custom text, images, font, opacity, placement, scale, rotation, and color.
 */
data class WatermarkConfig(
    val type: WatermarkType = WatermarkType.TEXT,
    val text: String = "CONFIDENTIAL",
    val imageUri: Uri? = null,
    val builtInStamp: BuiltInImageStamp = BuiltInImageStamp.CONFIDENTIAL_SEAL,
    val font: WatermarkFont = WatermarkFont.SANS_SERIF,
    val opacity: Float = 0.30f,
    val rotationDegrees: Float = -45f,
    val fontSizeSp: Float = 42f,
    val scaleFactor: Float = 1.0f,
    val placement: WatermarkPlacement = WatermarkPlacement.CENTER,
    val offsetXNormalized: Float = 0.50f,
    val offsetYNormalized: Float = 0.50f,
    val colorHex: Long = 0xFFE11D48, // Crimson Accent
    val includePageNumbers: Boolean = true
) {
    // Backwards compatibility property
    val isDiagonal: Boolean
        get() = rotationDegrees == -45f
}

/**
 * Mode for the PDF Security & Encryption studio.
 */
enum class EncryptionMode(val label: String) {
    ENCRYPT_LOCK("Encrypt & Lock PDF"),
    DECRYPT_UNLOCK("Unlock / Strip Password")
}

/**
 * Encryption cipher strength options.
 */
enum class EncryptionKeyStrength(val label: String, val keyLengthBits: Int, val description: String) {
    AES_256("AES-256 Bit", 256, "Vault-grade AES encryption for sensitive files"),
    STANDARD_128("128-Bit Standard", 128, "High compatibility across legacy PDF readers")
}

/**
 * Configuration for encrypting or unlocking PDF documents.
 */
data class PdfEncryptionConfig(
    val mode: EncryptionMode = EncryptionMode.ENCRYPT_LOCK,
    val userPassword: String = "",
    val confirmPassword: String = "",
    val ownerPassword: String = "",
    val keyStrength: EncryptionKeyStrength = EncryptionKeyStrength.AES_256,
    val allowPrinting: Boolean = true,
    val allowCopying: Boolean = false,
    val allowModifying: Boolean = false,
    val unlockPassword: String = "",
    val lastProtectedFilePath: String? = null,
    val lastProtectedFileName: String? = null
)

