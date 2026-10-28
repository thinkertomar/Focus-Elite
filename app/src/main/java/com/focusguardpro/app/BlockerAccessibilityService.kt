package com.focusguardpro.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent

class BlockerAccessibilityService : AccessibilityService() {

    private var lastBlockedAppPkg: String? = null
    private var lastBlockedAppAtMs: Long = 0L
    private var lastBlockedHost: String? = null
    private var lastBlockedAtMs: Long = 0L
    private var lastContentEvalAtMs: Long = 0L
    private var lastContentEvalPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsTamperGuard.recordAccessibilityJustConnected(this)
        SettingsTamperGuard.recordVerifiedHeartbeat(this)
        try {
            WatchdogService.start(this)
            FocusForegroundMonitorService.start(this)
            FocusMonitorWorker.schedule(this)
        } catch (_: Throwable) {}
    }

    private fun isSelfPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg == packageName ||
            pkg == "com.focuselite.app" ||
            pkg == "com.focusguardpro.elite" ||
            pkg == "com.focusguardpro.app"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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

        // Ignore background status bar / SystemUI content ticks when another app is in the foreground window
        val activePkg = when {
            rawEventPkg == "com.android.systemui" &&
                eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !topWindowPkg.isNullOrBlank() &&
                topWindowPkg != "com.android.systemui" -> topWindowPkg
            !rawEventPkg.isNullOrBlank() -> rawEventPkg
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
            // Only overwrite ForegroundTracker when the window state actually switched to a non-SystemUI excluded app (e.g. Home Launcher)
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && activePkg != "com.android.systemui") {
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

        // 4. If current foreground window belongs to our own app (e.g. typing in DeactivationActivity),
        // do not evaluate background events
        if (isSelfPackage(topWindowPkg)) {
            ForegroundTracker.currentPackage = topWindowPkg
            return
        }

        val now = System.currentTimeMillis()
        val isWindowTransition = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            activePkg != ForegroundTracker.currentPackage

        // Throttle high-frequency content change evaluations for the same package to every 750ms
        // while always evaluating window transitions and clicks immediately
        val shouldEvaluateApp = isWindowTransition ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            activePkg != lastContentEvalPkg ||
            (now - lastContentEvalAtMs) >= 750L

        // 5. Evaluate App Blocking Decision
        if (shouldEvaluateApp) {
            lastContentEvalPkg = activePkg
            lastContentEvalAtMs = now
            try {
                val decision = BlockPolicyEngine.evaluateApp(this, activePkg)
                if (decision is BlockDecision.Blocked) {
                    if (activePkg != lastBlockedAppPkg || (now - lastBlockedAppAtMs) >= 600L) {
                        lastBlockedAppPkg = activePkg
                        lastBlockedAppAtMs = now

                        // Progressive Enhancement: If Porter/Shizuku is active, force-stop the distracting app
                        if (PorterBridge.isEnhancedModeActive()) {
                            try {
                                PorterBridge.forceStopPackage(decision.target)
                            } catch (_: Exception) {}
                        }
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        TimeLimitManager(this).triggerBlock(decision.target, decision.reason.wireKey)
                    }
                    return
                }
            } catch (_: Exception) {}
        }

        // 6. Track foreground package transitions
        if (activePkg != ForegroundTracker.currentPackage && !SystemExclusions.isInputMethod(this, activePkg)) {
            val previousPkg = ForegroundTracker.currentPackage
            ForegroundTracker.currentPackage = activePkg
            ForegroundTracker.currentPackageSince = now
            try {
                AwayTimeTracker.resolveIfDue(this)
            } catch (_: Exception) {}

            if (previousPkg != null && BrowserUrlDetector.isBrowser(previousPkg) && !BrowserUrlDetector.isBrowser(activePkg)) {
                try {
                    BrowserFairTimeTracker.clearSample(this)
                } catch (_: Exception) {}
            }
        }

        // 7. Check browser URL if active package is a browser
        if (BrowserUrlDetector.isBrowser(activePkg)) {
            try {
                checkBrowserUrl(activePkg)
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}

    private fun checkBrowserUrl(browserPackage: String) {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        val host = BrowserUrlDetector.extractHost(root, browserPackage) ?: return

        val decision = BlockPolicyEngine.evaluateWebsite(this, host)
        if (decision is BlockDecision.Blocked) {
            val now = System.currentTimeMillis()
            if (host != lastBlockedHost || now - lastBlockedAtMs > 2000L) {
                lastBlockedHost = host
                lastBlockedAtMs = now
                performGlobalAction(GLOBAL_ACTION_BACK)
                TimeLimitManager(this).triggerBlock(decision.target, decision.reason.wireKey)
            }
        }
    }
}
