package com.focusguardpro.app

import android.content.Context
import com.focusguardpro.app.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object BlockPolicyEngine {

    private const val PREFS_BROWSER_NON_STUDY = "FocusGuard_BrowserNonStudyTracker"
    private const val PREFS_UNBLOCKED_TODAY = "FocusGuard_UnblockedToday"

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val systemStateCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var hardBlockedSetCache: Set<String> = emptySet()
    @Volatile
    private var lastCacheSyncMs: Long = 0L
    @Volatile
    private var isCacheLoaded: Boolean = false
    private val isSyncingCache = AtomicBoolean(false)

    fun warmCacheAsync(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && isCacheLoaded && (now - lastCacheSyncMs) < 4_000L) return
        if (!isSyncingCache.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        cacheScope.launch {
            try {
                val allStates = AppDatabase.getDatabase(appCtx).systemDao().getAllSystemState()
                val newMap = HashMap<String, String>(allStates.size)
                for (st in allStates) {
                    newMap[st.stateKey] = st.stateValue
                }
                systemStateCache.putAll(newMap)
                val rawHard = newMap["HARD_BLOCKED_APPS"] ?: ""
                hardBlockedSetCache = if (rawHard.isBlank()) {
                    emptySet()
                } else {
                    rawHard.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                }
                lastCacheSyncMs = System.currentTimeMillis()
                isCacheLoaded = true
            } catch (_: Throwable) {
            } finally {
                isSyncingCache.set(false)
            }
        }
    }

    fun getFastLimitMinutes(context: Context, packageName: String): Int {
        warmCacheAsync(context)
        val raw = systemStateCache["LIMIT_MINUTES_$packageName"]
        if (raw != null) {
            return raw.toIntOrNull() ?: 0
        }
        return if (isCacheLoaded) 0 else try {
            TimeLimitManager.getLimitMinutes(context, packageName)
        } catch (_: Throwable) { 0 }
    }

    fun getFastBlackoutStartMinutes(context: Context, packageName: String): Int {
        warmCacheAsync(context)
        val raw = systemStateCache["BLACKOUT_START_MIN_$packageName"]
        if (raw != null) {
            return raw.toIntOrNull() ?: -1
        }
        return -1
    }

    fun getFastBlackoutEndMinutes(context: Context, packageName: String): Int {
        warmCacheAsync(context)
        val raw = systemStateCache["BLACKOUT_END_MIN_$packageName"]
        if (raw != null) {
            return raw.toIntOrNull() ?: -1
        }
        return -1
    }

    fun isFastHardBlocked(context: Context, packageName: String): Boolean {
        warmCacheAsync(context)
        if (isCacheLoaded) {
            return hardBlockedSetCache.contains(packageName)
        }
        return try {
            HardBlockManager.isHardBlocked(context, packageName)
        } catch (_: Throwable) { false }
    }

    fun isFastEmergencyActive(context: Context, packageName: String): Boolean {
        warmCacheAsync(context)
        val until = systemStateCache["EMERGENCY_UNTIL_$packageName"]?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() < until
    }

    fun isFastBypassActive(context: Context, target: String): Boolean {
        warmCacheAsync(context)
        val until = systemStateCache["BYPASS_EXPIRY_$target"]?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() < until
    }

    private val EXTRA_BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.microsoft.emmx",
        "com.duckduckgo.mobile.android",
        "com.UCMobile.intl",
        "com.kiwibrowser.browser",
        "com.vivaldi.browser",
        "com.mi.globalbrowser",
        "com.android.browser",
        "com.heytap.browser",
        "com.vivo.browser",
        "mark.via.gp",
        "com.ecosia.android",
        "com.yandex.browser"
    )

    /**
     * Built-in educational, academic, coding, documentation, and study platforms
     * that are ALWAYS allowed 24/7 in browsers and never count toward the 30-minute
     * daily non-study browser limit.
     */
    private val STUDY_WEBSITE_DOMAINS = setOf(
        // Online Learning & Indian Exam Prep
        "sciastra.com",
        "pw.live",
        "physicswallah.live",
        "unacademy.com",
        "byjus.com",
        "byjusexamprep.com",
        "doubtnut.com",
        "toppr.com",
        "vedantu.com",
        "testbook.com",
        "oliveboard.in",
        "adda247.com",
        "embibe.com",
        "allen.ac.in",
        "aakash.ac.in",
        "motion.ac.in",
        "careerwill.com",
        "nextias.com",
        "visionias.in",
        "drishtiias.com",
        "insightsias.com",
        "studyiq.com",
        "apnikaksha.net",
        "apnacollege.in",
        "codewithharry.com",
        "takeuforward.org",
        "neetprep.com",
        "marks.app",
        "selfstudys.com",
        "sarthaks.com",
        "shaalaa.com",
        "learncbse.in",
        "tiwariacademy.com",
        "jagranjosh.com",
        "clearias.com",
        "pmfias.com",
        "mrunal.org",
        "gktoday.in",
        "affairscloud.com",
        "indiabix.com",
        "prepinsta.com",
        "toprankers.com",
        "smartkeeda.com",
        "practicemock.com",
        "guidely.in",
        "ixambee.com",
        "edutap.co.in",
        "forumias.com",
        "iasbaba.com",
        "vajiramandravi.com",
        "rauias.com",
        "sleepyclasses.com",
        // Official Academic & Government Portals
        "iiseradmission.in",
        "niser.ac.in",
        "iisc.ac.in",
        "ncert.nic.in",
        "epathshala.nic.in",
        "diksha.gov.in",
        "swayam.gov.in",
        "swayamprabha.gov.in",
        "nptel.ac.in",
        "cbse.gov.in",
        "nta.ac.in",
        "jeemain.nta.nic.in",
        "neet.nta.nic.in",
        "josaa.nic.in",
        "csab.nic.in",
        "digilocker.gov.in",
        "abc.gov.in",
        "upsc.gov.in",
        "ssc.nic.in",
        "ssc.gov.in",
        "ibps.in",
        "nios.ac.in",
        "ignou.ac.in",
        "ugc.ac.in",
        "aicte-india.org",
        "ndl.iitkgp.ac.in",
        // Global MOOCs & University OpenCourseWare
        "coursera.org",
        "edx.org",
        "udemy.com",
        "khanacademy.org",
        "mit.edu",
        "ocw.mit.edu",
        "stanford.edu",
        "harvard.edu",
        "berkeley.edu",
        "ox.ac.uk",
        "cam.ac.uk",
        "freecodecamp.org",
        "theodinproject.com",
        "codecademy.com",
        "brilliant.org",
        "pluralsight.com",
        "udacity.com",
        "alison.com",
        "futurelearn.com",
        "open.edu",
        "classcentral.com",
        // Programming, CS, Competitive Coding & Docs
        "github.com",
        "gitlab.com",
        "bitbucket.org",
        "stackoverflow.com",
        "stackexchange.com",
        "superuser.com",
        "askubuntu.com",
        "serverfault.com",
        "mathoverflow.net",
        "leetcode.com",
        "hackerrank.com",
        "codeforces.com",
        "codechef.com",
        "atcoder.jp",
        "cses.fi",
        "spoj.com",
        "usaco.guide",
        "cp-algorithms.com",
        "interviewbit.com",
        "scaler.com",
        "geeksforgeeks.org",
        "w3schools.com",
        "developer.mozilla.org",
        "mdn.io",
        "developer.android.com",
        "kotlinlang.org",
        "docs.oracle.com",
        "python.org",
        "rust-lang.org",
        "golang.org",
        "go.dev",
        "react.dev",
        "nodejs.org",
        "kaggle.com",
        "colab.research.google.com",
        "huggingface.co",
        "replit.com",
        "codesandbox.io",
        "codepen.io",
        "jsfiddle.net",
        "devdocs.io",
        "roadmap.sh",
        "programiz.com",
        "tutorialspoint.com",
        "javatpoint.com",
        "baeldung.com",
        // Encyclopedias, Research Papers, Science & Math Tools
        "wikipedia.org",
        "wikimedia.org",
        "wiktionary.org",
        "wikibooks.org",
        "wikiversity.org",
        "britannica.com",
        "scholar.google.com",
        "scholar.google.co.in",
        "arxiv.org",
        "researchgate.net",
        "academia.edu",
        "ncbi.nlm.nih.gov",
        "pubmed.ncbi.nlm.nih.gov",
        "sciencedirect.com",
        "springer.com",
        "nature.com",
        "ieee.org",
        "acm.org",
        "jstor.org",
        "ssrn.com",
        "wolframalpha.com",
        "desmos.com",
        "geogebra.org",
        "symbolab.com",
        "mathway.com",
        "chemguide.co.uk",
        "ptable.com",
        "merriam-webster.com",
        "dictionary.com",
        "cambridge.org",
        "oxfordlearnersdictionaries.com",
        "archive.org",
        "openlibrary.org",
        "gutenberg.org",
        "studocu.com",
        "scribd.com",
        "slideshare.net",
        // AI Study Assistants, Cloud Docs & Flashcards
        "chatgpt.com",
        "chat.openai.com",
        "openai.com",
        "claude.ai",
        "gemini.google.com",
        "aistudio.google.com",
        "notebooklm.google.com",
        "perplexity.ai",
        "deepseek.com",
        "poe.com",
        "phind.com",
        "you.com",
        "copilot.microsoft.com",
        "docs.google.com",
        "drive.google.com",
        "sheets.google.com",
        "slides.google.com",
        "classroom.google.com",
        "books.google.com",
        "keep.google.com",
        "calendar.google.com",
        "notion.so",
        "notion.site",
        "obsidian.md",
        "overleaf.com",
        "ankiweb.net",
        "quizlet.com",
        "duolingo.com",
        "memrise.com",
        "remnote.com",
        "evernote.com",
        "onenote.com",
        "excalidraw.com"
    )

    /**
     * Known social media, entertainment, streaming, and gaming distraction domains.
     * Even if a user marks their Browser app as "Study" or "Improve" in Category Settings,
     * visiting these explicit distraction domains is still treated as Non-Study (unless whitelisted in Web Shield).
     */
    private val KNOWN_DISTRACTION_DOMAINS = setOf(
        "instagram.com",
        "facebook.com",
        "fb.com",
        "tiktok.com",
        "snapchat.com",
        "twitter.com",
        "x.com",
        "reddit.com",
        "netflix.com",
        "primevideo.com",
        "hotstar.com",
        "jiocinema.com",
        "sonyliv.com",
        "zee5.com",
        "twitch.tv",
        "discord.com",
        "pinterest.com",
        "9gag.com",
        "buzzfeed.com",
        "poki.com",
        "crazygames.com",
        "roblox.com"
    )

    /**
     * Search engine domains allowed for navigating to study websites unless explicitly blocked.
     */
    private val NEUTRAL_NAVIGATION_DOMAINS = setOf(
        "google.com",
        "google.co.in",
        "bing.com",
        "duckduckgo.com",
        "search.brave.com",
        "ecosia.org",
        "startpage.com"
    )

    fun isBrowserPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return EXTRA_BROWSER_PACKAGES.contains(packageName) ||
            try {
                BrowserUrlDetector.isBrowser(packageName)
            } catch (_: Throwable) {
                false
            }
    }

    /**
     * Returns true if the user explicitly categorized [browserPackage] as a Study or Improvement app
     * in CategorySettingsActivity (and did not mark it as Ignored).
     */
    fun isBrowserMarkedAsStudyOrImprove(context: Context, browserPackage: String?): Boolean {
        if (browserPackage.isNullOrBlank()) return false
        val ignored = try { AppCategoryPrefs.getIgnoredApps(context) } catch (_: Throwable) { emptySet() }
        if (ignored.contains(browserPackage)) return false
        val study = try { AppCategoryPrefs.getStudyApps(context) } catch (_: Throwable) { emptySet() }
        val improve = try { AppCategoryPrefs.getImprovementApps(context) } catch (_: Throwable) { emptySet() }
        return study.contains(browserPackage) || improve.contains(browserPackage)
    }

    fun isKnownDistractionDomain(context: Context, rawHost: String?): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isBlank()) return false
        if (WebsitePrefs.isFair(context, host)) return false
        if (WebsitePrefs.isBlocked(context, host)) return true
        for (dist in KNOWN_DISTRACTION_DOMAINS) {
            if (host == dist || host.endsWith(".$dist")) {
                return true
            }
        }
        return false
    }

    fun normalizeHost(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = raw.trim().lowercase(Locale.US)
        cleaned = cleaned.removePrefix("https://").removePrefix("http://")
        val slashIdx = cleaned.indexOf('/')
        if (slashIdx >= 0) {
            cleaned = cleaned.substring(0, slashIdx)
        }
        val queryIdx = cleaned.indexOf('?')
        if (queryIdx >= 0) {
            cleaned = cleaned.substring(0, queryIdx)
        }
        val colonIdx = cleaned.indexOf(':')
        if (colonIdx >= 0) {
            cleaned = cleaned.substring(0, colonIdx)
        }
        cleaned = cleaned.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        return cleaned.trim()
    }

    /**
     * Returns true if [rawHost] is a Study / Educational website.
     * Study websites work 24/7 in browsers and never count toward the 30-minute daily limit.
     */
    fun isStudyWebsite(context: Context, rawHost: String?): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isBlank()) return false

        // If the user explicitly added this domain to Blocked Websites in Web Shield, it is NOT a study website
        if (WebsitePrefs.isBlocked(context, host)) {
            return false
        }

        // User-configured Fair / Study websites explicitly fed into Web Shield
        if (WebsitePrefs.isFair(context, host)) {
            return true
        }

        return false
    }

    private fun isBuiltInAcademicOrStudyDomain(rawHost: String?): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isBlank()) return false
        if (host.endsWith(".edu") ||
            host.endsWith(".edu.in") ||
            host.endsWith(".ac.in") ||
            host.endsWith(".ac.uk") ||
            host.endsWith(".edu.au") ||
            host.endsWith(".nic.in") ||
            host.endsWith(".gov.in")
        ) {
            return true
        }
        for (studyDomain in STUDY_WEBSITE_DOMAINS) {
            if (host == studyDomain || host.endsWith(".$studyDomain")) {
                return true
            }
        }
        return false
    }

    /**
     * Context-aware study website check for a specific [browserPackage]:
     * - If the user did NOT select a Study/Improve category for [browserPackage], ONLY websites
     *   explicitly fed into Web Shield ([WebsitePrefs.isFair]) are treated as Study websites.
     * - If the user explicitly selected [browserPackage] as a Study or Improvement app in Category Settings,
     *   then Web Shield Fair sites, built-in academic domains, and non-distraction domains are treated as Study.
     */
    fun isStudyWebsiteForBrowser(context: Context, browserPackage: String, rawHost: String?): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isBlank()) return false
        if (WebsitePrefs.isBlocked(context, host)) return false
        if (host == "non-study-browsing.local") return false

        // 1. Explicitly fed into Web Shield ("Fair / Study Websites") -> always Study 24/7
        if (WebsitePrefs.isFair(context, host)) return true

        // 2. If browser has NO category selected (neither Study nor Improve), unlisted websites are Non-Study
        if (!isBrowserMarkedAsStudyOrImprove(context, browserPackage)) {
            return false
        }

        // 3. Browser IS categorized as Study or Improve: allow academic/study domains and non-distraction domains
        if (isBuiltInAcademicOrStudyDomain(host)) return true
        return !isKnownDistractionDomain(context, host)
    }

    fun isNeutralNavigationWebsite(context: Context, rawHost: String?): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isBlank()) return true
        if (WebsitePrefs.isBlocked(context, host)) return false
        if (!host.contains('.')) return true // internal pages like chrome://newtab, brave://newtab, about:blank
        for (navDomain in NEUTRAL_NAVIGATION_DOMAINS) {
            if (host == navDomain || host.endsWith(".$navDomain")) {
                return true
            }
        }
        return false
    }

    private fun todayDateKey(nowMs: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
    }

    fun isUnblockedForToday(context: Context, packageName: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS_UNBLOCKED_TODAY, Context.MODE_PRIVATE)
        return prefs.getString("unblocked_$packageName", null) == todayDateKey(nowMs)
    }

    fun setUnblockedForToday(context: Context, packageName: String, unblocked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS_UNBLOCKED_TODAY, Context.MODE_PRIVATE)
        if (unblocked) {
            prefs.edit().putString("unblocked_$packageName", todayDateKey(nowMs)).apply()
        } else {
            prefs.edit().remove("unblocked_$packageName").apply()
        }
    }

    /**
     * Records active foreground time on a browser URL.
     * - Verified Study websites increment verified study time (`study_ms_*`).
     * - Neutral search pages allow quick navigation without crediting `study_ms_*`.
     * - Non-study websites increment per-browser and global non-study millisecond counters (`non_study_ms_*`).
     */
    fun recordBrowserWebsiteSample(
        context: Context,
        browserPackage: String,
        rawHost: String?,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val host = normalizeHost(rawHost)
        if (host.isBlank()) return

        val prefs = context.getSharedPreferences(PREFS_BROWSER_NON_STUDY, Context.MODE_PRIVATE)
        val dayKey = todayDateKey(nowMs)
        val storedDay = prefs.getString("day_key", null)
        if (storedDay != dayKey) {
            prefs.edit().clear().putString("day_key", dayKey).apply()
        }

        val isStudy = isStudyWebsiteForBrowser(context, browserPackage, host)
        val isNeutral = isNeutralNavigationWebsite(context, host)

        val lastTick = prefs.getLong("last_browser_tick_ms_$browserPackage", 0L)
        val deltaMs = if (lastTick > 0L && nowMs > lastTick) {
            (nowMs - lastTick).coerceAtMost(15_000L)
        } else {
            1_000L
        }

        val prevHost = prefs.getString("active_host_$browserPackage", null)
        val prevHostSince = prefs.getLong("active_host_since_$browserPackage", nowMs)
        val hostSince = if (prevHost == host && prevHostSince > 0L) prevHostSince else nowMs

        val editor = prefs.edit()
            .putString("active_host_$browserPackage", host)
            .putLong("active_host_ts_$browserPackage", nowMs)
            .putLong("active_host_since_$browserPackage", hostSince)
            .putBoolean("active_is_study_$browserPackage", isStudy)
            .putLong("last_browser_tick_ms_$browserPackage", nowMs)

        if (isStudy) {
            // Verified Study website: credit to study browser time
            val prevStudyPkgMs = prefs.getLong("study_ms_${browserPackage}_$dayKey", 0L)
            val prevStudyAllMs = prefs.getLong("study_ms_all_$dayKey", 0L)
            editor
                .putLong("study_ms_${browserPackage}_$dayKey", prevStudyPkgMs + deltaMs)
                .putLong("study_ms_all_$dayKey", prevStudyAllMs + deltaMs)
                .putLong("last_non_study_tick_ms", 0L)
                .apply()
            try {
                BrowserFairTimeTracker.sample(context, host)
            } catch (_: Throwable) {}
            return
        }

        if (isNeutral) {
            // Neutral navigation page (e.g., google.com search or newtab):
            // Never credit to Study time! If user lingers > 15s on a search page in an uncategorized browser,
            // count additional time toward non-study browsing so it cannot be used as an infinite loophole.
            val lingeredMs = nowMs - hostSince
            if (!isBrowserMarkedAsStudyOrImprove(context, browserPackage) && lingeredMs > 15_000L) {
                val prevNonStudyPkgMs = prefs.getLong("non_study_ms_${browserPackage}_$dayKey", 0L)
                val prevNonStudyAllMs = prefs.getLong("non_study_ms_$dayKey", 0L)
                editor
                    .putLong("last_non_study_tick_ms", nowMs)
                    .putLong("non_study_ms_${browserPackage}_$dayKey", prevNonStudyPkgMs + deltaMs)
                    .putLong("non_study_ms_$dayKey", prevNonStudyAllMs + deltaMs)
            }
            editor.apply()
            return
        }

        // Non-study website: accumulate elapsed time for both this browser package and global non-study counter
        val prevNonStudyPkgMs = prefs.getLong("non_study_ms_${browserPackage}_$dayKey", 0L)
        val prevNonStudyAllMs = prefs.getLong("non_study_ms_$dayKey", 0L)
        editor
            .putLong("last_non_study_tick_ms", nowMs)
            .putLong("non_study_ms_${browserPackage}_$dayKey", prevNonStudyPkgMs + deltaMs)
            .putLong("non_study_ms_$dayKey", prevNonStudyAllMs + deltaMs)
            .apply()

        try {
            BrowserFairTimeTracker.sample(context, host)
        } catch (_: Throwable) {}
    }

    fun clearBrowserWebsiteSample(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_BROWSER_NON_STUDY, Context.MODE_PRIVATE)
        prefs.edit().putLong("last_non_study_tick_ms", 0L).apply()
        try {
            BrowserFairTimeTracker.clearSample(context)
        } catch (_: Throwable) {}
    }

    fun getLastActiveBrowserHost(context: Context, browserPackage: String, nowMs: Long = System.currentTimeMillis()): String? {
        val prefs = context.getSharedPreferences(PREFS_BROWSER_NON_STUDY, Context.MODE_PRIVATE)
        val ts = prefs.getLong("active_host_ts_$browserPackage", 0L)
        val host = prefs.getString("active_host_$browserPackage", null) ?: return null
        // Neutral search hosts expire quickly (12s) so a collapsed URL bar after clicking a search result
        // never masquerades as remaining on google.com
        if (isNeutralNavigationWebsite(context, host)) {
            if (nowMs - ts > 12_000L) return null
            return host
        }
        // Verified hosts remain valid while staying inside the browser (e.g. reading with hidden toolbar)
        if (nowMs - ts > 7_200_000L) return null
        return host
    }

    fun isLastActiveBrowserHostStudy(context: Context, browserPackage: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isBrowserMarkedAsStudyOrImprove(context, browserPackage)) {
            val host = getLastActiveBrowserHost(context, browserPackage, nowMs)
            if (host.isNullOrBlank()) return true
            return !isKnownDistractionDomain(context, host)
        }
        val host = getLastActiveBrowserHost(context, browserPackage, nowMs) ?: return false
        return isStudyWebsiteForBrowser(context, browserPackage, host)
    }

    /**
     * Returns verified Study Browser usage minutes today for [browserPackage].
     * - For an uncategorized browser, ONLY counts time spent on websites explicitly fed into Web Shield (`study_ms_*`).
     * - For a browser explicitly categorized as Study/Improve, counts total browser time minus confirmed non-study time.
     */
    fun getStudyBrowserMinutesForPackage(
        context: Context,
        browserPackage: String,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val prefs = context.getSharedPreferences(PREFS_BROWSER_NON_STUDY, Context.MODE_PRIVATE)
        val dayKey = todayDateKey(nowMs)
        val isToday = prefs.getString("day_key", null) == dayKey
        val verifiedPkgStudyMs = if (isToday) {
            prefs.getLong("study_ms_${browserPackage}_$dayKey", 0L)
        } else {
            0L
        }
        if (isBrowserMarkedAsStudyOrImprove(context, browserPackage)) {
            val totalBrowserMs = try {
                UsageStatsHelper.getTodayScreenTimeForPackage(context, browserPackage)
            } catch (_: Throwable) {
                0L
            }
            val sampledNonStudyMs = if (isToday) {
                prefs.getLong("non_study_ms_${browserPackage}_$dayKey", 0L)
            } else {
                0L
            }
            return maxOf(verifiedPkgStudyMs, (totalBrowserMs - sampledNonStudyMs).coerceAtLeast(0L)) / 60_000L
        }
        return verifiedPkgStudyMs / 60_000L
    }

    /**
     * Computes today's Non-Study Browser usage minutes for a specific [browserPackage].
     * - If the browser is marked as Study/Improve, only counts confirmed non-study samples.
     * - If the browser has NO category selected, reconciles with OS UsageStats (`totalBrowserMs - verifiedStudyMs`)
     *   so any website not fed into Web Shield counts as Non-Study time, fills the progress bar, and blocks at 30m.
     */
    fun getNonStudyBrowserMinutesForPackage(
        context: Context,
        browserPackage: String,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val prefs = context.getSharedPreferences(PREFS_BROWSER_NON_STUDY, Context.MODE_PRIVATE)
        val dayKey = todayDateKey(nowMs)
        val isToday = prefs.getString("day_key", null) == dayKey

        val sampledPkgNonStudyMs = if (isToday) {
            prefs.getLong("non_study_ms_${browserPackage}_$dayKey", 0L)
        } else {
            0L
        }

        // If the user explicitly marked this browser as Study or Improve, only count confirmed non-study samples
        if (isBrowserMarkedAsStudyOrImprove(context, browserPackage)) {
            return sampledPkgNonStudyMs / 60_000L
        }

        val verifiedPkgStudyMs = if (isToday) {
            prefs.getLong("study_ms_${browserPackage}_$dayKey", 0L)
        } else {
            0L
        }

        // Uncategorized browser: reconcile with OS UsageStats so all non-Web-Shield browsing counts toward Non-Study
        val usageAccessBrowserMs = try {
            UsageStatsHelper.getTodayScreenTimeForPackage(context, browserPackage)
        } catch (_: Throwable) {
            0L
        }
        val reconciledMs = maxOf(
            sampledPkgNonStudyMs,
            (usageAccessBrowserMs - verifiedPkgStudyMs).coerceAtLeast(0L)
        )

        return reconciledMs / 60_000L
    }

    fun getNonStudyBrowserMinutesToday(context: Context, nowMs: Long = System.currentTimeMillis()): Long {
        val prefs = context.getSharedPreferences(PREFS_BROWSER_NON_STUDY, Context.MODE_PRIVATE)
        val dayKey = todayDateKey(nowMs)
        val isToday = prefs.getString("day_key", null) == dayKey
        val localMinutes = if (isToday) {
            prefs.getLong("non_study_ms_$dayKey", 0L) / 60_000L
        } else {
            0L
        }
        val roomMinutes = try {
            BrowserFairTimeTracker.getDistractingMinutesToday(context)
        } catch (_: Throwable) {
            0L
        }
        var sumUncategorizedBrowserMinutes = 0L
        var maxBrowserPkgMinutes = 0L
        for (pkg in EXTRA_BROWSER_PACKAGES) {
            val pkgMins = try {
                getNonStudyBrowserMinutesForPackage(context, pkg, nowMs)
            } catch (_: Throwable) {
                0L
            }
            if (pkgMins > maxBrowserPkgMinutes) {
                maxBrowserPkgMinutes = pkgMins
            }
            if (pkgMins > 0L) {
                sumUncategorizedBrowserMinutes += pkgMins
            }
        }
        return maxOf(localMinutes, roomMinutes, maxBrowserPkgMinutes, sumUncategorizedBrowserMinutes)
    }

    /**
     * Evaluates whether a specific website [rawHost] inside [browserPackage] should be allowed or blocked.
     * - Study websites (and non-distraction sites inside a Study/Improve-designated browser) are ALWAYS ALLOWED 24/7.
     * - Non-study websites respect Blocked Websites, Browser Hard Block, Focus Layer, Blackout, and the 30-minute daily cap.
     */
    fun evaluateBrowserUrl(
        context: Context,
        browserPackage: String,
        rawHost: String?,
        nowMs: Long = FocusLayerManager.verifiedNowMs(context)
    ): BlockDecision {
        val shieldPrefs = context.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            return BlockDecision.Allowed
        }

        val host = normalizeHost(rawHost)
        if (host.isBlank()) {
            return BlockDecision.Allowed
        }

        // 1. Explicitly blocked domain in Web Shield is always blocked (unless temporary bypass is active)
        if (WebsitePrefs.isBlocked(context, host)) {
            if (isFastBypassActive(context, host)) {
                return BlockDecision.Allowed
            }
            return BlockDecision.Blocked(BlockReason.WEBSITE, host)
        }

        // 2. CRITICAL RULE: Study Websites (and non-distraction sites in a Study/Improve Browser) work ALL THE TIME (24/7)!
        if (isStudyWebsiteForBrowser(context, browserPackage, host)) {
            return BlockDecision.Allowed
        }

        // 3. Neutral search engines / new-tab pages are allowed so user can navigate to study websites
        if (isNeutralNavigationWebsite(context, host)) {
            return BlockDecision.Allowed
        }

        // --- Below this line, `host` is confirmed to be a NON-STUDY / DISTRACTION WEBSITE ---

        if (isFastBypassActive(context, host) ||
            isFastBypassActive(context, browserPackage) ||
            isFastEmergencyActive(context, browserPackage)
        ) {
            return BlockDecision.Allowed
        }

        // 4. Scheduled Focus Layer, Manual Focus Mode, or Active Deep Focus Session blocks Non-Study Websites
        if (FocusLayerManager.isScheduledActiveNow(context, nowMs)) {
            return BlockDecision.Blocked(BlockReason.FOCUS_LAYER_SCHEDULED, host, "Non-study website blocked during Focus")
        }
        if (FocusLayerManager.isManualOverrideActive(context) && FocusLayerManager.isActiveNow(context)) {
            return BlockDecision.Blocked(BlockReason.FOCUS_LAYER_MANUAL, host, "Non-study website blocked during Focus")
        }
        if (FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.RUNNING) {
            return BlockDecision.Blocked(BlockReason.FOCUS_LAYER_MANUAL, host, "Non-study website blocked during Deep Focus")
        }

        // 5. Browser Blackout window applies to Non-Study Websites
        val blackoutStart = getFastBlackoutStartMinutes(context, browserPackage)
        val blackoutEnd = getFastBlackoutEndMinutes(context, browserPackage)
        if (isTimeInBlackout(blackoutStart, blackoutEnd, nowMs)) {
            return BlockDecision.Blocked(BlockReason.BLACKOUT, host)
        }

        // 6. Browser Non-Study Website Daily Limit (default 30 minutes criteria)
        val customLimit = getFastLimitMinutes(context, browserPackage)
        val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(context)
        val effectiveLimit = if (customLimit > 0) customLimit else defaultCap
        val nonStudyUsedMinutes = maxOf(
            getNonStudyBrowserMinutesForPackage(context, browserPackage, nowMs),
            getNonStudyBrowserMinutesToday(context, nowMs)
        )

        if (effectiveLimit > 0 && nonStudyUsedMinutes >= effectiveLimit && !isUnblockedForToday(context, browserPackage, nowMs)) {
            if (customLimit <= 0 && !isBrowserMarkedAsStudyOrImprove(context, browserPackage)) {
                systemStateCache["LIMIT_MINUTES_$browserPackage"] = effectiveLimit.toString()
                cacheScope.launch {
                    try {
                        TimeLimitManager.setLimitMinutes(context.applicationContext, browserPackage, effectiveLimit)
                    } catch (_: Exception) {}
                }
            }
            return BlockDecision.Blocked(
                BlockReason.DAILY_LIMIT,
                host,
                "Non-study browser limit reached (${nonStudyUsedMinutes}m / ${effectiveLimit}m)"
            )
        }

        // 7. If the user explicitly toggled Hard Block ON for this browser (and limit is not set or hard-locked),
        // block Non-Study Websites while keeping Study Websites open 24/7!
        if (isFastHardBlocked(context, browserPackage) && !isUnblockedForToday(context, browserPackage, nowMs)) {
            return BlockDecision.Blocked(
                BlockReason.WEBSITE,
                host,
                "Non-study website blocked (Study websites remain open 24/7)"
            )
        }

        return BlockDecision.Allowed
    }

    fun evaluateApp(context: Context, packageName: String, nowMs: Long = FocusLayerManager.verifiedNowMs(context)): BlockDecision {
        val shieldPrefs = context.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            return BlockDecision.Allowed
        }

        // 1. Never block Focus Elite or any system/keyboard exclusion
        if (SystemExclusions.isExcludedPackage(context, packageName)) {
            return BlockDecision.Allowed
        }

        // 2. CRITICAL: Never hard-block the entire Browser package at the app level!
        // Browser locking is enforced per-website via evaluateBrowserUrl() so Study Websites work 24/7.
        if (isBrowserPackage(packageName)) {
            return BlockDecision.Allowed
        }

        // 3. Active emergency access or temporary bypass
        if (isFastEmergencyActive(context, packageName) ||
            isFastBypassActive(context, packageName)
        ) {
            return BlockDecision.Allowed
        }

        // 4. Hard Block check (explicitly blocked apps are always blocked)
        if (isFastHardBlocked(context, packageName)) {
            return BlockDecision.Blocked(BlockReason.HARD_BLOCK, packageName)
        }

        // 5. Check Focus Layer (respects custom hours, active days criteria, and Calendar Holiday lock)
        if (FocusLayerManager.isScheduledActiveNow(context, nowMs) && !FocusLayerManager.isAllowedDuringFocus(context, packageName)) {
            return BlockDecision.Blocked(BlockReason.FOCUS_LAYER_SCHEDULED, packageName)
        }

        if (FocusLayerManager.isManualOverrideActive(context) && FocusLayerManager.isActiveNow(context) && !FocusLayerManager.isAllowedDuringFocus(context, packageName)) {
            return BlockDecision.Blocked(BlockReason.FOCUS_LAYER_MANUAL, packageName)
        }

        // 6. Check active Deep Focus Studio session (Pomodoro / FocusSessionManager)
        if (FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.RUNNING &&
            !FocusLayerManager.isAllowedDuringFocus(context, packageName)
        ) {
            return BlockDecision.Blocked(BlockReason.FOCUS_LAYER_MANUAL, packageName, "Active Deep Focus Session")
        }

        // 7. Blackout window check
        val blackoutStart = getFastBlackoutStartMinutes(context, packageName)
        val blackoutEnd = getFastBlackoutEndMinutes(context, packageName)
        if (isTimeInBlackout(blackoutStart, blackoutEnd, nowMs)) {
            return BlockDecision.Blocked(BlockReason.BLACKOUT, packageName)
        }

        // 8. Daily usage limit (including automatic 30-minute daily cap for Ignored and non-Study/non-Improvement apps)
        val unblockedToday = isUnblockedForToday(context, packageName, nowMs)
        val isIgnored = AppCategoryPrefs.getIgnoredApps(context).contains(packageName)
        val isWhitelisted = ((AppCategoryPrefs.getStudyApps(context).contains(packageName) ||
            AppCategoryPrefs.getImprovementApps(context).contains(packageName)) && !isIgnored) ||
            unblockedToday
        val customLimit = getFastLimitMinutes(context, packageName)
        val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(context)

        val limitMinutes = when {
            unblockedToday -> 0
            isIgnored -> if (customLimit in 1..29) customLimit else 30
            customLimit > 0 -> customLimit
            !isWhitelisted -> defaultCap
            else -> 0
        }

        if (limitMinutes > 0) {
            val usedMinutes = UsageStatsHelper.getTodayScreenTimeForPackage(context, packageName) / 60000L
            if (usedMinutes >= limitMinutes) {
                // Ensure TimeLimitManager has the effective limit persisted asynchronously so BlockActivity shows the exact cap
                if (customLimit <= 0) {
                    systemStateCache["LIMIT_MINUTES_$packageName"] = limitMinutes.toString()
                    cacheScope.launch {
                        try {
                            TimeLimitManager.setLimitMinutes(context.applicationContext, packageName, limitMinutes)
                        } catch (_: Exception) {}
                    }
                }
                val detailMsg = if (isIgnored) "30m_cap_ignored" else null
                return BlockDecision.Blocked(BlockReason.DAILY_LIMIT, packageName, detailMsg)
            }
        }

        return BlockDecision.Allowed
    }

    fun evaluateWebsite(context: Context, host: String, nowMs: Long = System.currentTimeMillis()): BlockDecision {
        return evaluateBrowserUrl(context, "com.android.chrome", host, nowMs)
    }

    fun isAppBlocked(context: Context, packageName: String): Boolean {
        if (isBrowserPackage(packageName)) {
            val nowMs = FocusLayerManager.verifiedNowMs(context)
            if (isUnblockedForToday(context, packageName, nowMs)) return false
            val customLimit = getFastLimitMinutes(context, packageName)
            val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(context)
            val effectiveLimit = if (customLimit > 0) customLimit else defaultCap
            val pkgNonStudyMins = getNonStudyBrowserMinutesForPackage(context, packageName, nowMs)
            return isFastHardBlocked(context, packageName) ||
                pkgNonStudyMins >= effectiveLimit
        }
        return evaluateApp(context, packageName) is BlockDecision.Blocked
    }

    fun setAppBlocked(context: Context, packageName: String, blocked: Boolean, defaultLimitMinutes: Int = 30) {
        val updatedSet = hardBlockedSetCache.toMutableSet()
        if (blocked) updatedSet.add(packageName) else updatedSet.remove(packageName)
        hardBlockedSetCache = updatedSet
        systemStateCache["HARD_BLOCKED_APPS"] = updatedSet.joinToString(",")
        try {
            HardBlockManager.setHardBlocked(context, packageName, blocked)
        } catch (_: Exception) {}
        setUnblockedForToday(context, packageName, !blocked)
        if (blocked) {
            val existingLimit = getFastLimitMinutes(context, packageName)
            if (existingLimit <= 0) {
                setTimeLimit(context, packageName, defaultLimitMinutes)
            }
        } else {
            setTimeLimit(context, packageName, 0)
        }
    }

    fun setTimeLimit(context: Context, packageName: String, minutes: Int) {
        systemStateCache["LIMIT_MINUTES_$packageName"] = minutes.toString()
        TimeLimitManager.setLimitMinutes(context, packageName, minutes)
        if (minutes > 0) {
            setUnblockedForToday(context, packageName, false)
        }
    }

    fun setBlackoutWindow(context: Context, packageName: String, startMin: Int, endMin: Int) {
        systemStateCache["BLACKOUT_START_MIN_$packageName"] = startMin.toString()
        systemStateCache["BLACKOUT_END_MIN_$packageName"] = endMin.toString()
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
