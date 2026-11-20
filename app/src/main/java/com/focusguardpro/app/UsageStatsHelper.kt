package com.focusguardpro.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

object UsageStatsHelper {

    private const val LOOKBACK_MS = 60 * 60 * 1000L // 1-hour lookback for cross-midnight active sessions
    private const val MAX_SINGLE_SESSION_MS = 8 * 60 * 60 * 1000L // 8h cap per continuous session (supports 5h+ study/video sessions like SciAstra)
    private const val MAX_PAUSED_COMPANION_MS = 12 * 60 * 1000L // 12m max companion grace if not live on screen
    private const val SNAPSHOT_CACHE_TTL_MS = 8_000L

    private const val PREFS_WELLBEING_CACHE = "focus_wellbeing_cache_v3"
    private const val PREFS_HISTORICAL_CACHE = "focus_historical_days_v5"

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

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRefreshingToday = AtomicBoolean(false)
    private val computeLock = Any()

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
    private var cachedHomeLauncherPkgs: Set<String>? = null

    @Volatile
    private var cachedExcludedTimestampMs: Long = 0L

    @Volatile
    private var cachedForegroundPkg: String? = null

    @Volatile
    private var cachedForegroundTimestampMs: Long = 0L

    private val isRefreshingForeground = AtomicBoolean(false)

    private val historicalDayTotalCache = ConcurrentHashMap<String, Long>()
    private val historicalDayPerAppCache = ConcurrentHashMap<String, Map<String, Long>>()

    fun invalidateCache() {
        cachedTodayTimestampMs = 0L
    }

    fun getCachedTodaySnapshot(context: Context? = null): WellbeingSnapshot? {
        val inMem = cachedTodaySnapshot
        val (todayStart, _) = todayRange()
        if (inMem != null && kotlin.math.abs(inMem.startMs - todayStart) < 60_000L) {
            return inMem
        }
        if (context != null) {
            val warm = loadWarmSnapshotFromPrefs(context, todayStart)
            if (warm != null) {
                cachedTodaySnapshot = warm
                return warm
            }
        }
        return inMem
    }

    private fun getHomeLauncherPackages(context: Context): Set<String> {
        val existing = cachedHomeLauncherPkgs
        if (existing != null && (System.currentTimeMillis() - cachedExcludedTimestampMs) < 300_000L) {
            return existing
        }
        val homes = HashSet<String>()
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val homeInfos = context.packageManager.queryIntentActivities(homeIntent, 0)
            for (ri in homeInfos) {
                ri.activityInfo?.packageName?.let { homes.add(it) }
            }
        } catch (_: Throwable) {}
        cachedHomeLauncherPkgs = homes
        return homes
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
        excluded.add("com.miui.securitycenter")
        excluded.add("com.android.permissioncontroller")
        excluded.add("com.google.android.permissioncontroller")
        excluded.add("com.android.packageinstaller")
        excluded.add("com.google.android.packageinstaller")
        excluded.add("com.android.mtp")
        excluded.add("com.android.bluetooth")
        excluded.add("com.android.nfc")
        excluded.add("com.android.shell")

