package com.focusguardpro.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

object SystemExclusions {

    private val hardcodedExcluded: Set<String> = setOf(
        "com.android.systemui",
        "android",
        "com.android.settings",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.touchtype.swiftkey.beta",
        "com.samsung.android.honeyboard",
        "com.android.inputmethod.latin",
        "com.miui.voiceassist",
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "com.facemoji.lite.xiaomi",
        "com.syntellia.fleksy.keyboard",
        "com.menny.android.anysoftkeyboard",
        "org.dslul.openboard.inputmethod.latin",
        "dev.patrickgold.florisboard"
    )

    fun isExcludedPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        val myPkg = context.packageName
        if (packageName == myPkg || packageName == "com.focusguardpro.app" || packageName == "com.focusguardpro.elite") {
            return true
        }
        if (hardcodedExcluded.contains(packageName)) {
            return true
        }
        if (isInputMethod(context, packageName)) {
            return true
        }
        val launcher = getDefaultLauncherPackage(context)
        if (launcher != null && launcher == packageName) {
            return true
        }
        val dialer = getDefaultDialerPackage(context)
        return dialer != null && dialer == packageName
    }

    fun isInputMethod(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val lowerPkg = packageName.lowercase()

        // 1. Direct heuristic pattern matching for known keyboard namespaces
        if (lowerPkg.contains("inputmethod") ||
            lowerPkg.contains("keyboard") ||
            lowerPkg.contains(".ime") ||
            lowerPkg.endsWith(".ime") ||
            lowerPkg.contains("swiftkey") ||
            lowerPkg.contains("gboard") ||
            lowerPkg.contains("honeyboard") ||
            lowerPkg.contains("fleksy") ||
            lowerPkg.contains("florisboard") ||
            lowerPkg.contains("openboard") ||
            lowerPkg.contains("anysoftkeyboard")
        ) {
            return true
        }

        // 2. Query active system default and enabled input methods via Secure Settings
        try {
            val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!defaultIme.isNullOrEmpty() && defaultIme.contains(packageName)) {
                return true
            }
            val enabledImes = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            if (!enabledImes.isNullOrEmpty() && enabledImes.contains(packageName)) {
                return true
            }
        } catch (_: Exception) {}

        // 3. Query InputMethodManager
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val list = imm?.inputMethodList
            if (list != null) {
                for (info in list) {
                    if (info.packageName == packageName) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        return false
    }

    private fun getDefaultDialerPackage(context: Context): String? {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            tm?.defaultDialerPackage
        } catch (_: Exception) {
            null
        }
    }

    private fun getDefaultLauncherPackage(context: Context): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }
}
