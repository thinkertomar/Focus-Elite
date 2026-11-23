package com.focusguardpro.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.AuditRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-Signal Overnight Sleep & Mid-Night Awakening Reconstruction Engine.
 *
 * Solves the real-world fragmented sleep problem (e.g. going to sleep at 11:00 PM,
 * waking up 1–2 times briefly to check the phone / drink water / eat a snack, falling
 * back asleep, and finally waking up at 6:00 AM):
 *
 * 1. Fuses real-time screen lifecycle events recorded by WatchdogService
 *    (SCREEN_OFF / SCREEN_ON / USER_PRESENT) with Android UsageStatsManager
 *    overnight events (SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE / KEYGUARD /
 *    ACTIVITY_RESUMED) and hourly hardware step pulses.
 * 2. Builds a clean timeline of continuous inactive/dark rest blocks across the
 *    biological overnight window (8:00 PM previous evening -> 1:00 PM wake day).
 * 3. Smooths micro clock glances (< 90 seconds) without breaking sleep continuity.
 * 4. Intelligently stitches adjacent sleep blocks separated by mid-night awakenings
 *    (1.5 min to 45 min, or up to 75 min if flanked by two >= 45 min night blocks)
 *    into one unified Sleep Episode.
 * 5. Computes exact Bedtime, Final Wake-up, Time in Bed, Effective Actual Sleep
 *    (Time in Bed minus true awake interruptions), Sleep Efficiency %, Recovery Score,
 *    and itemized Awakening episodes with timestamps and inferred reasons.
 */
object SmartSleepEngine {

    private const val PREFS_SIGNALS = "FocusElite_SleepSignals_v1"
    private const val KEY_SCREEN_EVENTS = "screen_events_json"
    private const val KEY_OVERRIDE_PREFIX = "sleep_override_"
    private const val MAX_STORED_EVENTS = 600
    private const val RETENTION_MS = 14L * 86_400_000L

    // Event type constants for our persistent signal log
    const val SIG_SCREEN_OFF = 1
    const val SIG_SCREEN_ON = 2
    const val SIG_UNLOCK = 3

    // Thresholds tuned for real human sleep behavior
    private const val MICRO_GLANCE_MAX_MS = 90_000L            // <= 90s clock check doesn't split sleep
    private const val MIN_SLEEP_BLOCK_MS = 20L * 60_000L       // >= 20m dark/still block counts as sleep segment
    private const val STANDARD_AWAKE_STITCH_MS = 45L * 60_000L // <= 45m mid-night wake-up stitches cleanly
    private const val EXTENDED_AWAKE_STITCH_MS = 75L * 60_000L // <= 75m stitches if both sides >= 45m at night
    private const val EXTENDED_SIDE_MIN_MS = 45L * 60_000L

    data class SleepSegment(
        val startMs: Long,
        val endMs: Long,
        val isAwake: Boolean,
        val label: String = "",
        val stepsDuring: Int = 0
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
        val durationMinutes: Int get() = (durationMs / 60_000L).toInt()
    }

    data class AwakeningEpisode(
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val reason: String,
        val stepsDuring: Int = 0
    ) {
        val durationMinutes: Int get() = max(1, (durationMs / 60_000.0).roundToInt())
    }

    data class SleepDetectionResult(
        val dateKey: String,
        val bedtimeMs: Long,
        val wakeTimeMs: Long,
        val timeInBedMs: Long,
        val effectiveSleepMs: Long,
        val awakeTotalMs: Long,
        val efficiencyPct: Int,
        val qualityScore: Int,
        val qualityLabel: String,
        val awakenings: List<AwakeningEpisode>,
        val segments: List<SleepSegment>,
        val microGlanceCount: Int,
        val signalSource: String,
        val confidenceLabel: String,
        val isManualAdjusted: Boolean = false,
        val isAlreadyLogged: Boolean = false,
        val loggedRecordId: Int? = null,
        val insightText: String = ""
    ) {
        val hasValidSleep: Boolean get() = effectiveSleepMs >= 20L * 60_000L && wakeTimeMs > bedtimeMs
    }

    data class WeeklySleepSummary(
        val nightsLogged: Int,
        val avgEffectiveSleepMs: Long,
        val avgEfficiencyPct: Int,
        val sleepDebtMs: Long,
        val consistencyLabel: String
    )

    private data class SignalEvent(
        val timestamp: Long,
        val type: Int // SIG_SCREEN_OFF, SIG_SCREEN_ON, SIG_UNLOCK
    )

    private data class ActiveInterval(
        val startMs: Long,
        val endMs: Long,
        val unlocked: Boolean
    )

