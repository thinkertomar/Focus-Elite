package com.focusguardpro.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

object SystemExclusions {

    // Core Android OS, System UI, Settings, and Essential System Utility packages
    private val SYSTEM_UTILITY_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        // Dialer, Phone, Contacts & SMS/Messaging
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.samsung.android.app.contacts",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        // Camera, Clock/Alarm, Calculator, Calendar, Files, Recorder, SIM, Emergency
        "com.android.camera",
        "com.android.camera2",
        "com.google.android.GoogleCamera",
        "com.sec.android.app.camera",
        "com.oplus.camera",
        "com.oneplus.camera",
        "com.miui.camera",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.android.calculator2",
        "com.google.android.calculator",
        "com.sec.android.app.popupcalculator",
        "com.miui.calculator",
        "com.android.calendar",
        "com.google.android.calendar",
        "com.samsung.android.calendar",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.google.android.apps.nbu.files",
        "com.sec.android.app.myfiles",
        "com.mi.android.globalFileexplorer",
        "com.android.soundrecorder",
        "com.google.android.apps.recorder",
        "com.sec.android.app.voicenote",
        "com.android.stk",
        "com.android.emergency",
        "com.google.android.safetycore",
        "com.google.android.apps.safetyhub",
        "com.google.android.apps.wellbeing",
        "com.google.android.apps.healthdata"
    )

    // Pre-installed or OEM-bundled apps that ARE distractions / browsers / media and MUST remain
    // categorizable and blockable even if they carry FLAG_SYSTEM on the device ROM.
    private val PREINSTALLED_DISTRACTION_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.youtube.kids",
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "com.mi.globalbrowser",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca",
        "com.instagram.android",
        "com.instagram.barcelona",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.snapchat.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "in.startv.hotstar",
        "com.jio.media.ondemand",
        "com.spotify.music",
        "tv.twitch.android.app",
        "com.discord",
        "org.telegram.messenger",
        "com.pinterest",
        "com.linkedin.android",
        "com.google.android.play.games",
        "com.google.android.videos"
    )

    private val PREINSTALLED_DISTRACTION_KEYWORDS = listOf(
        "youtube",
        "chrome",
        "browser",
        "sbrowser",
        "facebook",
        "instagram",
        "whatsapp",
        "snapchat",
        "tiktok",
        "musically",
        "twitter",
        "reddit",
        "netflix",
        "primevideo",
        "hotstar",
        "spotify",
        "twitch",
        "discord",
        "telegram",
        "pinterest",
        "game"
    )

    // Essential 2FA, Authenticator & Payment utilities that should never be blocked mid-workflow
    private val ESSENTIAL_UTILITY_PACKAGES = setOf(
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.duosecurity.duomobile",
        "com.authy.authy",
        "org.fedorahosted.freeotp",
        "com.beemdevelopment.aegis",
        "com.x8bit.bitwarden",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp" // BHIM UPI
    )

    @Volatile
    private var cachedInputMethods: Set<String>? = null
    @Volatile
    private var cachedDefaultIme: String? = null
    @Volatile
    private var lastImeFetchTime: Long = 0L

    @Volatile
    private var cachedDefaultLauncher: String? = null
    @Volatile
    private var cachedDefaultDialer: String? = null
    @Volatile
    private var cachedDefaultSms: String? = null
    @Volatile
    private var lastDefaultsFetchTime: Long = 0L

    private val systemFlagCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>(256)
    private val excludedDecisionCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>(256)
    @Volatile
    private var decisionCacheEpochMs: Long = 0L

    fun onPackageChanged(packageName: String) {
        if (packageName.isBlank()) return
        systemFlagCache.remove(packageName)
        excludedDecisionCache.remove(packageName)
    }

    fun invalidateCache() {
        lastImeFetchTime = 0L
        lastDefaultsFetchTime = 0L
        systemFlagCache.clear()
        excludedDecisionCache.clear()
        decisionCacheEpochMs = 0L
    }

    private fun refreshDefaultsIfNeeded(context: Context, now: Long) {
        if (now - lastDefaultsFetchTime < 60_000L && lastDefaultsFetchTime > 0L) return
        val appCtx = context.applicationContext
        cachedDefaultLauncher = queryDefaultLauncher(appCtx)
        cachedDefaultDialer = queryDefaultDialer(appCtx)
        cachedDefaultSms = queryDefaultSmsPackage(appCtx)
        lastDefaultsFetchTime = now
        if (now - decisionCacheEpochMs > 60_000L) {
            excludedDecisionCache.clear()
            decisionCacheEpochMs = now
        }
    }

    fun isInputMethod(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val now = System.currentTimeMillis()
        var imes = cachedInputMethods
        if (imes == null || now - lastImeFetchTime > 60_000L) {
            imes = try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.inputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
            } catch (_: Exception) {
                emptySet()
            }
            cachedDefaultIme = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            } catch (_: Exception) {
                null
            }
            cachedInputMethods = imes
            lastImeFetchTime = now
        }
        if (imes.contains(packageName)) return true

        val defaultIme = cachedDefaultIme
        if (!defaultIme.isNullOrBlank() && defaultIme.startsWith("$packageName/")) {
            return true
        }

        val lower = packageName.lowercase()
        return lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower == "com.google.android.inputmethod.latin" ||
            lower == "com.samsung.android.honeyboard" ||
            lower == "com.touchtype.swiftkey"
    }

    /**
     * Checks whether [packageName] is an Android system app / device utility that is NOT
     * a pre-installed distraction/browser/social/video app.
     */
    fun isSystemOrCoreUtilityApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == context.packageName ||
            packageName == "com.focuselite.app" ||
            packageName == "com.focusguardpro.elite" ||
            packageName == "com.focusguardpro.app"
        ) {
            return true
        }
        if (SYSTEM_UTILITY_PACKAGES.contains(packageName)) return true
        if (ESSENTIAL_UTILITY_PACKAGES.contains(packageName)) return true

        val now = System.currentTimeMillis()
        refreshDefaultsIfNeeded(context, now)

        excludedDecisionCache[packageName]?.let { return it }

        val decision = computeSystemOrCoreUtilityApp(context, packageName)
        excludedDecisionCache[packageName] = decision
        return decision
    }

    private fun computeSystemOrCoreUtilityApp(context: Context, packageName: String): Boolean {
        if (isInputMethod(context, packageName)) return true
        if (packageName == cachedDefaultLauncher) return true
        if (packageName == cachedDefaultDialer) return true
        if (packageName == cachedDefaultSms) return true

        // Never treat known pre-installed distraction / browser / social apps as exempt system utilities
        if (isPreinstalledDistractionPackage(packageName)) {
            return false
        }

        systemFlagCache[packageName]?.let { return it }

        // Check Android ApplicationInfo flags for system/ROM apps (Camera, Gallery, Clock, Calculator, Settings, OEM tools)
        val isSystemFlag = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) {
            false
        }
        systemFlagCache[packageName] = isSystemFlag
        return isSystemFlag
    }

    fun isPreinstalledDistractionPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        if (PREINSTALLED_DISTRACTION_PACKAGES.contains(lower) || PREINSTALLED_DISTRACTION_PACKAGES.contains(packageName)) {
            return true
        }
        if (BrowserUrlDetector.isBrowser(packageName)) {
            return true
        }
        return PREINSTALLED_DISTRACTION_KEYWORDS.any { kw -> lower.contains(kw) }
    }

    fun isExcludedPackage(context: Context, packageName: String): Boolean {
        return isSystemOrCoreUtilityApp(context, packageName)
    }

    private fun queryDefaultLauncher(context: Context): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryDefaultDialer(context: Context): String? {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val telecomDefault = tm?.defaultDialerPackage
            if (!telecomDefault.isNullOrBlank()) {
                return telecomDefault
            }
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryDefaultSmsPackage(context: Context): String? {
        return try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (_: Throwable) {
            null
        }
    }
}
