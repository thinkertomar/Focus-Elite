package com.focusguardpro.app

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.AuditRecord
import kotlin.math.abs

/**
 * SettingsTamperGuard
 *
 * Enforces two core Zero-Escape Fortress protections:
 * 1. Dual-Clock Monotonic Time Anchor: Pairs wall-clock time (System.currentTimeMillis)
 *    with hardware monotonic elapsed time (SystemClock.elapsedRealtime) so manually shifting
 *    the Android device clock or timezone cannot bypass the 9:00 AM - 6:00 PM Focus Layer.
 * 2. Surgical Android Settings Node Inspector: Inspects the active window inside Android
 *    Settings and blocks attempts to access Focus Elite App Info (Force Stop / Uninstall),
 *    Accessibility settings, Device Admin deactivation, or Date & Time settings while
 *    Fortress Lock or a Deep Focus session is active.
 */
object SettingsTamperGuard {

    private const val PREFS_NAME = "fortress_monotonic_clock_prefs"
    private const val KEY_ANCHOR_WALL_MS = "anchor_wall_ms"
    private const val KEY_ANCHOR_ELAPSED_MS = "anchor_elapsed_ms"
    private const val KEY_TAMPER_DETECTED_AT_ELAPSED = "tamper_detected_at_elapsed"
    private const val KEY_TAMPER_OFFSET_MS = "tamper_offset_ms"

    // Maximum legitimate drift between wall clock and monotonic clock before flagging clock tamper (2 minutes)
    private const val MAX_ALLOWED_CLOCK_DRIFT_MS = 120_000L

    private var lastSettingsInterceptAtMs: Long = 0L

    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.samsung.android.lool",
        "com.coloros.safecenter",
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    fun isSettingsOrInstallerPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return SETTINGS_PACKAGES.contains(packageName) ||
            packageName.endsWith(".settings") ||
            packageName.contains("packageinstaller")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records a verified monotonic anchor when the system clock is in sync, or preserves
     * the monotonic offset if the user tampered with the device clock.
     */
    fun recordVerifiedHeartbeat(context: Context) {
        val p = prefs(context)
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()

        val prevWall = p.getLong(KEY_ANCHOR_WALL_MS, 0L)
        val prevElapsed = p.getLong(KEY_ANCHOR_ELAPSED_MS, 0L)

        // First initialization or device rebooted (elapsedNow < prevElapsed)
        if (prevWall <= 0L || prevElapsed <= 0L || elapsedNow < prevElapsed) {
            p.edit()
                .putLong(KEY_ANCHOR_WALL_MS, wallNow)
                .putLong(KEY_ANCHOR_ELAPSED_MS, elapsedNow)
                .putLong(KEY_TAMPER_DETECTED_AT_ELAPSED, 0L)
                .putLong(KEY_TAMPER_OFFSET_MS, 0L)
                .apply()
            return
        }

        val expectedWallNow = prevWall + (elapsedNow - prevElapsed)
        val drift = wallNow - expectedWallNow

        if (abs(drift) > MAX_ALLOWED_CLOCK_DRIFT_MS) {
            // Device wall clock was jumped forward or backward!
            p.edit()
                .putLong(KEY_TAMPER_DETECTED_AT_ELAPSED, elapsedNow)
                .putLong(KEY_TAMPER_OFFSET_MS, -drift)
                .apply()
        } else {
            // Clock is honest; refresh baseline anchor smoothly
            p.edit()
                .putLong(KEY_ANCHOR_WALL_MS, wallNow)
                .putLong(KEY_ANCHOR_ELAPSED_MS, elapsedNow)
                .putLong(KEY_TAMPER_DETECTED_AT_ELAPSED, 0L)
                .putLong(KEY_TAMPER_OFFSET_MS, 0L)
                .apply()
        }
    }

