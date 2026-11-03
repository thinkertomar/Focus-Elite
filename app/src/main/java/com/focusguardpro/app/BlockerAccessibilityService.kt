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
            "custom_tab_toolbar",
            "url_text",
            "sbrowser_url_bar"
        )

        private val DOMAIN_REGEX = Regex("""(?:https?://)?(?:www\.)?([a-zA-Z0-9-]+\.(?:[a-zA-Z]{2,})(?:\.[a-zA-Z]{2,})?)(?:[/?#:]|$)""")
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

    // Tracks recently active packages from AccessibilityEvents (crucial for MIUI/HyperOS floating windows)
    private val recentEventPackages = ConcurrentHashMap<String, Long>()

    // Native Accessibility Overlay shield (renders above floating windows & fullscreen apps; immune to MIUI BAL blocks)
    private var lockOverlayView: View? = null
    private var lockOverlayBlockedPkg: String? = null
    private var lockOverlayShownAtMs: Long = 0L

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

    /**
     * Returns ALL currently visible application packages across fullscreen, split-screen,
     * and MIUI/HyperOS floating (freeform) windows.
     */
    fun getAllLiveVisiblePackages(): List<String> {
        val result = LinkedHashSet<String>()

        try {
            val rootPkg = rootInActiveWindow?.packageName?.toString()
            if (!rootPkg.isNullOrBlank() && !isIgnoredWindowOverlayPackage(rootPkg)) {
                result.add(rootPkg)
            }
        } catch (_: Throwable) {}

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
                    if (pkg.isNotBlank() && !isIgnoredWindowOverlayPackage(pkg)) {
                        result.add(pkg)
                    }
                }
            }
        } catch (_: Throwable) {}

        // Include any floating-window package that emitted a live AccessibilityEvent in the last 3.5 seconds
        val now = System.currentTimeMillis()
        val iterator = recentEventPackages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value <= 3_500L) {
                if (!isIgnoredWindowOverlayPackage(entry.key) && !SystemExclusions.isExcludedPackage(this, entry.key)) {
                    result.add(entry.key)
                }
            } else {
                iterator.remove()
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
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            dismissAccessibilityOverlayShield()
            return
        }
        if (AppLifecycleCoordinator.isRestoreInProgress) return

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return

        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) return

        val visiblePackages = getAllLiveVisiblePackages().toMutableList()
        if (visiblePackages.isEmpty()) {
            val fallbackPkg = try {
                UsageStatsHelper.getForegroundPackage(this)
            } catch (_: Throwable) {
                null
            }
            if (!fallbackPkg.isNullOrBlank() && !isIgnoredWindowOverlayPackage(fallbackPkg)) {
                visiblePackages.add(fallbackPkg)
            }
        }

        if (visiblePackages.isEmpty()) return

        val primaryPkg = visiblePackages.first()
        ForegroundTracker.currentPackage = primaryPkg
        val now = FocusLayerManager.verifiedNowMs(this)

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

            if (SystemExclusions.isExcludedPackage(this, livePkg)) {
                continue
            }

            if (BlockPolicyEngine.isBrowserPackage(livePkg)) {
                checkBrowserUrl(livePkg, forceSample = true)
                continue
            }

            val decision = BlockPolicyEngine.evaluateApp(this, livePkg, now)
            if (decision is BlockDecision.Blocked) {
                anyBlockedVisible = true
                if (livePkg != lastBlockedAppPkg || (now - lastBlockedAppAtMs) >= 850L) {
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
        if (!anyBlockedVisible && lockOverlayView != null && (now - lockOverlayShownAtMs) >= 1800L) {
            val blockedPkg = lockOverlayBlockedPkg
            if (blockedPkg == null || !visiblePackages.contains(blockedPkg)) {
                dismissAccessibilityOverlayShield()
            }
        }
    }

    /**
     * Executes immediate foreground & floating-window eviction plus a native TYPE_ACCESSIBILITY_OVERLAY
     * lock shield and BlockActivity launch. Works 100% on Xiaomi MIUI / HyperOS even when MIUI blocks
     * background startActivity() calls or when the blocked app is inside a floating window.
     */
    fun enforceBlockNow(
        target: String,
        reasonWireKey: String,
        isWebsite: Boolean = false,
        blockedPackageName: String = target
    ) {
        recentEventPackages.remove(blockedPackageName)
        mainHandler.post {
            try {
                if (isWebsite) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    showAccessibilityOverlayShield(target, reasonWireKey, isWebsite = true, blockedPackageName = blockedPackageName)
                    mainHandler.postDelayed({
                        try {
                            TimeLimitManager(this@BlockerAccessibilityService).triggerBlock(target, reasonWireKey)
                        } catch (_: Exception) {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }, 120L)
                    return@post
                }

                // 1. Progressive Enhancement: If Porter/Shizuku is active, force-stop the distracting app immediately
                if (PorterBridge.isEnhancedModeActive()) {
                    try {
                        PorterBridge.forceStopPackage(blockedPackageName)
                    } catch (_: Exception) {}
                }

                // 2. Immediately display native TYPE_ACCESSIBILITY_OVERLAY shield above all fullscreen & floating windows
                showAccessibilityOverlayShield(target, reasonWireKey, isWebsite = false, blockedPackageName = blockedPackageName)

                // 3. Check if user is simultaneously studying in another allowed window (e.g. SciAstra fullscreen + ChatGPT floating)
                val visibleNow = getAllLiveVisiblePackages()
                val hasAllowedStudyCompanion = visibleNow.any { pkg ->
                    pkg != blockedPackageName &&
                        !isSelfPackage(pkg) &&
                        !SystemExclusions.isExcludedPackage(this@BlockerAccessibilityService, pkg) &&
                        (AppCategoryPrefs.getStudyApps(this@BlockerAccessibilityService).contains(pkg) ||
                            AppCategoryPrefs.getImprovementApps(this@BlockerAccessibilityService).contains(pkg))
                }

                // 4. Immediately send BACK to collapse/close the floating window or active screen;
                // if no study app is open underneath, also send HOME immediately so fullscreen blocked apps vanish at once!
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (!hasAllowedStudyCompanion) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }

                // 5. Launch BlockActivity for standard full-screen detail view
                try {
                    TimeLimitManager(this@BlockerAccessibilityService).triggerBlock(target, reasonWireKey)
                } catch (_: Exception) {}

                // 6. Follow-up verification after 220ms: if the blocked package is still visible on screen, force HOME eviction
                mainHandler.postDelayed({
                    try {
                        val stillVisible = getAllLiveVisiblePackages().contains(blockedPackageName) ||
                            UsageStatsHelper.getForegroundPackage(this@BlockerAccessibilityService) == blockedPackageName
                        if (stillVisible) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            if (!hasAllowedStudyCompanion) {
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }
                            try {
                                TimeLimitManager(this@BlockerAccessibilityService).triggerBlock(target, reasonWireKey)
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }, 220L)
            } catch (_: Exception) {}
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    /**
     * Renders an instant native Accessibility Service overlay (`TYPE_ACCESSIBILITY_OVERLAY`)
     * directly over any fullscreen app or MIUI/HyperOS floating window. Requires zero extra
     * permissions and cannot be suppressed by Xiaomi MIUI background startActivity restrictions.
     */
    private fun showAccessibilityOverlayShield(
        target: String,
        reasonWireKey: String,
        isWebsite: Boolean,
        blockedPackageName: String
    ) {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            dismissAccessibilityOverlayShield()

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
                0
            }
            val customLimit = TimeLimitManager.getLimitMinutes(this, blockedPackageName)
            val isIgnored = AppCategoryPrefs.getIgnoredApps(this).contains(blockedPackageName)
            val effectiveCap = when {
                isIgnored -> if (customLimit in 1..29) customLimit else 30
                customLimit > 0 -> customLimit
                else -> UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
            }

            val reasonSubtitle = when (reasonWireKey) {
                BlockReason.DAILY_LIMIT.wireKey -> {
                    if (isIgnored) {
                        "$appLabel is in your Ignored category (30m/day cap).\nToday's usage: ${usedMins}m / ${effectiveCap}m limit reached."
                    } else {
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
                    "Non-study website ($target) is blocked. Study websites remain open 24/7."
                else ->
                    "$appLabel is currently restricted by Focus Elite (${usedMins}m / ${effectiveCap}m today)."
            }

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#F2090D16"))
                setPadding(dp(24f), dp(32f), dp(24f), dp(32f))
                isClickable = true
                isFocusable = true
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(22f), dp(24f), dp(22f), dp(22f))
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
                text = "Close $appLabel & Continue Studying"
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
                    dismissAccessibilityOverlayShield()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    val visible = getAllLiveVisiblePackages()
                    val studyOpen = visible.any { pkg ->
                        pkg != blockedPackageName &&
                            (AppCategoryPrefs.getStudyApps(this@BlockerAccessibilityService).contains(pkg) ||
                                AppCategoryPrefs.getImprovementApps(this@BlockerAccessibilityService).contains(pkg))
                    }
                    if (!studyOpen) {
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
                    dismissAccessibilityOverlayShield()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    try {
                        val blockIntent = Intent(this@BlockerAccessibilityService, BlockActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
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
        } catch (_: Throwable) {}
    }

    fun dismissAccessibilityOverlayShield() {
        mainHandler.post {
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
                checkBrowserUrl(browserPackage, forceSample = true)
            } catch (_: Exception) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        instance = this
        ensureWatchdogRunning()

        val shieldPrefs = getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            return
        }
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
        if (!rawEventPkg.isNullOrBlank() &&
            !isIgnoredWindowOverlayPackage(rawEventPkg) &&
            !isSelfPackage(rawEventPkg)
        ) {
            if (SystemExclusions.isExcludedPackage(this, rawEventPkg) &&
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                // Returning to Home/Launcher clears stale floating-window event cache
                recentEventPackages.clear()
            } else if (!SystemExclusions.isExcludedPackage(this, rawEventPkg)) {
                recentEventPackages[rawEventPkg] = System.currentTimeMillis()
            }
        }

        // Build candidate packages to inspect: BOTH the event's own package (e.g. a floating window
        // like ChatGPT on top of SciAstra) AND all currently visible windows!
        val candidatePackages = LinkedHashSet<String>()
        if (!rawEventPkg.isNullOrBlank() && !isIgnoredWindowOverlayPackage(rawEventPkg)) {
            candidatePackages.add(rawEventPkg)
        }
        for (visPkg in getAllLiveVisiblePackages()) {
            if (!isIgnoredWindowOverlayPackage(visPkg)) {
                candidatePackages.add(visPkg)
            }
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

            // 5. If active package is a Browser, apply Study vs. Non-Study Website policy
            if (BlockPolicyEngine.isBrowserPackage(activePkg)) {
                if (isWindowTransition ||
                    eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    (now - lastBrowserEvalAtMs) >= 600L
                ) {
                    lastBrowserEvalAtMs = now
                    try {
                        checkBrowserUrl(activePkg, forceSample = false)
                    } catch (_: Exception) {}
                }
                continue
            }

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

    private fun checkBrowserUrl(browserPackage: String, forceSample: Boolean) {
        val now = FocusLayerManager.verifiedNowMs(this)
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }

        // Do not inspect while user is actively typing in the address bar (Wait until they navigate)
        if (root != null && isUrlBarFocusedForTyping(root, browserPackage)) {
            return
        }

        val detectedHost = if (root != null) {
            extractBrowserHostReliable(root, browserPackage)
        } else {
            null
        }

        val lastHost = BlockPolicyEngine.getLastActiveBrowserHost(this, browserPackage, now)
        val host = detectedHost ?: lastHost

        if (!host.isNullOrBlank()) {
            // Record sample (Study websites never count toward the 30m non-study limit; Non-study websites do)
            BlockPolicyEngine.recordBrowserWebsiteSample(this, browserPackage, host, now)

            val decision = BlockPolicyEngine.evaluateBrowserUrl(this, browserPackage, host, now)
            if (decision is BlockDecision.Blocked) {
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

        // Fallback when toolbar is hidden/collapsed and no active Study host was detected:
        // Reconcile with Android Usage Access so non-study browsing cannot bypass the 30m cap by hiding the toolbar
        if (forceSample && !BlockPolicyEngine.isLastActiveBrowserHostStudy(this, browserPackage, now)) {
            val customLimit = TimeLimitManager.getLimitMinutes(this, browserPackage)
            val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
            val effectiveLimit = if (customLimit > 0) customLimit else defaultCap
            val usedNonStudyMins = BlockPolicyEngine.getNonStudyBrowserMinutesForPackage(this, browserPackage, now)
            if (effectiveLimit > 0 && usedNonStudyMins >= effectiveLimit && !BlockPolicyEngine.isUnblockedForToday(this, browserPackage, now)) {
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
    }

    private fun isUrlBarFocusedForTyping(root: AccessibilityNodeInfo, browserPackage: String): Boolean {
        val candidatePackages = listOf(browserPackage, "com.android.chrome", "com.brave.browser", "org.mozilla.firefox").distinct()
        for (pkg in candidatePackages) {
            for (viewId in DIRECT_URL_VIEW_IDS) {
                val nodes = try {
                    root.findAccessibilityNodeInfosByViewId("$pkg:id/$viewId")
                } catch (_: Exception) {
                    null
                } ?: continue
                for (node in nodes) {
                    if (node.isFocused && node.isEditable) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun extractBrowserHostReliable(root: AccessibilityNodeInfo, browserPackage: String): String? {
        // 1. Direct O(1) viewId lookup across Chromium/Brave/Firefox/Samsung/Opera toolbar IDs
        val candidatePackages = listOf(browserPackage, "com.android.chrome", "com.brave.browser", "org.mozilla.firefox").distinct()
        for (pkg in candidatePackages) {
            for (viewId in DIRECT_URL_VIEW_IDS) {
                val nodes = try {
                    root.findAccessibilityNodeInfosByViewId("$pkg:id/$viewId")
                } catch (_: Exception) {
                    null
                } ?: continue
                for (node in nodes) {
                    if (node.isFocused && node.isEditable) continue
                    val rawText = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()
                    val parsed = parseHostFromText(rawText)
                    if (!parsed.isNullOrBlank()) {
                        return parsed
                    }
                }
            }
        }

        // 2. Try existing BrowserUrlDetector
        val fromLegacy = try {
            BrowserUrlDetector.extractHost(root, browserPackage)
        } catch (_: Exception) {
            null
        }
        if (!fromLegacy.isNullOrBlank()) {
            return BlockPolicyEngine.normalizeHost(fromLegacy)
        }

        // 3. Deep 240-node tree scan for any URL bar suffix or visible domain string
        val budget = intArrayOf(240)
        return scanTreeForHost(root, budget)
    }

    private fun scanTreeForHost(node: AccessibilityNodeInfo?, budget: IntArray): String? {
        if (node == null || budget[0] <= 0) return null
        budget[0]--

        val viewIdRes = try {
            node.viewIdResourceName ?: ""
        } catch (_: Exception) {
            ""
        }

        val isUrlNode = DIRECT_URL_VIEW_IDS.any { suffix ->
            viewIdRes.endsWith(":id/$suffix") ||
                viewIdRes.contains("url", ignoreCase = true) ||
                viewIdRes.contains("omnibox", ignoreCase = true) ||
                viewIdRes.contains("location_bar", ignoreCase = true)
        }

        if (isUrlNode && !(node.isFocused && node.isEditable)) {
            val text = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()
            val host = parseHostFromText(text)
            if (!host.isNullOrBlank()) return host
        }

        val count = node.childCount
        for (i in 0 until count) {
            if (budget[0] <= 0) break
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val found = scanTreeForHost(child, budget)
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    private fun parseHostFromText(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val trimmed = rawText.trim()
        if (trimmed.contains(' ') && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            val match = DOMAIN_REGEX.find(trimmed) ?: return null
            val candidate = BlockPolicyEngine.normalizeHost(match.groupValues[1])
            return if (candidate.contains('.')) candidate else null
        }
        val normalized = BlockPolicyEngine.normalizeHost(trimmed)
        if (normalized.contains('.') && normalized.length >= 4 && !normalized.contains(' ')) {
            return normalized
        }
        return null
    }
}
