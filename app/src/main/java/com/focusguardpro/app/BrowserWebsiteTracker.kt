package com.focusguardpro.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 100% Offline per-website browsing time & visit tracker.
 * Records exact time spent on each domain per browser per day (retains last 10 days),
 * powering both:
 * 1. Per-browser website drilldown inside Daily Usage Access (UsageStatsActivity)
 * 2. "Today's Browsing" section inside Web Shield (EliteWebShieldActivity)
 */
object BrowserWebsiteTracker {

    private const val PREFS_NAME = "FocusElite_WebsiteUsage_v1"
    private const val MAX_STORED_DAYS = 10

    data class SiteUsageRecord(
        val host: String,
        val browserPackage: String,
        val durationMs: Long,
        val visits: Int,
        val lastVisitedMs: Long,
        val category: String // "STUDY", "NON_STUDY", "NEUTRAL", "BLOCKED"
    )

    data class DayBrowsingSummary(
        val totalMs: Long,
        val studyMs: Long,
        val nonStudyMs: Long,
        val sites: List<SiteUsageRecord>
    )

    private fun dayKey(tsMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(tsMs))

    @Synchronized
    fun recordSample(
        context: Context,
        browserPackage: String,
        host: String,
        deltaMs: Long,
        isStudy: Boolean,
        isNeutral: Boolean,
        isBlocked: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val cleanHost = BlockPolicyEngine.normalizeHost(host)
        if (cleanHost.isBlank() ||
            !cleanHost.contains('.') ||
            cleanHost == "non-study-browsing.local" ||
            deltaMs <= 0L
        ) {
            return
        }

        val safeDelta = deltaMs.coerceIn(500L, 15_000L)
        val dk = dayKey(nowMs)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString("day_$dk", null)
        val root = try {
            if (rawJson.isNullOrBlank()) JSONObject() else JSONObject(rawJson)
        } catch (_: Throwable) {
            JSONObject()
        }

        val entryKey = "$browserPackage|$cleanHost"
        val existing = root.optJSONObject(entryKey) ?: JSONObject()
        val prevMs = existing.optLong("ms", 0L)
        val prevVisits = existing.optInt("v", 0)
        val prevLast = existing.optLong("ts", 0L)

        // Count a new visit if > 45 seconds have elapsed since last sample on this site
        val newVisits = if (prevVisits == 0 || (nowMs - prevLast) > 45_000L) prevVisits + 1 else prevVisits
        val cat = when {
            isBlocked -> "BLOCKED"
            isStudy -> "STUDY"
            isNeutral -> "NEUTRAL"
            else -> "NON_STUDY"
        }

        existing.put("h", cleanHost)
        existing.put("b", browserPackage)
        existing.put("ms", prevMs + safeDelta)
        existing.put("v", newVisits)
        existing.put("ts", nowMs)
        existing.put("c", cat)
        root.put(entryKey, existing)

        val editor = prefs.edit().putString("day_$dk", root.toString())
        pruneOldDays(prefs, editor)
        editor.apply()
    }

    private fun pruneOldDays(prefs: android.content.SharedPreferences, editor: android.content.SharedPreferences.Editor) {
        try {
            val keys = prefs.all.keys.filter { it.startsWith("day_") }.sortedDescending()
            if (keys.size > MAX_STORED_DAYS) {
                for (i in MAX_STORED_DAYS until keys.size) {
                    editor.remove(keys[i])
                }
            }
        } catch (_: Throwable) {}
    }

    private fun loadRawRecordsForDay(context: Context, dayStartMs: Long): List<SiteUsageRecord> {
        val dk = dayKey(dayStartMs)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString("day_$dk", null) ?: return emptyList()
        val list = ArrayList<SiteUsageRecord>()
        try {
            val root = JSONObject(rawJson)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = root.optJSONObject(k) ?: continue
                val h = obj.optString("h", "")
                val b = obj.optString("b", "")
                val ms = obj.optLong("ms", 0L)
                if (h.isBlank() || ms <= 0L) continue
                // Dynamically re-check current Study/Blocked rules so 1-tap changes reflect immediately
                val cat = when {
                    WebsitePrefs.isBlocked(context, h) -> "BLOCKED"
                    WebsitePrefs.isFair(context, h) || BlockPolicyEngine.isStudyWebsiteForBrowser(context, b, h) -> "STUDY"
                    BlockPolicyEngine.isNeutralNavigationWebsite(context, h) -> "NEUTRAL"
                    else -> "NON_STUDY"
                }
                list.add(
                    SiteUsageRecord(
                        host = h,
                        browserPackage = b,
                        durationMs = ms,
                        visits = obj.optInt("v", 1).coerceAtLeast(1),
                        lastVisitedMs = obj.optLong("ts", dayStartMs),
                        category = cat
                    )
                )
            }
        } catch (_: Throwable) {}
        return list.sortedByDescending { it.durationMs }
    }

    fun getWebsitesForBrowserOnDay(
        context: Context,
        browserPackage: String,
        dayStartMs: Long
    ): List<SiteUsageRecord> {
        return loadRawRecordsForDay(context, dayStartMs)
            .filter { it.browserPackage == browserPackage }
            .sortedByDescending { it.durationMs }
    }

    fun getAllWebsitesOnDay(context: Context, dayStartMs: Long): List<SiteUsageRecord> {
        val raw = loadRawRecordsForDay(context, dayStartMs)
        if (raw.isEmpty()) return emptyList()

        // Aggregate identical hosts across multiple browsers cleanly
        val merged = LinkedHashMap<String, SiteUsageRecord>()
        for (r in raw) {
            val cur = merged[r.host]
            if (cur == null) {
                merged[r.host] = r
            } else {
                merged[r.host] = cur.copy(
                    durationMs = cur.durationMs + r.durationMs,
                    visits = cur.visits + r.visits,
                    lastVisitedMs = maxOf(cur.lastVisitedMs, r.lastVisitedMs)
                )
            }
        }
        return merged.values.sortedByDescending { it.durationMs }
    }

    fun getTodaySummary(context: Context): DayBrowsingSummary {
        val now = System.currentTimeMillis()
        val sites = getAllWebsitesOnDay(context, now)
        var total = 0L
        var study = 0L
        var nonStudy = 0L
        for (s in sites) {
            total += s.durationMs
            when (s.category) {
                "STUDY" -> study += s.durationMs
                "NON_STUDY", "BLOCKED" -> nonStudy += s.durationMs
            }
        }
        return DayBrowsingSummary(
            totalMs = total,
            studyMs = study,
            nonStudyMs = nonStudy,
            sites = sites
        )
    }

    fun formatSiteDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(1L)
        val hrs = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return when {
            hrs > 0L -> "${hrs}h ${mins}m"
            mins > 0L -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }
}
