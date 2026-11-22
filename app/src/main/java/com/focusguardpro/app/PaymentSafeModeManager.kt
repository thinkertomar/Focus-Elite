package com.focusguardpro.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Universal Payment & Banking Safe Mode Manager.
 *
 * Banking & UPI apps (BHIM, YONO SBI, PhonePe, HDFC, ICICI, etc.) query Android's
 * AccessibilityManager.getEnabledAccessibilityServiceList() and refuse to open if any
 * third-party AccessibilityService is active.
 *
 * How this solves it universally:
 * 1. Auto-Yield (or 1-Tap Quick Settings Tile / Notification Action):
 *    Immediately pauses Focus Elite's AccessibilityService via disableSelf() (API 24+)
 *    and/or Settings.Secure / PorterBridge so payment apps open cleanly with ZERO manual
 *    digging into Android Settings.
 * 2. Background Protection Stays Live:
 *    FocusForegroundMonitorService (UsageStats-based) continues running, whitelists the
 *    payment app, suppresses Watchdog/Porter Accessibility auto-revive during payment,
 *    and detects the exact moment the user exits the payment app.
 * 3. Smart Auto-Resume:
 *    - Tier 1 (100% Zero-Touch): If WRITE_SECURE_SETTINGS (one-time ADB grant, survives reboots)
 *      OR Shizuku/Porter is granted, re-enables Focus Elite Accessibility automatically as soon
 *      as you leave the payment app.
 *    - Tier 2 (Standard Android 1-Tap): Posts a high-priority "Payment Done · Tap to Resume Shield"
 *      notification opening Accessibility Settings with SettingsTamperGuard grace pre-unlocked.
 */
object PaymentSafeModeManager {

    private const val PREFS_NAME = "FocusGuard_PaymentSafeMode"
    private const val KEY_AUTO_YIELD_ENABLED = "auto_yield_enabled"
    private const val KEY_ACTIVE = "pay_safe_active"
    private const val KEY_STARTED_AT_MS = "pay_safe_started_at_ms"
    private const val KEY_TRIGGER_PKG = "pay_safe_trigger_pkg"
    private const val KEY_SEEN_IN_PAYMENT_APP = "pay_safe_seen_in_payment_app"
    private const val KEY_LEFT_PAYMENT_SINCE_MS = "pay_safe_left_payment_since_ms"
    private const val KEY_CUSTOM_PAYMENT_PKGS = "custom_payment_pkgs"

    const val RESUME_CHANNEL_ID = "focusguard_pay_safe_resume"
    const val RESUME_NOTIFICATION_ID = 2077

    // Safety caps so Pay Safe Mode can never accidentally leave shield paused indefinitely
    private const val PRE_LAUNCH_WAIT_TIMEOUT_MS = 90_000L   // 90s to open payment app after tapping Tile
    private const val EXIT_DEBOUNCE_MS = 3_500L              // 3.5s outside payment app before restoring
    private const val MAX_PAY_SAFE_DURATION_MS = 10 * 60_000L // 10 min hard safety cap

