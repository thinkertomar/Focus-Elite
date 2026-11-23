package com.focusguardpro.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BlockerAccessibilityService? = null

        private val DIRECT_URL_VIEW_IDS = listOf(
            "url_bar",
            "url_bar_title",
            "location_bar_edit_text",
            "location_bar",
            "omnibox_url_text",
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_origin_view",
            "search_box_text",
            "editUrl",
            "bro_common_omnibox_edit_text",
            "address_bar_edit_text",
            "address_bar",
            "addressbar",
            "url_field",
            "url_edit_text",
            "custom_tab_toolbar",
            "custom_tab_url_bar",
            "toolbar_url",
            "url_text",
            "sbrowser_url_bar"
        )

        // Supports multi-level subdomains (e.g. aistudio.google.com, en.m.wikipedia.org, pw.live)
        private val DOMAIN_REGEX = Regex("""(?:https?://)?([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+)(?:[/?#:]|\s|$)""")

        private val NON_DOMAIN_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp",
            "mp4", "mp3", "wav", "m4a", "mkv", "webm", "avi",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml",
            "zip", "rar", "7z", "tar", "gz", "apk", "aab", "exe", "dmg", "iso", "bin"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var watchdogThread: android.os.HandlerThread? = null
    @Volatile
    private var watchdogHandler: Handler? = null

    private var lastBlockedAppPkg: String? = null
    private var lastBlockedAppAtMs: Long = 0L
    private var lastBlockedHost: String? = null
    private var lastBlockedAtMs: Long = 0L
    private var lastContentEvalAtMs: Long = 0L
    private var lastContentEvalPkg: String? = null
    private var lastBrowserEvalAtMs: Long = 0L

    // Tracks recently active packages from AccessibilityEvents (for active MIUI/HyperOS floating windows)
    private val recentEventPackages = ConcurrentHashMap<String, Long>()
    // Tracks recently evicted blocked packages so trailing close/animation events do not re-trigger blocking
    private val recentlyEvictedPackages = ConcurrentHashMap<String, Long>()

    // Native Accessibility Overlay shield (renders above floating windows & fullscreen apps; immune to MIUI BAL blocks)
    private var lockOverlayView: View? = null
    private var lockOverlayBlockedPkg: String? = null
    private var lockOverlayShownAtMs: Long = 0L

    private val autoDismissOverlayRunnable = Runnable {
        try {
            val blockedPkg = lockOverlayBlockedPkg
            if (blockedPkg == null) {
                dismissAccessibilityOverlayShield()
                return@Runnable
            }
            val activePkg = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (_: Throwable) {
                null
            }
            val liveVisible = getAllLiveVisiblePackages()
            if (activePkg != blockedPkg && !liveVisible.contains(blockedPkg)) {
                dismissAccessibilityOverlayShield()
            }
        } catch (_: Throwable) {
            dismissAccessibilityOverlayShield()
        }
    }

    /**
     * Continuous 1-second live window watchdog running on a dedicated background HandlerThread.
     * Guarantees that if an app (e.g. ChatGPT in a floating window or fullscreen) crosses its
     * 30-minute daily cap while already open on screen, it is immediately locked without stalling the UI thread.
     */
    private val liveWatchdogRunnable = object : Runnable {
        override fun run() {
            try {
                runLiveWindowCheck()
            } catch (_: Throwable) {}
            watchdogHandler?.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SettingsTamperGuard.recordAccessibilityJustConnected(this)
        SettingsTamperGuard.recordVerifiedHeartbeat(this)
        PaymentSafeModeManager.onAccessibilityReconnected(this)
        ensureWatchdogRunning()

        try {
            WatchdogService.start(this)
            FocusForegroundMonitorService.start(this)
            FocusMonitorWorker.schedule(this)
        } catch (_: Throwable) {}
    }

    private fun ensureWatchdogRunning() {
        val currentThread = watchdogThread
        if (currentThread == null || !currentThread.isAlive) {
            val ht = android.os.HandlerThread("BlockerWatchdogThread").apply { start() }
            watchdogThread = ht
            val handler = Handler(ht.looper)
            watchdogHandler = handler
            handler.post(liveWatchdogRunnable)
        }
    }

    override fun onDestroy() {
        dismissAccessibilityOverlayShield()
        watchdogHandler?.removeCallbacks(liveWatchdogRunnable)
        watchdogThread?.quitSafely()
        watchdogThread = null
        watchdogHandler = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun isSelfPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg == packageName ||
            pkg == "com.focuselite.app" ||
            pkg == "com.focusguardpro.elite" ||
            pkg == "com.focusguardpro.app"
    }

    private fun isIgnoredWindowOverlayPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        return pkg == "com.android.systemui" ||
            pkg == "com.miui.freeform" ||
            pkg == "com.miui.contentextension" ||
            pkg == "com.miui.touchassistant" ||
            SystemExclusions.isInputMethod(this, pkg)
    }

    private fun isRecentlyEvicted(pkg: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val evictedAt = recentlyEvictedPackages[pkg] ?: return false
        if (nowMs - evictedAt <= 2500L) {
            return true
        }
        recentlyEvictedPackages.remove(pkg)
        return false
    }

    /**
     * Returns ALL currently visible application packages across fullscreen, split-screen,
     * and MIUI/HyperOS floating (freeform) windows, while filtering out closing fullscreen
     * windows and recently evicted apps after returning to Home/Settings/Focus Elite.
     */
    fun getAllLiveVisiblePackages(): List<String> {
        val result = LinkedHashSet<String>()
        val now = System.currentTimeMillis()

        val activeRootPkg = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }

        if (!activeRootPkg.isNullOrBlank() && !isIgnoredWindowOverlayPackage(activeRootPkg)) {
            // If the user is on Home Launcher, Focus Elite, or Settings, clear any stale event cache
            if (isSelfPackage(activeRootPkg) ||
                SystemExclusions.isExcludedPackage(this, activeRootPkg) ||
                SettingsTamperGuard.isSettingsOrInstallerPackage(activeRootPkg)
            ) {
                recentEventPackages.clear()
            }
            if (!isRecentlyEvicted(activeRootPkg, now)) {
                result.add(activeRootPkg)
            }
        }

        val isSafeForegroundActive = !activeRootPkg.isNullOrBlank() && (
            isSelfPackage(activeRootPkg) ||
                SystemExclusions.isExcludedPackage(this, activeRootPkg) ||
                SettingsTamperGuard.isSettingsOrInstallerPackage(activeRootPkg)
            )

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val rect = android.graphics.Rect()

        try {
            val winList = windows
            if (winList != null) {
                for (win in winList) {
                    if (win.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                        win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        win.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    ) {
                        continue
                    }
                    val pkg = try {
                        win.root?.packageName?.toString()
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (pkg.isBlank() || isIgnoredWindowOverlayPackage(pkg)) continue
                    if (isRecentlyEvicted(pkg, now)) continue

                    if (pkg != activeRootPkg && isSafeForegroundActive) {
                        // When Home Launcher, Focus Elite, or Settings is the active window,
                        // only include another app window if it is a genuine floating / split-screen
                        // window (smaller than fullscreen) and NOT an animating-out fullscreen window.
                        try {
                            win.getBoundsInScreen(rect)
                            val w = rect.width()
                            val h = rect.height()
                            val isFloatingOrSplit = w in 80..(screenW * 92 / 100) ||
                                h in 80..(screenH * 85 / 100)
                            if (!isFloatingOrSplit) {
                                continue
                            }
                        } catch (_: Throwable) {
                            continue
                        }
                    }

                    result.add(pkg)
                }
            }
        } catch (_: Throwable) {}

        // Only include recent event packages if we are NOT on Home Launcher / Focus Elite / Settings
        if (!isSafeForegroundActive) {
            val iterator = recentEventPackages.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value <= 2_000L && !isRecentlyEvicted(entry.key, now)) {
                    if (!isIgnoredWindowOverlayPackage(entry.key) && !SystemExclusions.isExcludedPackage(this, entry.key)) {
                        result.add(entry.key)
                    }
                } else {
                    iterator.remove()
                }
            }
        }

        return result.toList()
    }

    /**
     * Returns the primary live foreground window package from the Accessibility window manager,
     * prioritizing any currently visible window that is subject to blocking if multiple windows are open.
     */
    fun getLiveForegroundPackage(): String? {
        val allVisible = getAllLiveVisiblePackages()
        if (allVisible.isEmpty()) return null
        if (allVisible.size == 1) return allVisible[0]

        val now = FocusLayerManager.verifiedNowMs(this)
        for (pkg in allVisible) {
            if (!isSelfPackage(pkg) &&
                !SystemExclusions.isExcludedPackage(this, pkg) &&
                !BlockPolicyEngine.isBrowserPackage(pkg)
            ) {
                val decision = try {
                    BlockPolicyEngine.evaluateApp(this, pkg, now)
                } catch (_: Throwable) {
                    BlockDecision.Allowed
                }
                if (decision is BlockDecision.Blocked) {
                    return pkg
                }
            }
        }
        return allVisible.firstOrNull { !SystemExclusions.isExcludedPackage(this, it) } ?: allVisible[0]
    }

    private fun runLiveWindowCheck() {
        val shieldPrefs = getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        val shieldActive = shieldPrefs.getBoolean("shield_active", true)
        if (!shieldActive) {
            dismissAccessibilityOverlayShield()
        }
        if (AppLifecycleCoordinator.isRestoreInProgress) return

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            dismissAccessibilityOverlayShield()
            return
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            dismissAccessibilityOverlayShield()
            return
        }

        val visiblePackages = getAllLiveVisiblePackages()
        if (visiblePackages.isEmpty()) {
            // If no blocked window is visible and overlay has been shown for >= 1.5s, dismiss it
            if (lockOverlayView != null && (System.currentTimeMillis() - lockOverlayShownAtMs) >= 1500L) {
                dismissAccessibilityOverlayShield()
            }
            return
        }

        val primaryPkg = visiblePackages.first()
        ForegroundTracker.currentPackage = primaryPkg
        val now = FocusLayerManager.verifiedNowMs(this)

        // If user is currently inside Focus Elite or Settings and the blocked package is no longer visible,
        // immediately clear the lock overlay so Focus Elite and Settings are never obstructed.
        if (lockOverlayView != null) {
            val blockedPkg = lockOverlayBlockedPkg
            val blockedStillVisible = blockedPkg != null && visiblePackages.contains(blockedPkg)
            if (!blockedStillVisible) {
                val elapsedOverlay = System.currentTimeMillis() - lockOverlayShownAtMs
                if (isSelfPackage(primaryPkg) ||
                    SettingsTamperGuard.isSettingsOrInstallerPackage(primaryPkg) ||
                    elapsedOverlay >= 1500L
                ) {
                    dismissAccessibilityOverlayShield()
                }
            }
        }

        var anyBlockedVisible = false

        // Inspect EVERY visible window (fullscreen + floating window + split-screen)
        for (livePkg in visiblePackages) {
            if (isSelfPackage(livePkg) || SystemExclusions.isInputMethod(this, livePkg)) {
                continue
            }

            if (SettingsTamperGuard.isSettingsOrInstallerPackage(livePkg)) {
                val rootNode = try {
                    rootInActiveWindow
                } catch (_: Exception) {
                    null
                }
                if (SettingsTamperGuard.inspectAndBlockSettingsTamper(this, livePkg, rootNode)) {
                    return
                }
            }

            // Payment & Banking Safe Auto-Yield MUST run BEFORE SystemExclusions
            // (because BHIM, GPay, PhonePe & Paytm are in SystemExclusions to prevent blocking)
            if (PaymentSafeModeManager.isAutoYieldEnabled(this) &&
                PaymentSafeModeManager.isPaymentOrBankingPackage(this, livePkg)
            ) {
                PaymentSafeModeManager.enterPaymentSafeMode(this, triggeringPackage = livePkg, autoTriggered = true)
                return
            }

            if (SystemExclusions.isExcludedPackage(this, livePkg)) {
                continue
            }

            if (BlockPolicyEngine.isBrowserPackage(livePkg)) {
                // First check if the browser package itself is blocked at the app level (only when shield is active)
                if (shieldActive) {
                    val browserAppDecision = BlockPolicyEngine.evaluateApp(this, livePkg, now)
                    if (browserAppDecision is BlockDecision.Blocked) {
                        anyBlockedVisible = true
                        if (livePkg != lastBlockedAppPkg || (now - lastBlockedAppAtMs) >= 1200L) {
                            lastBlockedAppPkg = livePkg
                            lastBlockedAppAtMs = now
                            enforceBlockNow(
                                target = browserAppDecision.target,
                                reasonWireKey = browserAppDecision.reason.wireKey,
                                isWebsite = false,
                                blockedPackageName = livePkg
                            )
                        }
                        return
                    }
                }
                // Always read & record visited websites (24/7 — both in Focus Hours and Free Time);
                // enforce blocking only when shieldActive is true (adult protection remains always-on inside checkBrowserUrl).
                checkBrowserUrl(livePkg, forceSample = true, allowBlocking = shieldActive)
                continue
            }

            if (!shieldActive) continue

            val decision = BlockPolicyEngine.evaluateApp(this, livePkg, now)
            if (decision is BlockDecision.Blocked) {
                anyBlockedVisible = true
                if (livePkg != lastBlockedAppPkg || (now - lastBlockedAppAtMs) >= 1200L) {
                    lastBlockedAppPkg = livePkg
                    lastBlockedAppAtMs = now
                    enforceBlockNow(
                        target = decision.target,
                        reasonWireKey = decision.reason.wireKey,
                        isWebsite = false,
                        blockedPackageName = livePkg
                    )
                }
                return
            }
        }

        // Auto-dismiss accessibility lock overlay once the blocked app/floating window is no longer visible
        if (!anyBlockedVisible && lockOverlayView != null && (System.currentTimeMillis() - lockOverlayShownAtMs) >= 1500L) {
            dismissAccessibilityOverlayShield()
        }
    }

    /**
     * Executes immediate foreground & floating-window eviction plus a native TYPE_ACCESSIBILITY_OVERLAY
     * lock shield. Evicts the distracting app to Home and automatically dismisses the overlay once
     * the distracting app has exited so the popup never gets stuck over Home, Settings, or Focus Elite.
     */
    fun enforceBlockNow(
        target: String,
        reasonWireKey: String,
        isWebsite: Boolean = false,
        blockedPackageName: String = target
    ) {
        val nowSys = System.currentTimeMillis()
        recentEventPackages.remove(blockedPackageName)
        recentlyEvictedPackages[blockedPackageName] = nowSys
        if (ForegroundTracker.currentPackage == blockedPackageName) {
            ForegroundTracker.currentPackage = null
            ForegroundTracker.currentPackageSince = 0L
        }

        mainHandler.post {
            try {
                if (isWebsite) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    showAccessibilityOverlayShield(target, reasonWireKey, isWebsite = true, blockedPackageName = blockedPackageName)
                    return@post
                }

                // 1. Progressive Enhancement: If Porter/Shizuku is active, force-stop the distracting app immediately
                if (PorterBridge.isEnhancedModeActive()) {
                    try {
                        PorterBridge.forceStopPackage(blockedPackageName)
                    } catch (_: Exception) {}
                }

                // 2. Check if user is simultaneously studying in another allowed window (e.g. SciAstra fullscreen + ChatGPT floating)
                val visibleNow = getAllLiveVisiblePackages()
                val hasAllowedStudyCompanion = visibleNow.any { pkg ->
                    pkg != blockedPackageName &&
                        !isSelfPackage(pkg) &&
                        !SystemExclusions.isExcludedPackage(this@BlockerAccessibilityService, pkg) &&
                        (AppCategoryPrefs.getStudyApps(this@BlockerAccessibilityService).contains(pkg) ||
                            AppCategoryPrefs.getImprovementApps(this@BlockerAccessibilityService).contains(pkg))
                }

                // 3. Immediately display native TYPE_ACCESSIBILITY_OVERLAY shield above fullscreen & floating windows
                showAccessibilityOverlayShield(target, reasonWireKey, isWebsite = false, blockedPackageName = blockedPackageName)

                // 4. Immediately send BACK to collapse/close the floating window or active screen;
                // if no study app is open underneath, also send HOME immediately so fullscreen blocked apps exit at once.
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (!hasAllowedStudyCompanion) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }

                // 5. Follow-up check after 280ms: if the blocked package is still the active window, send HOME once more
                mainHandler.postDelayed({
                    try {
                        val activeRoot = try {
                            rootInActiveWindow?.packageName?.toString()
                        } catch (_: Throwable) {
                            null
                        }
                        if (activeRoot == blockedPackageName) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            if (!hasAllowedStudyCompanion) {
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }
                        }
                    } catch (_: Exception) {}
                }, 280L)
            } catch (_: Exception) {}
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    /**
     * Renders an instant native Accessibility Service overlay (`TYPE_ACCESSIBILITY_OVERLAY`).
     * Uses `FLAG_NOT_FOCUSABLE` so it never blinds `rootInActiveWindow` or traps navigation,
     * and automatically dismisses itself once the blocked app is exited.
     */
    private fun showAccessibilityOverlayShield(
        target: String,
        reasonWireKey: String,
        isWebsite: Boolean,
        blockedPackageName: String
    ) {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            mainHandler.removeCallbacks(autoDismissOverlayRunnable)

            val existingView = lockOverlayView
            if (existingView != null) {
                lockOverlayView = null
                lockOverlayBlockedPkg = null
                try {
                    wm.removeViewImmediate(existingView)
                } catch (_: Throwable) {}
            }

            val appLabel = if (isWebsite) {
                target
            } else {
                try {
                    val ai = packageManager.getApplicationInfo(blockedPackageName, 0)
                    packageManager.getApplicationLabel(ai).toString()
                } catch (_: Throwable) {
                    blockedPackageName
                }
            }

            val usedMins = if (!isWebsite) {
                try {
                    (UsageStatsHelper.getTodayScreenTimeForPackage(this, blockedPackageName) / 60_000L).toInt()
                } catch (_: Throwable) {
                    0
                }
            } else {
                BlockPolicyEngine.getSharedFocusBrowserNonStudyMinutesToday(this).toInt()
            }
            val customLimit = TimeLimitManager.getLimitMinutes(this, blockedPackageName)
            val isIgnored = AppCategoryPrefs.getIgnoredApps(this).contains(blockedPackageName)
            val effectiveCap = when {
                isWebsite -> BlockPolicyEngine.getSharedBrowserLimitMinutes(this)
                isIgnored -> if (customLimit in 1..29) customLimit else 30
                customLimit > 0 -> customLimit
                else -> UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
            }

            val reasonSubtitle = when (reasonWireKey) {
                BlockReason.DAILY_LIMIT.wireKey -> {
                    when {
                        isWebsite ->
                            "Shared Focus-Hours browser limit reached across all browsers (${usedMins}m / ${effectiveCap}m).\nStudy websites stay open 24/7, and full browser access returns after Focus Hours."
                        isIgnored ->
                            "$appLabel is in your Ignored category (30m/day cap).\nToday's usage: ${usedMins}m / ${effectiveCap}m limit reached."
                        else ->
                            "Daily time limit reached for $appLabel (${usedMins}m / ${effectiveCap}m used today)."
                    }
                }
                BlockReason.FOCUS_LAYER_SCHEDULED.wireKey,
                BlockReason.FOCUS_LAYER_MANUAL.wireKey ->
                    "$appLabel is locked during active Focus Hours (${FocusLayerManager.formatWindowSummary(this)})."
                BlockReason.HARD_BLOCK.wireKey ->
                    "$appLabel is Hard-Blocked in your Focus Elite shield."
                BlockReason.BLACKOUT.wireKey ->
                    "$appLabel is locked during its scheduled Blackout Window."
                BlockReason.WEBSITE.wireKey ->
                    "Non-study website ($target) is blocked during Focus Hours. Study websites remain open 24/7."
                else ->
                    "$appLabel is currently restricted by Focus Elite (${usedMins}m / ${effectiveCap}m today)."
            }

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#E6090D16"))
                setPadding(dp(24f), dp(32f), dp(24f), dp(32f))
                isClickable = true
                // Tapping anywhere outside the card dismisses the popup immediately
                setOnClickListener {
                    recentEventPackages.remove(blockedPackageName)
                    recentlyEvictedPackages[blockedPackageName] = System.currentTimeMillis()
                    dismissAccessibilityOverlayShield()
                }
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(22f), dp(24f), dp(22f), dp(22f))
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(20f).toFloat()
                    setColor(Color.parseColor("#111827"))
                    setStroke(dp(1.5f), Color.parseColor("#EF4444"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val badgeView = TextView(this).apply {
                text = "🛡️ FOCUS ELITE SHIELD • ACCESS BLOCKED"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#F87171"))
                gravity = Gravity.CENTER
            }

            val titleView = TextView(this).apply {
                text = "$appLabel Locked"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, dp(8f))
            }

            val descView = TextView(this).apply {
                text = reasonSubtitle
                textSize = 14f
                setTextColor(Color.parseColor("#CBD5E1"))
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
                setPadding(0, 0, 0, dp(18f))
            }

            val closeWindowBtn = Button(this).apply {
                text = "Close & Return to Study / Home"
                isAllCaps = false
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.parseColor("#2563EB"))
                }
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10f)
                }
                setOnClickListener {
                    recentEventPackages.remove(blockedPackageName)
                    recentlyEvictedPackages[blockedPackageName] = System.currentTimeMillis()
                    dismissAccessibilityOverlayShield()
                    val activeRoot = try {
                        rootInActiveWindow?.packageName?.toString()
                    } catch (_: Throwable) {
                        null
                    }
                    if (activeRoot == blockedPackageName) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                }
            }

            val detailsBtn = Button(this).apply {
                text = "Open Lock Details / Emergency Options"
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.parseColor("#93C5FD"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1f), Color.parseColor("#3B82F6"))
                }
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    recentEventPackages.remove(blockedPackageName)
                    recentlyEvictedPackages[blockedPackageName] = System.currentTimeMillis()
                    dismissAccessibilityOverlayShield()
                    try {
                        val blockIntent = Intent(this@BlockerAccessibilityService, BlockActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("BLOCKED_PACKAGE", target)
                            putExtra("BLOCK_REASON", reasonWireKey)
                            putExtra("blocked_package", target)
                            putExtra("block_reason", reasonWireKey)
                        }
                        startActivity(blockIntent)
                    } catch (_: Exception) {}
                }
            }

            card.addView(badgeView)
            card.addView(titleView)
            card.addView(descView)
            card.addView(closeWindowBtn)
            card.addView(detailsBtn)
            root.addView(card)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            wm.addView(root, params)
            lockOverlayView = root
            lockOverlayBlockedPkg = blockedPackageName
            lockOverlayShownAtMs = System.currentTimeMillis()

            // Schedule automatic dismissal once the distracting app has been evicted to Home
            mainHandler.postDelayed(autoDismissOverlayRunnable, 2200L)
        } catch (_: Throwable) {}
    }

    fun dismissAccessibilityOverlayShield() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoDismissOverlayRunnable)
            val view = lockOverlayView ?: return@post
            lockOverlayView = null
            lockOverlayBlockedPkg = null
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeViewImmediate(view)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Called by [FocusForegroundMonitorService] every second when a browser is in the foreground
     * so non-study website usage is sampled and enforced even if the user is passively watching/reading.
     */
    fun evaluateActiveBrowserFromMonitor(browserPackage: String) {
        mainHandler.post {
            try {
                val shieldPrefs = getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
                val shieldActive = shieldPrefs.getBoolean("shield_active", true)
                checkBrowserUrl(browserPackage, forceSample = true, allowBlocking = shieldActive)
            } catch (_: Exception) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        instance = this
        ensureWatchdogRunning()

        val shieldPrefs = getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        val shieldActive = shieldPrefs.getBoolean("shield_active", true)
        if (AppLifecycleCoordinator.isRestoreInProgress) {
            return
        }

        // Never trigger blocking on keyboard typing or text selection events
        val eventType = event?.eventType ?: 0
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            return
        }

        val now = FocusLayerManager.verifiedNowMs(this)
        val rawEventPkg = event?.packageName?.toString()

        // FAST-PATH Payment & Banking Auto-Yield: runs on the very first AccessibilityEvent millisecond
        // BEFORE SystemExclusions (since BHIM/PhonePe/Paytm/GPay are in SystemExclusions).
        if (!rawEventPkg.isNullOrBlank() &&
            PaymentSafeModeManager.isAutoYieldEnabled(this) &&
            PaymentSafeModeManager.isPaymentOrBankingPackage(this, rawEventPkg)
        ) {
            PaymentSafeModeManager.enterPaymentSafeMode(this, triggeringPackage = rawEventPkg, autoTriggered = true)
            return
        }

        if (!rawEventPkg.isNullOrBlank() && !isIgnoredWindowOverlayPackage(rawEventPkg)) {
            if (isSelfPackage(rawEventPkg) ||
                SystemExclusions.isExcludedPackage(this, rawEventPkg) ||
                SettingsTamperGuard.isSettingsOrInstallerPackage(rawEventPkg)
            ) {
                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    recentEventPackages.clear()
                    // If the user switched to Focus Elite, Settings, or Home Launcher and the blocked app is gone,
                    // dismiss the lock overlay so it never stays stuck on screen.
                    if (lockOverlayView != null && rawEventPkg != lockOverlayBlockedPkg) {
                        if (isSelfPackage(rawEventPkg) ||
                            SettingsTamperGuard.isSettingsOrInstallerPackage(rawEventPkg) ||
                            (System.currentTimeMillis() - lockOverlayShownAtMs) >= 900L
                        ) {
                            dismissAccessibilityOverlayShield()
                        }
                    }
                }
            } else if (!isRecentlyEvicted(rawEventPkg)) {
                recentEventPackages[rawEventPkg] = System.currentTimeMillis()
            }
        }

        // Build candidate packages to inspect from currently visible windows
        val candidatePackages = LinkedHashSet<String>()
        for (visPkg in getAllLiveVisiblePackages()) {
            if (!isIgnoredWindowOverlayPackage(visPkg) && !isRecentlyEvicted(visPkg)) {
                candidatePackages.add(visPkg)
            }
        }
        if (!rawEventPkg.isNullOrBlank() &&
            !isIgnoredWindowOverlayPackage(rawEventPkg) &&
            !isRecentlyEvicted(rawEventPkg) &&
            (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)
        ) {
            candidatePackages.add(rawEventPkg)
        }

        if (candidatePackages.isEmpty()) return

        // Do not trigger app blocking while the device is locked (lockscreen/keyguard active)
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            return
        }

        for (activePkg in candidatePackages) {
            // 1. CRITICAL: Never block Focus Elite itself
            if (isSelfPackage(activePkg)) {
                ForegroundTracker.currentPackage = activePkg
                continue
            }

            // 2. CRITICAL: Never block any keyboard or input method editor
            if (SystemExclusions.isInputMethod(this, activePkg)) {
                continue
            }

            // 2.5 ZERO-ESCAPE FORTRESS: Surgical Android Settings & Uninstaller Tamper Guard
            if (SettingsTamperGuard.isSettingsOrInstallerPackage(activePkg)) {
                val rootNode = try {
                    rootInActiveWindow
                } catch (_: Exception) {
                    null
                }
                if (SettingsTamperGuard.inspectAndBlockSettingsTamper(this, activePkg, rootNode)) {
                    return
                }
            }

            // 2.9 Payment & Banking Auto-Yield MUST run BEFORE SystemExclusions
            if (PaymentSafeModeManager.isAutoYieldEnabled(this) &&
                PaymentSafeModeManager.isPaymentOrBankingPackage(this, activePkg)
            ) {
                PaymentSafeModeManager.enterPaymentSafeMode(this, triggeringPackage = activePkg, autoTriggered = true)
                return
            }

            // 3. System exclusions (SystemUI, Launcher, Phone Dialer, benign Settings screens)
            if (SystemExclusions.isExcludedPackage(this, activePkg)) {
                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && activePkg != "com.android.systemui") {
                    val prev = ForegroundTracker.currentPackage
                    if (prev != null && BlockPolicyEngine.isBrowserPackage(prev)) {
                        BlockPolicyEngine.clearBrowserWebsiteSample(this)
                    }
                    ForegroundTracker.currentPackage = activePkg
                    ForegroundTracker.currentPackageSince = System.currentTimeMillis()
                }
                continue
            }

            val isWindowTransition = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                activePkg != ForegroundTracker.currentPackage

            // 4. Track foreground package transitions
            if (activePkg != ForegroundTracker.currentPackage && !SystemExclusions.isInputMethod(this, activePkg)) {
                val previousPkg = ForegroundTracker.currentPackage
                ForegroundTracker.currentPackage = activePkg
                ForegroundTracker.currentPackageSince = now
                try {
                    AwayTimeTracker.resolveIfDue(this)
                } catch (_: Exception) {}

                if (previousPkg != null && BlockPolicyEngine.isBrowserPackage(previousPkg) && !BlockPolicyEngine.isBrowserPackage(activePkg)) {
                    try {
                        BlockPolicyEngine.clearBrowserWebsiteSample(this)
                    } catch (_: Exception) {}
                }
            }

            // 5. If active package is a Browser, ALWAYS track visited websites (24/7) and enforce shared Focus-Hours policy
            if (BlockPolicyEngine.isBrowserPackage(activePkg)) {
                if (isWindowTransition ||
                    eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    (now - lastBrowserEvalAtMs) >= 600L
                ) {
                    lastBrowserEvalAtMs = now
                    try {
                        val eventRootHint = if (rawEventPkg == activePkg) {
                            try { event?.source?.window?.root } catch (_: Throwable) { null }
                        } else {
                            null
                        }
                        checkBrowserUrl(
                            browserPackage = activePkg,
                            forceSample = false,
                            allowBlocking = shieldActive,
                            eventRootHint = eventRootHint
                        )
                    } catch (_: Exception) {}
                }
                continue
            }

            if (!shieldActive) continue

            // 6. Evaluate Non-Browser App Blocking Decision (Hard Block, Focus Layer, Blackout, 30m Daily Limit)
            val shouldEvaluateApp = isWindowTransition ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                activePkg != lastContentEvalPkg ||
                (now - lastContentEvalAtMs) >= 500L

            if (shouldEvaluateApp) {
                lastContentEvalPkg = activePkg
                lastContentEvalAtMs = now
                try {
                    val decision = BlockPolicyEngine.evaluateApp(this, activePkg, now)
                    if (decision is BlockDecision.Blocked) {
                        if (activePkg != lastBlockedAppPkg || (now - lastBlockedAppAtMs) >= 650L) {
                            lastBlockedAppPkg = activePkg
                            lastBlockedAppAtMs = now
                            enforceBlockNow(
                                target = decision.target,
                                reasonWireKey = decision.reason.wireKey,
                                isWebsite = false,
                                blockedPackageName = activePkg
                            )
                        }
                        return
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onInterrupt() {}

    private fun findBrowserWindowRoot(
        browserPackage: String,
        eventRootHint: AccessibilityNodeInfo? = null
    ): AccessibilityNodeInfo? {
        // 1. Check event source window root if it matches the browser package
        if (eventRootHint != null) {
            val hintPkg = try { eventRootHint.packageName?.toString() } catch (_: Throwable) { null }
            if (hintPkg == browserPackage) return eventRootHint
        }

        // 2. Check rootInActiveWindow ONLY if it actually belongs to the browser (avoids MIUI overlay/IME mismatch)
        val activeRoot = try { rootInActiveWindow } catch (_: Throwable) { null }
        val activePkg = try { activeRoot?.packageName?.toString() } catch (_: Throwable) { null }
        if (activeRoot != null && activePkg == browserPackage) {
            return activeRoot
        }

        // 3. Scan interactive windows to find the actual browser window root (handles floating/split-screen/MIUI)
        var bestRoot: AccessibilityNodeInfo? = null
        var bestArea = -1L
        val rect = android.graphics.Rect()
        try {
            val winList = windows
            if (winList != null) {
                for (win in winList) {
                    if (win.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                        win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        win.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    ) continue
                    val root = try { win.root } catch (_: Throwable) { null } ?: continue
                    val pkg = try { root.packageName?.toString() } catch (_: Throwable) { null }
                    if (pkg != browserPackage) continue
                    if (win.isActive || win.isFocused) {
                        return root
                    }
                    try {
                        win.getBoundsInScreen(rect)
                        val area = rect.width().toLong() * rect.height().toLong()
                        if (area > bestArea) {
                            bestArea = area
                            bestRoot = root
                        }
                    } catch (_: Throwable) {
                        if (bestRoot == null) bestRoot = root
                    }
                }
            }
        } catch (_: Throwable) {}

        return bestRoot ?: activeRoot
    }

    private fun isInputMethodWindowVisible(): Boolean {
        return try {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkBrowserUrl(
        browserPackage: String,
        forceSample: Boolean,
        allowBlocking: Boolean = true,
        eventRootHint: AccessibilityNodeInfo? = null
    ) {
        val now = FocusLayerManager.verifiedNowMs(this)
        val root = findBrowserWindowRoot(browserPackage, eventRootHint)
        val imeOpen = isInputMethodWindowVisible()

        // Only pause inspection while the keyboard is ACTUALLY open AND user is actively typing in the address bar
        if (root != null && imeOpen && isUrlBarFocusedForTyping(root, browserPackage)) {
            return
        }

        val detectedHost = if (root != null) {
            extractBrowserHostReliable(root, browserPackage, imeOpen)
        } else {
            null
        }

        val lastHost = BlockPolicyEngine.getLastActiveBrowserHost(this, browserPackage, now)
        val host = detectedHost ?: lastHost

        if (!host.isNullOrBlank()) {
            // ALWAYS record sample (24/7 website tracking; Study sites never count toward the shared 30m Focus pool)
            BlockPolicyEngine.recordBrowserWebsiteSample(this, browserPackage, host, now)

            val decision = BlockPolicyEngine.evaluateBrowserUrl(this, browserPackage, host, now)
            if (decision is BlockDecision.Blocked) {
                if (decision.details == "ADULT_RESTRICTED") {
                    try {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    } catch (_: Throwable) {}
                    lastBlockedHost = host
                    lastBlockedAtMs = now
                    enforceBlockNow(
                        target = "Restricted Content",
                        reasonWireKey = BlockReason.HARD_BLOCK.wireKey,
                        isWebsite = true,
                        blockedPackageName = browserPackage
                    )
                    return
                }
                if (!allowBlocking) return
                if (host != lastBlockedHost || (now - lastBlockedAtMs) > 1400L) {
                    lastBlockedHost = host
                    lastBlockedAtMs = now
                    enforceBlockNow(
                        target = decision.target,
                        reasonWireKey = decision.reason.wireKey,
                        isWebsite = true,
                        blockedPackageName = browserPackage
                    )
                }
            }
            return
        }

        // Fallback when toolbar is hidden/collapsed and no real host has been seen yet:
        // Only account toward Focus-Hours pool when Focus Hours are active and browser is not a Study browser.
        if (!allowBlocking || !BlockPolicyEngine.isFocusHoursActiveForBrowsers(this, now)) {
            return
        }
        if (BlockPolicyEngine.isBrowserMarkedAsStudyOrImprove(this, browserPackage)) {
            return
        }
        if (!BlockPolicyEngine.isLastActiveBrowserHostStudy(this, browserPackage, now)) {
            if (forceSample) {
                BlockPolicyEngine.recordBrowserWebsiteSample(this, browserPackage, "non-study-browsing.local", now)
            }
            val fallbackDecision = BlockPolicyEngine.evaluateBrowserUrl(this, browserPackage, "non-study-browsing.local", now)
            if (fallbackDecision is BlockDecision.Blocked) {
                if ("non-study-browsing.local" != lastBlockedHost || (now - lastBlockedAtMs) > 1800L) {
                    lastBlockedHost = "non-study-browsing.local"
                    lastBlockedAtMs = now
                    enforceBlockNow(
                        target = browserPackage,
                        reasonWireKey = fallbackDecision.reason.wireKey,
                        isWebsite = true,
                        blockedPackageName = browserPackage
                    )
                }
            }
        }
    }

    private fun isUrlBarFocusedForTyping(root: AccessibilityNodeInfo, browserPackage: String): Boolean {
        val candidatePackages = listOf(
            browserPackage,
            "com.android.chrome",
            "com.brave.browser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser"
        ).distinct()
        for (pkg in candidatePackages) {
            for (viewId in DIRECT_URL_VIEW_IDS) {
                val nodes = try {
                    root.findAccessibilityNodeInfosByViewId("$pkg:id/$viewId")
                } catch (_: Exception) {
                    null
                } ?: continue
                for (node in nodes) {
                    if (node.isFocused && node.isEditable) {
                        val txt = node.text?.toString()?.trim().orEmpty()
                        // If user cleared the bar or is typing a partial query/word without a domain, wait until they navigate
                        if (txt.isEmpty() || txt.contains(' ') || !txt.contains('.')) {
                            return true
                        }
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun extractBrowserHostReliable(
        root: AccessibilityNodeInfo,
        browserPackage: String,
        imeOpen: Boolean
    ): String? {
        // 1. Direct O(1) viewId lookup across Chromium/Brave/Firefox/Edge/Samsung/Opera toolbar IDs
        val candidatePackages = listOf(
            browserPackage,
            "com.android.chrome",
            "com.brave.browser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser"
        ).distinct()
        for (pkg in candidatePackages) {
            for (viewId in DIRECT_URL_VIEW_IDS) {
                val nodes = try {
                    root.findAccessibilityNodeInfosByViewId("$pkg:id/$viewId")
                } catch (_: Exception) {
                    null
                } ?: continue
                for (node in nodes) {
                    if (imeOpen && node.isFocused && node.isEditable) continue
                    val parsed = extractHostFromNode(node)
                    if (!parsed.isNullOrBlank()) {
                        return parsed
                    }
                }
            }
        }

        // 2. Breadth-First Search (BFS) across up to 360 nodes so shallow toolbar views are checked
        //    BEFORE deep webpage DOM content!
        val fromBfs = scanTreeBfsForHost(root, imeOpen)
        if (!fromBfs.isNullOrBlank()) {
            return fromBfs
        }

        // 3. Fallback to legacy BrowserUrlDetector
        val fromLegacy = try {
            BrowserUrlDetector.extractHost(root, browserPackage)
        } catch (_: Exception) {
            null
        }
        if (!fromLegacy.isNullOrBlank()) {
            val normalized = parseHostFromText(fromLegacy)
            if (!normalized.isNullOrBlank()) return normalized
        }

        return null
    }

    private fun extractHostFromNode(node: AccessibilityNodeInfo): String? {
        val textHost = parseHostFromText(node.text?.toString())
        if (!textHost.isNullOrBlank()) return textHost
        val descHost = parseHostFromText(node.contentDescription?.toString())
        if (!descHost.isNullOrBlank()) return descHost
        return null
    }

    private fun scanTreeBfsForHost(root: AccessibilityNodeInfo, imeOpen: Boolean): String? {
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        val topToolbarCutoff = (screenH * 0.24f).toInt()
        val bottomToolbarCutoff = (screenH * 0.78f).toInt()
        val rect = android.graphics.Rect()

        val queue = ArrayDeque<AccessibilityNodeInfo>(64)
        queue.add(root)
        var visited = 0
        var fallbackToolbarHost: String? = null

        while (queue.isNotEmpty() && visited < 360) {
            val node = queue.removeFirst()
            visited++

            val viewIdRes = try { node.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
            val className = try { node.className?.toString() ?: "" } catch (_: Throwable) { "" }

            val isUrlId = viewIdRes.isNotEmpty() && (
                DIRECT_URL_VIEW_IDS.any { suffix -> viewIdRes.endsWith(":id/$suffix") } ||
                    viewIdRes.contains("url", ignoreCase = true) ||
                    viewIdRes.contains("omnibox", ignoreCase = true) ||
                    viewIdRes.contains("location_bar", ignoreCase = true) ||
                    viewIdRes.contains("address", ignoreCase = true)
                )

            if (isUrlId && !(imeOpen && node.isFocused && node.isEditable)) {
                val host = extractHostFromNode(node)
                if (!host.isNullOrBlank()) return host
            }

            // Secondary toolbar heuristic: EditText or TextView positioned inside top or bottom browser toolbar
            if (fallbackToolbarHost == null &&
                (className.endsWith("EditText") || className.endsWith("TextView")) &&
                !(imeOpen && node.isFocused && node.isEditable)
            ) {
                try {
                    node.getBoundsInScreen(rect)
                    val inTopOrBottomBar = (rect.top in 0..topToolbarCutoff && rect.height() in 1..280) ||
                        (rect.bottom >= bottomToolbarCutoff && rect.height() in 1..280)
                    if (inTopOrBottomBar) {
                        val candidate = extractHostFromNode(node)
                        if (!candidate.isNullOrBlank()) {
                            // EditText in toolbar is almost certainly the address bar -> return immediately
                            if (className.endsWith("EditText")) return candidate
                            fallbackToolbarHost = candidate
                        }
                    }
                } catch (_: Throwable) {}
            }

            val count = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                // Skip descending into huge WebView DOM subtrees during toolbar discovery when we already have sibling toolbars
                val childClass = try { child.className?.toString() ?: "" } catch (_: Throwable) { "" }
                if (childClass.endsWith("WebView")) continue
                queue.add(child)
            }
        }
        return fallbackToolbarHost
    }

    private fun parseHostFromText(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val trimmed = rawText.trim()
        if (trimmed.length < 4 || trimmed.length > 512) return null

        // Ignore obvious browser placeholder hints
        val lower = trimmed.lowercase()
        if (lower.startsWith("search or type") ||
            lower.startsWith("search or enter") ||
            lower.startsWith("type a url") ||
            lower.startsWith("chrome://") ||
            lower.startsWith("brave://") ||
            lower.startsWith("about:") ||
            lower.startsWith("edge://")
        ) {
            return null
        }

        val candidate = if (trimmed.contains(' ') && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            val match = DOMAIN_REGEX.find(trimmed) ?: return null
            BlockPolicyEngine.normalizeHost(match.groupValues[1])
        } else {
            BlockPolicyEngine.normalizeHost(trimmed)
        }

        if (candidate.length < 4 || candidate.contains(' ') || !candidate.contains('.')) {
            return null
        }

        val parts = candidate.split('.')
        if (parts.size < 2) return null
        val tld = parts.last()
        if (tld.length < 2 || tld.length > 18 || !tld.all { it in 'a'..'z' }) return null
        if (NON_DOMAIN_EXTENSIONS.contains(tld)) return null
        if (parts.any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') || !it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' } }) {
            return null
        }
        return candidate
    }
}
