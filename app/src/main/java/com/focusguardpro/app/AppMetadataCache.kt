package com.focusguardpro.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, memory-resident cache for Android application metadata (labels and icons),
 * backed by a persistent label ledger so apps that are used and later uninstalled keep their
 * real human-readable names in Usage Stats forever.
 */
object AppMetadataCache {

    private const val PREFS_LABEL_LEDGER = "FocusElite_AppLabelLedger_v1"

    private val labelCache = ConcurrentHashMap<String, String>(256)
    private val iconCache = ConcurrentHashMap<String, Drawable>(256)
    private val deletedBadgeCache = ConcurrentHashMap<String, Drawable>(64)
    private val installedCache = ConcurrentHashMap<String, Boolean>(256)
    @Volatile
    private var installedCacheEpochMs: Long = 0L
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isWarmingOrWarm = AtomicBoolean(false)
    private val ledgerLoaded = AtomicBoolean(false)

    @Volatile
    private var appContextRef: Context? = null

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        ensureLedgerLoaded(appCtx)
    }

    private fun ensureLedgerLoaded(context: Context?) {
        val ctx = context?.applicationContext ?: appContextRef ?: return
        if (!ledgerLoaded.compareAndSet(false, true)) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_LABEL_LEDGER, Context.MODE_PRIVATE)
            for ((k, v) in prefs.all) {
                if (v is String && v.isNotBlank()) {
                    labelCache.putIfAbsent(k, v)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun persistLabel(context: Context?, packageName: String, label: String) {
        val ctx = context?.applicationContext ?: appContextRef ?: return
        if (label.isBlank()) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_LABEL_LEDGER, Context.MODE_PRIVATE)
            if (prefs.getString(packageName, null) != label) {
                prefs.edit().putString(packageName, label).apply()
            }
        } catch (_: Throwable) {}
    }

    fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val now = System.currentTimeMillis()
        if (now - installedCacheEpochMs > 30_000L) {
            installedCache.clear()
            installedCacheEpochMs = now
        }
        installedCache[packageName]?.let { return it }
        val installed = try {
            pm.getApplicationInfo(packageName, 0)
            true
        } catch (_: Throwable) {
            false
        }
        installedCache[packageName] = installed
        return installed
    }

    fun onPackageRemoved(packageName: String) {
        // Drop icon cache (APK is gone), mark uninstalled immediately, and KEEP labelCache + disk ledger intact!
        iconCache.remove(packageName)
        installedCache[packageName] = false
        SystemExclusions.onPackageChanged(packageName)
    }

    fun onPackageAdded(packageName: String) {
        if (packageName.isBlank()) return
        installedCache[packageName] = true
        iconCache.remove(packageName)
        SystemExclusions.onPackageChanged(packageName)
    }

    fun getLabel(pm: PackageManager, packageName: String, context: Context? = null): String {
        ensureLedgerLoaded(context)
        val cached = labelCache[packageName]
        if (!cached.isNullOrBlank()) return cached

        val resolved = try {
            val ai = pm.getApplicationInfo(packageName, 0)
            installedCache[packageName] = true
            pm.getApplicationLabel(ai).toString().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            installedCache[packageName] = false
            null
        }

        if (resolved != null) {
            labelCache[packageName] = resolved
            persistLabel(context, packageName, resolved)
            return resolved
        }

        // Uninstalled app not previously seen: derive a clean human-readable name from package ID
        val derived = deriveReadableNameFromPackage(packageName)
        labelCache[packageName] = derived
        return derived
    }

    private fun deriveReadableNameFromPackage(packageName: String): String {
        val genericSegments = setOf(
            "com", "org", "net", "io", "co", "in", "us", "uk", "app", "apps",
            "android", "mobile", "client", "lite", "pro", "free", "beta", "release",
            "global", "official", "main", "google", "inc", "ltd"
        )
        val parts = packageName.split('.').filter { it.isNotBlank() }
        val meaningful = parts.lastOrNull { it.lowercase(Locale.US) !in genericSegments }
            ?: parts.lastOrNull()
            ?: packageName
        return meaningful
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { seg ->
                seg.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                }
            }
            .ifBlank { packageName }
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
     * Generates a sleek monogram badge icon for an uninstalled app whose APK icon no longer exists.
     */
    fun createDeletedAppBadgeDrawable(context: Context, displayName: String): Drawable {
        val initial = displayName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val cacheKey = "del_$initial"
        deletedBadgeCache[cacheKey]?.let { return it }

        val density = context.resources.displayMetrics.density
        val sizePx = (38f * density).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A1822")
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F43F5E")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        val radius = 11f * density
        val inset = 1.5f * density
        val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FDA4AF")
            textSize = sizePx * 0.44f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val fm = textPaint.fontMetrics
        val textY = sizePx / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(initial, sizePx / 2f, textY, textPaint)

        // Small coral corner dot indicating uninstalled/preserved state
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F43F5E")
            style = Paint.Style.FILL
        }
        val dotR = 3.5f * density
        canvas.drawCircle(sizePx - 6.5f * density, 6.5f * density, dotR, dotPaint)

        val drawable = BitmapDrawable(context.resources, bmp)
        deletedBadgeCache[cacheKey] = drawable
        return drawable
    }

    fun warmUpAsync(context: Context) {
        init(context)
        if (!isWarmingOrWarm.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        warmScope.launch {
            try {
                warmUpInternal(appCtx)
            } catch (_: Throwable) {
                isWarmingOrWarm.set(false)
            }
        }
    }

    /**
     * Warms up application labels and icons on Dispatchers.IO in the background.
     */
    suspend fun warmUp(context: Context) = withContext(Dispatchers.IO) {
        init(context)
        if (!isWarmingOrWarm.compareAndSet(false, true)) return@withContext
        try {
            warmUpInternal(context.applicationContext)
        } catch (_: Throwable) {
            isWarmingOrWarm.set(false)
        }
    }

    private fun warmUpInternal(context: Context) {
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val prefs = context.getSharedPreferences(PREFS_LABEL_LEDGER, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var dirty = false

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                installedCache[pkg] = true
                val label = try { ri.loadLabel(pm).toString() } catch (_: Throwable) { "" }
                if (label.isNotBlank()) {
                    labelCache[pkg] = label
                    if (prefs.getString(pkg, null) != label) {
                        editor.putString(pkg, label)
                        dirty = true
                    }
                }
                if (!iconCache.containsKey(pkg)) {
                    try {
                        val icon = ri.loadIcon(pm)
                        iconCache[pkg] = icon
                    } catch (_: Throwable) {}
                }
            }
            installedCacheEpochMs = System.currentTimeMillis()
            if (dirty) editor.apply()
        } catch (_: Throwable) {}
    }
}
