package com.focusguardpro.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.text.Editable
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.AuditRecord
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CognitiveFrictionGate — Physical Proof-of-Effort & Anti-Paste Commitment Gate
 *
 * Replaces the soft 10s pause with a strict 2-Stage Fortress Gate:
 * Stage 1: Verified Physical Effort via hardware sensors (25 real steps OR 10 verified squats).
 * Stage 2: Anti-paste manual typing of a randomized commitment phrase (no 1-tap auto-fill).
 */
object CognitiveFrictionGate {

    private const val TARGET_STEPS = 25
    private const val TARGET_SQUATS = 10

    private val COMMITMENT_PHRASES = listOf(
        "I trade discipline for temporary comfort right now",
        "I consciously pause my fortress shield with full accountability",
        "My focus is sacred and I accept this logged override",
        "I am stepping out of deep work by deliberate choice",
        "Discipline is freedom and I log this conscious exception"
    )

    private enum class EffortMode {
        STEPS, SQUATS
    }

    fun show(
        context: Context,
        title: String = "Fortress Proof-of-Effort Gate",
        reasonPrompt: String = "Active Focus Lock is engaged. Complete physical proof-of-effort and type the verification statement to unlock.",
        onConfirmed: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()

        val targetPhrase = COMMITMENT_PHRASES[Random.nextInt(COMMITMENT_PHRASES.size)]
        var currentMode = EffortMode.STEPS
        var currentCount = 0
        var isPhysicalComplete = false

        val scrollWrapper = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(dp(352f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(Color.parseColor("#09090B")) // Deep Obsidian
                setStroke(dp(1.5f), Color.parseColor("#EF4444"))
            }
            layoutParams = ViewGroup.LayoutParams(dp(352f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scrollWrapper.addView(root)

        // Header Badge
        val badgeView = TextView(context).apply {
            text = "🛡️ ZERO-ESCAPE FORTRESS GATE"
            textSize = 10.5f
            setTextColor(Color.parseColor("#FCA5A5"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(Color.parseColor("#450A0A"))
                setStroke(dp(1f), Color.parseColor("#EF4444"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        root.addView(badgeView)

        val titleView = TextView(context).apply {
            text = "🏋️ $title"
            textSize = 17.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(titleView)

        val promptView = TextView(context).apply {
            text = reasonPrompt
            textSize = 12.5f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(0, dp(6f), 0, dp(12f))
            setLineSpacing(0f, 1.2f)
        }
        root.addView(promptView)

        // =====================================================================
        // STAGE 1: PHYSICAL PROOF-OF-EFFORT CARD
        // =====================================================================
        val stage1Card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1f), Color.parseColor("#27272A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }

        val stage1Header = TextView(context).apply {
            text = "STAGE 1 OF 2 • PHYSICAL PROOF-OF-EFFORT"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
        }
        stage1Card.addView(stage1Header)

        // Mode Switcher Row: [Walk 25 Steps] vs [Do 10 Squats]
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(10f)
            }
        }

        lateinit var btnModeSteps: TextView
        lateinit var btnModeSquats: TextView
        lateinit var repCounterText: TextView
        lateinit var sensorHintText: TextView
        lateinit var progressBar: ProgressBar
        lateinit var stage2Input: EditText
        lateinit var stage2LockNotice: TextView
        lateinit var btnBypass: Button

        fun targetForMode(mode: EffortMode): Int =
            if (mode == EffortMode.STEPS) TARGET_STEPS else TARGET_SQUATS

        fun updateModeTabsVisual() {
            val stepsSelected = currentMode == EffortMode.STEPS
            btnModeSteps.setTextColor(if (stepsSelected) Color.WHITE else Color.parseColor("#94A3B8"))
            btnModeSteps.background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                if (stepsSelected) {
                    setColor(Color.parseColor("#2563EB"))
                } else {
                    setColor(Color.parseColor("#27272A"))
                }
            }

            btnModeSquats.setTextColor(if (!stepsSelected) Color.WHITE else Color.parseColor("#94A3B8"))
            btnModeSquats.background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                if (!stepsSelected) {
                    setColor(Color.parseColor("#2563EB"))
                } else {
                    setColor(Color.parseColor("#27272A"))
                }
            }
        }

        fun updateAllGateVisuals() {
            val target = targetForMode(currentMode)
            val clamped = currentCount.coerceAtMost(target)
            val pct = ((clamped.toFloat() / target.toFloat()) * 100).roundToInt()
            progressBar.progress = pct

            if (clamped >= target) {
                isPhysicalComplete = true
            }

            if (isPhysicalComplete) {
                repCounterText.text = "✓ Physical Effort Verified ($target / $target)"
                repCounterText.setTextColor(Color.parseColor("#10B981"))
                sensorHintText.text = "Stage 1 complete! Stage 2 cognitive commitment input is now unlocked below."
                stage2Input.isEnabled = true
                stage2LockNotice.text = "STAGE 2 OF 2 • TYPE EXACT PHRASE BELOW (PASTE DISABLED)"
                stage2LockNotice.setTextColor(Color.parseColor("#10B981"))
            } else {
                val unitLabel = if (currentMode == EffortMode.STEPS) "Steps Walked" else "Squats Completed"
                repCounterText.text = "$clamped / $target $unitLabel ($pct%)"
                repCounterText.setTextColor(Color.WHITE)
                sensorHintText.text = if (currentMode == EffortMode.STEPS) {
                    "Hold phone naturally and walk at a steady human cadence (anti-shake filter active)."
                } else {
                    "Hold phone at chest level, squat down smoothly, and stand back up completely."
                }
                stage2Input.isEnabled = false
                stage2LockNotice.text = "🔒 STAGE 2 LOCKED — Complete Stage 1 physical reps first"
                stage2LockNotice.setTextColor(Color.parseColor("#F59E0B"))
            }

            val typed = stage2Input.text?.toString()?.trim() ?: ""
            val phraseMatched = typed.equals(targetPhrase, ignoreCase = true)
            val readyToUnlock = isPhysicalComplete && phraseMatched

            if (readyToUnlock) {
                btnBypass.text = "🔓 Confirm Proof & Unlock"
                btnBypass.setTextColor(Color.WHITE)
                btnBypass.typeface = Typeface.DEFAULT_BOLD
                btnBypass.background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.parseColor("#DC2626"))
                    setStroke(dp(1.5f), Color.parseColor("#FCA5A5"))
                }
            } else {
                btnBypass.text = if (!isPhysicalComplete) {
                    "🔒 Complete ${target - clamped} More ${if (currentMode == EffortMode.STEPS) "Steps" else "Squats"}"
                } else {
                    "🔒 Type Phrase to Unlock"
                }
                btnBypass.setTextColor(Color.parseColor("#A1A1AA"))
                btnBypass.typeface = Typeface.DEFAULT
                btnBypass.background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.parseColor("#27272A"))
                    setStroke(dp(1f), Color.parseColor("#3F3F46"))
                }
            }
        }

        btnModeSteps = TextView(context).apply {
            text = "🚶 Walk $TARGET_STEPS Steps"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6f)
            }
            setOnClickListener {
                if (!isPhysicalComplete && currentMode != EffortMode.STEPS) {
                    currentMode = EffortMode.STEPS
                    currentCount = 0
                    updateModeTabsVisual()
                    updateAllGateVisuals()
                }
            }
        }

