package com.focusguardpro.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance, memory-resident cache for Android application metadata (labels and icons).
 *
 * Eliminates Binder IPC and disk drawable decompression bottlenecks during Home and UsageStats renders,
 * completely preventing ANRs and eliminating stutter when scrolling or opening the app.
 */
object AppMetadataCache {

    private val labelCache = ConcurrentHashMap<String, String>(256)
    private val iconCache = ConcurrentHashMap<String, Drawable>(256)

    fun getLabel(pm: PackageManager, packageName: String): String {
        return labelCache.getOrPut(packageName) {
            try {
                val ai = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Throwable) {
                packageName.substringAfterLast('.')
            }
        }
    }

    fun getIcon(pm: PackageManager, packageName: String): Drawable? {
        val existing = iconCache[packageName]
        if (existing != null) return existing
        return try {
            val d = pm.getApplicationIcon(packageName)
            iconCache[packageName] = d
            d
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Warms up application labels and icons on Dispatchers.IO in the background.
     */
    suspend fun warmUp(context: Context) = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (!labelCache.containsKey(pkg)) {
                    val label = try { ri.loadLabel(pm).toString() } catch (_: Throwable) { pkg.substringAfterLast('.') }
                    labelCache[pkg] = label
                }
                if (!iconCache.containsKey(pkg)) {
                    try {
                        val icon = ri.loadIcon(pm)
                        iconCache[pkg] = icon
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }
}
