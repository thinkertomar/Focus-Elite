package com.focusguardpro.app.calisthenics

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Two-frame exercise demo (start position <-> finish position) with a soft crossfade.
 * Tap to pause/resume. Collapses itself (GONE) when the exercise has no offline images.
 */
class ExerciseDemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val frameStart = ImageView(context)
    private val frameEnd = ImageView(context)
    private val label = TextView(context)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var paths: List<String> = emptyList()
    private var generation = 0
    private var startReady = false
    private var endReady = false
    private var showingEnd = false
    private var playing = true
    private var loopRunning = false

    private val tick = object : Runnable {
        override fun run() {
            if (!loopRunning) return
            showFrame(!showingEnd)
            uiHandler.postDelayed(this, FRAME_HOLD_MS)
        }
    }

    init {
        val radius = 16f * density
        setBackgroundColor(Color.parseColor("#EEF0F4"))
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }

        listOf(frameStart, frameEnd).forEach {
            it.scaleType = ImageView.ScaleType.FIT_CENTER
            it.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(it)
        }
        frameEnd.alpha = 0f

        label.textSize = 10f
        label.setTextColor(Color.WHITE)
        label.typeface = android.graphics.Typeface.DEFAULT_BOLD
        label.letterSpacing = 0.08f
        label.setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
        label.background = GradientDrawable().apply {
            setColor(Color.parseColor("#B3000000"))
            cornerRadius = 20f * density
        }
        val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.setMargins((10 * density).toInt(), 0, 0, (10 * density).toInt())
        label.layoutParams = lp
        addView(label)

        setOnClickListener {
            if (paths.size < 2 || !startReady || !endReady) return@setOnClickListener
            playing = !playing
            if (playing) startLoop() else stopLoop()
            updateLabel()
        }
    }

    fun bind(relativePaths: List<String>) {
        generation++
        val gen = generation
        stopLoop()
        paths = relativePaths
        startReady = false
        endReady = false
        showingEnd = false
        playing = true
        frameStart.setImageDrawable(null)
        frameEnd.setImageDrawable(null)
        frameEnd.alpha = 0f

        if (paths.isEmpty() || ExerciseImageLoader.isKnownMissing(paths[0])) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        label.visibility = View.GONE

        val target = (resources.displayMetrics.widthPixels).coerceAtLeast(480)
        ExerciseImageLoader.load(context, paths[0], target) { bmp ->
            if (gen != generation) return@load
            if (bmp == null) { visibility = View.GONE; return@load }
            frameStart.setImageBitmap(bmp)
            startReady = true
            updateLabel()
            maybeStart()
        }
        if (paths.size > 1) {
            ExerciseImageLoader.load(context, paths[1], target) { bmp ->
                if (gen != generation) return@load
                if (bmp != null) {
                    frameEnd.setImageBitmap(bmp)
                    endReady = true
                    maybeStart()
                }
            }
        }
    }

    private fun maybeStart() {
        if (startReady && endReady && playing && isShown) startLoop()
        updateLabel()
    }

    private fun showFrame(end: Boolean) {
        showingEnd = end
        frameEnd.animate().cancel()
        frameEnd.animate().alpha(if (end) 1f else 0f).setDuration(FADE_MS).start()
        updateLabel()
    }

    private fun updateLabel() {
        if (!startReady) { label.visibility = View.GONE; return }
        val dual = endReady
        label.visibility = View.VISIBLE
        label.text = when {
            !dual -> "POSITION"
            !playing -> (if (showingEnd) "FINISH" else "START") + "  ·  TAP TO PLAY"
            else -> if (showingEnd) "FINISH" else "START"
        }
    }

    private fun startLoop() {
        if (loopRunning || !(startReady && endReady)) return
        loopRunning = true
        uiHandler.postDelayed(tick, FRAME_HOLD_MS)
    }

    private fun stopLoop() {
        loopRunning = false
        uiHandler.removeCallbacks(tick)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (playing) startLoop() } else stopLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FRAME_HOLD_MS = 1100L
        private const val FADE_MS = 380L
    }
}