    /**
     * Called when Intent.ACTION_TIME_CHANGED or Intent.ACTION_TIMEZONE_CHANGED fires.
     */
    fun onSystemTimeChanged(context: Context) {
        recordVerifiedHeartbeat(context)
        if (isClockTamperActive(context)) {
            logTamperAudit(context, "CLOCK_SPOOF_ATTEMPT: System clock or timezone modified during active monitoring")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "🛡️ Fortress Clock Anchor: System time change detected. Schedule remains bound to hardware monotonic clock.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Returns true if the user has shifted the system clock relative to the hardware monotonic clock.
     */
    fun isClockTamperActive(context: Context): Boolean {
        val p = prefs(context)
        val prevWall = p.getLong(KEY_ANCHOR_WALL_MS, 0L)
        val prevElapsed = p.getLong(KEY_ANCHOR_ELAPSED_MS, 0L)
        val elapsedNow = SystemClock.elapsedRealtime()
        if (prevWall <= 0L || prevElapsed <= 0L || elapsedNow < prevElapsed) {
            return false
        }
        val expectedWallNow = prevWall + (elapsedNow - prevElapsed)
        val drift = abs(System.currentTimeMillis() - expectedWallNow)
        return drift > MAX_ALLOWED_CLOCK_DRIFT_MS
    }

    /**
     * Returns the tamper-proof current timestamp in milliseconds.
     * If the user alters the system clock, this returns the monotonic-anchored time
     * so 9:00 AM - 6:00 PM schedules and session timers cannot be escaped via time-travel.
     */
    fun getVerifiedCurrentTimeMillis(context: Context): Long {
        val p = prefs(context)
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()

        val prevWall = p.getLong(KEY_ANCHOR_WALL_MS, 0L)
        val prevElapsed = p.getLong(KEY_ANCHOR_ELAPSED_MS, 0L)

        if (prevWall <= 0L || prevElapsed <= 0L || elapsedNow < prevElapsed) {
            p.edit()
                .putLong(KEY_ANCHOR_WALL_MS, wallNow)
                .putLong(KEY_ANCHOR_ELAPSED_MS, elapsedNow)
                .apply()
            return wallNow
        }

        val expectedWallNow = prevWall + (elapsedNow - prevElapsed)
        val drift = abs(wallNow - expectedWallNow)

        return if (drift > MAX_ALLOWED_CLOCK_DRIFT_MS) {
            expectedWallNow
        } else {
            wallNow
        }
    }

    private const val KEY_PERMISSION_SETUP_GRACE_UNTIL_ELAPSED = "permission_setup_grace_until_elapsed"
    private const val KEY_ACCESSIBILITY_CONNECTED_AT_ELAPSED = "accessibility_connected_at_elapsed"

    /**
     * Grants a temporary grace window (default 3 minutes) when the user explicitly launches
     * an Android Settings screen from inside Focus Elite to grant Usage Access, Overlay,
     * Battery Optimization, Device Admin, or Accessibility permissions.
     */
    fun grantPermissionSetupGrace(context: Context, durationMs: Long = 180_000L) {
        val until = SystemClock.elapsedRealtime() + durationMs
        prefs(context).edit()
            .putLong(KEY_PERMISSION_SETUP_GRACE_UNTIL_ELAPSED, until)
            .apply()
    }

    /**
     * Called from BlockerAccessibilityService.onServiceConnected() so that right after the user
     * toggles Accessibility ON inside Android Settings, they have 60 seconds to calmly exit
     * Android Settings without triggering a false-positive Settings Tamper popup.
     */
    fun recordAccessibilityJustConnected(context: Context) {
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs(context).edit()
            .putLong(KEY_ACCESSIBILITY_CONNECTED_AT_ELAPSED, nowElapsed)
            .apply()
    }

    fun isInPermissionSetupGrace(context: Context): Boolean {
        val p = prefs(context)
        val nowElapsed = SystemClock.elapsedRealtime()
        val graceUntil = p.getLong(KEY_PERMISSION_SETUP_GRACE_UNTIL_ELAPSED, 0L)
        if (graceUntil > nowElapsed) return true

        val connectedAt = p.getLong(KEY_ACCESSIBILITY_CONNECTED_AT_ELAPSED, 0L)
        if (connectedAt > 0L && nowElapsed >= connectedAt && (nowElapsed - connectedAt) < 60_000L) {
            return true
        }
        return false
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns true only when Usage Access, Device Admin, and Accessibility Service are all active.
     * Until all core permissions are enabled, SettingsTamperGuard never blocks Android Settings
     * so the user can smoothly complete the 4-step setup in order.
     */
    fun areAllCoreSetupPermissionsGranted(context: Context): Boolean {
        return hasUsageStatsPermission(context) &&
            isDeviceAdminAlreadyActive(context) &&
            isAccessibilityAlreadyEnabled(context)
    }

    /**
     * Returns true when the user has completed the initial setup (permissions + app categories).
     */
    fun isInitialSetupComplete(context: Context): Boolean {
        return areAllCoreSetupPermissionsGranted(context) &&
            UiThemePrefs.isSetupCategoriesConfigured(context)
    }

    /**
     * Surgical Android Settings Node Inspector.
     * Returns true if a tamper screen was detected and blocked.
     */
    fun inspectAndBlockSettingsTamper(
        service: AccessibilityService,
        activePkg: String,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        if (!isSettingsOrInstallerPackage(activePkg)) return false

        // CRITICAL FIX: Never block Android Settings while the user is in the Permission Setup Grace
        // Window (e.g., right after tapping Enable Accessibility / Admin / Usage, or within 60s of
        // turning Accessibility ON), or before all core setup permissions have been granted!
        if (isInPermissionSetupGrace(service) || !areAllCoreSetupPermissionsGranted(service)) {
            return false
        }

        val isFocusLocked = FocusLayerManager.isLockedUntilCalendarHoliday(service) ||
            FocusLayerManager.isActiveNow(service) ||
            isDeepFocusSessionRunning()

        if (!isFocusLocked) return false

        val root = rootNode ?: return false
        val collectedTexts = ArrayList<String>(64)
        collectWindowTexts(root, collectedTexts, maxNodes = 140)
        if (collectedTexts.isEmpty()) return false

        val combined = collectedTexts.joinToString(" | ").lowercase()

        val mentionsOurApp = combined.contains("focus elite") ||
            combined.contains("focusguard") ||
            combined.contains("com.focusguardpro")

        // 1. Package Uninstaller or App Info (Force Stop / Uninstall / Clear Data) for Focus Elite
        val isAppInfoOrUninstallTamper = mentionsOurApp && (
            combined.contains("force stop") ||
                combined.contains("uninstall") ||
                combined.contains("clear storage") ||
                combined.contains("clear data") ||
                combined.contains("app info") ||
                combined.contains("do you want to uninstall") ||
                activePkg.contains("packageinstaller")
            )

        // 2. Accessibility Service revocation attempt (only block if accessibility is ALREADY enabled)
        val isAccessibilityTamper = isAccessibilityAlreadyEnabled(service) &&
            mentionsOurApp &&
            (combined.contains("accessibility") ||
                combined.contains("use focus elite") ||
                combined.contains("stop focus elite") ||
                combined.contains("turn off") ||
                combined.contains("shortcut"))

        // 3. Device Admin deactivation attempt (only block if Device Admin is ALREADY active)
        val isDeviceAdminTamper = isDeviceAdminAlreadyActive(service) &&
            mentionsOurApp &&
            (combined.contains("device admin") ||
                combined.contains("deactivate") ||
                combined.contains("remove active admin"))

        // 4. Date & Time settings screen (clock spoofing attempt during active focus)
        val isDateTimeTamper = combined.contains("date & time") ||
            combined.contains("date and time") ||
            combined.contains("automatic date & time") ||
            combined.contains("set time") ||
            combined.contains("set date") ||
            (combined.contains("time zone") && combined.contains("automatic"))

        val tamperReason = when {
            isAppInfoOrUninstallTamper -> "Protected App Info / Force Stop / Uninstall"
            isAccessibilityTamper -> "Protected Accessibility Shield Settings"
            isDeviceAdminTamper -> "Protected Device Admin Lock"
            isDateTimeTamper -> "Protected Date & Time Clock Settings"
            else -> null
        } ?: return false

        val now = SystemClock.elapsedRealtime()
        if (now - lastSettingsInterceptAtMs < 1200L) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return true
        }
        lastSettingsInterceptAtMs = now

        // Execute immediate defensive back-action and route away from Settings
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        logTamperAudit(service, "SETTINGS_TAMPER_BLOCKED: $tamperReason")

        // Launch PermissionTamperWarningActivity or show prominent Toast notice
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                service.applicationContext,
                "🛡️ Fortress Lock Active: $tamperReason cannot be modified during focus hours!",
                Toast.LENGTH_LONG
            ).show()
            try {
                val warnIntent = Intent(service, PermissionTamperWarningActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("tamper_reason", tamperReason)
                }
                service.startActivity(warnIntent)
            } catch (_: Throwable) {}
        }

        return true
    }

