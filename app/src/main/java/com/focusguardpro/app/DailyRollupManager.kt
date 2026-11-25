package com.focusguardpro.app

import android.content.Context
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.DailyUsageSnapshot
import com.focusguardpro.app.db.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object DailyRollupManager {

    private const val PREFS_ROLLUP = "focus_daily_rollup_prefs"
    private const val LAST_ROLLUP_KEY = "LAST_ROLLUP_DAY"
    private const val RETENTION_DAYS = 35
    private const val MAX_CATCHUP_DAYS = 7

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRollingUp = AtomicBoolean(false)

    fun rollupIfNeeded(context: Context) {
        val appCtx = context.applicationContext
        val yesterdayCal = startOfDay(1)
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val yesterdayKey = dayFmt.format(yesterdayCal.time)

        val prefs = appCtx.getSharedPreferences(PREFS_ROLLUP, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_ROLLUP_KEY, null) == yesterdayKey) {
            return
        }

        if (!isRollingUp.compareAndSet(false, true)) return
        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(appCtx).systemDao()
                val lastRollup = prefs.getString(LAST_ROLLUP_KEY, null) ?: dao.getState(LAST_ROLLUP_KEY)
                if (lastRollup != null && lastRollup >= yesterdayKey) {
                    prefs.edit().putString(LAST_ROLLUP_KEY, yesterdayKey).apply()
                    return@launch
                }

                for (daysAgo in MAX_CATCHUP_DAYS downTo 1) {
                    val cal = startOfDay(daysAgo)
                    val dKey = dayFmt.format(cal.time)
                    if (lastRollup != null && dKey <= lastRollup) continue
                    if (dao.countSnapshotsForDay(dKey) > 0) continue

                    val dStart = cal.timeInMillis
                    val dEnd = dStart + 86_400_000L - 1L
                    val perApp = UsageStatsHelper.getPerAppUsage(appCtx, dStart, dEnd)
                    if (perApp.isNotEmpty()) {
                        val rows = ArrayList<DailyUsageSnapshot>(perApp.size)
                        for ((pkg, ms) in perApp) {
                            val mins = (ms / 60_000L).toInt()
                            if (mins > 0) {
                                rows.add(
                                    DailyUsageSnapshot(
                                        dayKey = dKey,
                                        packageName = pkg,
                                        minutes = mins
                                    )
                                )
                            }
                        }
                        if (rows.isNotEmpty()) {
                            dao.upsertDailyUsageSnapshots(rows)
                        }
                    }
                }

                dao.putState(SystemState(LAST_ROLLUP_KEY, yesterdayKey, System.currentTimeMillis()))
                prefs.edit().putString(LAST_ROLLUP_KEY, yesterdayKey).apply()

                val oldestCal = startOfDay(RETENTION_DAYS)
                val oldestKey = dayFmt.format(oldestCal.time)
                dao.deleteSnapshotsOlderThan(oldestKey)

                try {
                    SmartSleepEngine.syncRecentNightsArchive(appCtx, lookbackDays = 14)
                } catch (_: Throwable) {}
            } catch (_: Throwable) {
            } finally {
                isRollingUp.set(false)
            }
        }
    }

    private fun startOfDay(daysAgo: Int): Calendar {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
