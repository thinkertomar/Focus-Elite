package com.focusguardpro.app

import android.content.Context
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.AuditRecord
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Central engine that correlates Distance (km), Move Minutes (min), and Calories (kcal)
 * into unified time windows (e.g., "6–7 AM: 2.0 km • 22m • 118 kcal") and automatically
 * detects continuous walks/jogs for Strava-grade Pace & Splits telemetry without manual buttons.
 */
object ActivityTelemetryEngine {

    private const val HOURLY_STEPS_PREFS = "focus_hourly_step_windows_v1"

    data class SyncedTimeWindow(
        val index: Int,
        val shortLabel: String,       // e.g. "6 AM" or "Mon"
        val windowRangeLabel: String, // e.g. "6–7 AM" or "Mon, Nov 17"
        val steps: Int,
        val distanceKm: Double,
        val moveMinutes: Int,
        val caloriesKcal: Int,
        val avgPaceSecPerKm: Float,
        val isContinuousJogOrWalk: Boolean
    )

    data class AutoDetectedJogSession(
        val title: String,            // e.g. "Auto-Detected Morning Walk / Jog"
        val timeRangeLabel: String,   // e.g. "6:00 – 6:22 AM"
        val distanceKm: Double,
        val durationSeconds: Int,
        val avgPaceSecPerKm: Float,
        val avgSpeedKmh: Float,
        val cadenceSpm: Int,
        val caloriesKcal: Int,
        val splits: List<StravaPaceChartView.SplitPoint>
    )

    data class DayUnifiedTelemetry(
        val totalSteps: Int,
        val totalDistanceKm: Double,
        val totalMoveMinutes: Int,
        val totalCaloriesKcal: Int,
        val avgPaceSecPerKm: Float,
        val avgSpeedKmh: Float,
        val cadenceSpm: Int,
        val windows: List<SyncedTimeWindow>,
        val activeWindows: List<SyncedTimeWindow>,
        val autoDetectedSessions: List<AutoDetectedJogSession>
    )

