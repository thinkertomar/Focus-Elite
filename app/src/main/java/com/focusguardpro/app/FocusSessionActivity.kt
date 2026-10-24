package com.focusguardpro.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FocusSessionActivity : AppCompatActivity() {

    private lateinit var timerDisplay: TextView
    private lateinit var timerSubtext: TextView
    private lateinit var timerCircleContainer: LinearLayout
    private lateinit var startStopButton: Button
    private lateinit var intentionLabel: TextView
    private lateinit var chipsContainer: LinearLayout
    private lateinit var soundChipsContainer: LinearLayout
    private lateinit var statsSummaryText: TextView

    private var selectedDurationMinutes = 25
    private var selectedSoundMode = AmbientFocusAudioEngine.SoundMode.GAMMA_40HZ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        FocusSessionManager.init(this)
        buildUi()
        observeSessionState()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.backgroundColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(28f), dp(16f), dp(14f))
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

        val titleView = TextView(this).apply {
            text = "Deep Focus Studio"
            textSize = 18.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        header.addView(titleView)

        val exportBtn = TextView(this).apply {
            text = "Export ↗"
            textSize = 12.5f
            setTextColor(palette.secondaryColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(palette.cardSurfaceColor)
                setStroke(dp(1f), palette.accentColor)
            }
            setOnClickListener {
                showExportDialog()
            }
        }
        header.addView(exportBtn)
        root.addView(header)

        // Scrollable content
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(32f))
        }

        // Intention Card
        val intentionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = UiThemePrefs.createCardBackground(this@FocusSessionActivity, cornerDp = 16f, isHero = false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20f)
            }
        }

        val intentionHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val intentionTag = TextView(this).apply {
            text = "CURRENT INTENTION"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editIntentionBtn = TextView(this).apply {
            text = "Edit ✏️"
            textSize = 12f
            setTextColor(Color.parseColor("#A1A1AA"))
            setOnClickListener { promptEditIntention() }
        }
        intentionHeaderRow.addView(intentionTag)
        intentionHeaderRow.addView(editIntentionBtn)
        intentionCard.addView(intentionHeaderRow)

        intentionLabel = TextView(this).apply {
            text = "🎯 Deep Work Session"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6f), 0, 0)
        }
        intentionCard.addView(intentionLabel)
        content.addView(intentionCard)

        // Circular Timer Display Card
        timerCircleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(36f), dp(24f), dp(36f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Color.parseColor("#121215"))
                setStroke(dp(2f), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(24f)
            }
        }

        timerDisplay = TextView(this).apply {
            text = "25:00"
            textSize = 54f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        timerCircleContainer.addView(timerDisplay)

        timerSubtext = TextView(this).apply {
            text = "Ready to begin • Pure Flow"
            textSize = 13f
            setTextColor(Color.parseColor("#71717A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, 0)
        }
        timerCircleContainer.addView(timerSubtext)
        content.addView(timerCircleContainer)

        // Session Duration Chips
        val durationLabel = TextView(this).apply {
            text = "SESSION LENGTH"
            textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8f))
        }
        content.addView(durationLabel)

        val durationScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20f)
            }
        }
        chipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        setupDurationChips()
        durationScroll.addView(chipsContainer)
        content.addView(durationScroll)

        // Ambient Sound Selector
        val soundLabel = TextView(this).apply {
            text = "FOCUS SOUNDSCAPE (PROCEDURAL AUDIO)"
            textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8f))
        }
        content.addView(soundLabel)

        val soundScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(28f)
            }
        }
        soundChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        setupSoundChips()
        soundScroll.addView(soundChipsContainer)
        content.addView(soundScroll)

        // Primary Action Button
        startStopButton = Button(this).apply {
            text = "⚡ START DEEP FOCUS"
            textSize = 15f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#38BDF8")) // Sky blue neon
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54f)
            ).apply {
                bottomMargin = dp(24f)
            }
            setOnClickListener {
                handlePrimaryAction()
            }
        }
        content.addView(startStopButton)

        // Stats & History Card
        val statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1f), Color.parseColor("#27272A"))
            }
        }

        val statsTitle = TextView(this).apply {
            text = "📊 Today's Focus Metrics"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        statsCard.addView(statsTitle)

        statsSummaryText = TextView(this).apply {
            text = "0 minutes focused today • 0 sessions completed"
            textSize = 13f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(0, dp(6f), 0, 0)
        }
        statsCard.addView(statsSummaryText)
        content.addView(statsCard)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        updateStatsSummary()
    }

    private fun setupDurationChips() {
        chipsContainer.removeAllViews()
        val options: List<Pair<Int, String>> = listOf(
            Pair(15, "15m Blitz"),
            Pair(25, "25m Pomodoro"),
            Pair(45, "45m Deep Dive"),
            Pair(60, "60m Flow State"),
            Pair(90, "90m Ultra")
        )
        for ((dur, label) in options) {
            val isSelected = (dur == selectedDurationMinutes)
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isSelected) Color.BLACK else Color.parseColor("#E4E4E7"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(if (isSelected) Color.parseColor("#38BDF8") else Color.parseColor("#27272A"))
                    if (!isSelected) setStroke(dp(1f), Color.parseColor("#3F3F46"))
                }
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8f)
                }
                setOnClickListener {
                    if (FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.IDLE) {
                        selectedDurationMinutes = dur
                        setupDurationChips()
                        val m = dur
                        timerDisplay.text = String.format("%02d:00", m)
                    }
                }
            }
            chipsContainer.addView(chip)
        }
    }

    private fun setupSoundChips() {
        soundChipsContainer.removeAllViews()
        val sounds: List<Pair<AmbientFocusAudioEngine.SoundMode, String>> = listOf(
            Pair(AmbientFocusAudioEngine.SoundMode.GAMMA_40HZ, "🌊 Gamma 40Hz"),
            Pair(AmbientFocusAudioEngine.SoundMode.SOFT_RAIN, "🌧️ Pink Rain"),
            Pair(AmbientFocusAudioEngine.SoundMode.BROWN_NOISE, "☕ Brown Noise"),
            Pair(AmbientFocusAudioEngine.SoundMode.OFF, "🔕 Silence")
        )
        for ((sm, label) in sounds) {
            val isSelected = (sm == selectedSoundMode)
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#A1A1AA"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(if (isSelected) Color.parseColor("#1E3A8A") else Color.parseColor("#18181B"))
                    setStroke(dp(1f), if (isSelected) Color.parseColor("#3B82F6") else Color.parseColor("#27272A"))
                }
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8f)
                }
                setOnClickListener {
                    selectedSoundMode = sm
                    setupSoundChips()
                    if (FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.RUNNING) {
                        AmbientFocusAudioEngine.setSoundMode(sm)
                    }
                }
            }
            soundChipsContainer.addView(chip)
        }
    }

    private fun handlePrimaryAction() {
        val currentStatus = FocusSessionManager.snapshot.value.state
        when (currentStatus) {
            FocusSessionManager.SessionState.IDLE, FocusSessionManager.SessionState.COMPLETED -> {
                val intention = intentionLabel.text.toString().trim()
                FocusSessionManager.startSession(
                    context = this,
                    durationMinutes = selectedDurationMinutes,
                    intention = intention,
                    soundMode = selectedSoundMode
                )
            }
            FocusSessionManager.SessionState.RUNNING -> {
                // Trigger Cognitive Friction Gate before allowing early exit/pause!
                CognitiveFrictionGate.show(
                    context = this,
                    title = "Pause Focus Session?",
                    reasonPrompt = "Your session is currently running in deep flow. Pausing now interrupts your neural focus momentum."
                ) {
                    FocusSessionManager.stopSession(this, completed = false)
                }
            }
            FocusSessionManager.SessionState.PAUSED -> {
                FocusSessionManager.resumeSession()
            }
        }
    }

    private fun observeSessionState() {
        lifecycleScope.launch {
            FocusSessionManager.snapshot.collectLatest { snapshot ->
                when (snapshot.state) {
                    FocusSessionManager.SessionState.IDLE -> {
                        startStopButton.text = "⚡ START DEEP FOCUS"
                        startStopButton.background = GradientDrawable().apply {
                            cornerRadius = dp(16f).toFloat()
                            setColor(Color.parseColor("#38BDF8"))
                        }
                        startStopButton.setTextColor(Color.BLACK)
                        timerCircleContainer.background = GradientDrawable().apply {
                            cornerRadius = dp(24f).toFloat()
                            setColor(Color.parseColor("#121215"))
                            setStroke(dp(2f), Color.parseColor("#27272A"))
                        }
                        timerSubtext.text = "Ready to begin • Pure Flow"
                        val m = selectedDurationMinutes
                        timerDisplay.text = String.format("%02d:00", m)
                    }
                    FocusSessionManager.SessionState.RUNNING -> {
                        val m = snapshot.remainingSeconds / 60
                        val s = snapshot.remainingSeconds % 60
                        timerDisplay.text = String.format("%02d:%02d", m, s)
                        startStopButton.text = "🛑 CONCLUDE SESSION"
                        startStopButton.background = GradientDrawable().apply {
                            cornerRadius = dp(16f).toFloat()
                            setColor(Color.parseColor("#DC2626")) // Red
                        }
                        startStopButton.setTextColor(Color.WHITE)
                        timerCircleContainer.background = GradientDrawable().apply {
                            cornerRadius = dp(24f).toFloat()
                            setColor(Color.parseColor("#064E3B")) // Emerald dark glow
                            setStroke(dp(2f), Color.parseColor("#10B981"))
                        }
                        timerSubtext.text = "🟢 Active Flow (${snapshot.soundMode.displayName})"
                        intentionLabel.text = snapshot.intention
                    }
                    FocusSessionManager.SessionState.PAUSED -> {
                        startStopButton.text = "▶️ RESUME SESSION"
                        startStopButton.background = GradientDrawable().apply {
                            cornerRadius = dp(16f).toFloat()
                            setColor(Color.parseColor("#F59E0B")) // Amber
                        }
                        startStopButton.setTextColor(Color.BLACK)
                        timerSubtext.text = "⏸️ Session Paused"
                    }
                    FocusSessionManager.SessionState.COMPLETED -> {
                        startStopButton.text = "🎉 SESSION COMPLETED - START NEW"
                        startStopButton.background = GradientDrawable().apply {
                            cornerRadius = dp(16f).toFloat()
                            setColor(Color.parseColor("#10B981")) // Green
                        }
                        startStopButton.setTextColor(Color.BLACK)
                        timerCircleContainer.background = GradientDrawable().apply {
                            cornerRadius = dp(24f).toFloat()
                            setColor(Color.parseColor("#064E3B"))
                            setStroke(dp(2f), Color.parseColor("#10B981"))
                        }
                        timerSubtext.text = "✨ Mindful Deep Session Achieved!"
                        timerDisplay.text = "00:00"
                    }
                }
                updateStatsSummary()
            }
        }
    }

    private fun promptEditIntention() {
        val input = EditText(this).apply {
            setText(intentionLabel.text)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#18181B"))
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        AlertDialog.Builder(this)
            .setTitle("Set Session Intention")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.isNotEmpty()) {
                    intentionLabel.text = txt
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStatsSummary() {
        val totalMins = FocusSessionManager.getTodayFocusMinutes(this)
        val sessionsCount = FocusSessionManager.getTodaySessionsCount(this)
        statsSummaryText.text = "$totalMins minutes focused today • $sessionsCount sessions completed"
    }

    private fun showExportDialog() {
        val options = arrayOf("Export Complete JSON Backup", "Export Sessions CSV (Spreadsheet)")
        AlertDialog.Builder(this)
            .setTitle("Export Focus Records")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> DataExportManager.exportAndShare(this, DataExportManager.ExportFormat.JSON)
                    1 -> DataExportManager.exportAndShare(this, DataExportManager.ExportFormat.CSV)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // If session is idle, ensure audio engine stopped
        if (FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.IDLE) {
            AmbientFocusAudioEngine.stop()
        }
    }
}
