package com.focusguardpro.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker that periodically checks foreground monitoring health
 * and enforces distraction blocking during the 9 to 6 window.
 */
class FocusMonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val UNIQUE_WORK_NAME = "focus_guard_periodic_monitor"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<FocusMonitorWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val shieldPrefs = ctx.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            return Result.success()
        }

        // 1. Ensure Foreground Monitor Service and Watchdog Service are running
        FocusForegroundMonitorService.start(ctx)
        WatchdogService.start(ctx)

        // 2. If currently 9 AM to 6 PM, check if current foreground package is distracting
        if (FocusLayerManager.isActiveNow(ctx)) {
            val fgPkg = UsageStatsHelper.getForegroundPackage(ctx)
            if (!fgPkg.isNullOrBlank() &&
                fgPkg != ctx.packageName &&
                fgPkg != "com.focusguardpro.elite" &&
                fgPkg != "com.focusguardpro.app" &&
                !SystemExclusions.isExcludedPackage(ctx, fgPkg) &&
                !SystemExclusions.isInputMethod(ctx, fgPkg)
            ) {
                val allowed = FocusLayerManager.isAllowedDuringFocus(ctx, fgPkg)
                if (!allowed) {
                    if (PorterBridge.isEnhancedModeActive()) {
                        try {
                            PorterBridge.forceStopPackage(fgPkg)
                        } catch (_: Exception) {}
                    }
                    TimeLimitManager(ctx).triggerBlock(fgPkg, BlockReason.FOCUS_LAYER_SCHEDULED.wireKey)
                }
            }
        }

        return Result.success()
    }
}
