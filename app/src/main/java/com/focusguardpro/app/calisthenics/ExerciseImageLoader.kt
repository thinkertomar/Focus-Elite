package com.focusguardpro.app.calisthenics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Zero-dependency offline image loader. Reads demo frames from assets/exercises/<relativePath>.
 * Missing files are remembered so lists never retry a failed asset lookup.
 */
object ExerciseImageLoader {

    private const val ASSET_ROOT = "exercises/"

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 12L).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun key(path: String, width: Int) = "$path@$width"

    fun peek(path: String, targetWidthPx: Int): Bitmap? = cache.get(key(path, targetWidthPx))

    fun isKnownMissing(path: String): Boolean = missing.contains(path)

    fun load(context: Context, relativePath: String, targetWidthPx: Int, onLoaded: (Bitmap?) -> Unit) {
        val k = key(relativePath, targetWidthPx)
        cache.get(k)?.let { onLoaded(it); return }
        if (missing.contains(relativePath)) { onLoaded(null); return }

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = decode(appContext, relativePath, targetWidthPx)
            if (bitmap != null) cache.put(k, bitmap) else missing.add(relativePath)
            main.post { onLoaded(bitmap) }
        }
    }

    fun loadInto(view: ImageView, relativePath: String?, targetWidthPx: Int, onMissing: (() -> Unit)? = null) {
        if (relativePath == null) {
            view.tag = null
            view.setImageDrawable(null)
            onMissing?.invoke()
            return
        }
        val k = key(relativePath, targetWidthPx)
        view.tag = k
        cache.get(k)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        load(view.context, relativePath, targetWidthPx) { bmp ->
            if (view.tag == k) {
                if (bmp != null) view.setImageBitmap(bmp) else onMissing?.invoke()
            }
        }
    }

    private fun decode(context: Context, relativePath: String, targetWidthPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(ASSET_ROOT + relativePath).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.assets.open(ASSET_ROOT + relativePath).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }
}