    private fun isDeepFocusSessionRunning(): Boolean {
        return try {
            val state = FocusSessionManager.snapshot.value.state
            state == FocusSessionManager.SessionState.RUNNING ||
                state == FocusSessionManager.SessionState.PAUSED
        } catch (_: Throwable) {
            false
        }
    }

    fun isAccessibilityAlreadyEnabled(context: Context): Boolean {
        return try {
            val expected = ComponentName(context, BlockerAccessibilityService::class.java)
            val enabledSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledSetting)
            while (splitter.hasNext()) {
                val item = ComponentName.unflattenFromString(splitter.next())
                if (item != null && item == expected) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isDeviceAdminAlreadyActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = ComponentName(context, MyAdminReceiver::class.java)
            dpm?.isAdminActive(admin) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun collectWindowTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxNodes: Int
    ) {
        if (node == null || out.size >= maxNodes) return
        try {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                out.add(text)
            }
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc != text) {
                out.add(desc)
            }
            val count = node.childCount
            for (i in 0 until count) {
                if (out.size >= maxNodes) break
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                }
                if (child != null) {
                    collectWindowTexts(child, out, maxNodes)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun logTamperAudit(context: Context, details: String) {
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            AppDatabase.getDatabase(context).systemDao().insertAuditRecord(
                AuditRecord(
                    id = 0,
                    eventType = "TAMPER_INTERCEPT",
                    timestamp = System.currentTimeMillis(),
                    details = details,
                    durationMillis = 0L
                )
            )
        } catch (_: Throwable) {}
    }
}
