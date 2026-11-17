package com.focusguardpro.app

import android.content.Context
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AppCategoryPrefs {

    private const val PREFS_NAME = "FocusGuard_Categories"
    private const val KEY_STUDY = "CATEGORY_STUDY"
    private const val KEY_IMPROVEMENT = "CATEGORY_IMPROVEMENT"
    private const val KEY_IGNORED = "CATEGORY_IGNORED"

    @Volatile
    private var cachedStudy: Set<String>? = null
    @Volatile
    private var cachedImprovement: Set<String>? = null
    @Volatile
    private var cachedIgnored: Set<String>? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    @Synchronized
    private fun initCacheIfNeeded(context: Context) {
        if (cachedStudy != null && cachedImprovement != null && cachedIgnored != null) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sStudy = prefs.getStringSet(KEY_STUDY, null)
        val sImprovement = prefs.getStringSet(KEY_IMPROVEMENT, null)
        val sIgnored = prefs.getStringSet(KEY_IGNORED, null)

        if (sStudy != null && sImprovement != null && sIgnored != null) {
            cachedStudy = HashSet(sStudy)
            cachedImprovement = HashSet(sImprovement)
            cachedIgnored = HashSet(sIgnored)
            return
        }

        // Fallback: If not in SharedPreferences yet, load safely from Room in background
        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context).systemDao()
                val dbStudy = db.getState(KEY_STUDY)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                val dbImprovement = db.getState(KEY_IMPROVEMENT)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                val dbIgnored = db.getState(KEY_IGNORED)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

                cachedStudy = dbStudy
                cachedImprovement = dbImprovement
                cachedIgnored = dbIgnored

                prefs.edit()
                    .putStringSet(KEY_STUDY, dbStudy)
                    .putStringSet(KEY_IMPROVEMENT, dbImprovement)
                    .putStringSet(KEY_IGNORED, dbIgnored)
                    .apply()
            } catch (_: Exception) {}
        }

        cachedStudy = sStudy?.let { HashSet(it) } ?: emptySet()
        cachedImprovement = sImprovement?.let { HashSet(it) } ?: emptySet()
        cachedIgnored = sIgnored?.let { HashSet(it) } ?: emptySet()
    }

    fun getStudyApps(context: Context): Set<String> {
        val cached = cachedStudy
        if (cached != null) return cached
        initCacheIfNeeded(context)
        return cachedStudy ?: emptySet()
    }

    fun getImprovementApps(context: Context): Set<String> {
        val cached = cachedImprovement
        if (cached != null) return cached
        initCacheIfNeeded(context)
        return cachedImprovement ?: emptySet()
    }

    fun getIgnoredApps(context: Context): Set<String> {
        val cached = cachedIgnored
        if (cached != null) return cached
        initCacheIfNeeded(context)
        return cachedIgnored ?: emptySet()
    }

    fun setStudyApps(context: Context, apps: Set<String>) {
        cachedStudy = HashSet(apps)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_STUDY, apps)
            .apply()
        scope.launch {
            try {
                AppDatabase.getDatabase(context).systemDao().putState(
                    SystemState(KEY_STUDY, apps.joinToString(","), System.currentTimeMillis())
                )
            } catch (_: Exception) {}
        }
    }

    fun setImprovementApps(context: Context, apps: Set<String>) {
        cachedImprovement = HashSet(apps)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IMPROVEMENT, apps)
            .apply()
        scope.launch {
            try {
                AppDatabase.getDatabase(context).systemDao().putState(
                    SystemState(KEY_IMPROVEMENT, apps.joinToString(","), System.currentTimeMillis())
                )
            } catch (_: Exception) {}
        }
    }

    fun setIgnoredApps(context: Context, apps: Set<String>) {
        cachedIgnored = HashSet(apps)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IGNORED, apps)
            .apply()
        scope.launch {
            try {
                AppDatabase.getDatabase(context).systemDao().putState(
                    SystemState(KEY_IGNORED, apps.joinToString(","), System.currentTimeMillis())
                )
            } catch (_: Exception) {}
        }
    }

    fun categoryOf(context: Context, packageName: String): String {
        return when {
            getStudyApps(context).contains(packageName) -> "study"
            getImprovementApps(context).contains(packageName) -> "improvement"
            getIgnoredApps(context).contains(packageName) -> "ignore"
            else -> "neutral"
        }
    }
}