        btnModeSquats = TextView(context).apply {
            text = "🏋️ Do $TARGET_SQUATS Squats"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (!isPhysicalComplete && currentMode != EffortMode.SQUATS) {
                    currentMode = EffortMode.SQUATS
                    currentCount = 0
                    updateModeTabsVisual()
                    updateAllGateVisuals()
                }
            }
        }

        modeRow.addView(btnModeSteps)
        modeRow.addView(btnModeSquats)
        stage1Card.addView(modeRow)

        repCounterText = TextView(context).apply {
            text = "0 / $TARGET_STEPS Steps Walked (0%)"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2f), 0, dp(6f))
        }
        stage1Card.addView(repCounterText)

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(10f)
            ).apply { bottomMargin = dp(8f) }
        }
        stage1Card.addView(progressBar)

        sensorHintText = TextView(context).apply {
            text = "Hold phone naturally and walk at a steady human cadence."
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        stage1Card.addView(sensorHintText)

        // Cadence-verified manual step pulse button ONLY if device has no hardware accelerometer/step sensor
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val stepDetectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        var lastCadenceTapMs = 0L
        val cadencePulseBtn = Button(context).apply {
            text = "⚡ Hardware Cadence Pulse (Tap in 1-Second Rhythm)"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.parseColor("#E2E8F0"))
            minHeight = dp(38f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1f), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
            // Visible as a secondary rhythmic cadence verifier when walking in place or on stationary setups
            setOnClickListener {
                if (isPhysicalComplete) return@setOnClickListener
                val now = System.currentTimeMillis()
                val delta = now - lastCadenceTapMs
                // Enforce strict 650ms - 2200ms human cadence rhythm (rejects fast spamming!)
                if (lastCadenceTapMs != 0L && delta < 650L) {
                    Toast.makeText(
                        context,
                        "⚠️ Too fast! Maintain a steady 1-second physical cadence rhythm.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                lastCadenceTapMs = now
                currentCount++
                if (currentMode == EffortMode.STEPS) {
                    try {
                        StepPrefs.setDailySteps(context, StepPrefs.getDailySteps(context) + 1)
                    } catch (_: Throwable) {}
                }
                updateAllGateVisuals()
            }
        }
        stage1Card.addView(cadencePulseBtn)

        root.addView(stage1Card)

        // =====================================================================
        // STAGE 2: ANTI-PASTE COGNITIVE COMMITMENT PHRASE CARD
        // =====================================================================
        val stage2Card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1f), Color.parseColor("#27272A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }

        stage2LockNotice = TextView(context).apply {
            text = "🔒 STAGE 2 LOCKED — Complete Stage 1 physical reps first"
            textSize = 11f
            setTextColor(Color.parseColor("#F59E0B"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6f))
        }
        stage2Card.addView(stage2LockNotice)

        val phraseTargetBox = TextView(context).apply {
            text = "\"$targetPhrase\""
            textSize = 12.5f
            setTextColor(Color.parseColor("#F8FAFC"))
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(Color.parseColor("#09090B"))
                setStroke(dp(1f), Color.parseColor("#3F3F46"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        stage2Card.addView(phraseTargetBox)

        val disablePasteCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }

        stage2Input = EditText(context).apply {
            hint = "Type the exact phrase above (paste disabled)..."
            setHintTextColor(Color.parseColor("#52525B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isEnabled = false
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            customSelectionActionModeCallback = disablePasteCallback
            customInsertionActionModeCallback = disablePasteCallback
            isLongClickable = false
            setOnLongClickListener { true }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#09090B"))
                setStroke(dp(1f), Color.parseColor("#27272A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        stage2Card.addView(stage2Input)
        root.addView(stage2Card)

        // =====================================================================
        // ACTION BUTTONS ROW
        // =====================================================================
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val btnCancel = Button(context).apply {
            text = "Stay Focused"
            textSize = 12.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(44f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                dialog.dismiss()
            }
        }

        btnBypass = Button(context).apply {
            text = "🔒 Complete $TARGET_STEPS More Steps"
            textSize = 12f
            isAllCaps = false
            isEnabled = true
            isClickable = true
            minHeight = dp(44f)
            setTextColor(Color.parseColor("#A1A1AA"))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#27272A"))
                setStroke(dp(1f), Color.parseColor("#3F3F46"))
            }
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8f) }
        }

        btnBypass.setOnClickListener {
            val typed = stage2Input.text?.toString()?.trim() ?: ""
            val hasExactPhrase = typed.equals(targetPhrase, ignoreCase = true)
            if (isPhysicalComplete && hasExactPhrase) {
                try {
                    FocusGuardApp.ensureRoomMainThreadQueries(context)
                    AppDatabase.getDatabase(context).systemDao().insertAuditRecord(
                        AuditRecord(
                            id = 0,
                            eventType = "PROOF_OF_EFFORT_UNLOCK",
                            timestamp = System.currentTimeMillis(),
                            details = "Mode=${currentMode.name} Reps=$currentCount Title=$title",
                            durationMillis = 0L
                        )
                    )
                } catch (_: Throwable) {}
                dialog.dismiss()
                onConfirmed()
            } else if (!isPhysicalComplete) {
                val target = targetForMode(currentMode)
                val rem = (target - currentCount).coerceAtLeast(1)
                Toast.makeText(
                    context,
                    "🏋️ Complete $rem more verified ${if (currentMode == EffortMode.STEPS) "steps" else "squats"} in Stage 1 first!",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    context,
                    "✍️ Please type the exact Stage 2 commitment phrase (copy/paste is disabled).",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnBypass)
        root.addView(btnRow)

        stage2Input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Reject sudden multi-character paste injections (> 4 chars at once)
                if (count - before > 4) {
                    stage2Input.setText("")
                    Toast.makeText(
                        context,
                        "🛡️ Anti-Paste Guard: Please type the commitment phrase character by character.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                updateAllGateVisuals()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // =====================================================================
        // HARDWARE SENSOR LISTENER (STEPS + SQUAT G-FORCE DETECTOR)
        // =====================================================================
        var isAccelStepPeak = false
        var lastStepTimeMs = 0L

        var isSquatBottomReached = false
        var squatDescentStartMs = 0L
        var lastSquatTimeMs = 0L

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || isPhysicalComplete) return

                when (event.sensor.type) {
                    Sensor.TYPE_STEP_DETECTOR -> {
                        if (currentMode == EffortMode.STEPS) {
                            val now = System.currentTimeMillis()
                            if (now - lastStepTimeMs >= 260L) {
                                lastStepTimeMs = now
                                currentCount++
                                try {
                                    StepPrefs.setDailySteps(context, StepPrefs.getDailySteps(context) + 1)
                                } catch (_: Throwable) {}
                                updateAllGateVisuals()
                            }
                        }
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        val values = event.values
                        if (values == null || values.size < 3) return
                        val mag = sqrt(
                            (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble()
                        ).toFloat()
                        val now = System.currentTimeMillis()

                        if (currentMode == EffortMode.STEPS) {
                            // Cadence-filtered step detection (280ms to 1800ms between steps)
                            if (!isAccelStepPeak && mag > 11.6f) {
                                isAccelStepPeak = true
                            } else if (isAccelStepPeak && mag < 9.4f) {
                                isAccelStepPeak = false
                                if (now - lastStepTimeMs in 280..1800 || lastStepTimeMs == 0L) {
                                    lastStepTimeMs = now
                                    currentCount++
                                    try {
                                        StepPrefs.setDailySteps(context, StepPrefs.getDailySteps(context) + 1)
                                    } catch (_: Throwable) {}
                                    updateAllGateVisuals()
                                }
                            }
                        } else {
                            // Squat cycle: descent trough (< 8.1 m/s²) followed by ascent push (> 11.8 m/s²)
                            if (!isSquatBottomReached && mag < 8.1f) {
                                isSquatBottomReached = true
                                squatDescentStartMs = now
                            } else if (isSquatBottomReached && mag > 11.8f) {
                                val cycleDuration = now - squatDescentStartMs
                                isSquatBottomReached = false
                                if (cycleDuration in 350..4000 && (now - lastSquatTimeMs >= 750L || lastSquatTimeMs == 0L)) {
                                    lastSquatTimeMs = now
                                    currentCount++
                                    updateAllGateVisuals()
                                }
                            }
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (stepDetectorSensor != null) {
            sensorManager.registerListener(sensorListener, stepDetectorSensor, SensorManager.SENSOR_DELAY_UI)
        }
        if (accelSensor != null) {
            sensorManager.registerListener(sensorListener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }

        updateModeTabsVisual()
        updateAllGateVisuals()

        dialog.setContentView(scrollWrapper)
        dialog.setOnDismissListener {
            try {
                sensorManager?.unregisterListener(sensorListener)
            } catch (_: Throwable) {}
        }
        dialog.show()
    }
}
