package com.focusguardpro.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Handles 1-tap notification shade actions for Pay Safe Mode (Enter Pay Safe / Resume Shield)
 * directly from Focus Elite's persistent shield notification without opening the app.
 */
class PaymentSafeActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ENTER_PAY_SAFE = "com.focusguardpro.app.ACTION_ENTER_PAY_SAFE"
        const val ACTION_RESUME_SHIELD = "com.focusguardpro.app.ACTION_RESUME_SHIELD"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_ENTER_PAY_SAFE -> {
                PaymentSafeModeManager.enterPaymentSafeMode(
                    context = context,
                    triggeringPackage = null,
                    autoTriggered = false
                )
            }
            ACTION_RESUME_SHIELD -> {
                val restored = PaymentSafeModeManager.exitPaymentSafeModeAndRestore(
                    context = context,
                    reason = "notification_action"
                )
                if (!restored) {
                    SettingsTamperGuard.grantPermissionSetupGrace(context, 180_000L)
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
        try {
            FocusForegroundMonitorService.start(context)
        } catch (_: Throwable) {}
    }
}
