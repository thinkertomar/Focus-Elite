package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.PdfEngine
import com.example.engine.PdfThumbnailCache
import com.example.ui.theme.CrimsonAccent

/**
 * High-performance PDF Preview Component.
 * Uses installed library dependencies (PdfRenderer and Apache PDFBox) with LRU memory caching
 * to render the first page of a document as a visual thumbnail for file list items.
 */
@Composable
fun PdfThumbnailPreview(
    uri: Uri,
    modifier: Modifier = Modifier,
    pageIndex: Int = 0,
    targetWidth: Int = 200,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val cacheKey = "${uri}_p_${pageIndex}_w_$targetWidth"
    var bitmap by remember(uri, pageIndex) { mutableStateOf(PdfThumbnailCache.get(cacheKey)) }
    var isLoading by remember(uri, pageIndex) { mutableStateOf(bitmap == null) }

    LaunchedEffect(uri, pageIndex) {
        if (bitmap == null) {
            isLoading = true
            val rendered = PdfEngine.renderFirstPageThumbnail(context, uri, pageIndex, targetWidth)
            bitmap = rendered
            isLoading = false
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        tonalElevation = 2.dp,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .testTag("pdf_thumbnail_preview")
    ) {
        Crossfade(targetState = bitmap, label = "thumb_crossfade") { currentBmp ->
            if (currentBmp != null) {
                Image(
                    bitmap = currentBmp.asImageBitmap(),
                    contentDescription = "First Page Preview",
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        // Fallback PDF badge
                        Icon(
                            imageVector = Icons.Filled.PictureAsPdf,
                            contentDescription = "PDF Document",
                            tint = CrimsonAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
