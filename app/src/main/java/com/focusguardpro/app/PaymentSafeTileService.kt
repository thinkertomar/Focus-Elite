package com.focusguardpro.app

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * 1-Tap Quick Settings Tile ("Pay Safe Mode") in the Android notification shade.
 *
 * - Tap BEFORE opening BHIM / YONO SBI / Banking apps to instantly pause Accessibility
 *   via disableSelf() so the payment app opens with ZERO Security Alert popups.
 * - Automatically restores Accessibility when you exit the payment app (Zero-Touch via
 *   WRITE_SECURE_SETTINGS / Shizuku, or 1-tap notification fallback).
 */
@RequiresApi(Build.VERSION_CODES.N)
class PaymentSafeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val paySafeActive = PaymentSafeModeManager.isActive(this)
        val a11yEnabled = SettingsTamperGuard.isAccessibilityAlreadyEnabled(this)

        if (paySafeActive || !a11yEnabled) {
            val restored = PaymentSafeModeManager.exitPaymentSafeModeAndRestore(this, reason = "qs_tile")
            if (!restored) {
                SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pi = android.app.PendingIntent.getActivity(
                            this,
                            2088,
                            intent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                        startActivityAndCollapse(pi)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(intent)
                    }
                } catch (_: Throwable) {
                    try {
                        startActivity(intent)
                    } catch (_: Throwable) {}
                }
            }
        } else {
            PaymentSafeModeManager.enterPaymentSafeMode(this, triggeringPackage = null, autoTriggered = false)
        }
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val paySafeActive = PaymentSafeModeManager.isActive(this)
        val a11yEnabled = SettingsTamperGuard.isAccessibilityAlreadyEnabled(this)

        tile.label = "Pay Safe Mode"
        when {
            paySafeActive -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Open Bank/UPI"
                }
            }
            !a11yEnabled -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to Resume"
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Shield Active"
                }
            }
        }
        try {
            tile.updateTile()
        } catch (_: Throwable) {}
    }
}