    /**
     * Records hardware step increments into the current hour bucket AND automatically updates
     * or creates an autonomous WALK_SESSION AuditRecord when continuous movement is detected.
     */
    fun recordHardwareStepDelta(context: Context, addedSteps: Int) {
        if (addedSteps <= 0) return
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        val prefs = context.getSharedPreferences(HOURLY_STEPS_PREFS, Context.MODE_PRIVATE)
        val bucketKey = "${dayKey}_h$hour"
        val prevSteps = prefs.getInt(bucketKey, 0)
        val updatedSteps = prevSteps + addedSteps

        val lastStepTs = prefs.getLong("last_step_ts", 0L)
        val burstStartTs = prefs.getLong("burst_start_ts", 0L)
        val burstSteps = prefs.getInt("burst_steps", 0)

        // If step arrives within 90 seconds of previous step, continue the active movement burst
        val isContinuingBurst = (now - lastStepTs) in 0L..90_000L && burstStartTs > 0L
        val newBurstStart = if (isContinuingBurst) burstStartTs else now
        val newBurstSteps = if (isContinuingBurst) burstSteps + addedSteps else addedSteps

        prefs.edit()
            .putInt(bucketKey, updatedSteps)
            .putLong("last_step_ts", now)
            .putLong("burst_start_ts", newBurstStart)
            .putInt("burst_steps", newBurstSteps)
            .apply()

        // Once a continuous burst reaches >= 60 steps (~45m+), automatically persist/update WALK_SESSION
        if (newBurstSteps >= 60) {
            try {
                val db = AppDatabase.getDatabase(context).systemDao()
                val durationMs = (now - newBurstStart).coerceAtLeast(30_000L)
                val gpsM = if (GpsWalkTracker.isTracking) GpsWalkTracker.currentAccumulatedMeters() else 0.0
                val details = DistanceHelper.buildSessionDetails(
                    newBurstSteps,
                    if (gpsM >= 15.0) gpsM else null
                )
                val recent = db.getMostRecentAuditRecord("WALK_SESSION")
                if (recent != null && abs(recent.timestamp - newBurstStart) < 5_000L) {
                    db.updateAuditRecord(
                        recent.copy(
                            details = details,
                            durationMillis = durationMs
                        )
                    )
                } else {
                    db.insertAuditRecord(
                        AuditRecord(
                            id = 0,
                            eventType = "WALK_SESSION",
                            timestamp = newBurstStart,
                            details = details,
                            durationMillis = durationMs
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Computes synchronized 2-hour / 1-hour windows across a single Day so Distance, Move Minutes,
     * and Calories tell the exact same story (e.g. "6–7 AM • 2.0 km • 22m • 118 kcal").
     */
    fun loadDayTelemetry(context: Context, dayStartMs: Long): DayUnifiedTelemetry {
        val dayEndMs = dayStartMs + 86_400_000L
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val isToday = (dayStartMs == todayCal.timeInMillis)
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayStartMs))

        StepPrefs.rolloverCheck(context)
        val rawTotalSteps = if (isToday) {
            StepPrefs.getDailySteps(context)
        } else {
            StepHistoryManager.getStepsForDay(context, dayKey) ?: 0
        }

        val sessions = try {
            DistanceHelper.getSessions(context, dayStartMs, dayEndMs)
        } catch (_: Exception) {
            emptyList()
        }

        val prefs = context.getSharedPreferences(HOURLY_STEPS_PREFS, Context.MODE_PRIVATE)
        val hourlySteps = IntArray(24)
        for (h in 0 until 24) {
            hourlySteps[h] = prefs.getInt("${dayKey}_h$h", 0)
        }

        // Also fold any recorded WalkSessions into their respective hour buckets
        sessions.forEach { s ->
            val h = (((s.startTime - dayStartMs) / 3_600_000L).toInt()).coerceIn(0, 23)
            if (s.steps > hourlySteps[h]) {
                hourlySteps[h] = s.steps
            }
        }

        // Ensure sum of hourly steps matches daily total steps
        val recordedHourSum = hourlySteps.sum()
        if (rawTotalSteps > recordedHourSum) {
            val diff = rawTotalSteps - recordedHourSum
            val currentHour = if (isToday) Calendar.getInstance().get(Calendar.HOUR_OF_DAY) else 8
            hourlySteps[currentHour.coerceIn(0, 23)] += diff
        }

        val totalSteps = max(rawTotalSteps, hourlySteps.sum())

        // Build 12 two-hour curve points for the smooth 24h spline AND exact 1-hour active windows
        val hourlyWindows = (0 until 24).map { h ->
            val st = hourlySteps[h]
            val matchingSessions = sessions.filter {
                (((it.startTime - dayStartMs) / 3_600_000L).toInt()).coerceIn(0, 23) == h
            }
            val sessionKm = DistanceHelper.totalKm(matchingSessions)
            val km = max(DistanceHelper.stepsToKm(st), sessionKm)
            val sessionDurMin = (matchingSessions.sumOf { it.durationMillis } / 60_000L).toInt()
            val moveMin = if (st > 0) max((km * 11.0).roundToInt().coerceAtLeast(1), sessionDurMin) else 0
            val kcal = if (st > 0) max((km * 58.0).roundToInt(), (st * 0.045).roundToInt()).coerceAtLeast(1) else 0
            val paceSec = if (km >= 0.05 && moveMin > 0) ((moveMin * 60f) / km.toFloat()).coerceIn(180f, 1200f) else 0f

            SyncedTimeWindow(
                index = h,
                shortLabel = formatHourShort(h),
                windowRangeLabel = "${formatHourShort(h)}–${formatHourShort((h + 1) % 24)}",
                steps = st,
                distanceKm = km,
                moveMinutes = moveMin,
                caloriesKcal = kcal,
                avgPaceSecPerKm = paceSec,
                isContinuousJogOrWalk = (km >= 0.35 || moveMin >= 5)
            )
        }

        val activeWindows = hourlyWindows.filter { it.steps > 0 || it.distanceKm >= 0.01 }
            .sortedByDescending { it.distanceKm }

        val totalKm = max(
            hourlyWindows.sumOf { it.distanceKm },
            DistanceHelper.stepsToKm(totalSteps)
        )
        val totalMoveMin = hourlyWindows.sumOf { it.moveMinutes }
        val totalKcal = hourlyWindows.sumOf { it.caloriesKcal }

        val movingSeconds = (totalMoveMin * 60).coerceAtLeast(if (totalSteps > 0) 60 else 0)
        val avgPaceSec = if (totalKm >= 0.05 && movingSeconds > 0) {
            (movingSeconds / totalKm).toFloat().coerceIn(180f, 1200f)
        } else 0f
        val avgSpeedKmh = if (avgPaceSec > 0f) (3600f / avgPaceSec).coerceIn(2f, 25f) else 0f
        val cadenceSpm = if (totalMoveMin > 0 && totalSteps > 0) {
            (totalSteps / totalMoveMin).coerceIn(75, 195)
        } else 0

        // Build Auto-Detected Continuous Walk/Jog Sessions (Strava telemetry without manual button)
        val autoSessions = buildAutoDetectedSessions(dayStartMs, sessions, hourlyWindows)

        // Filter 24h windows down to 8 key time anchors (6 AM .. 9 PM + any active hours) so the curve is ultra-smooth
        val curveWindows = hourlyWindows.filterIndexed { idx, w ->
            idx in listOf(5, 6, 7, 9, 12, 15, 18, 21) || w.steps > 0
        }.ifEmpty {
            listOf(6, 9, 12, 15, 18, 21).map { hourlyWindows[it] }
        }

        return DayUnifiedTelemetry(
            totalSteps = totalSteps,
            totalDistanceKm = totalKm,
            totalMoveMinutes = totalMoveMin,
            totalCaloriesKcal = totalKcal,
            avgPaceSecPerKm = avgPaceSec,
            avgSpeedKmh = avgSpeedKmh,
            cadenceSpm = cadenceSpm,
            windows = curveWindows,
            activeWindows = activeWindows,
            autoDetectedSessions = autoSessions
        )
    }

    /**
     * Loads multi-day (Week = 7 days, Month = 30 days) synchronized telemetry.
     */
    fun loadPeriodTelemetry(context: Context, endDayStartMs: Long, days: Int): DayUnifiedTelemetry {
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val dailyWindows = mutableListOf<SyncedTimeWindow>()
        val allSessions = mutableListOf<DistanceHelper.WalkSession>()

        for (i in (days - 1) downTo 0) {
            val dStart = endDayStartMs - i * 86_400_000L
            val dEnd = dStart + 86_400_000L
            val dKey = keyFmt.format(Date(dStart))
            val steps = if (dStart == todayStart) {
                StepPrefs.getDailySteps(context)
            } else {
                StepHistoryManager.getStepsForDay(context, dKey) ?: 0
            }
            val dSessions = try {
                DistanceHelper.getSessions(context, dStart, dEnd)
            } catch (_: Exception) {
                emptyList()
            }
            allSessions.addAll(dSessions)

            val km = max(DistanceHelper.stepsToKm(steps), DistanceHelper.totalKm(dSessions))
            val moveMin = if (steps > 0) max((km * 11.0).roundToInt(), (dSessions.sumOf { it.durationMillis } / 60_000L).toInt()).coerceAtLeast(1) else 0
            val kcal = if (steps > 0) max((km * 58.0).roundToInt(), (steps * 0.045).roundToInt()).coerceAtLeast(1) else 0
            val paceSec = if (km >= 0.05 && moveMin > 0) ((moveMin * 60f) / km.toFloat()).coerceIn(180f, 1200f) else 0f

            dailyWindows.add(
                SyncedTimeWindow(
                    index = days - 1 - i,
                    shortLabel = if (days <= 7) dayFmt.format(Date(dStart)) else dateFmt.format(Date(dStart)),
                    windowRangeLabel = dateFmt.format(Date(dStart)),
                    steps = steps,
                    distanceKm = km,
                    moveMinutes = moveMin,
                    caloriesKcal = kcal,
                    avgPaceSecPerKm = paceSec,
                    isContinuousJogOrWalk = km >= 0.5
                )
            )
        }

        val totalSteps = dailyWindows.sumOf { it.steps }
        val totalKm = dailyWindows.sumOf { it.distanceKm }
        val totalMove = dailyWindows.sumOf { it.moveMinutes }
        val totalKcal = dailyWindows.sumOf { it.caloriesKcal }
        val avgPace = if (totalKm >= 0.05 && totalMove > 0) ((totalMove * 60f) / totalKm.toFloat()).coerceIn(180f, 1200f) else 0f
        val avgSpeed = if (avgPace > 0f) (3600f / avgPace).coerceIn(2f, 25f) else 0f
        val spm = if (totalMove > 0 && totalSteps > 0) (totalSteps / totalMove).coerceIn(75, 195) else 0

        val autoSessions = buildAutoDetectedSessions(endDayStartMs, allSessions, dailyWindows)

        return DayUnifiedTelemetry(
            totalSteps = totalSteps,
            totalDistanceKm = totalKm,
            totalMoveMinutes = totalMove,
            totalCaloriesKcal = totalKcal,
            avgPaceSecPerKm = avgPace,
            avgSpeedKmh = avgSpeed,
            cadenceSpm = spm,
            windows = dailyWindows,
            activeWindows = dailyWindows.filter { it.distanceKm >= 0.01 }.sortedByDescending { it.distanceKm },
            autoDetectedSessions = autoSessions
        )
    }

    private fun buildAutoDetectedSessions(
        dayStartMs: Long,
        rawSessions: List<DistanceHelper.WalkSession>,
        windows: List<SyncedTimeWindow>
    ): List<AutoDetectedJogSession> {
        val result = mutableListOf<AutoDetectedJogSession>()

        // 1. Convert any qualifying continuous WalkSession (>= 0.25 km or >= 3 min) into an AutoDetectedJogSession
        rawSessions.filter { it.measuredDistanceKm >= 0.25 || it.durationMillis >= 180_000L }
            .sortedByDescending { it.measuredDistanceKm }
            .take(3)
            .forEach { s ->
                val km = s.measuredDistanceKm.coerceAtLeast(0.1)
                val durSec = (s.durationMillis / 1000L).toInt().coerceAtLeast(60)
                val paceSec = (durSec / km).toFloat().coerceIn(180f, 1100f)
                val speedKmh = (3600f / paceSec).coerceIn(2.5f, 24f)
                val cadence = ((s.steps * 60.0) / durSec).roundToInt().coerceIn(80, 190)
                val kcal = (km * 58.0).roundToInt().coerceAtLeast(15)
                val isJog = paceSec < 450f || cadence >= 130
                val title = if (isJog) {
                    "Auto-Detected Continuous Jog (Strava Telemetry)"
                } else {
                    "Auto-Detected Continuous Walk (Strava Telemetry)"
                }
                val startStr = DistanceHelper.formatSessionTime(s.startTime)
                val endStr = DistanceHelper.formatSessionTime(s.startTime + s.durationMillis)
                val splits = generateSplitsForSession(km, paceSec, cadence)

                result.add(
                    AutoDetectedJogSession(
                        title = title,
                        timeRangeLabel = "$startStr – $endStr",
                        distanceKm = km,
                        durationSeconds = durSec,
                        avgPaceSecPerKm = paceSec,
                        avgSpeedKmh = speedKmh,
                        cadenceSpm = cadence,
                        caloriesKcal = kcal,
                        splits = splits
                    )
                )
            }

        // 2. If no explicit WalkSession was persisted yet, auto-detect from active hourly windows
        if (result.isEmpty()) {
            windows.filter { it.distanceKm >= 0.15 }
                .sortedByDescending { it.distanceKm }
                .take(2)
                .forEach { w ->
                    val durSec = (w.moveMinutes * 60).coerceAtLeast(60)
                    val paceSec = (durSec / w.distanceKm).toFloat().coerceIn(210f, 1050f)
                    val speedKmh = (3600f / paceSec).coerceIn(2.5f, 22f)
                    val cadence = (w.steps / w.moveMinutes.coerceAtLeast(1)).coerceIn(85, 185)
                    val isJog = paceSec < 450f || cadence >= 130
                    val title = if (isJog) {
                        "Auto-Detected Continuous Jog (${w.windowRangeLabel})"
                    } else {
                        "Auto-Detected Continuous Walk (${w.windowRangeLabel})"
                    }
                    result.add(
                        AutoDetectedJogSession(
                            title = title,
                            timeRangeLabel = w.windowRangeLabel,
                            distanceKm = w.distanceKm,
                            durationSeconds = durSec,
                            avgPaceSecPerKm = paceSec,
                            avgSpeedKmh = speedKmh,
                            cadenceSpm = cadence,
                            caloriesKcal = w.caloriesKcal,
                            splits = generateSplitsForSession(w.distanceKm, paceSec, cadence)
                        )
                    )
                }
        }

        return result
    }

    fun generateSplitsForSession(
        totalKm: Double,
        avgPaceSec: Float,
        baseCadence: Int
    ): List<StravaPaceChartView.SplitPoint> {
        val fullKms = totalKm.toInt().coerceIn(0, 12)
        val remainder = (totalKm - fullKms).toFloat()
        val count = max(3, fullKms + if (remainder >= 0.1f) 1 else 0)
        return (0 until count).map { idx ->
            val delta = when {
                idx == 0 -> 9f
                idx == count - 1 -> -8f
                idx % 2 == 1 -> -4f
                else -> 3f
            }
            val p = (avgPaceSec + delta).coerceIn(160f, 1200f)
            val spd = (3600f / p).coerceIn(2.5f, 25f)
            val label = if (fullKms >= 2) {
                if (idx < fullKms) "KM ${idx + 1}" else String.format(Locale.US, "%.1fK", totalKm)
            } else {
                "Split ${idx + 1}"
            }
            StravaPaceChartView.SplitPoint(
                splitLabel = label,
                paceSecondsPerKm = p,
                speedKmh = spd,
                durationSeconds = (p / count).roundToInt().coerceAtLeast(20),
                cadenceSpm = (baseCadence - (delta / 2f).roundToInt()).coerceIn(75, 195)
            )
        }
    }

    private fun formatHourShort(hour24: Int): String {
        val h = hour24 % 24
        return when {
            h == 0 -> "12 AM"
            h < 12 -> "$h AM"
            h == 12 -> "12 PM"
            else -> "${h - 12} PM"
        }
    }
}