    /**
     * Built-in curated list of Indian & international UPI, banking, and payment applications
     * known to check Accessibility permissions or require uninterrupted payment flows.
     */
    private val BUILT_IN_PAYMENT_PACKAGES = setOf(
        // NPCI & Major UPI Apps
        "in.org.npci.upiapp",                       // BHIM UPI
        "com.google.android.apps.nbu.paisa.user",   // Google Pay (GPay)
        "com.phonepe.app",                          // PhonePe
        "com.phonepe.app.business",                 // PhonePe Business
        "net.one97.paytm",                          // Paytm
        "com.paytm.business",                       // Paytm for Business
        "com.paytmmall",
        "com.dreamplug.androidapp",                 // CRED
        "com.mobikwik_new",                         // MobiKwik
        "com.freecharge.android",                   // Freecharge
        "com.myairtelapp",                          // Airtel Thanks / Payments Bank
        "com.jio.myjio",                            // JioPay / MyJio
        "com.naviapp",                              // Navi UPI
        "com.fampay.in",                            // FamPay
        "com.slice.android",                        // Slice
        "money.jupiter",                            // Jupiter
        "com.fi.money",                             // Fi Money
        "com.bharatpe.app",                         // BharatPe
        "com.whatsapp.w4b",                         // (Excluded: only dedicated banking below)
        // Major Indian Banks
        "com.sbi.lotusintouch",                     // YONO SBI
        "com.sbi.SBIFreedomPlus",                   // YONO Lite SBI
        "com.sbi.sbipay",                           // BHIM SBI Pay
        "com.csam.icici.bank.imobile",              // iMobile Pay (ICICI)
        "com.snapwork.hdfc",                        // HDFC Bank MobileBanking
        "com.hdfcbank.payzapp",                     // HDFC PayZapp
        "com.axis.mobile",                          // Axis Mobile
        "com.upi.axispay",                          // BHIM Axis Pay
        "com.msf.kbank.mobile",                     // Kotak Mobile Banking
        "com.kotak811mobilebankingapp.instantsavingsupiproducts", // Kotak811
        "com.bankofbaroda.mconnect",                // bob World
        "com.Version1",                             // PNB ONE
        "com.pnb.mPassbook",                        // PNB mPassbook
        "com.canarabank.mobility",                  // Canara ai1
        "com.infrasofttech.CentralBank",            // Cent Mobile
        "com.unionbank.ecommerce.mobile.android",   // Vyom (Union Bank)
        "com.indusind.indie",                       // INDIE by IndusInd
        "com.indusmobile",                          // IndusMobile
        "com.idfcfirstbank.optimus",                // IDFC FIRST Bank
        "com.rblbank.mobank",                       // RBL MoBank
        "com.yesbank.iris",                         // iris by YES BANK
        "com.federalbank.fedmobile",                // FedMobile
        "com.iob.iobmobile",                        // IOB Mobile
        "com.indianbank.indsmart",                  // IndSMART
        "com.postinfo.ippb",                        // IPPB Mobile Banking
        "com.csb.mobilebanking",
        "com.au.bank",                              // AU 0101
        // Investment / Demat / Cards with Accessibility checks
        "com.zerodha.kite3",                        // Zerodha Kite
        "com.nextbillion.groww",                    // Groww
        "com.msf.angelmobile",                      // Angel One
        "in.upstox.app",                            // Upstox
        // Global Payment & Banking
        "com.paypal.android.p2pmobile",             // PayPal
        "com.venmo",                                // Venmo
        "com.squareup.cash",                        // Cash App
        "com.revolut.revolut",                      // Revolut
        "com.wise.android",                         // Wise
        "com.chase.sig.android",                    // Chase
        "com.infonow.bofa",                         // Bank of America
        "com.wf.wellsfargomobile"                   // Wells Fargo
    ) - setOf("com.whatsapp.w4b")

    private val PAYMENT_PACKAGE_TOKENS = listOf(
        ".upi", "upiapp", "bhim", "yono", "imobile", "payzapp",
        "netbanking", "mbanking", "mobilebanking", "axispay", "sbipay"
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoYieldEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_YIELD_ENABLED, true)

