package com.focusguardpro.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.view.inputmethod.InputMethodManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object UsageStatsHelper {

    private const val LOOKBACK_MS = 2 * 60 * 60 * 1000L // 2-hour lookback for cross-midnight sessions
    private const val MAX_SINGLE_SESSION_MS = 6 * 60 * 60 * 1000L // 6h safety cap against missing pause events
    private const val SNAPSHOT_CACHE_TTL_MS = 8_000L

    // Event constants for API compatibility
    private const val EVENT_ACTIVITY_RESUMED = UsageEvents.Event.ACTIVITY_RESUMED // 1
    private const val EVENT_ACTIVITY_PAUSED = UsageEvents.Event.ACTIVITY_PAUSED // 2
    private const val EVENT_SCREEN_INTERACTIVE = 15
    private const val EVENT_SCREEN_NON_INTERACTIVE = 16
    private const val EVENT_KEYGUARD_SHOWN = 17
    private const val EVENT_KEYGUARD_HIDDEN = 18
    private const val EVENT_ACTIVITY_STOPPED = 23
    private const val EVENT_DEVICE_SHUTDOWN = 26
    private const val EVENT_DEVICE_STARTUP = 27

    data class AppWellbeingStat(
        val packageName: String,
        val durationMs: Long,
        val launchCount: Int,
        val longestSessionMs: Long,
        val lastTimeUsedMs: Long,
        val category: String // "STUDY", "IMPROVE", "IGNORED", "DISTRACT", "BROWSER", "UTILITY"
    ) {
        val usedMinutes: Int
            get() = (durationMs / 60_000L).toInt()
    }

    data class WellbeingSnapshot(
        val startMs: Long,
        val endMs: Long,
        val totalScreenTimeMs: Long,
        val yesterdayScreenTimeMs: Long,
        val unlockCount: Int,
        val totalAppLaunches: Int,
        val studyMs: Long,
        val improveMs: Long,
        val distractMs: Long,
        val utilityMs: Long,
        val hourlyMinutes: IntArray,
        val perAppStats: Map<String, AppWellbeingStat>,
        val peakHourIndex: Int,
        val peakHourMinutes: Int
    )

    @Volatile
    private var cachedTodaySnapshot: WellbeingSnapshot? = null

    @Volatile
    private var cachedTodayTimestampMs: Long = 0L

    @Volatile
    private var cachedExcludedSystemPkgs: Set<String>? = null

    @Volatile
    private var cachedExcludedTimestampMs: Long = 0L

    fun invalidateCache() {
        cachedTodaySnapshot = null
        cachedTodayTimestampMs = 0L
    }

    /**
     * Packages that Android Digital Wellbeing excludes from total screen time:
     * - Home Screen Launchers (CATEGORY_HOME)
     * - SystemUI, Lockscreen, Permission Controllers, Package Installers
     * - Input Method Editors (Keyboards)
     * - Focus Elite itself (so checking your own focus dashboard doesn't inflate screen time)
     */
    fun getDigitalWellbeingExcludedPackages(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val existing = cachedExcludedSystemPkgs
        if (existing != null && (now - cachedExcludedTimestampMs) < 300_000L) {
            return existing
        }

        val excluded = HashSet<String>()
        excluded.add(context.packageName)
        excluded.add("com.focuselite.app")
        excluded.add("com.focusguardpro.app")
        excluded.add("com.focusguardpro.elite")
        excluded.add("android")
        excluded.add("com.android.systemui")
        excluded.add("com.miui.freeform")
        excluded.add("com.android.permissioncontroller")
        excluded.add("com.google.android.permissioncontroller")
        excluded.add("com.android.packageinstaller")
        excluded.add("com.google.android.packageinstaller")
        excluded.add("com.android.mtp")
        excluded.add("com.android.bluetooth")
        excluded.add("com.android.nfc")
        excluded.add("com.android.shell")

        val pm = context.packageManager
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val homeInfos = pm.queryIntentActivities(homeIntent, 0)
            for (ri in homeInfos) {
                ri.activityInfo?.packageName?.let { excluded.add(it) }
            }
        } catch (_: Throwable) {}

        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { imi ->
                excluded.add(imi.packageName)
            }
        } catch (_: Throwable) {}

        cachedExcludedSystemPkgs = excluded
        cachedExcludedTimestampMs = now
        return excluded
    }

    fun getTodayScreenTimeMillis(context: Context): Long {
        if (!hasUsageAccess(context)) return 0L
        return getTodayWellbeingSnapshot(context).totalScreenTimeMs
    }

    fun getScreenTimeMillis(context: Context, start: Long, end: Long): Long {
        if (!hasUsageAccess(context) || end <= start) return 0L
        val excluded = getDigitalWellbeingExcludedPackages(context)
        val perApp = getPerAppUsage(context, start, end)
        var sum = 0L
        for ((pkg, ms) in perApp) {
            if (!excluded.contains(pkg)) {
                sum += ms
            }
        }
        return sum
    }

    /**
     * Authoritative query for single package usage today with automatic TTL refresh
     * and live multi-window / floating-window delta reconciliation.
     */
    fun getTodayScreenTimeForPackage(context: Context, packageName: String): Long {
        if (!hasUsageAccess(context)) return 0L
        val now = System.currentTimeMillis()
        val snapshot = getTodayWellbeingSnapshot(context, forceRefresh = false)
        var bestMs = snapshot.perAppStats[packageName]?.durationMs ?: 0L

        // Add live in-flight seconds since the snapshot was taken if the app is currently visible
        val isCurrentlyVisible = try {
            ForegroundTracker.currentPackage == packageName ||
                BlockerAccessibilityService.instance?.getAllLiveVisiblePackages()?.contains(packageName) == true
        } catch (_: Throwable) {
            ForegroundTracker.currentPackage == packageName
        }
        if (isCurrentlyVisible && cachedTodayTimestampMs in 1 until now) {
            val liveDelta = (now - cachedTodayTimestampMs).coerceIn(0L, SNAPSHOT_CACHE_TTL_MS)
            bestMs += liveDelta
        }

        if (bestMs > 0L) return bestMs

        val (start, end) = todayRange()
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val agg = usm?.queryAndAggregateUsageStats(start, end)
            val fgMs = agg?.get(packageName)?.totalTimeInForeground
            if (fgMs != null && fgMs > 0L) {
                return fgMs.coerceAtMost(end - start)
            }
        } catch (_: Throwable) {}
        return 0L
    }

    /**
     * Computes the full Digital Wellbeing snapshot for today with an 8-second cache
     * so rapid calls from Home, App List, Widgets, and 1s Watchdog stay fast and accurate.
     */
    fun getTodayWellbeingSnapshot(context: Context, forceRefresh: Boolean = false): WellbeingSnapshot {
        val now = System.currentTimeMillis()
        val cached = cachedTodaySnapshot
        if (!forceRefresh && cached != null && (now - cachedTodayTimestampMs) <= SNAPSHOT_CACHE_TTL_MS) {
            return cached
        }

        val (start, end) = todayRange()
        val snapshot = computeWellbeingRange(context, start, end, includeYesterdayComparison = true)
        cachedTodaySnapshot = snapshot
        cachedTodayTimestampMs = now
        return snapshot
    }

    fun computeWellbeingRange(
        context: Context,
        start: Long,
        end: Long,
        includeYesterdayComparison: Boolean = false
    ): WellbeingSnapshot {
        val emptyHourly = IntArray(24)
        if (!hasUsageAccess(context) || end <= start) {
            return WellbeingSnapshot(
                startMs = start,
                endMs = end,
                totalScreenTimeMs = 0L,
                yesterdayScreenTimeMs = 0L,
                unlockCount = 0,
                totalAppLaunches = 0,
                studyMs = 0L,
                improveMs = 0L,
                distractMs = 0L,
                utilityMs = 0L,
                hourlyMinutes = emptyHourly,
                perAppStats = emptyMap(),
                peakHourIndex = 0,
                peakHourMinutes = 0
            )
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return WellbeingSnapshot(
                start, end, 0L, 0L, 0, 0, 0L, 0L, 0L, 0L, emptyHourly, emptyMap(), 0, 0
            )

        val excluded = getDigitalWellbeingExcludedPackages(context)
        val studyPkgs = try { AppCategoryPrefs.getStudyApps(context) } catch (_: Throwable) { emptySet() }
        val improvePkgs = try { AppCategoryPrefs.getImprovementApps(context) } catch (_: Throwable) { emptySet() }
        val ignorePkgs = try { AppCategoryPrefs.getIgnoredApps(context) } catch (_: Throwable) { emptySet() }

        val durationByPkg = HashMap<String, Long>()
        val launchesByPkg = HashMap<String, Int>()
        val longestByPkg = HashMap<String, Long>()
        val lastUsedByPkg = HashMap<String, Long>()
        val hourlyMs = LongArray(24)

        var unlockCount = 0
        var lastUnlockEventMs = 0L

        val queryStart = max(0L, start - LOOKBACK_MS)
        val events = try {
            usm.queryEvents(queryStart, end)
        } catch (_: Throwable) {
            null
        }

        // Multi-window & Floating-window aware state machine:
        // Tracks simultaneously visible apps (e.g. studying on SciAstra fullscreen while keeping ChatGPT in a floating window).
        var isInteractive = true
        var isKeyguardLocked = false
        val resumedClassesByPkg = HashMap<String, MutableSet<String>>()
        val visibleClassesByPkg = HashMap<String, MutableSet<String>>()
        val activeStartByPkg = HashMap<String, Long>()
        val pausedAtByPkg = HashMap<String, Long>()
        val otherResumesSincePauseByPkg = HashMap<String, MutableSet<String>>()

        var lastKnownResumedPkg: String? = null
        var lastKnownResumedClass: String? = null
        var lastCountedLaunchPkg: String? = null
        var lastCountedLaunchMs: Long = 0L

        fun recordInterval(pkg: String?, rawStart: Long, rawEnd: Long) {
            if (pkg.isNullOrEmpty() || rawStart <= 0L || rawEnd <= rawStart) return
            val clampedEnd = min(rawEnd, end)
            val clampedStart = max(rawStart, start)
            if (clampedEnd <= clampedStart) return

            val delta = min(clampedEnd - clampedStart, MAX_SINGLE_SESSION_MS)
            if (delta <= 0L) return

            durationByPkg[pkg] = (durationByPkg[pkg] ?: 0L) + delta
            val prevLongest = longestByPkg[pkg] ?: 0L
            if (delta > prevLongest) {
                longestByPkg[pkg] = delta
            }
            val prevLast = lastUsedByPkg[pkg] ?: 0L
            if (clampedEnd > prevLast) {
                lastUsedByPkg[pkg] = clampedEnd
            }

            // Bucket into hourly timeline only for non-excluded Digital Wellbeing apps
            if (!excluded.contains(pkg)) {
                distributeIntoHours(clampedStart, clampedStart + delta, start, hourlyMs)
            }
        }

        fun flushAllActivePackages(atTimestamp: Long) {
            val activeKeys = activeStartByPkg.keys.toList()
            for (activePkg in activeKeys) {
                val startTs = activeStartByPkg.remove(activePkg) ?: continue
                val pausedTs = pausedAtByPkg.remove(activePkg)
                val otherCount = otherResumesSincePauseByPkg.remove(activePkg)?.size ?: 0
                val effectiveEnd = when {
                    pausedTs != null && (atTimestamp - pausedTs) <= 5_000L -> pausedTs
                    pausedTs != null && otherCount >= 2 -> pausedTs
                    else -> atTimestamp
                }
                recordInterval(activePkg, startTs, effectiveEnd)
            }
            resumedClassesByPkg.clear()
            visibleClassesByPkg.clear()
            pausedAtByPkg.clear()
            otherResumesSincePauseByPkg.clear()
        }

        if (events != null) {
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val t = ev.timeStamp
                val pkg = ev.packageName
                val cls = ev.className ?: "__default__"

                when (ev.eventType) {
                    EVENT_SCREEN_INTERACTIVE -> {
                        isInteractive = true
                        if (!isKeyguardLocked && activeStartByPkg.isEmpty() && lastKnownResumedPkg != null) {
                            val rPkg = lastKnownResumedPkg!!
                            val rCls = lastKnownResumedClass ?: "__default__"
                            resumedClassesByPkg.getOrPut(rPkg) { HashSet() }.add(rCls)
                            visibleClassesByPkg.getOrPut(rPkg) { HashSet() }.add(rCls)
                            activeStartByPkg[rPkg] = t
                        }
                    }

                    EVENT_KEYGUARD_HIDDEN -> {
                        isKeyguardLocked = false
                        isInteractive = true
                        if (t in start..end && (t - lastUnlockEventMs) > 1_500L) {
                            unlockCount++
                            lastUnlockEventMs = t
                        }
                        if (activeStartByPkg.isEmpty() && lastKnownResumedPkg != null) {
                            val rPkg = lastKnownResumedPkg!!
                            val rCls = lastKnownResumedClass ?: "__default__"
                            resumedClassesByPkg.getOrPut(rPkg) { HashSet() }.add(rCls)
                            visibleClassesByPkg.getOrPut(rPkg) { HashSet() }.add(rCls)
                            activeStartByPkg[rPkg] = t
                        }
                    }

                    EVENT_SCREEN_NON_INTERACTIVE -> {
                        flushAllActivePackages(t)
                        isInteractive = false
                    }

                    EVENT_KEYGUARD_SHOWN -> {
                        flushAllActivePackages(t)
                        isKeyguardLocked = true
                    }

                    EVENT_DEVICE_SHUTDOWN, EVENT_DEVICE_STARTUP -> {
                        flushAllActivePackages(t)
                        isInteractive = ev.eventType == EVENT_DEVICE_STARTUP
                        isKeyguardLocked = false
                        lastKnownResumedPkg = null
                        lastKnownResumedClass = null
                    }

                    EVENT_ACTIVITY_RESUMED -> {
                        if (!pkg.isNullOrEmpty()) {
                            lastKnownResumedPkg = pkg
                            lastKnownResumedClass = cls

                            val wasAlreadyResumed = resumedClassesByPkg[pkg]?.isNotEmpty() == true
                            if (t in start..end && !excluded.contains(pkg)) {
                                if (!wasAlreadyResumed && (pkg != lastCountedLaunchPkg || (t - lastCountedLaunchMs) > 3_000L)) {
                                    launchesByPkg[pkg] = (launchesByPkg[pkg] ?: 0) + 1
                                    lastCountedLaunchPkg = pkg
                                    lastCountedLaunchMs = t
                                }
                            }

                            resumedClassesByPkg.getOrPut(pkg) { HashSet() }.add(cls)
                            visibleClassesByPkg.getOrPut(pkg) { HashSet() }.add(cls)

                            // If this package was paused earlier, check whether it remained visible in multi-window / behind a floating window
                            val prevPausedAt = pausedAtByPkg.remove(pkg)
                            val othersSincePause = otherResumesSincePauseByPkg.remove(pkg)?.size ?: 0
                            if (prevPausedAt != null && activeStartByPkg.containsKey(pkg)) {
                                val remainedVisibleMultiWindow = othersSincePause <= 1 && (t - prevPausedAt) <= 90 * 60_000L
                                if (!remainedVisibleMultiWindow) {
                                    val prevStart = activeStartByPkg.remove(pkg) ?: prevPausedAt
                                    recordInterval(pkg, prevStart, prevPausedAt)
                                }
                            }

                            // Notify other currently-paused packages that `pkg` resumed
                            if (!excluded.contains(pkg)) {
                                val pausedKeys = pausedAtByPkg.keys.toList()
                                for (pausedPkg in pausedKeys) {
                                    if (pausedPkg == pkg) continue
                                    val set = otherResumesSincePauseByPkg.getOrPut(pausedPkg) { HashSet() }
                                    set.add(pkg)
                                    // If 2+ distinct apps have resumed since `pausedPkg` paused, `pausedPkg` is no longer in multi-window
                                    if (set.size >= 2) {
                                        val pStart = activeStartByPkg.remove(pausedPkg)
                                        val pEnd = pausedAtByPkg.remove(pausedPkg)
                                        otherResumesSincePauseByPkg.remove(pausedPkg)
                                        visibleClassesByPkg.remove(pausedPkg)
                                        resumedClassesByPkg.remove(pausedPkg)
                                        if (pStart != null && pEnd != null) {
                                            recordInterval(pausedPkg, pStart, pEnd)
                                        }
                                    }
                                }
                            }

                            if (isInteractive && !isKeyguardLocked && !activeStartByPkg.containsKey(pkg)) {
                                activeStartByPkg[pkg] = t
                            }
                        }
                    }

                    EVENT_ACTIVITY_PAUSED -> {
                        if (!pkg.isNullOrEmpty()) {
                            val rSet = resumedClassesByPkg[pkg]
                            if (rSet != null) {
                                rSet.remove(cls)
                                if (cls == "__default__") rSet.clear()
                            }
                            if (rSet.isNullOrEmpty() && activeStartByPkg.containsKey(pkg) && !pausedAtByPkg.containsKey(pkg)) {
                                pausedAtByPkg[pkg] = t
                                otherResumesSincePauseByPkg[pkg] = HashSet()
                            }
                        }
                    }

                    EVENT_ACTIVITY_STOPPED -> {
                        if (!pkg.isNullOrEmpty()) {
                            val rSet = resumedClassesByPkg[pkg]
                            rSet?.remove(cls)
                            val vSet = visibleClassesByPkg[pkg]
                            vSet?.remove(cls)
                            if (cls == "__default__") {
                                rSet?.clear()
                                vSet?.clear()
                            }

                            if (rSet.isNullOrEmpty() && vSet.isNullOrEmpty()) {
                                val startTs = activeStartByPkg.remove(pkg)
                                val pausedTs = pausedAtByPkg.remove(pkg)
                                val otherCount = otherResumesSincePauseByPkg.remove(pkg)?.size ?: 0
                                if (startTs != null && startTs > 0L) {
                                    // If ACTIVITY_STOPPED arrives within 5s of ACTIVITY_PAUSED, it was a normal fullscreen transition.
                                    // If ACTIVITY_STOPPED arrives much later while <=1 companion floating window was used, the app
                                    // stayed visibly running on screen (e.g. SciAstra video lecture behind ChatGPT floating window) until `t`!
                                    val effectiveEnd = when {
                                        pausedTs == null -> t
                                        (t - pausedTs) <= 5_000L -> pausedTs
                                        otherCount <= 1 -> t
                                        else -> pausedTs
                                    }
                                    recordInterval(pkg, startTs, effectiveEnd)
                                }
                                if (lastKnownResumedPkg == pkg) {
                                    lastKnownResumedPkg = null
                                    lastKnownResumedClass = null
                                }
                            }
                        }
                    }
                }
            }
        }

        // Credit currently open foreground & multi-window/floating-window apps up to `end`
        if (isInteractive && !isKeyguardLocked && activeStartByPkg.isNotEmpty()) {
            val screenActuallyInteractive = try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.isInteractive ?: true
            } catch (_: Throwable) {
                true
            }
            val liveVisibleSet = try {
                BlockerAccessibilityService.instance?.getAllLiveVisiblePackages()?.toSet() ?: emptySet()
            } catch (_: Throwable) {
                emptySet()
            }
            val hasAnyActivelyResumed = activeStartByPkg.keys.any { pausedAtByPkg[it] == null }

            for ((pkg, startTs) in activeStartByPkg) {
                if (startTs !in 1 until end) continue
                val pausedTs = pausedAtByPkg[pkg]
                val otherCount = otherResumesSincePauseByPkg[pkg]?.size ?: 0
                val isStillVisibleMultiWindow = pausedTs == null ||
                    liveVisibleSet.contains(pkg) ||
                    (visibleClassesByPkg[pkg]?.isNotEmpty() == true && otherCount <= 1 && hasAnyActivelyResumed)

                if (screenActuallyInteractive && isStillVisibleMultiWindow) {
                    recordInterval(pkg, startTs, end)
                } else if (pausedTs != null) {
                    recordInterval(pkg, startTs, pausedTs)
                }
            }
        }

        // Reconcile with Android OS today-only UsageStats buckets so apps in floating windows or
        // long video lectures (like SciAstra or ChatGPT) are never undercounted compared to Digital Wellbeing
        try {
            val dailyStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            if (dailyStats != null) {
                val maxWindowMs = (end - start).coerceAtLeast(1L)
                val bestOsBucketByPkg = HashMap<String, Long>()
                val bestOsLastUsedByPkg = HashMap<String, Long>()
                for (stat in dailyStats) {
                    val pkg = stat.packageName ?: continue
                    val fgMs = stat.totalTimeInForeground
                    if (fgMs <= 0L || stat.lastTimeUsed < start) continue
                    // Only accept buckets that started today (or within 2h of midnight) so yesterday's usage never bleeds in
                    if (stat.firstTimeStamp >= start - LOOKBACK_MS && fgMs <= maxWindowMs) {
                        val prev = bestOsBucketByPkg[pkg] ?: 0L
                        if (fgMs > prev) {
                            bestOsBucketByPkg[pkg] = fgMs
                        }
                        val prevLast = bestOsLastUsedByPkg[pkg] ?: 0L
                        if (stat.lastTimeUsed > prevLast) {
                            bestOsLastUsedByPkg[pkg] = stat.lastTimeUsed
                        }
                    }
                }
                for ((pkg, osFgMs) in bestOsBucketByPkg) {
                    val currentComputed = durationByPkg[pkg] ?: 0L
                    if (osFgMs > currentComputed) {
                        durationByPkg[pkg] = osFgMs
                        if ((longestByPkg[pkg] ?: 0L) < osFgMs) {
                            longestByPkg[pkg] = osFgMs
                        }
                        val osLast = bestOsLastUsedByPkg[pkg] ?: 0L
                        if (osLast > (lastUsedByPkg[pkg] ?: 0L)) {
                            lastUsedByPkg[pkg] = osLast
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // Fallback for historical windows if OEM cleared raw UsageEvents but kept aggregated UsageStats
        if (durationByPkg.isEmpty()) {
            try {
                val agg = usm.queryAndAggregateUsageStats(start, end)
                if (agg != null) {
                    for ((pkg, stat) in agg) {
                        val fgMs = stat.totalTimeInForeground
                        if (fgMs > 0L) {
                            durationByPkg[pkg] = fgMs
                            lastUsedByPkg[pkg] = stat.lastTimeUsed
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        var totalScreenTimeMs = 0L
        var studyMs = 0L
        var improveMs = 0L
        var distractMs = 0L
        var utilityMs = 0L
        var totalLaunches = 0

        val perAppStats = HashMap<String, AppWellbeingStat>()
        for ((pkg, ms) in durationByPkg) {
            if (ms <= 0L) continue
            val isExcluded = excluded.contains(pkg)
            val isSystemUtility = SystemExclusions.isExcludedPackage(context, pkg) || isExcluded
            val launches = launchesByPkg[pkg] ?: if (ms >= 15_000L) 1 else 0
            val longest = longestByPkg[pkg] ?: ms
            val lastUsed = lastUsedByPkg[pkg] ?: start

            val category = when {
                studyPkgs.contains(pkg) -> "STUDY"
                improvePkgs.contains(pkg) -> "IMPROVE"
                BlockPolicyEngine.isBrowserPackage(pkg) -> "BROWSER"
                ignorePkgs.contains(pkg) && !isSystemUtility -> "IGNORED"
                isSystemUtility -> "UTILITY"
                else -> "DISTRACT"
            }

            perAppStats[pkg] = AppWellbeingStat(
                packageName = pkg,
                durationMs = ms,
                launchCount = launches,
                longestSessionMs = longest,
                lastTimeUsedMs = lastUsed,
                category = category
            )

            if (!isExcluded) {
                totalScreenTimeMs += ms
                totalLaunches += launches
                when (category) {
                    "STUDY" -> studyMs += ms
                    "IMPROVE" -> improveMs += ms
                    "BROWSER" -> {
                        val nonStudyMs = (BlockPolicyEngine.getNonStudyBrowserMinutesToday(context) * 60_000L).coerceAtMost(ms)
                        val browserStudyMs = (ms - nonStudyMs).coerceAtLeast(0L)
                        studyMs += browserStudyMs
                        distractMs += nonStudyMs
                    }
                    "IGNORED", "UTILITY" -> utilityMs += ms
                    else -> distractMs += ms
                }
            }
        }

        val hourlyMins = IntArray(24)
        var peakHourIdx = 0
        var peakHourMins = 0
        for (h in 0 until 24) {
            val mins = ((hourlyMs[h] + 30_000L) / 60_000L).toInt().coerceIn(0, 60)
            hourlyMins[h] = mins
            if (mins > peakHourMins) {
                peakHourMins = mins
                peakHourIdx = h
            }
        }

        val yesterdayMs = if (includeYesterdayComparison) {
            val yStart = start - 86_400_000L
            val yEnd = start - 1L
            try {
                val agg = usm.queryAndAggregateUsageStats(yStart, yEnd)
                var sum = 0L
                if (agg != null) {
                    for ((pkg, s) in agg) {
                        if (!excluded.contains(pkg) && s.totalTimeInForeground > 0L) {
                            sum += s.totalTimeInForeground
                        }
                    }
                }
                sum
            } catch (_: Throwable) { 0L }
        } else {
            0L
        }

        if (unlockCount == 0 && totalLaunches > 0) {
            unlockCount = 1
        }

        return WellbeingSnapshot(
            startMs = start,
            endMs = end,
            totalScreenTimeMs = totalScreenTimeMs,
            yesterdayScreenTimeMs = yesterdayMs,
            unlockCount = unlockCount,
            totalAppLaunches = totalLaunches,
            studyMs = studyMs,
            improveMs = improveMs,
            distractMs = distractMs,
            utilityMs = utilityMs,
            hourlyMinutes = hourlyMins,
            perAppStats = perAppStats,
            peakHourIndex = peakHourIdx,
            peakHourMinutes = peakHourMins
        )
    }

    private fun distributeIntoHours(segStart: Long, segEnd: Long, dayStart: Long, hourlyMs: LongArray) {
        var cursor = segStart
        while (cursor < segEnd) {
            val offsetMs = (cursor - dayStart).coerceAtLeast(0L)
            val hourIdx = (offsetMs / 3_600_000L).toInt()
            if (hourIdx !in 0..23) break
            val hourBucketEnd = dayStart + (hourIdx + 1) * 3_600_000L
            val sliceEnd = min(segEnd, hourBucketEnd)
            if (sliceEnd > cursor) {
                hourlyMs[hourIdx] += (sliceEnd - cursor)
                cursor = sliceEnd
            } else {
                break
            }
        }
    }

    /**
     * Preserves 100% binary compatibility with all callers in app-classes.jar and Kotlin sources.
     * Returns per-package foreground durations (in milliseconds) within [start, end],
     * excluding launchers/SystemUI/keyboards so all downstream totals match Digital Wellbeing.
     * Uses fast aggregate stats for historical days to avoid UI freezes.
     */
    fun getPerAppUsage(context: Context, start: Long, end: Long): Map<String, Long> {
        if (!hasUsageAccess(context) || end <= start) return emptyMap()
        val (todayStart, todayEnd) = todayRange()
        val isToday = kotlin.math.abs(start - todayStart) < 5_000L && kotlin.math.abs(end - todayEnd) < 15_000L
        val excluded = getDigitalWellbeingExcludedPackages(context)

        if (isToday) {
            val cached = cachedTodaySnapshot
            if (cached != null) {
                val result = HashMap<String, Long>(cached.perAppStats.size)
                for ((pkg, stat) in cached.perAppStats) {
                    if (!excluded.contains(pkg) && stat.durationMs > 0L) {
                        result[pkg] = stat.durationMs
                    }
                }
                return result
            }
        }

        // Fast O(1) query using Android OS aggregate table (prevents multi-day ANR loops)
        val result = HashMap<String, Long>()
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val agg = usm?.queryAndAggregateUsageStats(start, end)
            if (agg != null) {
                for ((pkg, stat) in agg) {
                    if (!excluded.contains(pkg) && stat.totalTimeInForeground > 0L) {
                        result[pkg] = stat.totalTimeInForeground
                    }
                }
            }
        } catch (_: Throwable) {}
        return result
    }

    fun getTopAppsToday(context: Context, limit: Int = 5): List<Pair<String, Long>> {
        if (!hasUsageAccess(context)) return emptyList()
        val (start, end) = todayRange()
        return getPerAppUsage(context, start, end)
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    fun getWeeklyTotals(context: Context): List<Pair<String, Long>> {
        if (!hasUsageAccess(context)) return emptyList()
        val result = ArrayList<Pair<String, Long>>(7)
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val cal = Calendar.getInstance()

        for (daysAgo in 6 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dStart = cal.timeInMillis
            val dEnd = if (daysAgo == 0) {
                System.currentTimeMillis()
            } else {
                dStart + 86_400_000L - 1L
            }
            val label = if (daysAgo == 0) "Today" else dayFmt.format(Date(dStart))
            val ms = if (daysAgo == 0) {
                getTodayScreenTimeMillis(context)
            } else {
                getScreenTimeMillis(context, dStart, dEnd)
            }
            result.add(label to ms)
        }
        return result
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val totalMinutes = totalSeconds / 60L
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        val secs = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours}h ${mins}m"
            mins > 0L -> "${mins}m"
            totalSeconds > 0L -> "${secs}s"
            else -> "0m"
        }
    }

    fun formatPreciseDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val totalMinutes = totalSeconds / 60L
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        val secs = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours}h ${mins}m"
            mins >= 10L -> "${mins}m"
            mins > 0L && secs > 0L -> "${mins}m ${secs}s"
            mins > 0L -> "${mins}m"
            else -> "${secs}s"
        }
    }

    fun formatShort(millis: Long): String {
        val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        return when {
            hours > 0L && mins > 0L -> "${hours}h${mins}m"
            hours > 0L -> "${hours}h"
            else -> "${mins}m"
        }
    }

    fun formatHourLabel(hour24: Int): String {
        return when (hour24) {
            0 -> "12 AM"
            in 1..11 -> "$hour24 AM"
            12 -> "12 PM"
            in 13..23 -> "${hour24 - 12} PM"
            else -> "${hour24}:00"
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun todayRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis().coerceAtLeast(start + 1L)
        return start to end
    }

    fun getForegroundPackage(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000L, now)
            val ev = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == EVENT_ACTIVITY_RESUMED && ev.timeStamp >= latestTime) {
                    val pkg = ev.packageName
                    if (!pkg.isNullOrEmpty() && pkg != "com.android.systemui") {
                        latestPkg = pkg
                        latestTime = ev.timeStamp
                    }
                }
            }
            latestPkg
        } catch (_: Throwable) {
            null
        }
    }
}
