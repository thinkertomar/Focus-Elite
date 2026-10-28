package com.focusguardpro.app

import android.content.Context
import java.util.Calendar

object BlockPolicyEngine {

    fun evaluateApp(context: Context, packageName: String, nowMs: Long = FocusLayerManager.verifiedNowMs(context)): BlockDecision {
        val shieldPrefs = context.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            return BlockDecision.Allowed
        }

        // Never block FocusGuard Pro or any system/keyboard exclusion
        if (SystemExclusions.isExcludedPackage(context, packageName)) {
            return BlockDecision.Allowed
        }

        // Check Focus Layer (respects custom hours, active days criteria, and Calendar Holiday lock)
        if (FocusLayerManager.isScheduledActiveNow(context, nowMs) && !FocusLayerManager.isAllowedDuringFocus(context, packageName)) {
            return BlockDecision.Blocked(BlockReason.FOCUS_LAYER_SCHEDULED, packageName)
        }

        if (FocusLayerManager.isManualOverrideActive(context) && FocusLayerManager.isActiveNow(context) && !FocusLayerManager.isAllowedDuringFocus(context, packageName)) {
            return BlockDecision.Blocked(BlockReason.FOCUS_LAYER_MANUAL, packageName)
        }

        // Hard Block check
        if (HardBlockManager.isHardBlocked(context, packageName)) {
            if (HardBlockManager.isEmergencyActive(context, packageName)) {
                return BlockDecision.Allowed
            }
            return BlockDecision.Blocked(BlockReason.HARD_BLOCK, packageName)
        }

        // Active temporary bypass
        if (SystemConfigManager.isBypassActive(context, packageName)) {
            return BlockDecision.Allowed
        }

        // Blackout window check
        val blackoutStart = TimeLimitManager.getBlackoutStartMinutes(context, packageName)
        val blackoutEnd = TimeLimitManager.getBlackoutEndMinutes(context, packageName)
        if (isTimeInBlackout(blackoutStart, blackoutEnd, nowMs)) {
            return BlockDecision.Blocked(BlockReason.BLACKOUT, packageName)
        }

        // Daily usage limit
        val isIgnored = AppCategoryPrefs.getIgnoredApps(context).contains(packageName)
        val customLimit = TimeLimitManager.getLimitMinutes(context, packageName)
        val limitMinutes = if (isIgnored) {
            if (customLimit in 1..29) customLimit else 30
        } else {
            customLimit
        }

        if (limitMinutes > 0) {
            val usedMinutes = if (BrowserUrlDetector.isBrowser(packageName)) {
                BrowserFairTimeTracker.getDistractingMinutesToday(context)
            } else {
                UsageStatsHelper.getTodayScreenTimeForPackage(context, packageName) / 60000
            }
            if (usedMinutes >= limitMinutes) {
                val detailMsg = if (isIgnored) "30m_cap_ignored" else null
                return BlockDecision.Blocked(BlockReason.DAILY_LIMIT, packageName, detailMsg)
            }
        }

        return BlockDecision.Allowed
    }

    fun evaluateWebsite(context: Context, host: String, nowMs: Long = System.currentTimeMillis()): BlockDecision {
        val shieldPrefs = context.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            return BlockDecision.Allowed
        }
        if (!WebsitePrefs.isBlocked(context, host)) {
            return BlockDecision.Allowed
        }
        if (SystemConfigManager.isBypassActive(context, host)) {
            return BlockDecision.Allowed
        }
        return BlockDecision.Blocked(BlockReason.WEBSITE, host)
    }

    fun isAppBlocked(context: Context, packageName: String): Boolean {
        return evaluateApp(context, packageName) is BlockDecision.Blocked
    }

    fun setAppBlocked(context: Context, packageName: String, blocked: Boolean, defaultLimitMinutes: Int = 1) {
        try {
            HardBlockManager.setHardBlocked(context, packageName, blocked)
        } catch (_: Exception) {}
        if (blocked) {
            val existingLimit = TimeLimitManager.getLimitMinutes(context, packageName)
            if (existingLimit <= 0) {
                setTimeLimit(context, packageName, defaultLimitMinutes)
            }
        } else {
            setTimeLimit(context, packageName, 0)
        }
    }

    fun setTimeLimit(context: Context, packageName: String, minutes: Int) {
        TimeLimitManager.setLimitMinutes(context, packageName, minutes)
    }

    fun setBlackoutWindow(context: Context, packageName: String, startMin: Int, endMin: Int) {
        TimeLimitManager.setBlackoutWindow(context, packageName, startMin, endMin)
    }

    fun isTimeInBlackout(startMinutes: Int, endMinutes: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (startMinutes < 0 || endMinutes < 0 || startMinutes == endMinutes) {
            return false
        }
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }
}