    /**
     * Called by WatchdogService on ACTION_SCREEN_OFF, ACTION_SCREEN_ON, and ACTION_USER_PRESENT.
     * Extremely lightweight append-only ring buffer (14 days max).
     */
    fun recordScreenSignal(context: Context, signalType: Int, timestampMs: Long = System.currentTimeMillis()) {
        try {
            val prefs = context.getSharedPreferences(PREFS_SIGNALS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SCREEN_EVENTS, null)
            val arr = if (!raw.isNullOrBlank()) JSONArray(raw) else JSONArray()

            // Avoid exact duplicate back-to-back events within 3 seconds
            if (arr.length() > 0) {
                val last = arr.optJSONObject(arr.length() - 1)
                if (last != null &&
                    last.optInt("t") == signalType &&
                    abs(timestampMs - last.optLong("ts")) < 3_000L
                ) {
                    return
                }
            }

            arr.put(JSONObject().apply {
                put("ts", timestampMs)
                put("t", signalType)
            })

            val cutoff = timestampMs - RETENTION_MS
            val trimmed = JSONArray()
            val startIdx = max(0, arr.length() - MAX_STORED_EVENTS)
            for (i in startIdx until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optLong("ts") >= cutoff) {
                    trimmed.put(obj)
                }
            }
            prefs.edit().putString(KEY_SCREEN_EVENTS, trimmed.toString()).apply()
        } catch (_: Throwable) {}
    }

    /**
     * Saves a user fine-tuned sleep adjustment for a specific dateKey ("yyyyMMdd") so their
     * exact Bedtime, Wake-up, and Awake Interruption minutes persist immediately.
     */
    fun saveManualAdjustment(
        context: Context,
        dateKey: String,
        bedtimeMs: Long,
        wakeTimeMs: Long,
        awakeMinutes: Int
    ) {
        try {
            val obj = JSONObject().apply {
                put("bed", bedtimeMs)
                put("wake", wakeTimeMs)
                put("awakeMin", awakeMinutes.coerceAtLeast(0))
            }
            context.getSharedPreferences(PREFS_SIGNALS, Context.MODE_PRIVATE)
                .edit()
                .putString("$KEY_OVERRIDE_PREFIX$dateKey", obj.toString())
                .apply()
        } catch (_: Throwable) {}
    }

    fun clearManualAdjustment(context: Context, dateKey: String) {
        try {
            context.getSharedPreferences(PREFS_SIGNALS, Context.MODE_PRIVATE)
                .edit()
                .remove("$KEY_OVERRIDE_PREFIX$dateKey")
                .apply()
        } catch (_: Throwable) {}
    }

    /**
     * Analyzes the overnight sleep ending on [targetDayStartMs] (midnight of the wake-up date).
     * Example: For Nov 23, scans from Nov 22 8:00 PM through Nov 23 1:00 PM (clamped to now).
     */
    fun analyzeSleepForDate(
        context: Context,
        targetDayStartMs: Long,
        fallbackAwayMs: Long = 0L
    ): SleepDetectionResult {
        val dateKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(targetDayStartMs))
        val dayEndMs = targetDayStartMs + 86_400_000L

        // Check if sleep was already logged to Room DB for this day
        val existingSleepRecord = try {
            AppDatabase.getDatabase(context).systemDao()
                .getAuditRecordsBetween(targetDayStartMs, dayEndMs)
                .firstOrNull { it.eventType == "AWAY_SLEPT" }
        } catch (_: Throwable) {
            null
        }

        // Check manual adjustment first
        val overrideJson = try {
            context.getSharedPreferences(PREFS_SIGNALS, Context.MODE_PRIVATE)
                .getString("$KEY_OVERRIDE_PREFIX$dateKey", null)
        } catch (_: Throwable) {
            null
        }
        if (!overrideJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(overrideJson)
                val bed = obj.optLong("bed", 0L)
                val wake = obj.optLong("wake", 0L)
                val awakeMin = obj.optInt("awakeMin", 0).coerceAtLeast(0)
                if (bed > 0L && wake > bed) {
                    return buildManualResult(
                        dateKey = dateKey,
                        bedtimeMs = bed,
                        wakeTimeMs = wake,
                        awakeMs = awakeMin * 60_000L,
                        existingRecord = existingSleepRecord
                    )
                }
            } catch (_: Throwable) {}
        }

        val now = System.currentTimeMillis()
        // Overnight scan window: 8:00 PM previous day -> 1:00 PM target day (clamped to now)
        val windowStartMs = targetDayStartMs - 4L * 3_600_000L // 20:00 prev day
        val rawWindowEndMs = targetDayStartMs + 13L * 3_600_000L // 13:00 target day
        val windowEndMs = min(rawWindowEndMs, now)

        if (windowEndMs <= windowStartMs + MIN_SLEEP_BLOCK_MS) {
            return buildEmptyResult(dateKey, existingSleepRecord)
        }

        // Gather signals from ALL available sources
        val usageSignals = queryUsageManagerScreenSignals(context, windowStartMs, windowEndMs)
        val watchdogSignals = loadRecordedScreenSignals(context, windowStartMs, windowEndMs)
        val combinedSignals = mergeSignals(usageSignals, watchdogSignals)

        val hourlyStepsMap = loadOvernightHourlySteps(context, targetDayStartMs)

        val signalSource = when {
            usageSignals.isNotEmpty() && watchdogSignals.isNotEmpty() -> "UsageEvents + Screen + Motion"
            usageSignals.isNotEmpty() -> "Android Screen & App Telemetry"
            watchdogSignals.isNotEmpty() -> "Live Screen Lifecycle + Motion"
            else -> "Away Timer Fallback"
        }

        val result = if (combinedSignals.size >= 2) {
            reconstructFromSignals(
                dateKey = dateKey,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                targetDayStartMs = targetDayStartMs,
                signals = combinedSignals,
                hourlySteps = hourlyStepsMap,
                signalSource = signalSource,
                existingRecord = existingSleepRecord
            )
        } else {
            null
        }

        if (result != null && result.hasValidSleep) {
            return result
        }

        // Fallback 1: If user already logged sleep for this day in DB, reconstruct cleanly from DB record
        if (existingSleepRecord != null && existingSleepRecord.durationMillis >= MIN_SLEEP_BLOCK_MS) {
            val wake = existingSleepRecord.timestamp.coerceIn(targetDayStartMs, dayEndMs)
            val bed = wake - existingSleepRecord.durationMillis
            return buildManualResult(
                dateKey = dateKey,
                bedtimeMs = bed,
                wakeTimeMs = wake,
                awakeMs = 0L,
                existingRecord = existingSleepRecord,
                sourceOverride = "Logged Timeline Record"
            )
        }

        // Fallback 2: Use AwayTimeTracker pending away ms if it looks like overnight rest (>= 2h)
        val pendingAway = max(fallbackAwayMs, AwayTimeTracker.getPendingAwayMs(context))
        val isToday = (targetDayStartMs == startOfDayMs(now))
        if (isToday && pendingAway in (2L * 3_600_000L)..(14L * 3_600_000L)) {
            val wake = now
            val bed = wake - pendingAway
            return buildManualResult(
                dateKey = dateKey,
                bedtimeMs = bed,
                wakeTimeMs = wake,
                awakeMs = 0L,
                existingRecord = existingSleepRecord,
                sourceOverride = "Screen-Off Rest Estimate",
                manualFlag = false
            )
        }

        return buildEmptyResult(dateKey, existingSleepRecord)
    }

    /**
     * Reconstructs true overnight sleep + mid-night awakenings from merged screen/app events.
     */
    private fun reconstructFromSignals(
        dateKey: String,
        windowStartMs: Long,
        windowEndMs: Long,
        targetDayStartMs: Long,
        signals: List<SignalEvent>,
        hourlySteps: Map<Int, Int>,
        signalSource: String,
        existingRecord: AuditRecord?
    ): SleepDetectionResult? {
        // 1. Convert sorted signals into active (screen-on/phone-use) intervals
        val rawActive = mutableListOf<ActiveInterval>()
        var activeStart: Long? = null
        var activeUnlocked = false

        for (ev in signals) {
            when (ev.type) {
                SIG_SCREEN_ON, SIG_UNLOCK -> {
                    if (activeStart == null) {
                        activeStart = ev.timestamp
                        activeUnlocked = (ev.type == SIG_UNLOCK)
                    } else if (ev.type == SIG_UNLOCK) {
                        activeUnlocked = true
                    }
                }
                SIG_SCREEN_OFF -> {
                    if (activeStart != null) {
                        val end = max(ev.timestamp, activeStart + 1_000L)
                        rawActive.add(ActiveInterval(activeStart, end, activeUnlocked))
                        activeStart = null
                        activeUnlocked = false
                    }
                }
            }
        }
        // If screen is still on at windowEndMs
        if (activeStart != null) {
            rawActive.add(ActiveInterval(activeStart, windowEndMs, activeUnlocked))
        }

        // Merge overlapping or very close active intervals (<= 2 min gap between screen taps = one awake episode)
        val mergedActive = mutableListOf<ActiveInterval>()
        for (iv in rawActive.sortedBy { it.startMs }) {
            if (mergedActive.isEmpty()) {
                mergedActive.add(iv)
            } else {
                val last = mergedActive.last()
                if (iv.startMs - last.endMs <= 120_000L) {
                    mergedActive[mergedActive.lastIndex] = ActiveInterval(
                        startMs = last.startMs,
                        endMs = max(last.endMs, iv.endMs),
                        unlocked = last.unlocked || iv.unlocked
                    )
                } else {
                    mergedActive.add(iv)
                }
            }
        }

        // 2. Filter out micro clock glances (< 90s) and count them separately
        var microGlances = 0
        val significantAwake = mutableListOf<ActiveInterval>()
        for (iv in mergedActive) {
            val dur = iv.endMs - iv.startMs
            if (dur <= MICRO_GLANCE_MAX_MS && !iv.unlocked) {
                microGlances++
            } else if (dur <= 45_000L) {
                // Sub-45s brief screen peek even if unlocked (e.g. checking alarm/time)
                microGlances++
            } else {
                significantAwake.add(iv)
            }
        }

        // 3. Complement of significantAwake inside [windowStartMs, windowEndMs] = dark/inactive sleep blocks
        val darkBlocks = mutableListOf<SleepSegment>()
        var cursor = windowStartMs
        for (aw in significantAwake) {
            val segEnd = aw.startMs.coerceIn(windowStartMs, windowEndMs)
            if (segEnd - cursor >= MIN_SLEEP_BLOCK_MS) {
                darkBlocks.add(SleepSegment(cursor, segEnd, isAwake = false, label = "Asleep"))
            }
            cursor = max(cursor, aw.endMs.coerceIn(windowStartMs, windowEndMs))
        }
        if (windowEndMs - cursor >= MIN_SLEEP_BLOCK_MS) {
            // Only include tail dark block if the user already has a wake signal or it's in the past
            // (If it's currently 10 AM and phone is currently in hand, windowEndMs is right now & cursor == windowEndMs)
            darkBlocks.add(SleepSegment(cursor, windowEndMs, isAwake = false, label = "Asleep"))
        }

        if (darkBlocks.isEmpty()) return null

        // 4. Select primary night-time anchor sleep block
        // Biological night core: 10:00 PM prev day -> 9:30 AM target day
        val nightCoreStart = targetDayStartMs - 2L * 3_600_000L
        val nightCoreEnd = targetDayStartMs + 9L * 3_600_000L + 30L * 60_000L

        fun nightOverlapScore(seg: SleepSegment): Long {
            val overlapStart = max(seg.startMs, nightCoreStart)
            val overlapEnd = min(seg.endMs, nightCoreEnd)
            val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
            return overlap * 2L + seg.durationMs
        }

        val anchorIdx = darkBlocks.indices.maxByOrNull { nightOverlapScore(darkBlocks[it]) } ?: return null
        val anchor = darkBlocks[anchorIdx]
        if (anchor.durationMs < 35L * 60_000L) {
            // Check if stitched neighbors make a real sleep episode
            val totalDark = darkBlocks.sumOf { it.durationMs }
            if (totalDark < 60L * 60_000L) return null
        }

        // 5. Stitch backward & forward across mid-night awakenings!
        var firstIdx = anchorIdx
        var lastIdx = anchorIdx

        // Expand backward (earlier in the night, e.g. fell asleep at 11 PM, woke at 1:30 AM)
        while (firstIdx > 0) {
            val prevBlock = darkBlocks[firstIdx - 1]
            val currBlock = darkBlocks[firstIdx]
            val gapMs = currBlock.startMs - prevBlock.endMs
            val canStitch = isStitchableAwakening(
                gapStartMs = prevBlock.endMs,
                gapEndMs = currBlock.startMs,
                leftBlockMs = prevBlock.durationMs,
                rightBlockMs = currBlock.durationMs,
                nightCoreStart = nightCoreStart,
                nightCoreEnd = nightCoreEnd
            )
            if (canStitch && gapMs > 0L) {
                firstIdx--
            } else {
                break
            }
        }

        // Expand forward (later in the morning, e.g. woke at 4:15 AM briefly, went back to sleep till 6:00 AM)
        while (lastIdx < darkBlocks.lastIndex) {
            val currBlock = darkBlocks[lastIdx]
            val nextBlock = darkBlocks[lastIdx + 1]
            val gapMs = nextBlock.startMs - currBlock.endMs
            val canStitch = isStitchableAwakening(
                gapStartMs = currBlock.endMs,
                gapEndMs = nextBlock.startMs,
                leftBlockMs = currBlock.durationMs,
                rightBlockMs = nextBlock.durationMs,
                nightCoreStart = nightCoreStart,
                nightCoreEnd = nightCoreEnd
            )
            if (canStitch && gapMs > 0L) {
                lastIdx++
            } else {
                break
            }
        }

        val stitchedSleepBlocks = darkBlocks.subList(firstIdx, lastIdx + 1)
        val bedtimeMs = stitchedSleepBlocks.first().startMs
        val wakeTimeMs = stitchedSleepBlocks.last().endMs
        val timeInBedMs = (wakeTimeMs - bedtimeMs).coerceAtLeast(0L)

        // 6. Build full alternating timeline of Asleep & Mid-Night Awakening segments
        val timelineSegments = mutableListOf<SleepSegment>()
        val awakenings = mutableListOf<AwakeningEpisode>()
        var effectiveSleepMs = 0L
        var awakeTotalMs = 0L

        for (i in stitchedSleepBlocks.indices) {
            val block = stitchedSleepBlocks[i]
            timelineSegments.add(block)
            effectiveSleepMs += block.durationMs

            if (i < stitchedSleepBlocks.lastIndex) {
                val nextBlock = stitchedSleepBlocks[i + 1]
                val awStart = block.endMs
                val awEnd = nextBlock.startMs
                val awDur = (awEnd - awStart).coerceAtLeast(0L)
                if (awDur > 0L) {
                    awakeTotalMs += awDur
                    val steps = estimateStepsDuringInterval(awStart, awEnd, targetDayStartMs, hourlySteps)
                    val hadUnlock = significantAwake.any {
                        it.startMs < awEnd && it.endMs > awStart && it.unlocked
                    }
                    val reason = classifyAwakeningReason(awDur, steps, hadUnlock)
                    timelineSegments.add(
                        SleepSegment(
                            startMs = awStart,
                            endMs = awEnd,
                            isAwake = true,
                            label = reason,
                            stepsDuring = steps
                        )
                    )
                    awakenings.add(
                        AwakeningEpisode(
                            startMs = awStart,
                            endMs = awEnd,
                            durationMs = awDur,
                            reason = reason,
                            stepsDuring = steps
                        )
                    )
                }
            }
        }

        // Sanity guard: sleep must be at least 30 minutes
        if (effectiveSleepMs < 30L * 60_000L) return null

        val efficiencyPct = if (timeInBedMs > 0L) {
            ((effectiveSleepMs * 100.0) / timeInBedMs).roundToInt().coerceIn(40, 100)
        } else {
            100
        }

        val (qualityScore, qualityLabel) = computeQualityScore(
            effectiveSleepMs = effectiveSleepMs,
            efficiencyPct = efficiencyPct,
            awakeningCount = awakenings.size,
            bedtimeMs = bedtimeMs,
            targetDayStartMs = targetDayStartMs
        )

        val confidence = when {
            signalSource.contains("UsageEvents") && signalSource.contains("Screen") -> "High Accuracy"
            signalSource.contains("UsageEvents") || signalSource.contains("Screen") -> "Verified Auto"
            else -> "Estimated"
        }

        val insight = buildSleepInsight(
            effectiveSleepMs = effectiveSleepMs,
            timeInBedMs = timeInBedMs,
            efficiencyPct = efficiencyPct,
            awakenings = awakenings,
            microGlances = microGlances
        )

        return SleepDetectionResult(
            dateKey = dateKey,
            bedtimeMs = bedtimeMs,
            wakeTimeMs = wakeTimeMs,
            timeInBedMs = timeInBedMs,
            effectiveSleepMs = effectiveSleepMs,
            awakeTotalMs = awakeTotalMs,
            efficiencyPct = efficiencyPct,
            qualityScore = qualityScore,
            qualityLabel = qualityLabel,
            awakenings = awakenings,
            segments = timelineSegments,
            microGlanceCount = microGlances,
            signalSource = signalSource,
            confidenceLabel = confidence,
            isManualAdjusted = false,
            isAlreadyLogged = existingRecord != null,
            loggedRecordId = existingRecord?.id,
            insightText = insight
        )
    }

    private fun isStitchableAwakening(
        gapStartMs: Long,
        gapEndMs: Long,
        leftBlockMs: Long,
        rightBlockMs: Long,
        nightCoreStart: Long,
        nightCoreEnd: Long
    ): Boolean {
        val gapMs = gapEndMs - gapStartMs
        if (gapMs <= 0L) return true
        // Never stitch gaps happening well outside night/early-morning hours
        val midpoint = (gapStartMs + gapEndMs) / 2L
        val insideNight = midpoint in (nightCoreStart - 60L * 60_000L)..(nightCoreEnd + 60L * 60_000L)
        if (!insideNight) return false

        // Standard mid-night wake-up (water/snack/bathroom/phone check up to 45m)
        if (gapMs <= STANDARD_AWAKE_STITCH_MS) return true

        // Extended mid-night wake-up (45–75m) if flanked by two substantial sleep blocks (>= 45m each)
        if (gapMs <= EXTENDED_AWAKE_STITCH_MS &&
            leftBlockMs >= EXTENDED_SIDE_MIN_MS &&
            rightBlockMs >= EXTENDED_SIDE_MIN_MS
        ) {
            return true
        }
        return false
    }

    private fun classifyAwakeningReason(durationMs: Long, steps: Int, unlocked: Boolean): String {
        val mins = (durationMs / 60_000L).toInt()
        return when {
            steps >= 18 -> "Out of bed / Walked (~$steps steps)"
            steps >= 5 -> "Got up briefly (water / snack / restroom)"
            unlocked && mins >= 12 -> "Awake on phone (${mins}m)"
            unlocked -> "Brief phone check (${mins}m)"
            else -> "Restless awake gap (${mins}m)"
        }
    }

    private fun computeQualityScore(
        effectiveSleepMs: Long,
        efficiencyPct: Int,
        awakeningCount: Int,
        bedtimeMs: Long,
        targetDayStartMs: Long
    ): Pair<Int, String> {
        val hours = effectiveSleepMs / 3_600_000.0
        // Duration score (max 55 pts): optimal 7.0 – 9.0 hrs
        val durScore = when {
            hours in 7.0..9.0 -> 55
            hours in 6.0..7.0 -> (42 + (hours - 6.0) * 13).roundToInt()
            hours in 9.0..10.0 -> (55 - (hours - 9.0) * 8).roundToInt()
            hours in 5.0..6.0 -> (30 + (hours - 5.0) * 12).roundToInt()
            hours < 5.0 -> (hours / 5.0 * 30).roundToInt().coerceIn(5, 30)
            else -> 44
        }

        // Efficiency score (max 30 pts)
        val effScore = ((efficiencyPct - 60).coerceIn(0, 40) * 30 / 40)

        // Continuity score (max 15 pts)
        val contScore = when (awakeningCount) {
            0 -> 15
            1 -> 12
            2 -> 9
            3 -> 5
            else -> 2
        }

        // Slight bonus for healthy bedtime (9:30 PM – 12:15 AM)
        val bedOffsetMin = ((bedtimeMs - targetDayStartMs) / 60_000L).toInt()
        val circadianBonus = if (bedOffsetMin in -150..15) 3 else 0

        val total = (durScore + effScore + contScore + circadianBonus).coerceIn(10, 100)
        val label = when {
            total >= 86 -> "Restorative Sleep"
            total >= 72 -> "Good Recovery"
            total >= 55 -> "Light / Fragmented"
            else -> "Sleep Deficit"
        }
        return total to label
    }

    private fun buildSleepInsight(
        effectiveSleepMs: Long,
        timeInBedMs: Long,
        efficiencyPct: Int,
        awakenings: List<AwakeningEpisode>,
        microGlances: Int
    ): String {
        val effStr = formatDuration(effectiveSleepMs)
        val bedStr = formatDuration(timeInBedMs)
        return when {
            awakenings.isEmpty() && microGlances == 0 ->
                "Uninterrupted sleep ($effStr) with $efficiencyPct% efficiency."
            awakenings.isEmpty() ->
                "Continuous sleep ($effStr) · $microGlances brief clock check(s) ignored."
            awakenings.size == 1 ->
                "Stitched full night ($bedStr in bed) minus 1 mid-night wake-up (${awakenings[0].durationMinutes}m) -> $effStr effective sleep."
            else -> {
                val totalAwMin = awakenings.sumOf { it.durationMinutes }
                "Stitched full night ($bedStr in bed) minus ${awakenings.size} mid-night awakenings (${totalAwMin}m awake) -> $effStr effective sleep."
            }
        }
    }

    private fun queryUsageManagerScreenSignals(
        context: Context,
        startMs: Long,
        endMs: Long
    ): List<SignalEvent> {
        val list = mutableListOf<SignalEvent>()
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return list
            val events = usm.queryEvents(startMs, endMs) ?: return list
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val ts = ev.timeStamp
                if (ts < startMs || ts > endMs) continue
                when (ev.eventType) {
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> list.add(SignalEvent(ts, SIG_SCREEN_OFF))
                    UsageEvents.Event.SCREEN_INTERACTIVE -> list.add(SignalEvent(ts, SIG_SCREEN_ON))
                    UsageEvents.Event.KEYGUARD_HIDDEN -> list.add(SignalEvent(ts, SIG_UNLOCK))
                    UsageEvents.Event.KEYGUARD_SHOWN -> {
                        // Keyguard shown indicates lock; if screen turns off right after, SCREEN_NON_INTERACTIVE captures it
                    }
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        val pkg = ev.packageName ?: ""
                        if (pkg.isNotBlank() && pkg != "android" && !pkg.contains("systemui")) {
                            list.add(SignalEvent(ts, SIG_UNLOCK))
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    private fun loadRecordedScreenSignals(
        context: Context,
        startMs: Long,
        endMs: Long
    ): List<SignalEvent> {
        val list = mutableListOf<SignalEvent>()
        try {
            val raw = context.getSharedPreferences(PREFS_SIGNALS, Context.MODE_PRIVATE)
                .getString(KEY_SCREEN_EVENTS, null) ?: return list
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ts = obj.optLong("ts", 0L)
                val t = obj.optInt("t", 0)
                if (ts in startMs..endMs && t in 1..3) {
                    list.add(SignalEvent(ts, t))
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    private fun mergeSignals(a: List<SignalEvent>, b: List<SignalEvent>): List<SignalEvent> {
        if (a.isEmpty()) return b.sortedBy { it.timestamp }
        if (b.isEmpty()) return a.sortedBy { it.timestamp }
        val merged = (a + b).sortedBy { it.timestamp }
        val deduped = mutableListOf<SignalEvent>()
        for (ev in merged) {
            val prev = deduped.lastOrNull()
            if (prev != null && prev.type == ev.type && abs(ev.timestamp - prev.timestamp) < 4_000L) {
                continue
            }
            deduped.add(ev)
        }
        return deduped
    }

    private fun loadOvernightHourlySteps(context: Context, targetDayStartMs: Long): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        try {
            val prefs = context.getSharedPreferences("focus_hourly_step_windows_v1", Context.MODE_PRIVATE)
            val prevDate = Date(targetDayStartMs - 86_400_000L)
            val currDate = Date(targetDayStartMs)
            val prevHyphen = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(prevDate)
            val prevBasic = SimpleDateFormat("yyyyMMdd", Locale.US).format(prevDate)
            val currHyphen = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(currDate)
            val currBasic = SimpleDateFormat("yyyyMMdd", Locale.US).format(currDate)

            // Previous evening hours 20..23 stored under negative keys -4..-1
            for (h in 20..23) {
                val st = max(prefs.getInt("${prevHyphen}_h$h", 0), prefs.getInt("${prevBasic}_h$h", 0))
                if (st > 0) map[h - 24] = st
            }
            // Target morning hours 0..12 stored under keys 0..12
            for (h in 0..12) {
                val st = max(prefs.getInt("${currHyphen}_h$h", 0), prefs.getInt("${currBasic}_h$h", 0))
                if (st > 0) map[h] = st
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun estimateStepsDuringInterval(
        startMs: Long,
        endMs: Long,
        targetDayStartMs: Long,
        hourlySteps: Map<Int, Int>
    ): Int {
        if (hourlySteps.isEmpty()) return 0
        val startHourRel = ((startMs - targetDayStartMs) / 3_600_000L).toInt()
        val endHourRel = ((endMs - targetDayStartMs) / 3_600_000L).toInt()
        var total = 0
        for (h in startHourRel..endHourRel) {
            total += hourlySteps[h] ?: 0
        }
        return total
    }

    private fun buildManualResult(
        dateKey: String,
        bedtimeMs: Long,
        wakeTimeMs: Long,
        awakeMs: Long,
        existingRecord: AuditRecord?,
        sourceOverride: String = "Manual Fine-Tuned",
        manualFlag: Boolean = true
    ): SleepDetectionResult {
        val timeInBed = (wakeTimeMs - bedtimeMs).coerceAtLeast(60_000L)
        val clampedAwake = awakeMs.coerceIn(0L, (timeInBed - 15L * 60_000L).coerceAtLeast(0L))
        val effective = (timeInBed - clampedAwake).coerceAtLeast(60_000L)
        val effPct = ((effective * 100.0) / timeInBed).roundToInt().coerceIn(40, 100)
        val targetMidnight = startOfDayMs(wakeTimeMs)

        val segments = mutableListOf<SleepSegment>()
        val awakenings = mutableListOf<AwakeningEpisode>()
        if (clampedAwake >= 60_000L && timeInBed >= 40L * 60_000L) {
            val midStart = bedtimeMs + (timeInBed * 0.45).toLong()
            val midEnd = (midStart + clampedAwake).coerceAtMost(wakeTimeMs - 10L * 60_000L)
            segments.add(SleepSegment(bedtimeMs, midStart, isAwake = false, label = "Asleep"))
            segments.add(SleepSegment(midStart, midEnd, isAwake = true, label = "Awake interruption"))
            segments.add(SleepSegment(midEnd, wakeTimeMs, isAwake = false, label = "Asleep"))
            awakenings.add(
                AwakeningEpisode(
                    startMs = midStart,
                    endMs = midEnd,
                    durationMs = midEnd - midStart,
                    reason = "Logged awake time (${(clampedAwake / 60_000L).toInt()}m)"
                )
            )
        } else {
            segments.add(SleepSegment(bedtimeMs, wakeTimeMs, isAwake = false, label = "Asleep"))
        }

        val (score, label) = computeQualityScore(effective, effPct, awakenings.size, bedtimeMs, targetMidnight)
        return SleepDetectionResult(
            dateKey = dateKey,
            bedtimeMs = bedtimeMs,
            wakeTimeMs = wakeTimeMs,
            timeInBedMs = timeInBed,
            effectiveSleepMs = effective,
            awakeTotalMs = clampedAwake,
            efficiencyPct = effPct,
            qualityScore = score,
            qualityLabel = label,
            awakenings = awakenings,
            segments = segments,
            microGlanceCount = 0,
            signalSource = sourceOverride,
            confidenceLabel = if (manualFlag) "Adjusted" else "Estimated",
            isManualAdjusted = manualFlag,
            isAlreadyLogged = existingRecord != null,
            loggedRecordId = existingRecord?.id,
            insightText = if (clampedAwake > 0L) {
                "${formatDuration(timeInBed)} in bed minus ${(clampedAwake / 60_000L)}m awake -> ${formatDuration(effective)} effective sleep."
            } else {
                "Effective sleep: ${formatDuration(effective)} ($effPct% efficiency)."
            }
        )
    }

    private fun buildEmptyResult(dateKey: String, existingRecord: AuditRecord?): SleepDetectionResult {
        return SleepDetectionResult(
            dateKey = dateKey,
            bedtimeMs = 0L,
            wakeTimeMs = 0L,
            timeInBedMs = 0L,
            effectiveSleepMs = 0L,
            awakeTotalMs = 0L,
            efficiencyPct = 0,
            qualityScore = 0,
            qualityLabel = "No Sleep Detected",
            awakenings = emptyList(),
            segments = emptyList(),
            microGlanceCount = 0,
            signalSource = "Waiting for overnight signals",
            confidenceLabel = "Pending",
            isManualAdjusted = false,
            isAlreadyLogged = existingRecord != null,
            loggedRecordId = existingRecord?.id,
            insightText = "Keep Focus Elite running overnight or tap Adjust to log your exact sleep."
        )
    }

    /**
     * Logs (or updates) the effective sleep record into Room DB (`AWAY_SLEPT`) so Daily Notebook,
     * SleepSummaryActivity, AuditSummaryActivity, and EliteCalendarActivity all stay 100% synced.
     */
    fun logSleepToTimeline(context: Context, result: SleepDetectionResult, targetDayStartMs: Long) {
        if (!result.hasValidSleep) return
        val dao = AppDatabase.getDatabase(context).systemDao()
        val dayEndMs = targetDayStartMs + 86_400_000L

        // Remove any prior auto/manual AWAY_SLEPT for this date to prevent duplicate sleep entries
        val existing = dao.getAuditRecordsBetween(targetDayStartMs, dayEndMs)
            .filter { it.eventType == "AWAY_SLEPT" }
        existing.forEach { dao.deleteAuditRecord(it.id) }

        val awakePart = if (result.awakenings.isNotEmpty()) {
            " · ${result.awakenings.size} wake-up (${result.awakeTotalMs / 60_000L}m)"
        } else ""
        val details = "${formatClock(result.bedtimeMs)}–${formatClock(result.wakeTimeMs)} · Eff ${result.efficiencyPct}%$awakePart"

        // Anchor timestamp safely inside the target day so getAuditRecordsBetween always finds it
        val safeTs = result.wakeTimeMs.coerceIn(targetDayStartMs + 60_000L, dayEndMs - 60_000L)
        dao.insertAuditRecord(
            AuditRecord(
                id = 0,
                eventType = "AWAY_SLEPT",
                timestamp = safeTs,
                details = details,
                durationMillis = result.effectiveSleepMs
            )
        )
        AwayTimeTracker.markSleepAnswered(context)
    }

    /**
     * Computes 7-night sleep consistency & recovery summary from logged AWAY_SLEPT records.
     */
    fun getWeeklySleepSummary(context: Context, endDayStartMs: Long): WeeklySleepSummary {
        return try {
            val startMs = endDayStartMs - 6L * 86_400_000L
            val endMs = endDayStartMs + 86_400_000L
            val records = AppDatabase.getDatabase(context).systemDao()
                .getAuditRecordsBetween(startMs, endMs)
                .filter { it.eventType == "AWAY_SLEPT" && it.durationMillis >= 20L * 60_000L }

            if (records.isEmpty()) {
                return WeeklySleepSummary(0, 0L, 0, 0L, "Start logging")
            }

            // Group by date so multiple edits on one day don't skew average
            val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            val byDay = records.groupBy { dayFmt.format(Date(it.timestamp)) }
                .mapValues { (_, list) -> list.maxOf { it.durationMillis } }

            val durations = byDay.values.toList()
            val avgMs = durations.sum() / durations.size
            val targetMs = 7L * 3_600_000L + 30L * 60_000L // 7h 30m ideal baseline
            val debtMs = durations.sumOf { (targetMs - it).coerceAtLeast(0L) }

            val mean = avgMs.toDouble()
            val avgDevMin = durations.map { abs(it - mean) / 60_000.0 }.average()
            val consistency = when {
                durations.size < 2 -> "Building baseline"
                avgDevMin <= 30.0 -> "High Consistency"
                avgDevMin <= 65.0 -> "Moderate"
                else -> "Irregular"
            }

            // Extract logged efficiencies when present in details ("Eff 94%")
            val effRegex = Regex("Eff (\\d+)%")
            val effs = records.mapNotNull { rec ->
                effRegex.find(rec.details)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            val avgEff = if (effs.isNotEmpty()) effs.average().roundToInt() else 92

            WeeklySleepSummary(
                nightsLogged = durations.size,
                avgEffectiveSleepMs = avgMs,
                avgEfficiencyPct = avgEff,
                sleepDebtMs = debtMs,
                consistencyLabel = consistency
            )
        } catch (_: Throwable) {
            WeeklySleepSummary(0, 0L, 0, 0L, "Building baseline")
        }
    }

    fun formatDuration(ms: Long): String {
        val totalMin = (ms / 60_000L).coerceAtLeast(0L)
        val h = totalMin / 60L
        val m = totalMin % 60L
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h 0m"
            else -> "${m}m"
        }
    }

    fun formatClock(ms: Long): String {
        if (ms <= 0L) return "—"
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    }

    private fun startOfDayMs(timeMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
