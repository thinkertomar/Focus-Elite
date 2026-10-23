package com.focusguardpro.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BlockerAccessibilityService : AccessibilityService() {

    private var lastBlockedHost: String? = null
    private var lastBlockedAtMs: Long = 0L
    private var lastTamperWarningAtMs: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val shieldPrefs = getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            return
        }
        if (AppLifecycleCoordinator.isRestoreInProgress) {
            return
        }

        // Never trigger blocking on keyboard typing or content change events
        val eventType = event?.eventType ?: 0
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            return
        }

        val eventPkg = event?.packageName?.toString()
        val activePkg = if (!eventPkg.isNullOrBlank()) {
            eventPkg
        } else {
            try {
                rootInActiveWindow?.packageName?.toString() ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        if (activePkg.isBlank()) return

        // 1. CRITICAL: Never block FocusGuard Pro itself (DeactivationActivity, BlockActivity, Settings, etc.)
        val myPkg = packageName
        if (activePkg == myPkg || activePkg == "com.focusguardpro.elite" || activePkg == "com.focusguardpro.app") {
            ForegroundTracker.currentPackage = activePkg
            return
        }

        // 2. CRITICAL: Never block any keyboard or input method editor
        if (SystemExclusions.isInputMethod(this, activePkg)) {
            return
        }

        // 3. System exclusions (SystemUI, Launcher, Phone Dialer, Settings)
        if (SystemExclusions.isExcludedPackage(this, activePkg)) {
            ForegroundTracker.currentPackage = activePkg
            ForegroundTracker.currentPackageSince = System.currentTimeMillis()
            return
        }

        // Do not trigger app blocking while the device is locked (lockscreen/keyguard active)
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            return
        }

        // 4. If current foreground window belongs to our own app (e.g. typing in DeactivationActivity),
        // do not evaluate background events
        val topWindowPkg = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
        if (topWindowPkg == myPkg || topWindowPkg == "com.focusguardpro.elite" || topWindowPkg == "com.focusguardpro.app") {
            ForegroundTracker.currentPackage = topWindowPkg
            return
        }

        // 5. Evaluate App Blocking Decision
        try {
            val decision = BlockPolicyEngine.evaluateApp(this, activePkg)
            if (decision is BlockDecision.Blocked) {
                // Progressive Enhancement: If Porter/Shizuku is active, force-stop the distracting app
                if (PorterBridge.isEnhancedModeActive()) {
                    try {
                        PorterBridge.forceStopPackage(decision.target)
                    } catch (_: Exception) {}
                }
                performGlobalAction(GLOBAL_ACTION_BACK)
                TimeLimitManager(this).triggerBlock(decision.target, decision.reason.wireKey)
                return
            }
        } catch (_: Exception) {}

        // 6. Track foreground package transitions
        if (activePkg != ForegroundTracker.currentPackage && !SystemExclusions.isInputMethod(this, activePkg)) {
            val previousPkg = ForegroundTracker.currentPackage
            ForegroundTracker.currentPackage = activePkg
            ForegroundTracker.currentPackageSince = System.currentTimeMillis()
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