        excluded.addAll(getHomeLauncherPackages(context))

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
        return getTodayWellbeingSnapshot(context, forceRefresh = false).totalScreenTimeMs
    }

    fun getScreenTimeMillis(context: Context, start: Long, end: Long): Long {
        if (!hasUsageAccess(context) || end <= start) return 0L
        val (todayStart, todayEnd) = todayRange()
        val isToday = kotlin.math.abs(start - todayStart) < 60_000L && end >= todayStart
        if (isToday) {
            return getTodayScreenTimeMillis(context).coerceAtMost(todayEnd - todayStart)
        }
        return getHistoricalDayScreenTimeMillis(context, start, end)
    }

    /**
     * Authoritative, non-blocking query for single package usage today.
     * NEVER blocks the Main UI Thread with UsageStatsManager Binder IPC calls.
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
            val liveDelta = (now - cachedTodayTimestampMs).coerceIn(0L, 15_000L)
            bestMs += liveDelta
        }

        return bestMs
    }

    /**
     * Universal Digital Wellbeing snapshot provider:
     * - If called from the Main UI Thread, NEVER blocks on Binder IPC; returns the warm/in-memory
     *   snapshot in <0.1ms and schedules an asynchronous background refresh if stale.
     * - If called from a background thread (Dispatchers.IO), deduplicates concurrent refreshes via
     *   [computeLock] so system_server is never flooded with parallel UsageEvents scans.
     */
    fun getTodayWellbeingSnapshot(context: Context, forceRefresh: Boolean = false): WellbeingSnapshot {
        val now = System.currentTimeMillis()
        val (start, end) = todayRange()
        val existing = getCachedTodaySnapshot(context)

        if (!forceRefresh && existing != null && (now - cachedTodayTimestampMs) <= SNAPSHOT_CACHE_TTL_MS) {
            return existing
        }

        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        if (isMainThread) {
            triggerAsyncTodayRefresh(context.applicationContext)
            return existing ?: emptySnapshot(start, end)
        }

        // Background thread path: serialize computation so 2+ IO threads never stampede UsageStatsService
        synchronized(computeLock) {
            val recheckNow = System.currentTimeMillis()
            val recheckCached = cachedTodaySnapshot
            if (!forceRefresh && recheckCached != null &&
                kotlin.math.abs(recheckCached.startMs - start) < 60_000L &&
                (recheckNow - cachedTodayTimestampMs) <= SNAPSHOT_CACHE_TTL_MS
            ) {
                return recheckCached
            }

            val fresh = computeWellbeingRange(context.applicationContext, start, end, includeYesterdayComparison = true)
            cachedTodaySnapshot = fresh
            cachedTodayTimestampMs = System.currentTimeMillis()
            saveWarmSnapshotToPrefs(context.applicationContext, fresh)
            return fresh
        }
    }

    fun triggerAsyncTodayRefresh(context: Context) {
        if (!isRefreshingToday.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        bgScope.launch {
            try {
                val (start, end) = todayRange()
                synchronized(computeLock) {
                    val fresh = computeWellbeingRange(appCtx, start, end, includeYesterdayComparison = true)
                    cachedTodaySnapshot = fresh
                    cachedTodayTimestampMs = System.currentTimeMillis()
                    saveWarmSnapshotToPrefs(appCtx, fresh)
                }
            } catch (_: Throwable) {
            } finally {
                isRefreshingToday.set(false)
            }
        }
    }

    private fun emptySnapshot(start: Long, end: Long): WellbeingSnapshot {
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
            hourlyMinutes = IntArray(24),
            perAppStats = emptyMap(),
            peakHourIndex = 0,
            peakHourMinutes = 0
        )
    }

    fun computeWellbeingRange(
        context: Context,
        start: Long,
        end: Long,
        includeYesterdayComparison: Boolean = false
    ): WellbeingSnapshot {
        if (!hasUsageAccess(context) || end <= start) {
            return emptySnapshot(start, end)
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptySnapshot(start, end)

        // Ensure fast policy cache is warm so downstream category/limit checks never block
        BlockPolicyEngine.warmCacheAsync(context)

        val excluded = getDigitalWellbeingExcludedPackages(context)
        val homePkgs = getHomeLauncherPackages(context)
        val studyPkgs = try { AppCategoryPrefs.getStudyApps(context) } catch (_: Throwable) { emptySet() }
        val improvePkgs = try { AppCategoryPrefs.getImprovementApps(context) } catch (_: Throwable) { emptySet() }
        val ignorePkgs = try { AppCategoryPrefs.getIgnoredApps(context) } catch (_: Throwable) { emptySet() }

        val durationByPkg = HashMap<String, Long>()
        val launchesByPkg = HashMap<String, Int>()
        val longestByPkg = HashMap<String, Long>()
        val lastUsedByPkg = HashMap<String, Long>()
        val activeScreenIntervals = ArrayList< LongArray>()

        var unlockCount = 0
        var lastUnlockEventMs = 0L

        val queryStart = max(0L, start - LOOKBACK_MS)
        val events = try {
            usm.queryEvents(queryStart, end)
        } catch (_: Throwable) {
            null
        }

        val liveVisibleSet = try {
            BlockerAccessibilityService.instance?.getAllLiveVisiblePackages()?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }

        // State machine for accurate foreground + verified multi-window/floating-window tracking
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

            // If a session started before midnight (rawStart < start) and ended without any post-midnight resume,
            // cap cross-midnight carry-over to 45 minutes unless the user actively interacted after midnight.
            val maxSessionCap = if (rawStart < start) 45 * 60_000L else MAX_SINGLE_SESSION_MS
            val delta = min(clampedEnd - clampedStart, maxSessionCap)
            if (delta <= 0L) return
            val effectiveEnd = clampedStart + delta

            durationByPkg[pkg] = (durationByPkg[pkg] ?: 0L) + delta
            val prevLongest = longestByPkg[pkg] ?: 0L
            if (delta > prevLongest) {
                longestByPkg[pkg] = delta
            }
            val prevLast = lastUsedByPkg[pkg] ?: 0L
            if (effectiveEnd > prevLast) {
                lastUsedByPkg[pkg] = effectiveEnd
            }

            if (!excluded.contains(pkg)) {
                activeScreenIntervals.add(longArrayOf(clampedStart, effectiveEnd))
            }
        }

        fun resolvePausedEffectiveEnd(pkg: String, pausedTs: Long?, stopOrFlushTs: Long, otherCount: Int): Long {
            if (pausedTs == null) return stopOrFlushTs
            val pauseGap = stopOrFlushTs - pausedTs
            if (pauseGap <= 5_000L) return pausedTs
            // Only credit time after ACTIVITY_PAUSED if the app remained in a verified multi-window/floating
            // session (either currently visible on screen or within a short 12m companion window with <=1 other app)
            val isVerifiedCompanion = liveVisibleSet.contains(pkg) ||
                (otherCount == 1 && pauseGap <= MAX_PAUSED_COMPANION_MS && isInteractive && !isKeyguardLocked)
            return if (isVerifiedCompanion) stopOrFlushTs else pausedTs
        }

        fun flushAllActivePackages(atTimestamp: Long) {
            val activeKeys = activeStartByPkg.keys.toList()
            for (activePkg in activeKeys) {
                val startTs = activeStartByPkg.remove(activePkg) ?: continue
                val pausedTs = pausedAtByPkg.remove(activePkg)
                val otherCount = otherResumesSincePauseByPkg.remove(activePkg)?.size ?: 0
                val effectiveEnd = resolvePausedEffectiveEnd(activePkg, pausedTs, atTimestamp, otherCount)
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
                            if (!homePkgs.contains(rPkg)) {
                                val rCls = lastKnownResumedClass ?: "__default__"
                                resumedClassesByPkg.getOrPut(rPkg) { HashSet() }.add(rCls)
                                visibleClassesByPkg.getOrPut(rPkg) { HashSet() }.add(rCls)
                                activeStartByPkg[rPkg] = t
                            }
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
                            if (!homePkgs.contains(rPkg)) {
                                val rCls = lastKnownResumedClass ?: "__default__"
                                resumedClassesByPkg.getOrPut(rPkg) { HashSet() }.add(rCls)
                                visibleClassesByPkg.getOrPut(rPkg) { HashSet() }.add(rCls)
                                activeStartByPkg[rPkg] = t
                            }
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

                            // CRITICAL FIX: If the user returns to the Home Screen Launcher, close all paused apps
                            // at their pause timestamp so paused apps never accumulate phantom hours overnight!
                            if (homePkgs.contains(pkg)) {
                                val pausedKeys = pausedAtByPkg.keys.toList()
                                for (pausedPkg in pausedKeys) {
                                    if (liveVisibleSet.contains(pausedPkg)) continue
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

                            val prevPausedAt = pausedAtByPkg.remove(pkg)
                            val othersSincePause = otherResumesSincePauseByPkg.remove(pkg)?.size ?: 0
                            if (prevPausedAt != null && activeStartByPkg.containsKey(pkg)) {
                                val remainedVisibleMultiWindow = liveVisibleSet.contains(pkg) ||
                                    (othersSincePause <= 1 && (t - prevPausedAt) <= MAX_PAUSED_COMPANION_MS)
                                if (!remainedVisibleMultiWindow) {
                                    val prevStart = activeStartByPkg.remove(pkg) ?: prevPausedAt
                                    recordInterval(pkg, prevStart, prevPausedAt)
                                }
                            }

                            // Notify other currently-paused packages that `pkg` resumed
                            if (!excluded.contains(pkg) && pausedAtByPkg.isNotEmpty()) {
                                var toRemove: MutableList<String>? = null
                                for (pausedPkg in pausedAtByPkg.keys) {
                                    if (pausedPkg == pkg) continue
                                    val set = otherResumesSincePauseByPkg.getOrPut(pausedPkg) { HashSet() }
                                    set.add(pkg)
                                    val pAt = pausedAtByPkg[pausedPkg] ?: t
                                    if (set.size >= 2 || (t - pAt) > MAX_PAUSED_COMPANION_MS) {
                                        if (toRemove == null) toRemove = ArrayList()
                                        toRemove.add(pausedPkg)
                                    }
                                }
                                toRemove?.forEach { pausedPkg ->
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
                                    val effectiveEnd = resolvePausedEffectiveEnd(pkg, pausedTs, t, otherCount)
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

        // Credit currently open foreground & verified live multi-window/floating-window apps up to `end`
        if (isInteractive && !isKeyguardLocked && activeStartByPkg.isNotEmpty()) {
            val screenActuallyInteractive = try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isInteractive ?: true
            } catch (_: Throwable) {
                true
            }
            for ((pkg, startTs) in activeStartByPkg) {
                if (startTs !in 1 until end) continue
                val pausedTs = pausedAtByPkg[pkg]
                val isStillVisible = pausedTs == null ||
                    liveVisibleSet.contains(pkg) ||
                    ((end - pausedTs) <= 15_000L && visibleClassesByPkg[pkg]?.isNotEmpty() == true)

                if (screenActuallyInteractive && isStillVisible) {
                    recordInterval(pkg, startTs, end)
                } else if (pausedTs != null) {
                    recordInterval(pkg, startTs, pausedTs)
                }
            }
        }

        val (todayStartMs, _) = todayRange()
        val isCompletedPastDay = end <= todayStartMs
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(start))

        // For completed past days (e.g., Yesterday / Tue Nov 17) or if OEM cleared UsageEvents:
        // Reconcile per-package durations with verified single-day INTERVAL_DAILY buckets and any archived
        // live snapshot from that day so early-morning sessions rotated out of the UsageEvents buffer (like a 5h SciAstra lecture) are never lost.
        var recoveredExtraMs = 0L
        if (isCompletedPastDay || durationByPkg.isEmpty()) {
            val archivedSnap = if (isCompletedPastDay) loadArchivedDaySnapshotFromPrefs(context, dayKey, start) else null
            if (archivedSnap != null) {
                for ((pkg, archStat) in archivedSnap.perAppStats) {
                    val cur = durationByPkg[pkg] ?: 0L
                    if (archStat.durationMs > cur) {
                        val diff = archStat.durationMs - cur
                        durationByPkg[pkg] = archStat.durationMs
                        if (!excluded.contains(pkg)) recoveredExtraMs += diff
                    }
                    if (archStat.launchCount > (launchesByPkg[pkg] ?: 0)) {
                        launchesByPkg[pkg] = archStat.launchCount
                    }
                    if (archStat.longestSessionMs > (longestByPkg[pkg] ?: 0L)) {
                        longestByPkg[pkg] = archStat.longestSessionMs
                    }
                }
            }

            try {
                val dailyStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                if (dailyStats != null) {
                    val maxWindowMs = (end - start).coerceAtLeast(1L).coerceAtMost(16 * 3_600_000L)
                    for (stat in dailyStats) {
                        val pkg = stat.packageName ?: continue
                        val fgMs = stat.totalTimeInForeground
                        if (fgMs <= 0L || fgMs > maxWindowMs) continue
                        val bucketSpan = stat.lastTimeStamp - stat.firstTimeStamp
                        // Verify this is a single-day bucket belonging to [start, end] (allowing for UTC/IST rollover)
                        val isValidSingleDayBucket = if (isCompletedPastDay) {
                            bucketSpan in 1L..(28 * 3_600_000L) &&
                                stat.firstTimeStamp >= (start - 6 * 3_600_000L) &&
                                stat.firstTimeStamp < end &&
                                stat.lastTimeUsed in start..(end + 4 * 3_600_000L)
                        } else {
                            stat.firstTimeStamp >= start && stat.lastTimeUsed >= start
                        }
                        if (isValidSingleDayBucket) {
                            val prev = durationByPkg[pkg] ?: 0L
                            if (fgMs > prev) {
                                val diff = fgMs - prev
                                durationByPkg[pkg] = fgMs
                                if (fgMs > (longestByPkg[pkg] ?: 0L)) longestByPkg[pkg] = fgMs
                                if (stat.lastTimeUsed > (lastUsedByPkg[pkg] ?: 0L)) lastUsedByPkg[pkg] = stat.lastTimeUsed
                                if (!excluded.contains(pkg)) recoveredExtraMs += diff
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // Compute merged wall-clock active screen intervals so overlapping apps never push an hour > 60m
        val hourlyMs = LongArray(24)
        var mergedWallClockTotalMs = 0L
        if (activeScreenIntervals.isNotEmpty()) {
            activeScreenIntervals.sortBy { it[0] }
            var curStart = activeScreenIntervals[0][0]
            var curEnd = activeScreenIntervals[0][1]
            for (i in 1 until activeScreenIntervals.size) {
                val nextStart = activeScreenIntervals[i][0]
                val nextEnd = activeScreenIntervals[i][1]
                if (nextStart <= curEnd) {
                    if (nextEnd > curEnd) curEnd = nextEnd
                } else {
                    mergedWallClockTotalMs += (curEnd - curStart)
                    distributeIntoHours(curStart, curEnd, start, hourlyMs)
                    curStart = nextStart
                    curEnd = nextEnd
                }
            }
            mergedWallClockTotalMs += (curEnd - curStart)
            distributeIntoHours(curStart, curEnd, start, hourlyMs)
        }

        var sumAppScreenTimeMs = 0L
        var studyMs = 0L
        var improveMs = 0L
        var distractMs = 0L
        var utilityMs = 0L
        var totalLaunches = 0

        val nonStudyBrowserMinutesToday = try {
            BlockPolicyEngine.getNonStudyBrowserMinutesToday(context)
        } catch (_: Throwable) {
            0L
        }

        val perAppStats = HashMap<String, AppWellbeingStat>(durationByPkg.size)
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
                sumAppScreenTimeMs += ms
                totalLaunches += launches
                when (category) {
                    "STUDY" -> studyMs += ms
                    "IMPROVE" -> improveMs += ms
                    "BROWSER" -> {
                        val verifiedStudyMins = BlockPolicyEngine.getStudyBrowserMinutesForPackage(context, pkg)
                        val pkgNonStudyMins = BlockPolicyEngine.getNonStudyBrowserMinutesForPackage(context, pkg)
                        val browserStudyMs = (verifiedStudyMins * 60_000L).coerceIn(0L, ms)
                        val nonStudyMs = maxOf(
                            (pkgNonStudyMins * 60_000L).coerceAtMost(ms),
                            (ms - browserStudyMs).coerceAtLeast(0L)
                        )
                        studyMs += (ms - nonStudyMs).coerceAtLeast(0L)
                        distractMs += nonStudyMs
                    }
                    "IGNORED", "UTILITY" -> utilityMs += ms
                    else -> distractMs += ms
                }
            }
        }

        // Merge Focus Elite module-split self-usage (Practice/Deep Focus -> STUDY, Fitness/Workouts -> IMPROVE, Config -> UTILITY)
        var selfProductiveMs = 0L
        try {
            val selfSummary = FocusSelfUsageTracker.getDayModuleSummary(context, start)
            if (selfSummary.studyMs >= 10_000L) {
                perAppStats[FocusSelfUsageTracker.VIRTUAL_PKG_STUDY] = AppWellbeingStat(
                    packageName = FocusSelfUsageTracker.VIRTUAL_PKG_STUDY,
                    durationMs = selfSummary.studyMs,
                    launchCount = selfSummary.studyOpens.coerceAtLeast(1),
                    longestSessionMs = selfSummary.studyLongestMs,
                    lastTimeUsedMs = selfSummary.studyLastUsedMs,
                    category = "STUDY"
                )
                studyMs += selfSummary.studyMs
                sumAppScreenTimeMs += selfSummary.studyMs
                selfProductiveMs += selfSummary.studyMs
                totalLaunches += selfSummary.studyOpens.coerceAtLeast(1)
            }
            if (selfSummary.improveMs >= 10_000L) {
                perAppStats[FocusSelfUsageTracker.VIRTUAL_PKG_IMPROVE] = AppWellbeingStat(
                    packageName = FocusSelfUsageTracker.VIRTUAL_PKG_IMPROVE,
                    durationMs = selfSummary.improveMs,
                    launchCount = selfSummary.improveOpens.coerceAtLeast(1),
                    longestSessionMs = selfSummary.improveLongestMs,
                    lastTimeUsedMs = selfSummary.improveLastUsedMs,
                    category = "IMPROVE"
                )
                improveMs += selfSummary.improveMs
                sumAppScreenTimeMs += selfSummary.improveMs
                selfProductiveMs += selfSummary.improveMs
                totalLaunches += selfSummary.improveOpens.coerceAtLeast(1)
            }
            if (selfSummary.utilityMs >= 30_000L) {
                perAppStats[FocusSelfUsageTracker.VIRTUAL_PKG_UTILITY] = AppWellbeingStat(
                    packageName = FocusSelfUsageTracker.VIRTUAL_PKG_UTILITY,
                    durationMs = selfSummary.utilityMs,
                    launchCount = selfSummary.utilityOpens.coerceAtLeast(1),
                    longestSessionMs = selfSummary.utilityLongestMs,
                    lastTimeUsedMs = selfSummary.utilityLastUsedMs,
                    category = "UTILITY"
                )
                utilityMs += selfSummary.utilityMs
                sumAppScreenTimeMs += selfSummary.utilityMs
                selfProductiveMs += selfSummary.utilityMs
            }
            for (h in 0 until 24) {
                val addMs = selfSummary.hourlyStudyMs[h] + selfSummary.hourlyImproveMs[h] + selfSummary.hourlyUtilityMs[h]
                if (addMs > 0L) {
                    hourlyMs[h] = min(3_600_000L, hourlyMs[h] + addMs)
                }
            }
        } catch (_: Throwable) {}

        val effectiveWallClockMs = mergedWallClockTotalMs + recoveredExtraMs + selfProductiveMs
        val totalScreenTimeMs = if (effectiveWallClockMs > 0L) {
            min(sumAppScreenTimeMs, (effectiveWallClockMs * 115L) / 100L)
        } else {
            sumAppScreenTimeMs
        }.coerceAtMost(18 * 3_600_000L)

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
            getHistoricalDayScreenTimeMillis(context, yStart, yEnd)
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
                hourlyMs[hourIdx] = min(3_600_000L, hourlyMs[hourIdx] + (sliceEnd - cursor))
                cursor = sliceEnd
            } else {
                break
            }
        }
    }

    data class WeeklyDayRange(
        val dayKey: String,
        val shortLabel: String,
        val fullDateLabel: String,
        val startMs: Long,
        val endMs: Long,
        val totalScreenTimeMs: Long,
        val isToday: Boolean
    )

    private val historicalSnapshotCache = ConcurrentHashMap<String, WellbeingSnapshot>()

    /**
     * Authoritative full WellbeingSnapshot for ANY day (Today or any historical day in Last 7 Days).
     * Uses the exact same multi-activity foreground state machine as Today, reconciled with
     * single-day INTERVAL_DAILY buckets and archived snapshots so apps like SciAstra never drop hours.
     */
    fun getDayWellbeingSnapshot(context: Context, start: Long, end: Long, forceRefresh: Boolean = false): WellbeingSnapshot {
        if (!hasUsageAccess(context) || end <= start) return emptySnapshot(start, end)
        val (todayStart, _) = todayRange()
        val isToday = kotlin.math.abs(start - todayStart) < 60_000L && end >= todayStart
        if (isToday) {
            return getTodayWellbeingSnapshot(context, forceRefresh = forceRefresh)
        }

        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(start))
        if (!forceRefresh) {
            val inMem = historicalSnapshotCache[dayKey]
            if (inMem != null) return inMem

            val fromDisk = loadArchivedDaySnapshotFromPrefs(context, dayKey, start, prefsName = PREFS_HISTORICAL_CACHE)
            if (fromDisk != null && fromDisk.totalScreenTimeMs > 0L) {
                historicalSnapshotCache[dayKey] = fromDisk
                return fromDisk
            }
        }

        val prevDayStart = start - 86_400_000L
        val prevDayEnd = start - 1L
        val computed = computeWellbeingRange(context, start, end, includeYesterdayComparison = false)
        val prevTotal = if (end <= todayStart) {
            // Optional lightweight comparison with the day before
            val prevKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(prevDayStart))
            historicalDayTotalCache[prevKey]
                ?: context.getSharedPreferences(PREFS_HISTORICAL_CACHE, Context.MODE_PRIVATE).getLong("total_$prevKey", 0L)
        } else 0L

        val finalSnap = if (prevTotal > 0L) computed.copy(yesterdayScreenTimeMs = prevTotal) else computed

        if (end <= todayStart) {
            historicalSnapshotCache[dayKey] = finalSnap
            historicalDayTotalCache[dayKey] = finalSnap.totalScreenTimeMs
            val excluded = getDigitalWellbeingExcludedPackages(context)
            val perAppMap = HashMap<String, Long>()
            for ((pkg, st) in finalSnap.perAppStats) {
                if (!excluded.contains(pkg) && st.durationMs > 0L) {
                    perAppMap[pkg] = st.durationMs
                }
            }
            historicalDayPerAppCache[dayKey] = perAppMap
            saveArchivedDaySnapshotToPrefs(context, dayKey, finalSnap, perAppMap)
        }
        return finalSnap
    }

    /**
     * Preserves 100% binary compatibility with all callers in app-classes.jar and Kotlin sources.
     * Uses persistent disk cache for historical days so 7D/30D rollups and charts execute in <1ms.
     */
    fun getPerAppUsage(context: Context, start: Long, end: Long): Map<String, Long> {
        if (!hasUsageAccess(context) || end <= start) return emptyMap()
        val (todayStart, _) = todayRange()
        val isToday = kotlin.math.abs(start - todayStart) < 60_000L && end >= todayStart
        val excluded = getDigitalWellbeingExcludedPackages(context)

        if (isToday) {
            val snapshot = getTodayWellbeingSnapshot(context, forceRefresh = false)
            val result = HashMap<String, Long>(snapshot.perAppStats.size)
            for ((pkg, stat) in snapshot.perAppStats) {
                if (!excluded.contains(pkg) && stat.durationMs > 0L) {
                    result[pkg] = stat.durationMs
                }
            }
            return result
        }

        return computeHistoricalDayPerApp(context, start, end)
    }

    private fun computeHistoricalDayPerApp(context: Context, start: Long, end: Long): Map<String, Long> {
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(start))
        val inMem = historicalDayPerAppCache[dayKey]
        if (inMem != null) return inMem

        val (todayStart, _) = todayRange()
        val isCompletedPastDay = end <= todayStart
        val prefs = context.getSharedPreferences(PREFS_HISTORICAL_CACHE, Context.MODE_PRIVATE)
        if (isCompletedPastDay && prefs.contains("map_$dayKey")) {
            val rawJson = prefs.getString("map_$dayKey", null)
            if (!rawJson.isNullOrEmpty()) {
                try {
                    val obj = JSONObject(rawJson)
                    val map = HashMap<String, Long>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.optLong(k, 0L)
                        if (v > 0L) map[k] = v
                    }
                    if (map.isNotEmpty()) {
                        historicalDayPerAppCache[dayKey] = map
                        return map
                    }
                } catch (_: Throwable) {}
            }
        }

        // Delegate directly to getDayWellbeingSnapshot which runs the full multi-activity state machine + daily bucket reconciliation
        val snap = getDayWellbeingSnapshot(context, start, end)
        val excluded = getDigitalWellbeingExcludedPackages(context)
        val result = HashMap<String, Long>()
        for ((pkg, stat) in snap.perAppStats) {
            if (!excluded.contains(pkg) && stat.durationMs > 0L) {
                result[pkg] = stat.durationMs
            }
        }
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

    fun getHistoricalDayScreenTimeMillis(context: Context, start: Long, end: Long): Long {
        if (!hasUsageAccess(context) || end <= start) return 0L
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(start))
        val cached = historicalDayTotalCache[dayKey]
        if (cached != null) return cached

        val (todayStart, _) = todayRange()
        val isCompletedPastDay = end <= todayStart
        val prefs = context.getSharedPreferences(PREFS_HISTORICAL_CACHE, Context.MODE_PRIVATE)
        if (isCompletedPastDay && prefs.contains("total_$dayKey")) {
            val saved = prefs.getLong("total_$dayKey", 0L).coerceIn(0L, 18 * 3_600_000L)
            if (saved > 0L) {
                historicalDayTotalCache[dayKey] = saved
                return saved
            }
        }

        val snap = getDayWellbeingSnapshot(context, start, end)
        return snap.totalScreenTimeMs
    }

    fun getWeeklyDayRanges(context: Context): List<WeeklyDayRange> {
        if (!hasUsageAccess(context)) return emptyList()
        val result = ArrayList<WeeklyDayRange>(7)
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val fullFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis()

        for (daysAgo in 6 downTo 0) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dStart = cal.timeInMillis
            val dEnd = if (daysAgo == 0) {
                now
            } else {
                dStart + 86_400_000L - 1L
            }
            val shortLabel = if (daysAgo == 0) "Today" else dayFmt.format(Date(dStart))
            val fullDateLabel = when (daysAgo) {
                0 -> "Today • ${fullFmt.format(Date(dStart))}"
                1 -> "Yesterday • ${fullFmt.format(Date(dStart))}"
                else -> fullFmt.format(Date(dStart))
            }
            val ms = if (daysAgo == 0) {
                getTodayScreenTimeMillis(context).coerceAtMost(now - dStart)
            } else {
                getHistoricalDayScreenTimeMillis(context, dStart, dEnd)
            }
            result.add(
                WeeklyDayRange(
                    dayKey = keyFmt.format(Date(dStart)),
                    shortLabel = shortLabel,
                    fullDateLabel = fullDateLabel,
                    startMs = dStart,
                    endMs = dEnd,
                    totalScreenTimeMs = ms,
                    isToday = daysAgo == 0
                )
            )
        }
        return result
    }

    fun getWeeklyTotals(context: Context): List<Pair<String, Long>> {
        return getWeeklyDayRanges(context).map { it.shortLabel to it.totalScreenTimeMs }
    }

    private fun serializeSnapshotToJson(snap: WellbeingSnapshot): JSONObject {
        val root = JSONObject()
        root.put("startMs", snap.startMs)
        root.put("endMs", snap.endMs)
        root.put("totalScreenTimeMs", snap.totalScreenTimeMs)
        root.put("yesterdayScreenTimeMs", snap.yesterdayScreenTimeMs)
        root.put("unlockCount", snap.unlockCount)
        root.put("totalAppLaunches", snap.totalAppLaunches)
        root.put("studyMs", snap.studyMs)
        root.put("improveMs", snap.improveMs)
        root.put("distractMs", snap.distractMs)
        root.put("utilityMs", snap.utilityMs)
        root.put("peakHourIndex", snap.peakHourIndex)
        root.put("peakHourMinutes", snap.peakHourMinutes)

        val hourlyArr = JSONArray()
        for (m in snap.hourlyMinutes) hourlyArr.put(m)
        root.put("hourlyMinutes", hourlyArr)

        val appsArr = JSONArray()
        for ((_, stat) in snap.perAppStats) {
            if (stat.durationMs < 5_000L) continue
            val obj = JSONObject()
            obj.put("pkg", stat.packageName)
            obj.put("dur", stat.durationMs)
            obj.put("lch", stat.launchCount)
            obj.put("lng", stat.longestSessionMs)
            obj.put("lst", stat.lastTimeUsedMs)
            obj.put("cat", stat.category)
            appsArr.put(obj)
        }
        root.put("apps", appsArr)
        return root
    }

    private fun deserializeSnapshotFromJson(root: JSONObject, expectedStartMs: Long): WellbeingSnapshot {
        val hourly = IntArray(24)
        val hArr = root.optJSONArray("hourlyMinutes")
        if (hArr != null) {
            for (i in 0 until min(24, hArr.length())) {
                hourly[i] = hArr.optInt(i, 0).coerceIn(0, 60)
            }
        }

        val perApp = HashMap<String, AppWellbeingStat>()
        val appsArr = root.optJSONArray("apps")
        if (appsArr != null) {
            for (i in 0 until appsArr.length()) {
                val o = appsArr.optJSONObject(i) ?: continue
                val pkg = o.optString("pkg", "")
                if (pkg.isEmpty()) continue
                perApp[pkg] = AppWellbeingStat(
                    packageName = pkg,
                    durationMs = o.optLong("dur", 0L),
                    launchCount = o.optInt("lch", 1),
                    longestSessionMs = o.optLong("lng", 0L),
                    lastTimeUsedMs = o.optLong("lst", expectedStartMs),
                    category = o.optString("cat", "DISTRACT")
                )
            }
        }

        return WellbeingSnapshot(
            startMs = root.optLong("startMs", expectedStartMs),
            endMs = root.optLong("endMs", System.currentTimeMillis()),
            totalScreenTimeMs = root.optLong("totalScreenTimeMs", 0L),
            yesterdayScreenTimeMs = root.optLong("yesterdayScreenTimeMs", 0L),
            unlockCount = root.optInt("unlockCount", 0),
            totalAppLaunches = root.optInt("totalAppLaunches", 0),
            studyMs = root.optLong("studyMs", 0L),
            improveMs = root.optLong("improveMs", 0L),
            distractMs = root.optLong("distractMs", 0L),
            utilityMs = root.optLong("utilityMs", 0L),
            hourlyMinutes = hourly,
            perAppStats = perApp,
            peakHourIndex = root.optInt("peakHourIndex", 0),
            peakHourMinutes = root.optInt("peakHourMinutes", 0)
        )
    }

    private fun saveArchivedDaySnapshotToPrefs(
        context: Context,
        dayKey: String,
        snap: WellbeingSnapshot,
        perAppMap: Map<String, Long>
    ) {
        try {
            val snapJson = serializeSnapshotToJson(snap).toString()
            val mapObj = JSONObject()
            for ((k, v) in perAppMap) {
                if (v >= 10_000L) mapObj.put(k, v)
            }
            context.getSharedPreferences(PREFS_HISTORICAL_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putLong("total_$dayKey", snap.totalScreenTimeMs)
                .putString("map_$dayKey", mapObj.toString())
                .putString("snap_$dayKey", snapJson)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun loadArchivedDaySnapshotFromPrefs(
        context: Context,
        dayKey: String,
        expectedStartMs: Long,
        prefsName: String = PREFS_WELLBEING_CACHE
    ): WellbeingSnapshot? {
        return try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val raw = prefs.getString("snap_$dayKey", null) ?: return null
            deserializeSnapshotFromJson(JSONObject(raw), expectedStartMs)
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveWarmSnapshotToPrefs(context: Context, snap: WellbeingSnapshot) {
        try {
            val jsonStr = serializeSnapshotToJson(snap).toString()
            val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(snap.startMs))
            context.getSharedPreferences(PREFS_WELLBEING_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putString("today_snapshot_json", jsonStr)
                .putLong("today_start_ms", snap.startMs)
                .putString("snap_$dayKey", jsonStr)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun loadWarmSnapshotFromPrefs(context: Context, expectedStartMs: Long): WellbeingSnapshot? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_WELLBEING_CACHE, Context.MODE_PRIVATE)
            val savedStart = prefs.getLong("today_start_ms", 0L)
            if (kotlin.math.abs(savedStart - expectedStartMs) > 60_000L) return null
            val raw = prefs.getString("today_snapshot_json", null) ?: return null
            deserializeSnapshotFromJson(JSONObject(raw), expectedStartMs)
        } catch (_: Throwable) {
            null
        }
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
        val now = System.currentTimeMillis()
        if ((now - cachedForegroundTimestampMs) <= 1_500L) {
            return cachedForegroundPkg
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (isRefreshingForeground.compareAndSet(false, true)) {
                val appCtx = context.applicationContext
                bgScope.launch {
                    try {
                        cachedForegroundPkg = queryForegroundPackageInternal(appCtx)
                        cachedForegroundTimestampMs = System.currentTimeMillis()
                    } catch (_: Throwable) {
                    } finally {
                        isRefreshingForeground.set(false)
                    }
                }
            }
            return cachedForegroundPkg ?: ForegroundTracker.currentPackage
        }
        val pkg = queryForegroundPackageInternal(context)
        cachedForegroundPkg = pkg
        cachedForegroundTimestampMs = now
        return pkg
    }

    private fun queryForegroundPackageInternal(context: Context): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 30_000L, now) ?: return null
            val ev = UsageEvents.Event()
            var currentResumedPkg: String? = null
            var currentResumedClass: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                if (pkg == "com.android.systemui" || SystemExclusions.isInputMethod(context, pkg)) continue
                val cls = ev.className ?: ""
                when (ev.eventType) {
                    EVENT_ACTIVITY_RESUMED -> {
                        currentResumedPkg = pkg
                        currentResumedClass = cls
                    }
                    EVENT_ACTIVITY_PAUSED, EVENT_ACTIVITY_STOPPED -> {
                        if (currentResumedPkg == pkg && (currentResumedClass == cls || cls.isEmpty() || currentResumedClass.isNullOrEmpty())) {
                            currentResumedPkg = null
                            currentResumedClass = null
                        }
                    }
                    EVENT_SCREEN_NON_INTERACTIVE, EVENT_KEYGUARD_SHOWN -> {
                        currentResumedPkg = null
                        currentResumedClass = null
                    }
                }
            }
            currentResumedPkg
        } catch (_: Throwable) {
            null
        }
    }
}
