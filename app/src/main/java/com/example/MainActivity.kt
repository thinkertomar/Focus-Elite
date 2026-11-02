package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.model.CropRectNormalized
import com.example.ui.components.PageCropDialog
import androidx.core.content.FileProvider
import com.example.data.model.AppScreen
import com.example.ui.components.LoadingProgressDialog
import com.example.ui.components.PdfCraftBottomNav
import com.example.ui.components.UnlockProtectedPdfDialog
import com.example.ui.screens.DocumentHubScreen
import com.example.ui.screens.PageOrganizerScreen
import com.example.ui.screens.PdfToolsHubScreen
import com.example.ui.screens.PdfViewerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.PdfCraftViewModel
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: PdfCraftViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle intent if user opened a PDF file from an external app/file manager
        intent?.data?.let { uri ->
            if (intent.type == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)) {
                viewModel.loadDocument(uri)
                viewModel.setScreen(AppScreen.VIEWER)
            }
        }

        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val recentDocs by viewModel.recentDocuments.collectAsStateWithLifecycle()
                val localFiles by viewModel.localFiles.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                var pendingCropConfig by remember { mutableStateOf<Pair<CropRectNormalized, Boolean>?>(null) }
                val cropSaveLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/pdf")
                ) { uri: Uri? ->
                    uri?.let { targetUri ->
                        pendingCropConfig?.let { (rect, applyToAll) ->
                            viewModel.executeCrop(rect, applyToAll, targetUri) { success ->
                                if (success) {
                                    viewModel.loadDocument(targetUri)
                                    viewModel.setScreen(AppScreen.VIEWER)
                                }
                            }
                        }
                    }
                }

                // Display notifications & errors in snackbars
                LaunchedEffect(uiState.userNotice) {
                    uiState.userNotice?.let { notice ->
                        snackbarHostState.showSnackbar(notice, duration = SnackbarDuration.Short)
                        viewModel.clearUserNotice()
                    }
                }

                LaunchedEffect(uiState.errorMessage) {
                    uiState.errorMessage?.let { error ->
                        snackbarHostState.showSnackbar(error, duration = SnackbarDuration.Long)
                        viewModel.clearError()
                    }
                }

                // Android Back button handling
                BackHandler(enabled = uiState.currentScreen != AppScreen.HUB) {
                    when (uiState.currentScreen) {
                        AppScreen.FILES -> viewModel.setScreen(AppScreen.HUB)
                        AppScreen.ORGANIZER -> viewModel.setScreen(AppScreen.VIEWER)
                        AppScreen.VIEWER -> viewModel.setScreen(AppScreen.HUB)
                        AppScreen.TOOLS -> viewModel.setScreen(AppScreen.HUB)
                        AppScreen.SETTINGS -> viewModel.setScreen(AppScreen.HUB)
                        AppScreen.HUB -> { /* Allow default back */ }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        PdfCraftBottomNav(
                            currentScreen = uiState.currentScreen,
                            onNavigate = { screen -> viewModel.setScreen(screen) },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (uiState.currentScreen) {
                            AppScreen.HUB -> {
                                DocumentHubScreen(
                                    recentDocuments = recentDocs,
                                    localFiles = localFiles,
                                    activeDocumentName = uiState.metadata?.fileName,
                                    activeDocumentPages = uiState.pages.size,
                                    homeSearchQuery = uiState.homeSearchQuery,
                                    onHomeSearchQueryChange = { q -> viewModel.setHomeSearchQuery(q) },
                                    selectedHomeTab = uiState.selectedHomeTab,
                                    onSelectHomeTab = { tab -> viewModel.setSelectedHomeTab(tab) },
                                    onToggleStar = { uriString -> viewModel.toggleStarDocument(uriString) },
                                    onOpenUri = { uri ->
                                        viewModel.loadDocument(uri)
                                        viewModel.setScreen(AppScreen.VIEWER)
                                    },
                                    onOpenAndSearchOcr = { initialQuery ->
                                        viewModel.setScreen(AppScreen.VIEWER)
                                        viewModel.openOcrSearch(initialQuery)
                                    },
                                    onOpenAndEditPdf = { optionalUri ->
                                        if (optionalUri != null && optionalUri != uiState.currentUri) {
                                            viewModel.loadDocument(optionalUri)
                                        }
                                        viewModel.setScreen(AppScreen.VIEWER)
                                        viewModel.togglePencilEditMode(true)
                                    },
                                    onLoadSample = {
                                        viewModel.loadSampleDocument()
                                        viewModel.setScreen(AppScreen.VIEWER)
                                    },
                                    onNavigateScreen = { screen -> viewModel.setScreen(screen) },
                                    onNavigateTool = { tool -> viewModel.setActiveTool(tool) },
                                    onDeleteRecent = { uriString ->
                                        scope.launch { viewModel.deleteRecentDocument(uriString) }
                                    }
                                )
                            }

                            AppScreen.FILES -> {
                                com.example.ui.screens.LocalPdfListScreen(
                                    files = localFiles,
                                    onOpenFile = { uri ->
                                        viewModel.loadDocument(uri)
                                        viewModel.setScreen(AppScreen.VIEWER)
                                    },
                                    onOrganizeFile = { uri ->
                                        viewModel.loadDocument(uri)
                                        viewModel.setScreen(AppScreen.ORGANIZER)
                                    },
                                    onDeleteFile = { item ->
                                        viewModel.deleteLocalFileItem(item)
                                    },
                                    onRefresh = {
                                        viewModel.refreshLocalFiles()
                                    },
                                    onImportPdf = { uri ->
                                        viewModel.importPdfFromUri(uri)
                                        viewModel.setScreen(AppScreen.VIEWER)
                                    },
                                    onConvertPhotos = { uris ->
                                        viewModel.setSelectedImages(uris)
                                        viewModel.setActiveTool(com.example.data.model.ToolType.IMAGES_TO_PDF)
                                        viewModel.setScreen(AppScreen.TOOLS)
                                    },
                                    onNavigateScreen = { screen -> viewModel.setScreen(screen) }
                                )
                            }

                            AppScreen.VIEWER -> {
                                PdfViewerScreen(
                                    metadata = uiState.metadata,
                                    pages = uiState.pages,
                                    currentPageIndex = uiState.currentPageIndex,
                                    highResBitmap = uiState.highResBitmap,
                                    isLoading = uiState.isLoading,
                                    isNightMode = uiState.isNightMode,
                                    onToggleNightMode = { viewModel.toggleNightMode() },
                                    onOpenCrop = { viewModel.openCropDialog(uiState.currentPageIndex) },
                                    onQuickRotatePage = { viewModel.quickRotateCurrentPage(90) },
                                    onPageSelected = { idx -> viewModel.setCurrentPage(idx) },
                                    onNextPage = { viewModel.nextPage() },
                                    onPrevPage = { viewModel.previousPage() },
                                    onNavigateScreen = { screen -> viewModel.setScreen(screen) },
                                    onShare = {
                                        viewModel.exportToShare { shareUri ->
                                            sharePdf(shareUri)
                                        }
                                    },
                                    onSaveMetadata = { title, author, subject, keywords ->
                                        viewModel.saveDocumentMetadata(title, author, subject, keywords) {}
                                    },
                                    // ML Kit Full-Document OCR Search
                                    isOcrSearchOpen = uiState.isOcrSearchOpen,
                                    ocrSearchQuery = uiState.ocrSearchQuery,
                                    ocrSearchMatches = uiState.ocrSearchMatches,
                                    activeMatchIndex = uiState.activeMatchIndex,
                                    isOcrIndexing = uiState.isOcrIndexing,
                                    ocrIndexedPagesCount = uiState.ocrIndexedPagesCount,
                                    onOpenOcrSearch = { viewModel.openOcrSearch() },
                                    onCloseOcrSearch = { viewModel.closeOcrSearch() },
                                    onOcrSearchQueryChange = { q -> viewModel.updateOcrSearchQuery(q) },
                                    onNextOcrMatch = { viewModel.nextOcrMatch() },
                                    onPrevOcrMatch = { viewModel.prevOcrMatch() },
                                    onSelectOcrMatch = { idx -> viewModel.selectOcrMatch(idx) },
                                    // Interactive Pencil Edit Studio
                                    isPencilEditMode = uiState.isPencilEditMode,
                                    currentPageOcrBlocks = uiState.pageOcrBlocks[uiState.currentPageIndex].orEmpty(),
                                    selectedEditBlock = uiState.selectedEditBlock,
                                    pendingTextEdits = uiState.pendingTextEdits,
                                    onTogglePencilEditMode = { viewModel.togglePencilEditMode() },
                                    onSelectBlockForEdit = { block -> viewModel.selectTextBlockForEdit(block) },
                                    onStageTextEdit = { op -> viewModel.saveOrUpdateTextEdit(op) },
                                    onRemoveTextEdit = { editId -> viewModel.removePendingTextEdit(editId) },
                                    onCommitTextEdits = { viewModel.commitPendingTextEdits() },
                                    ocrResult = uiState.ocrResult,
                                    isOcrRunning = uiState.isOcrRunning,
                                    showOcrDialog = uiState.showOcrDialog,
                                    onRunOcr = { viewModel.runOcrOnCurrentPage() },
                                    onCloseOcr = { viewModel.closeOcrDialog() },
                                    onOpenSettings = { viewModel.setScreen(AppScreen.SETTINGS) },
                                    smoothTransitions = uiState.smoothTransitionsEnabled,
                                    hapticsEnabled = uiState.hapticFeedbackEnabled
                                )
                            }

                            AppScreen.ORGANIZER -> {
                                PageOrganizerScreen(
                                    pages = uiState.pages,
                                    documentTitle = uiState.metadata?.fileName ?: "Document.pdf",
                                    onRotatePage = { id, deg -> viewModel.rotatePage(id, deg) },
                                    onRotateSelected = { deg -> viewModel.rotateSelectedOrAll(deg) },
                                    onToggleSelect = { id -> viewModel.togglePageSelection(id) },
                                    onSelectAll = { select -> viewModel.selectAllPages(select) },
                                    onMoveLeft = { idx -> viewModel.movePageLeft(idx) },
                                    onMoveRight = { idx -> viewModel.movePageRight(idx) },
                                    onDeletePage = { id -> viewModel.deletePage(id) },
                                    onDeleteSelected = { viewModel.deleteSelectedPages() },
                                    onDuplicatePage = { id -> viewModel.duplicatePage(id) },
                                    onResetOrder = { viewModel.resetPageOrder() },
                                    onSaveReorganized = { targetUri ->
                                        viewModel.saveReorganizedPdf(targetUri) { success ->
                                            if (success) viewModel.setScreen(AppScreen.VIEWER)
                                        }
                                    },
                                    onShare = {
                                        viewModel.exportToShare { shareUri ->
                                            sharePdf(shareUri)
                                        }
                                    },
                                    onCropPage = { pageIdx ->
                                        viewModel.openCropDialog(pageIdx)
                                    }
                                )
                            }

                            AppScreen.TOOLS -> {
                                PdfToolsHubScreen(
                                    activeTool = uiState.activeTool,
                                    onSelectTool = { tool -> viewModel.setActiveTool(tool) },
                                    currentDocName = uiState.metadata?.fileName ?: "Document.pdf",
                                    currentDocPages = uiState.pages.size,
                                    compressionPreset = uiState.compressionPreset,
                                    onCompressionPresetChange = { preset -> viewModel.setCompressionPreset(preset) },
                                    onExecuteCompress = { preset, targetUri ->
                                        viewModel.executeCompress(preset, targetUri) { success ->
                                            if (success) {
                                                viewModel.loadDocument(targetUri)
                                                viewModel.setScreen(AppScreen.VIEWER)
                                            }
                                        }
                                    },
                                    onOpenCropTool = { viewModel.openCropDialog(uiState.currentPageIndex) },
                                    mergeQueue = uiState.mergeQueue,
                                    onAddMergeFiles = { uris -> viewModel.addFilesToMergeQueue(uris) },
                                    onRemoveMergeItem = { idx -> viewModel.removeMergeItem(idx) },
                                    onMoveMergeUp = { idx -> viewModel.moveMergeItemUp(idx) },
                                    onMoveMergeDown = { idx -> viewModel.moveMergeItemDown(idx) },
                                    onExecuteMerge = { targetUri ->
                                        viewModel.executeMerge(targetUri) { success ->
                                            if (success) {
                                                viewModel.loadDocument(targetUri)
                                                viewModel.setScreen(AppScreen.VIEWER)
                                            }
                                        }
                                    },
                                    splitRangeInput = uiState.splitRangeInput,
                                    onSplitRangeChange = { range -> viewModel.setSplitRangeInput(range) },
                                    onExecuteSplit = { targetUri ->
                                        viewModel.executeSplit(targetUri) { success ->
                                            if (success) {
                                                viewModel.loadDocument(targetUri)
                                                viewModel.setScreen(AppScreen.VIEWER)
                                            }
                                        }
                                    },
                                    selectedImageUris = uiState.selectedImageUris,
                                    onSelectImages = { uris -> viewModel.setSelectedImages(uris) },
                                    onExecuteImagesToPdf = { targetUri ->
                                        viewModel.executeImagesToPdf(targetUri) { success ->
                                            if (success) {
                                                viewModel.loadDocument(targetUri)
                                                viewModel.setScreen(AppScreen.VIEWER)
                                            }
                                        }
                                    },
                                    onExecutePdfToImages = { callback ->
                                        viewModel.executePdfToImages(callback)
                                    },
                                    watermarkConfig = uiState.watermarkConfig,
                                    onWatermarkTextChange = { txt -> viewModel.setWatermarkText(txt) },
                                    onWatermarkOpacityChange = { op -> viewModel.setWatermarkOpacity(op) },
                                    onWatermarkDiagonalChange = { diag -> viewModel.setWatermarkDiagonal(diag) },
                                    onWatermarkPageNumbersChange = { num -> viewModel.setWatermarkPageNumbers(num) },
                                    onWatermarkTypeChange = { type -> viewModel.setWatermarkType(type) },
                                    onWatermarkImageUriChange = { uri -> viewModel.setWatermarkImageUri(uri) },
                                    onWatermarkBuiltInStampChange = { stamp -> viewModel.setWatermarkBuiltInStamp(stamp) },
                                    onWatermarkFontChange = { font -> viewModel.setWatermarkFont(font) },
                                    onWatermarkRotationChange = { rot -> viewModel.setWatermarkRotation(rot) },
                                    onWatermarkScaleChange = { scale -> viewModel.setWatermarkScale(scale) },
                                    onWatermarkPlacementChange = { pos -> viewModel.setWatermarkPlacement(pos) },
                                    onWatermarkCustomOffsetChange = { x, y -> viewModel.setWatermarkCustomOffset(x, y) },
                                    onWatermarkColorChange = { color -> viewModel.setWatermarkColor(color) },
                                    onApplyWatermarkActive = { viewModel.applyWatermarkToActiveDocument() },
                                    onExecuteWatermark = { targetUri ->
                                        viewModel.executeApplyWatermark(targetUri) { success ->
                                            if (success) {
                                                viewModel.loadDocument(targetUri)
                                                viewModel.setScreen(AppScreen.VIEWER)
                                            }
                                        }
                                    },
                                    encryptionConfig = uiState.encryptionConfig,
                                    onEncryptionModeChange = { mode -> viewModel.setEncryptionMode(mode) },
                                    onEncryptUserPasswordChange = { pwd -> viewModel.setEncryptUserPassword(pwd) },
                                    onEncryptConfirmPasswordChange = { pwd -> viewModel.setEncryptConfirmPassword(pwd) },
                                    onEncryptOwnerPasswordChange = { pwd -> viewModel.setEncryptOwnerPassword(pwd) },
                                    onEncryptKeyStrengthChange = { str -> viewModel.setEncryptKeyStrength(str) },
                                    onEncryptAllowPrintingChange = { allow -> viewModel.setEncryptAllowPrinting(allow) },
                                    onEncryptAllowCopyingChange = { allow -> viewModel.setEncryptAllowCopying(allow) },
                                    onEncryptAllowModifyingChange = { allow -> viewModel.setEncryptAllowModifying(allow) },
                                    onUnlockPasswordChange = { pwd -> viewModel.setUnlockPassword(pwd) },
                                    onGenerateStrongPassword = { viewModel.generateStrongPassword() },
                                    onEncryptActivePdf = { viewModel.encryptActiveDocument() },
                                    onExecuteEncrypt = { targetUri ->
                                        viewModel.executeEncryptPdf(targetUri) { }
                                    },
                                    onUnlockActivePdf = { viewModel.unlockActiveDocument() },
                                    onExecuteDecrypt = { targetUri ->
                                        viewModel.executeDecryptPdf(targetUri) { success ->
                                            if (success) {
                                                viewModel.loadDocument(targetUri)
                                                viewModel.setScreen(AppScreen.VIEWER)
                                            }
                                        }
                                    },
                                    onShareProtectedFile = { filePath ->
                                        try {
                                            val file = File(filePath)
                                            if (file.exists()) {
                                                val shareUri = FileProvider.getUriForFile(
                                                    this@MainActivity,
                                                    "${applicationContext.packageName}.fileprovider",
                                                    file
                                                )
                                                sharePdf(shareUri)
                                            }
                                        } catch (ignored: Exception) {}
                                    }
                                )
                            }

                            AppScreen.SETTINGS -> {
                                SettingsScreen(
                                    cloudSettings = uiState.cloudSettings,
                                    smoothTransitions = uiState.smoothTransitionsEnabled,
                                    hapticsEnabled = uiState.hapticFeedbackEnabled,
                                    keepScreenAwake = uiState.keepScreenAwake,
                                    currentDocName = uiState.metadata?.fileName,
                                    onLinkGoogleDrive = { email -> viewModel.linkGoogleDrive(email) },
                                    onDisconnectGoogleDrive = { viewModel.disconnectGoogleDrive() },
                                    onSetCloudFolder = { folder -> viewModel.setCloudTargetFolder(folder) },
                                    onToggleAutoBackup = { auto -> viewModel.toggleAutoBackup(auto) },
                                    onToggleWifiOnly = { wifi -> viewModel.toggleWifiOnly(wifi) },
                                    onBackupCurrentDoc = {
                                        viewModel.backupCurrentDocumentToGoogleDrive { }
                                    },
                                    onSyncAllDocs = {
                                        viewModel.syncAllDocumentsToGoogleDrive { }
                                    },
                                    onToggleSmoothTransitions = { viewModel.toggleSmoothTransitions() },
                                    onToggleHaptics = { viewModel.toggleHaptics() },
                                    onToggleKeepScreenAwake = { viewModel.toggleKeepScreenAwake() },
                                    onClearThumbnailCache = { viewModel.clearThumbnailCache() },
                                    onNavigateBack = { viewModel.setScreen(AppScreen.HUB) }
                                )
                            }
                        }

                        // Interactive Crop Dialog Modal
                        if (uiState.showCropDialog) {
                            val cropTargetItem = uiState.pages.getOrNull(uiState.cropTargetPageIndex)
                            val cropBmp = if (uiState.cropTargetPageIndex == uiState.currentPageIndex && uiState.highResBitmap != null) {
                                uiState.highResBitmap
                            } else {
                                cropTargetItem?.thumbnail
                            }
                            PageCropDialog(
                                pageBitmap = cropBmp,
                                currentPageIndex = uiState.cropTargetPageIndex,
                                totalPages = uiState.pages.size,
                                onDismiss = { viewModel.closeCropDialog() },
                                onConfirmCrop = { cropRect, applyToAll ->
                                    pendingCropConfig = Pair(cropRect, applyToAll)
                                    val baseName = uiState.metadata?.fileName?.removeSuffix(".pdf") ?: "Document"
                                    cropSaveLauncher.launch("${baseName}_cropped.pdf")
                                }
                            )
                        }

                        // Password-Protected PDF Unlock Prompt Dialog
                        if (uiState.pendingLockedUri != null) {
                            UnlockProtectedPdfDialog(
                                fileName = uiState.pendingLockedFileName.ifBlank { "Protected_Document.pdf" },
                                errorMessage = uiState.unlockDialogError,
                                onUnlock = { password -> viewModel.unlockAndOpenPendingPdf(password) },
                                onDismiss = { viewModel.dismissUnlockDialog() }
                            )
                        }

                        // Modal Loading Progress
                        if (uiState.isLoading) {
                            LoadingProgressDialog(message = uiState.progressMessage ?: "Processing document...")
                        }
                    }
                }
            }
        }
    }

    private fun sharePdf(contentUri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Document with PdfCraft"))
    }
}
