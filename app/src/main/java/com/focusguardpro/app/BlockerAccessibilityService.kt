package com.focusguardpro.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BlockerAccessibilityService? = null

        private val DIRECT_URL_VIEW_IDS = listOf(
            "url_bar",
            "url_bar_title",
            "location_bar_edit_text",
            "omnibox_url_text",
            "mozac_browser_toolbar_url_view",
            "search_box_text",
            "editUrl",
            "bro_common_omnibox_edit_text",
            "address_bar_edit_text"
        )

        private val DOMAIN_REGEX = Regex("""(?:https?://)?(?:www\.)?([a-zA-Z0-9-]+\.(?:[a-zA-Z]{2,})(?:\.[a-zA-Z]{2,})?)(?:[/?#:]|$)""")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedAppPkg: String? = null
    private var lastBlockedAppAtMs: Long = 0L
    private var lastBlockedHost: String? = null
    private var lastBlockedAtMs: Long = 0L
    private var lastContentEvalAtMs: Long = 0L
    private var lastContentEvalPkg: String? = null
    private var lastBrowserEvalAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SettingsTamperGuard.recordAccessibilityJustConnected(this)
        SettingsTamperGuard.recordVerifiedHeartbeat(this)
        try {
            WatchdogService.start(this)
            FocusForegroundMonitorService.start(this)
            FocusMonitorWorker.schedule(this)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
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

    /**
     * Executes immediate foreground eviction and BlockActivity launch using AccessibilityService privileges.
     * Can be invoked directly by [BlockerAccessibilityService] or delegated from [FocusForegroundMonitorService].
     */
    fun enforceBlockNow(
        target: String,
        reasonWireKey: String,
        isWebsite: Boolean = false,
        blockedPackageName: String = target
    ) {
        mainHandler.post {
            try {
                if (isWebsite) {
                    // Navigate the browser tab back out of the non-study / blocked website first
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    mainHandler.postDelayed({
                        try {
                            TimeLimitManager(this@BlockerAccessibilityService).triggerBlock(target, reasonWireKey)
                        } catch (_: Exception) {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }, 140L)
                    return@post
                }

                // Progressive Enhancement: If Porter/Shizuku is active, force-stop the distracting app
                if (PorterBridge.isEnhancedModeActive()) {
                    try {
                        PorterBridge.forceStopPackage(blockedPackageName)
                    } catch (_: Exception) {}
                }

                // 1. Immediately launch BlockActivity from AccessibilityService context (exempt from BAL restrictions)
                try {
                    TimeLimitManager(this@BlockerAccessibilityService).triggerBlock(target, reasonWireKey)
                } catch (_: Exception) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }

                // 2. Verify after 260ms that the blocked app is no longer on top; if it is, force Home eviction + re-launch
                mainHandler.postDelayed({
                    try {
                        val currentWindowPkg = rootInActiveWindow?.packageName?.toString()
                        if (currentWindowPkg == blockedPackageName) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            mainHandler.postDelayed({
                                try {
                                    TimeLimitManager(this@BlockerAccessibilityService).triggerBlock(target, reasonWireKey)
                                } catch (_: Exception) {}
                            }, 150L)
                        }
                    } catch (_: Exception) {}
                }, 260L)
            } catch (_: Exception) {}
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

        val rawEventPkg = event?.packageName?.toString()
        val topWindowPkg = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }

        // Prefer the actual top active window package when SystemUI emits background status bar ticks
        val activePkg = when {
            !topWindowPkg.isNullOrBlank() &&
                topWindowPkg != "com.android.systemui" &&
                !SystemExclusions.isInputMethod(this, topWindowPkg) -> topWindowPkg
            !rawEventPkg.isNullOrBlank() && rawEventPkg != "com.android.systemui" -> rawEventPkg
            !topWindowPkg.isNullOrBlank() -> topWindowPkg
            else -> ""
        }

        if (activePkg.isBlank()) return

        // 1. CRITICAL: Never block Focus Elite itself (DeactivationActivity, BlockActivity, Settings, etc.)
        if (isSelfPackage(activePkg)) {
            ForegroundTracker.currentPackage = activePkg
            return
        }

        // 2. CRITICAL: Never block any keyboard or input method editor
        if (SystemExclusions.isInputMethod(this, activePkg)) {
            return
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
            return
        }

        // Do not trigger app blocking while the device is locked (lockscreen/keyguard active)
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            return
        }

        // 4. If current foreground window belongs to our own app, do not evaluate background events
        if (isSelfPackage(topWindowPkg)) {
            ForegroundTracker.currentPackage = topWindowPkg
            return
        }

        val now = System.currentTimeMillis()
        val isWindowTransition = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            activePkg != ForegroundTracker.currentPackage

        // 5. Track foreground package transitions
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

        // 6. If active package is a Browser, apply Study vs. Non-Study Website policy
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
            return
        }

        // 7. Evaluate Non-Browser App Blocking Decision (Hard Block, Focus Layer, Blackout, 30m Daily Limit)
        val shouldEvaluateApp = isWindowTransition ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            activePkg != lastContentEvalPkg ||
            (now - lastContentEvalAtMs) >= 600L

        if (shouldEvaluateApp) {
            lastContentEvalPkg = activePkg
            lastContentEvalAtMs = now
            try {
                val decision = BlockPolicyEngine.evaluateApp(this, activePkg, now)
                if (decision is BlockDecision.Blocked) {
                    if (activePkg != lastBlockedAppPkg || (now - lastBlockedAppAtMs) >= 700L) {
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

    override fun onInterrupt() {}

    private fun checkBrowserUrl(browserPackage: String, forceSample: Boolean) {
        val now = System.currentTimeMillis()
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

        val host = detectedHost ?: if (forceSample) {
            BlockPolicyEngine.getLastActiveBrowserHost(this, browserPackage, now)
        } else {
            null
        } ?: return

        // Record sample (Study websites never count toward the 30m non-study limit; Non-study websites do)
        BlockPolicyEngine.recordBrowserWebsiteSample(this, browserPackage, host, now)

        val decision = BlockPolicyEngine.evaluateBrowserUrl(this, browserPackage, host, now)
        if (decision is BlockDecision.Blocked) {
            if (host != lastBlockedHost || (now - lastBlockedAtMs) > 1500L) {
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
    }

    private fun isUrlBarFocusedForTyping(root: AccessibilityNodeInfo, browserPackage: String): Boolean {
        val candidatePackages = listOf(browserPackage, "com.android.chrome", "com.brave.browser").distinct()
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

        // 3. Deep 200-node tree scan for any URL bar suffix or visible domain string
        val budget = intArrayOf(200)
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
            viewIdRes.endsWith(":id/$suffix") || viewIdRes.contains("url", ignoreCase = true) || viewIdRes.contains("omnibox", ignoreCase = true)
        }

        if (isUrlNode && !(node.isFocused && node.isEditable)) {
            val text = node.text?.toString()?.trim()
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
            // Might be "https://en.m.wikipedia.org/..." or "wikipedia.org/wiki/..." without spaces
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
