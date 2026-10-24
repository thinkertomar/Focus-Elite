package com.focusguardpro.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Interactive Widget Studio & Quick-Pin Hub.
 * Combines 1-tap style selection & instant Home Screen pinning with a live,
 * touch-interactive WYSIWYG Command Deck preview and Dual Ring Goal Calibrator.
 */
class WidgetStudioActivity : AppCompatActivity() {

    private lateinit var previewView: InteractiveCommandDeckPreviewView
    private lateinit var dualNeonRingsView: DualNeonRingsView
    private lateinit var skinChipsContainer: LinearLayout
    private lateinit var durationChipsContainer: LinearLayout
    private lateinit var statusBadgeView: TextView
    private lateinit var budgetValueLabel: TextView
    private lateinit var stepGoalValueLabel: TextView
    private var ringAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        FocusSessionManager.init(this)
        buildStudioUi()
        animatePreviewSweep()
        observeLiveSessionUpdates()
    }

    override fun onResume() {
        super.onResume()
        refreshPinStatusBadge()
        previewView.invalidate()
        dualNeonRingsView.refreshTelemetryAndSyncHealthConnect(animate = false)
        FocusWidgetService.syncTelemetryNow(this)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun buildStudioUi() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.backgroundColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top App Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(28f), dp(16f), dp(12f))
        }

        val backBtn = TextView(this).apply {
            text = "← Home"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(palette.cardSurfaceColor)
                setStroke(dp(1f), palette.accentColor)
            }
            setOnClickListener { finish() }
        }
        header.addView(backBtn)

        val headerTitle = TextView(this).apply {
            text = "Command Deck Studio"
            textSize = 17.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(headerTitle)

        statusBadgeView = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        }
        header.addView(statusBadgeView)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(6f), dp(18f), dp(36f))
        }

        // =========================================================================
        // 1. QUICK-PIN & ARCHITECTURAL SKIN BAR (OPTION 2 + INSTANT PIN)
        // =========================================================================
        val quickPinCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = UiThemePrefs.createCardBackground(this@WidgetStudioActivity, cornerDp = 18f, isHero = true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18f) }
        }

        val quickPinTag = TextView(this).apply {
            text = "⚡ QUICK-PIN & ARCHITECTURAL SKIN SELECTOR"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
        }
        quickPinCard.addView(quickPinTag)

        val quickPinSub = TextView(this).apply {
            text = "Choose a cockpit skin below and pin the All-in-One Command Deck directly to your Android home screen."
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(4f), 0, dp(12f))
        }
        quickPinCard.addView(quickPinSub)

        val skinScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }
        skinChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        renderSkinChips()
        skinScroll.addView(skinChipsContainer)
        quickPinCard.addView(skinScroll)

        // Pin + Sync Buttons Row
        val pinBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pinNowBtn = Button(this).apply {
            text = "📌 Pin Command Deck Widget"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.parseColor("#09090B"))
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(48f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8f)
            }
            setOnClickListener {
                handlePinWidgetRequest()
            }
        }
        pinBtnRow.addView(pinNowBtn)

        val syncBtn = Button(this).apply {
            text = "🔄 Sync"
            textSize = 12.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(48f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1f), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(86f), dp(48f))
            setOnClickListener {
                FocusWidgetService.syncTelemetryNow(this@WidgetStudioActivity)
                dualNeonRingsView.refreshTelemetryAndSyncHealthConnect(animate = true)
                animatePreviewSweep()
                Toast.makeText(
                    this@WidgetStudioActivity,
                    "✨ Command Deck & Health Connect telemetry synced!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        pinBtnRow.addView(syncBtn)
        quickPinCard.addView(pinBtnRow)
        content.addView(quickPinCard)

        // =========================================================================
        // 2. LIVE INTERACTIVE WYSIWYG COMMAND DECK PREVIEW (OPTION 1)
        // =========================================================================
        val previewHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8f))
        }
        val previewLabel = TextView(this).apply {
            text = "LIVE INTERACTIVE COCKPIT PREVIEW (TAP ZONES TO TEST)"
            textSize = 11.5f
            setTextColor(Color.parseColor("#10B981"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        previewHeaderRow.addView(previewLabel)
        content.addView(previewHeaderRow)

        previewView = InteractiveCommandDeckPreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(156f)
            ).apply { bottomMargin = dp(8f) }
        }
        content.addView(previewView)

        val zoneGuideText = TextView(this).apply {
            text = "👆 Interactive Test: Tap Left Rings (Activity), Center Shield (Toggle Focus Layer), or Right [⚡ 25m + 40Hz] above to test live!"
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(4f), 0, dp(4f), dp(18f))
        }
        content.addView(zoneGuideText)

        // =========================================================================
        // 3. DUAL NEON RINGS CANVAS VIEW + HEALTH CONNECT CALIBRATOR
        // =========================================================================
        val calibratorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1f), Color.parseColor("#27272A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }

        val calTitle = TextView(this).apply {
            text = "🎯 Dual Neon Rings (Screen Time vs. Health Connect Steps)"
            textSize = 14.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        calibratorCard.addView(calTitle)

        dualNeonRingsView = DualNeonRingsView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(132f)
            ).apply {
                topMargin = dp(10f)
                bottomMargin = dp(6f)
            }
            setOnClickListener {
                refreshTelemetryAndSyncHealthConnect(animate = true) { steps, _, hc ->
                    val src = if (hc) "Health Connect" else "Step Sensor"
                    Toast.makeText(
                        this@WidgetStudioActivity,
                        "💚 Synced $steps steps via $src",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                FocusWidgetService.syncTelemetryNow(this@WidgetStudioActivity)
            }
        }
        calibratorCard.addView(dualNeonRingsView)

        // Screen Time Budget Slider
        val currentBudget = CommandDeckWidgetPrefs.getScreenTimeBudgetMinutes(this)
        budgetValueLabel = TextView(this).apply {
            text = "Outer Ring • Daily Screen-Time Budget: ${currentBudget / 60}h ${currentBudget % 60}m"
            textSize = 12.5f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12f), 0, dp(4f))
        }
        calibratorCard.addView(budgetValueLabel)

        val budgetSeek = SeekBar(this).apply {
            max = 420 // 60m to 480m
            progress = (currentBudget - 60).coerceIn(0, 420)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val steppedMins = ((progress + 60) / 15) * 15
                    CommandDeckWidgetPrefs.setScreenTimeBudgetMinutes(this@WidgetStudioActivity, steppedMins)
                    budgetValueLabel.text = "Outer Ring • Daily Screen-Time Budget: ${steppedMins / 60}h ${steppedMins % 60}m"
                    previewView.invalidate()
                    dualNeonRingsView.loadCachedTelemetry()
                    dualNeonRingsView.invalidate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    FocusWidgetService.syncTelemetryNow(this@WidgetStudioActivity)
                }
            })
        }
        calibratorCard.addView(budgetSeek)

        // Daily Step Goal Slider
        val currentStepGoal = CommandDeckWidgetPrefs.getDailyStepGoal(this)
        stepGoalValueLabel = TextView(this).apply {
            text = "Inner Ring • Daily Physical Step Target: $currentStepGoal steps"
            textSize = 12.5f
            setTextColor(Color.parseColor("#10B981"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14f), 0, dp(4f))
        }
        calibratorCard.addView(stepGoalValueLabel)

        val stepSeek = SeekBar(this).apply {
            max = 18000 // 2000 to 20000
            progress = (currentStepGoal - 2000).coerceIn(0, 18000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val steppedGoal = ((progress + 2000) / 500) * 500
                    CommandDeckWidgetPrefs.setDailyStepGoal(this@WidgetStudioActivity, steppedGoal)
                    stepGoalValueLabel.text = "Inner Ring • Daily Physical Step Target: $steppedGoal steps"
                    previewView.invalidate()
                    dualNeonRingsView.loadCachedTelemetry()
                    dualNeonRingsView.invalidate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    FocusWidgetService.syncTelemetryNow(this@WidgetStudioActivity)
                }
            })
        }
        calibratorCard.addView(stepSeek)
        content.addView(calibratorCard)

        // =========================================================================
        // 4. 1-TAP DEEP FOCUS + 40HZ GAMMA LAUNCHER SETTINGS
        // =========================================================================
        val focusCfgCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1f), Color.parseColor("#27272A"))
            }
        }

        val focusCfgTitle = TextView(this).apply {
            text = "⚡ 1-Tap Deep Focus & 40Hz Gamma Ignition"
            textSize = 14.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        focusCfgCard.addView(focusCfgTitle)

        val focusCfgSub = TextView(this).apply {
            text = "Configure the duration and neuro-acoustic soundscape triggered when you tap the right-hand Quick Focus button on the widget."
            textSize = 12f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(0, dp(4f), 0, dp(12f))
        }
        focusCfgCard.addView(focusCfgSub)

        val durScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }
        durationChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        renderQuickDurationChips()
        durScroll.addView(durationChipsContainer)
        focusCfgCard.addView(durScroll)

        // 40Hz Gamma Auto-Start Switch Row
        val gammaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gammaTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        gammaTextCol.addView(TextView(this).apply {
            text = "Auto-Synthesize 40Hz Gamma Waves"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        gammaTextCol.addView(TextView(this).apply {
            text = "Immediately starts procedural 40Hz binaural audio on 1-tap widget launch"
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        gammaRow.addView(gammaTextCol)

        val gammaSwitch = Switch(this).apply {
            isChecked = CommandDeckWidgetPrefs.isAuto40HzEnabled(this@WidgetStudioActivity)
            setOnCheckedChangeListener { _, isChecked ->
                CommandDeckWidgetPrefs.setAuto40HzEnabled(this@WidgetStudioActivity, isChecked)
                previewView.invalidate()
                EliteCommandWidgetProvider.updateAllWidgets(this@WidgetStudioActivity)
            }
        }
        gammaRow.addView(gammaSwitch)
        focusCfgCard.addView(gammaRow)

        content.addView(focusCfgCard)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        refreshPinStatusBadge()
    }

    private fun refreshPinStatusBadge() {
        val isPinned = EliteCommandWidgetProvider.isAnyWidgetPinned(this)
        statusBadgeView.text = if (isPinned) "✓ ACTIVE ON HOME" else "READY TO PIN"
        statusBadgeView.setTextColor(if (isPinned) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
        statusBadgeView.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(Color.parseColor("#18181B"))
            setStroke(dp(1f), if (isPinned) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
        }
    }

    private fun renderSkinChips() {
        skinChipsContainer.removeAllViews()
        val selected = CommandDeckWidgetPrefs.getSelectedSkin(this)
        for (skin in CommandDeckWidgetPrefs.WidgetSkin.values()) {
            val isSelected = skin == selected
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(if (isSelected) skin.panelColor else Color.parseColor("#18181B"))
                    setStroke(dp(if (isSelected) 2f else 1f), if (isSelected) skin.borderColor else Color.parseColor("#27272A"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10f) }
                setOnClickListener {
                    CommandDeckWidgetPrefs.setSelectedSkin(this@WidgetStudioActivity, skin)
                    renderSkinChips()
                    animatePreviewSweep()
                    EliteCommandWidgetProvider.updateAllWidgets(this@WidgetStudioActivity)
                }
            }
            chip.addView(TextView(this).apply {
                text = if (isSelected) "✦ ${skin.title}" else skin.title
                textSize = 13f
                setTextColor(if (isSelected) skin.borderColor else Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            chip.addView(TextView(this).apply {
                text = skin.subtitle
                textSize = 10.5f
                setTextColor(Color.parseColor("#94A3B8"))
            })
            skinChipsContainer.addView(chip)
        }
    }

    private fun renderQuickDurationChips() {
        durationChipsContainer.removeAllViews()
        val selectedMins = CommandDeckWidgetPrefs.getQuickFocusMinutes(this)
        val options = listOf(
            15 to "15m Blitz",
            25 to "25m Pomodoro",
            45 to "45m Deep Work",
            60 to "60m Flow State"
        )
        for ((mins, label) in options) {
            val isSelected = mins == selectedMins
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (isSelected) Color.parseColor("#38BDF8") else Color.parseColor("#27272A"))
                }
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8f) }
                setOnClickListener {
                    CommandDeckWidgetPrefs.setQuickFocusMinutes(this@WidgetStudioActivity, mins)
                    renderQuickDurationChips()
                    previewView.invalidate()
                    EliteCommandWidgetProvider.updateAllWidgets(this@WidgetStudioActivity)
                }
            }
            durationChipsContainer.addView(chip)
        }
    }

    private fun handlePinWidgetRequest() {
        val requested = EliteCommandWidgetProvider.requestPinCommandDeck(this)
        refreshPinStatusBadge()
        if (requested) {
            Toast.makeText(
                this,
                "📌 Confirm 'Add to Home Screen' in the system prompt!",
                Toast.LENGTH_LONG
            ).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("📌 Pin Command Deck Widget")
                .setMessage(
                    "Your custom skin & ring goals are saved!\n\n" +
                        "If your launcher didn't show a popup:\n" +
                        "1. Long-press an empty space on your phone's Home Screen.\n" +
                        "2. Tap 'Widgets' → 'Focus Elite'.\n" +
                        "3. Drag the Command Deck onto your Home Screen."
                )
                .setPositiveButton("Got It", null)
                .show()
        }
    }

    private fun animatePreviewSweep() {
        ringAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(0.1f, 1f).apply {
            duration = 650L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                previewView.animProgress = anim.animatedValue as Float
                previewView.invalidate()
            }
            start()
        }
    }

    private fun observeLiveSessionUpdates() {
        lifecycleScope.launch {
            FocusSessionManager.snapshot.collectLatest {
                previewView.invalidate()
            }
        }
    }

    /**
     * Custom View that renders the exact CommandDeckWidgetPrefs bitmap and allows
     * interactive touch testing of all 3 Command Deck zones directly inside the Studio.
     */
    private inner class InteractiveCommandDeckPreviewView(context: Context) : View(context) {
        var animProgress: Float = 1f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.coerceAtLeast(320)
            val h = height.coerceAtLeast(160)
            val bmp = CommandDeckWidgetPrefs.renderCommandDeckBitmap(context, w, h, animProgress)
            canvas.drawBitmap(bmp, 0f, 0f, null)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val xRatio = event.x / width.coerceAtLeast(1)
                val yRatio = event.y / height.coerceAtLeast(1)
                when {
                    xRatio < 0.33f -> {
                        startActivity(Intent(this@WidgetStudioActivity, ActivityStatsActivity::class.java))
                    }
                    xRatio < 0.68f -> {
                        val subIntent = Intent(this@WidgetStudioActivity, EliteCommandWidgetProvider::class.java).apply {
                            action = EliteCommandWidgetProvider.ACTION_TOGGLE_FOCUS
                        }
                        sendBroadcast(subIntent)
                        postDelayed({ invalidate() }, 150L)
                    }
                    else -> {
                        if (yRatio < 0.68f) {
                            val focusIntent = Intent(this@WidgetStudioActivity, EliteCommandWidgetProvider::class.java).apply {
                                action = EliteCommandWidgetProvider.ACTION_QUICK_FOCUS
                            }
                            sendBroadcast(focusIntent)
                            postDelayed({ invalidate() }, 150L)
                        } else {
                            animatePreviewSweep()
                            Toast.makeText(
                                this@WidgetStudioActivity,
                                "🎨 You are inside the live Command Deck Studio!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                return true
            }
            return true
        }
    }
}