    fun setAutoYieldEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_YIELD_ENABLED, enabled).apply()
    }

    fun getCustomPaymentPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_CUSTOM_PAYMENT_PKGS, emptySet())?.toSet() ?: emptySet()

    fun addCustomPaymentPackage(context: Context, pkg: String) {
        val clean = pkg.trim()
        if (clean.isBlank()) return
        val updated = getCustomPaymentPackages(context).toMutableSet().apply { add(clean) }
        prefs(context).edit().putStringSet(KEY_CUSTOM_PAYMENT_PKGS, updated).apply()
    }

    fun removeCustomPaymentPackage(context: Context, pkg: String) {
        val updated = getCustomPaymentPackages(context).toMutableSet().apply { remove(pkg) }
        prefs(context).edit().putStringSet(KEY_CUSTOM_PAYMENT_PKGS, updated).apply()
    }

    fun isPaymentOrBankingPackage(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.trim()
        if (BUILT_IN_PAYMENT_PACKAGES.contains(pkg)) return true
        if (getCustomPaymentPackages(context).contains(pkg)) return true
        val lower = pkg.lowercase()
        return PAYMENT_PACKAGE_TOKENS.any { lower.contains(it) }
    }

    fun isActive(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return false
        val startedAt = p.getLong(KEY_STARTED_AT_MS, 0L)
        if (startedAt <= 0L || (System.currentTimeMillis() - startedAt) > MAX_PAY_SAFE_DURATION_MS) {
            return false
        }
        return true
    }

    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun canZeroTouchAutoRestore(context: Context): Boolean {
        return hasWriteSecureSettingsPermission(context) || PorterBridge.isEnhancedModeActive()
    }

    fun getAdbGrantCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    }

    fun getStatusBadgeText(context: Context): String {
        return when {
            isActive(context) -> "💳 Pay Safe Active · Accessibility paused for payment"
            hasWriteSecureSettingsPermission(context) -> "⚡ Zero-Touch Auto-Resume Active (WRITE_SECURE_SETTINGS)"
            PorterBridge.isEnhancedModeActive() -> "⚡ Zero-Touch Auto-Resume Active (Shizuku/Porter)"
            isAutoYieldEnabled(context) -> "🛡️ Auto-Pause Active · 1-Tap Resume after payment"
            else -> "Paused (Manual Mode)"
        }
    }

    /**
     * Immediately pauses Focus Elite's AccessibilityService so banking/UPI apps (BHIM, YONO, etc.)
     * pass their Accessibility check without showing a blocking Security Alert.
     */
    fun enterPaymentSafeMode(
        context: Context,
        triggeringPackage: String? = null,
        autoTriggered: Boolean = false
    ) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        val seenInPayment = !triggeringPackage.isNullOrBlank() && isPaymentOrBankingPackage(appCtx, triggeringPackage)

        prefs(appCtx).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_AT_MS, now)
            .putString(KEY_TRIGGER_PKG, triggeringPackage ?: "")
            .putBoolean(KEY_SEEN_IN_PAYMENT_APP, seenInPayment)
            .putLong(KEY_LEFT_PAYMENT_SINCE_MS, 0L)
            .apply()

        // Ensure SettingsTamperGuard never blocks Accessibility Settings during/after payment
        SettingsTamperGuard.grantPermissionSetupGrace(appCtx, MAX_PAY_SAFE_DURATION_MS)

        // Cancel any stale resume notification
        cancelResumeNotification(appCtx)

        // 1. Disable our AccessibilityService cleanly via native API 24+ disableSelf()
        try {
            val svc = BlockerAccessibilityService.instance
            if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                svc.dismissAccessibilityOverlayShield()
                svc.disableSelf()
            }
        } catch (_: Throwable) {}

        // 2. Also scrub component from Settings.Secure if we hold WRITE_SECURE_SETTINGS or Shizuku
        //    so even immediate re-queries by BHIM see zero Focus Elite accessibility entries.
        try {
            if (hasWriteSecureSettingsPermission(appCtx)) {
                setAccessibilityEnabledViaSecureSettings(appCtx, enable = false)
            } else if (PorterBridge.isEnhancedModeActive()) {
                PorterBridge.setAccessibilityServiceDisabled(appCtx)
            }
        } catch (_: Throwable) {}

        // Keep UsageStats monitor alive so it detects when the user finishes paying
        try {
            FocusForegroundMonitorService.start(appCtx)
        } catch (_: Throwable) {}

        requestTileRefresh(appCtx)

        val toastMsg = if (autoTriggered) {
            if (canZeroTouchAutoRestore(appCtx)) {
                "💳 Pay Safe Mode ON — Accessibility paused for payment. Will auto-resume when you exit."
            } else {
                "💳 Pay Safe Mode ON — Accessibility paused so payment app works cleanly."
            }
        } else {
            "💳 Pay Safe Mode ON — Open BHIM / Bank app now. Shield resumes when you finish."
        }
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(appCtx, toastMsg, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Called every ~1s by FocusForegroundMonitorService while Pay Safe Mode is active.
     * Detects when the user has finished using their payment/banking app and restores the shield.
     */
    fun onForegroundPollTick(context: Context, visiblePackages: List<String>) {
        val appCtx = context.applicationContext
        val p = prefs(appCtx)
        if (!p.getBoolean(KEY_ACTIVE, false)) return

        val now = System.currentTimeMillis()
        val startedAt = p.getLong(KEY_STARTED_AT_MS, 0L)
        val elapsedTotal = now - startedAt

        // Hard 10-minute safety cap
        if (startedAt <= 0L || elapsedTotal >= MAX_PAY_SAFE_DURATION_MS) {
            exitPaymentSafeModeAndRestore(appCtx, reason = "timeout")
            return
        }

        // If user already manually re-enabled Accessibility, clear Pay Safe Mode silently
        if (SettingsTamperGuard.isAccessibilityAlreadyEnabled(appCtx) && BlockerAccessibilityService.instance != null) {
            clearPaySafeState(appCtx)
            return
        }

        val currentlyInPaymentApp = visiblePackages.any { isPaymentOrBankingPackage(appCtx, it) }
        val seenInPayment = p.getBoolean(KEY_SEEN_IN_PAYMENT_APP, false)

        if (currentlyInPaymentApp) {
            if (!seenInPayment || p.getLong(KEY_LEFT_PAYMENT_SINCE_MS, 0L) != 0L) {
                p.edit()
                    .putBoolean(KEY_SEEN_IN_PAYMENT_APP, true)
                    .putLong(KEY_LEFT_PAYMENT_SINCE_MS, 0L)
                    .apply()
            }
            return
        }

        // If triggered from Quick Settings Tile / Notification BEFORE opening BHIM,
        // give the user up to 90 seconds to switch into their payment app.
        if (!seenInPayment) {
            if (elapsedTotal >= PRE_LAUNCH_WAIT_TIMEOUT_MS) {
                exitPaymentSafeModeAndRestore(appCtx, reason = "idle_timeout")
            }
            return
        }

        // Ignore transient SystemUI / keyboard / blank states while payment app might be showing biometric prompt
        val nonSystemFg = visiblePackages.filter {
            it.isNotBlank() &&
                it != "com.android.systemui" &&
                !SystemExclusions.isInputMethod(appCtx, it)
        }
        if (nonSystemFg.isEmpty()) {
            return
        }

        // User has exited the payment app to Home or another app — debounce for 3.5 seconds
        val leftSince = p.getLong(KEY_LEFT_PAYMENT_SINCE_MS, 0L)
        if (leftSince <= 0L) {
            p.edit().putLong(KEY_LEFT_PAYMENT_SINCE_MS, now).apply()
            return
        }

        if ((now - leftSince) >= EXIT_DEBOUNCE_MS) {
            exitPaymentSafeModeAndRestore(appCtx, reason = "payment_exited")
        }
    }

    /**
     * Exits Pay Safe Mode and restores Focus Elite's AccessibilityService:
     * - Tier 1: Automatic zero-touch restore via WRITE_SECURE_SETTINGS or Shizuku/Porter.
     * - Tier 2: Posts a 1-tap "Resume Focus Shield" notification opening Accessibility Settings.
     */
    fun exitPaymentSafeModeAndRestore(context: Context, reason: String = "manual"): Boolean {
        val appCtx = context.applicationContext
        clearPaySafeState(appCtx)

        // Already enabled?
        if (SettingsTamperGuard.isAccessibilityAlreadyEnabled(appCtx)) {
            cancelResumeNotification(appCtx)
            requestTileRefresh(appCtx)
            return true
        }

        // Tier 1A: Direct WRITE_SECURE_SETTINGS (one-time ADB grant, works permanently across reboots)
        var restored = false
        if (hasWriteSecureSettingsPermission(appCtx)) {
            restored = setAccessibilityEnabledViaSecureSettings(appCtx, enable = true)
        }

        // Tier 1B: Shizuku / Porter Bridge
        if (!restored && PorterBridge.isEnhancedModeActive()) {
            restored = PorterBridge.ensureAccessibilityServiceEnabled(appCtx, forceBypassPaymentGuard = true)
        }

        requestTileRefresh(appCtx)

        if (restored) {
            cancelResumeNotification(appCtx)
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(
                        appCtx,
                        "🛡️ Focus Elite Shield auto-resumed after payment!",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Throwable) {}
            }
            return true
        }

        // Tier 2: Standard Android 1-Tap Resume Notification (with Tamper Guard grace unlocked)
        SettingsTamperGuard.grantPermissionSetupGrace(appCtx, 180_000L)
        postResumeShieldNotification(appCtx)
        return false
    }

    fun clearPaySafeState(context: Context) {
        val appCtx = context.applicationContext
        prefs(appCtx).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_STARTED_AT_MS, 0L)
            .putBoolean(KEY_SEEN_IN_PAYMENT_APP, false)
            .putLong(KEY_LEFT_PAYMENT_SINCE_MS, 0L)
            .apply()
        requestTileRefresh(appCtx)
    }

    fun onAccessibilityReconnected(context: Context) {
        val appCtx = context.applicationContext
        clearPaySafeState(appCtx)
        cancelResumeNotification(appCtx)
    }

    private fun setAccessibilityEnabledViaSecureSettings(context: Context, enable: Boolean): Boolean {
        return try {
            val cr = context.contentResolver
            val flatFull = ComponentName(context, BlockerAccessibilityService::class.java).flattenToString()
            val flatShort = ComponentName(context, BlockerAccessibilityService::class.java).flattenToShortString()
            val currentRaw = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val existing = currentRaw.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "null" }
                .filterNot { it == flatFull || it == flatShort || it.endsWith("/${BlockerAccessibilityService::class.java.canonicalName}") }
                .toMutableList()

            if (enable) {
                existing.add(flatFull)
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existing.joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } else {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existing.joinToString(":"))
                if (existing.isEmpty()) {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun postResumeShieldNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    RESUME_CHANNEL_ID,
                    "Pay Safe Shield Resume",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Prompts to resume Focus Elite Shield in 1 tap after completing a payment"
                }
                nm.createNotificationChannel(ch)
            }

            val openSettingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                RESUME_NOTIFICATION_ID,
                openSettingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, RESUME_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield_security)
                .setContentTitle("💳 Payment Done — Tap to Resume Focus Shield")
                .setContentText("Re-enable Focus Elite in Accessibility (1 tap). For 100% automatic resume, enable Zero-Touch in Focus & Rules.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Tap to re-enable Focus Elite in Accessibility Settings (Setup Grace unlocked). Want 100% zero-touch auto-resume? Grant WRITE_SECURE_SETTINGS once or connect Shizuku in Focus & Rules."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            nm.notify(RESUME_NOTIFICATION_ID, notif)
        } catch (_: Throwable) {}
    }

    fun cancelResumeNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(RESUME_NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }

    fun requestTileRefresh(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, PaymentSafeTileService::class.java)
                )
            } catch (_: Throwable) {}
        }
    }
}
