package com.focusguardpro.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * CognitiveFrictionGate
 *
 * Implements intentional cognitive friction to prevent impulsive exits while keeping
 * the "CONSCIOUS BYPASS" action clear, responsive, and immediately clickable once
 * the 10-second mindful breathing pause completes (or via 1-tap intention chips).
 */
object CognitiveFrictionGate {

    private const val REQUIRED_PHRASE = "I consciously choose to break focus"

    fun show(
        context: Context,
        title: String = "Mindful Pause Gate",
        reasonPrompt: String = "Take 10 seconds to breathe. Is pausing this session aligned with your deep work mission?",
        onConfirmed: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Color.parseColor("#18181B")) // Dark Zinc
                setStroke(dp(1.5f), Color.parseColor("#3F3F46"))
            }
            layoutParams = ViewGroup.LayoutParams(dp(345f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleView = TextView(context).apply {
            text = "🧘 $title"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(titleView)

        val promptView = TextView(context).apply {
            text = reasonPrompt
            textSize = 13f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(0, dp(8f), 0, dp(14f))
            setLineSpacing(0f, 1.25f)
        }
        root.addView(promptView)

        // Mindful breathing countdown label + progress bar
        var secondsRemaining = 10L
        var isTimerComplete = false

        val timerLabel = TextView(context).apply {
            text = "Breathe in slowly... (10s)"
            textSize = 12.5f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(6f))
        }
        root.addView(timerLabel)

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 100
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8f)
            ).apply {
                setMargins(0, 0, 0, dp(14f))
            }
        }
        root.addView(progressBar)

        // Verification Input Box + 1-Tap Quick Intention Chips
        val instructionText = TextView(context).apply {
            text = "Optional: Select a reason chip or type any note (unlocks automatically when pause finishes):"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(8f))
        }
        root.addView(instructionText)

        val input = EditText(context).apply {
            hint = "Enter short reason or tap a chip below..."
            setHintTextColor(Color.parseColor("#52525B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#09090B"))
                setStroke(dp(1f), Color.parseColor("#27272A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(10f))
            }
        }

        // Quick 1-Tap Intention Chips so user never has to type a long sentence manually
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16f)
            }
        }
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val quickReasons = listOf(
            "☕ Short Break" to "Taking a conscious short break",
            "📞 Urgent Task" to "Handling an urgent task",
            "✅ Done Early" to "Completed focus goal early",
            "✍️ Auto-Fill" to REQUIRED_PHRASE
        )
        for ((chipLabel, fillText) in quickReasons) {
            val chip = TextView(context).apply {
                text = chipLabel
                textSize = 11f
                setTextColor(Color.parseColor("#E2E8F0"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Color.parseColor("#27272A"))
                    setStroke(dp(1f), Color.parseColor("#3F3F46"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    input.setText(fillText)
                    input.setSelection(fillText.length)
                }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)

        root.addView(input)
        root.addView(chipScroll)

        // Action Buttons Row
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
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#2563EB")) // Vibrant blue
            }
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            setOnClickListener {
                dialog.dismiss()
            }
        }

        val btnBypass = Button(context).apply {
            text = "🔒 Conscious Bypass (10s)"
            textSize = 12f
            isAllCaps = false
            // Always keep clickable so tapping during countdown gives helpful feedback
            isEnabled = true
            isClickable = true
            setTextColor(Color.parseColor("#A1A1AA"))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#27272A"))
                setStroke(dp(1f), Color.parseColor("#3F3F46"))
            }
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(10f)
            }
            layoutParams = lp
        }

        fun updateBypassButtonVisuals() {
            val typed = input.text?.toString()?.trim() ?: ""
            val hasExactPhrase = typed.equals(REQUIRED_PHRASE, ignoreCase = true)
            // Unlock as soon as the 10s mindful pause finishes OR if the user typed the exact phrase
            val canBypassNow = isTimerComplete || hasExactPhrase

            if (canBypassNow) {
                btnBypass.text = "🔓 Conscious Bypass"
                btnBypass.setTextColor(Color.parseColor("#FCA5A5"))
                btnBypass.typeface = Typeface.DEFAULT_BOLD
                btnBypass.background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.parseColor("#450A0A"))
                    setStroke(dp(1.5f), Color.parseColor("#EF4444"))
                }
            } else {
                btnBypass.text = "🔒 Conscious Bypass (${secondsRemaining}s)"
                btnBypass.setTextColor(Color.parseColor("#A1A1AA"))
                btnBypass.typeface = Typeface.DEFAULT
                btnBypass.background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.parseColor("#27272A"))
                    setStroke(dp(1f), Color.parseColor("#3F3F46"))
                }
            }
        }

        btnBypass.setOnClickListener {
            val typed = input.text?.toString()?.trim() ?: ""
            val hasExactPhrase = typed.equals(REQUIRED_PHRASE, ignoreCase = true)
            if (isTimerComplete || hasExactPhrase) {
                dialog.dismiss()
                onConfirmed()
            } else {
                Toast.makeText(
                    context,
                    "🧘 Mindful pause in progress (${secondsRemaining}s left) — or tap '✍️ Auto-Fill' to bypass now.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnBypass)
        root.addView(btnRow)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateBypassButtonVisuals()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 10-second mindful breathing countdown
        val timer = object : CountDownTimer(10000L, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                secondsRemaining = (millisUntilFinished / 1000L) + 1
                val pct = ((millisUntilFinished.toFloat() / 10000f) * 100).toInt()
                progressBar.progress = pct
                val phase = if (secondsRemaining > 5) "Breathe in slowly..." else "Breathe out gently..."
                timerLabel.text = "$phase (${secondsRemaining}s)"
                updateBypassButtonVisuals()
            }

            override fun onFinish() {
                isTimerComplete = true
                secondsRemaining = 0L
                progressBar.progress = 0
                timerLabel.text = "✓ Mindful pause complete — Conscious Bypass unlocked"
                timerLabel.setTextColor(Color.parseColor("#10B981")) // Emerald Green
                updateBypassButtonVisuals()
            }
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            timer.cancel()
        }
        timer.start()
        dialog.show()
    }
}
