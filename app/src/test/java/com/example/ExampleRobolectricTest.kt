package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PdfCraft", appName)
  }

  @Test
  fun `verify watermark config and customization properties`() {
    val config = com.example.data.model.WatermarkConfig(
      type = com.example.data.model.WatermarkType.TEXT,
      text = "OFFICIAL COPY",
      font = com.example.data.model.WatermarkFont.MONOSPACE,
      opacity = 0.45f,
      rotationDegrees = -45f,
      colorHex = 0xFFE11D48
    )
    assertEquals("OFFICIAL COPY", config.text)
    assertEquals(com.example.data.model.WatermarkFont.MONOSPACE, config.font)
    assertEquals(0.45f, config.opacity, 0.001f)
    assertEquals(-45f, config.rotationDegrees, 0.001f)
    org.junit.Assert.assertTrue(config.isDiagonal)
  }

  @Test
  fun `verify document metadata formatting`() {
    val meta = com.example.data.model.DocumentMetadata(
      fileName = "Annual_Report.pdf",
      pageCount = 12,
      fileSizeFormatted = "2.0 MB",
      title = "Annual Financial Report",
      author = "Accounting Corp",
      subject = "Fiscal Year 2026",
      keywords = "finance, report, 2026"
    )
    assertEquals("Annual Financial Report", meta.title)
    assertEquals("Accounting Corp", meta.author)
    assertEquals("Fiscal Year 2026", meta.subject)
    assertEquals("2.0 MB", meta.fileSizeFormatted)
  }

  @Test
  fun `verify ocr search match and in-place pdf text edit models`() {
    val box = com.example.data.model.CropRectNormalized(0.1f, 0.2f, 0.8f, 0.26f)
    val match = com.example.data.model.OcrSearchMatch(
      id = "p0_b0_l1_m_0",
      pageIndex = 0,
      matchedQuery = "PdfCraft",
      fullLineText = "Welcome to PdfCraft Native Document Studio",
      boundingBox = box
    )
    assertEquals("PdfCraft", match.matchedQuery)
    assertEquals(0, match.pageIndex)

    val editOp = com.example.data.model.PdfTextEditOperation(
      id = "p0_b0_l1",
      pageIndex = 0,
      originalText = "Draft Version",
      newText = "Approved Final Version",
      boundingBox = box,
      fontSizeSp = 16f,
      maskBackground = true,
      isBold = true
    )
    assertEquals("Approved Final Version", editOp.newText)
    org.junit.Assert.assertTrue(editOp.maskBackground)
    org.junit.Assert.assertTrue(editOp.isBold)
  }

  @Test
  fun `verify watermark placement grid and built-in image stamp generation`() {
    val tiledConfig = com.example.data.model.WatermarkConfig(
      type = com.example.data.model.WatermarkType.IMAGE,
      builtInStamp = com.example.data.model.BuiltInImageStamp.APPROVED_STAMP,
      placement = com.example.data.model.WatermarkPlacement.TILED_GRID,
      opacity = 0.35f,
      scaleFactor = 1.25f
    )
    val tiledCoords = com.example.engine.PdfEngine.resolveWatermarkCoordinates(tiledConfig)
    assertEquals(12, tiledCoords.size)

    val customConfig = tiledConfig.copy(
      placement = com.example.data.model.WatermarkPlacement.CUSTOM,
      offsetXNormalized = 0.78f,
      offsetYNormalized = 0.14f
    )
    val customCoords = com.example.engine.PdfEngine.resolveWatermarkCoordinates(customConfig)
    assertEquals(1, customCoords.size)
    assertEquals(0.78f, customCoords.first().first, 0.001f)
    assertEquals(0.14f, customCoords.first().second, 0.001f)

    val stampBmp = com.example.engine.PdfEngine.generateBuiltInStampBitmap(
      com.example.data.model.BuiltInImageStamp.CONFIDENTIAL_SEAL
    )
    assertEquals(440, stampBmp.width)
    assertEquals(180, stampBmp.height)
  }

  @Test
  fun `verify pdf encryption and password decryption roundtrip`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sampleFile = com.example.engine.PdfEngine.createWelcomeSamplePdf(context)
    val sampleUri = android.net.Uri.fromFile(sampleFile)

    val encryptedFile = java.io.File(context.cacheDir, "test_encrypted.pdf")
    val encryptConfig = com.example.data.model.PdfEncryptionConfig(
      userPassword = "SecretPassword123!",
      confirmPassword = "SecretPassword123!",
      keyStrength = com.example.data.model.EncryptionKeyStrength.AES_256,
      allowPrinting = true,
      allowCopying = false,
      allowModifying = false
    )

    val encryptOk = java.io.FileOutputStream(encryptedFile).use { fos ->
      com.example.engine.PdfEngine.encryptPdf(context, sampleUri, encryptConfig, fos)
    }
    org.junit.Assert.assertTrue(encryptOk)
    org.junit.Assert.assertTrue(encryptedFile.exists() && encryptedFile.length() > 100L)

    val encryptedUri = android.net.Uri.fromFile(encryptedFile)
    val isLocked = com.example.engine.PdfEngine.isPdfEncrypted(context, encryptedUri)
    org.junit.Assert.assertTrue(isLocked)

    val unlockedFile = com.example.engine.PdfEngine.unlockPdfToTempFile(
      context,
      encryptedUri,
      "SecretPassword123!",
      "test_encrypted.pdf"
    )
    org.junit.Assert.assertNotNull(unlockedFile)
    val isStillLocked = com.example.engine.PdfEngine.isPdfEncrypted(
      context,
      android.net.Uri.fromFile(unlockedFile!!)
    )
    org.junit.Assert.assertFalse(isStillLocked)
  }
}

