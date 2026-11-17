package com.focusguardpro.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "focusguard_onboarding"
        private const val KEY_DONE = "onboarding_done"

        fun isOnboardingDone(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)
        }

        fun markDone(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DONE, true)
                .apply()
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOnboardingDone(this)) {
            goToMain()
            return
        }

        val palette = UiThemePrefs.getSelectedPalette(this)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.backgroundColor)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24f), dp(36f), dp(24f), dp(32f))
        }

        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_focus_elite_logo)
            layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f)).apply {
                marginEnd = dp(14f)
            }
        }
        logoRow.addView(logo)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleCol.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        titleCol.addView(TextView(this).apply {
            text = "🔒 100% Offline • Zero Internet Permission"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#34D399"))
        })
        logoRow.addView(titleCol)
        root.addView(logoRow)

        root.addView(TextView(this).apply {
            text = "Welcome! To make first-time setup effortless and ensure the Focus Lock never blocks you while you are configuring permissions, our Help & Setup Hub walks you through 4 simple steps in order:"
            textSize = 13.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(0f, 1.22f)
            setPadding(0, 0, 0, dp(16f))
        })

        val steps = listOf(
            "Step 1: Grant Usage Access" to "Allows Focus Elite to measure your screen time and daily app usage locally on your phone.",
            "Step 2: Define Your App Criteria (System Apps Excluded)" to "Mark apps as Study/Improve (always allowed) or Ignored (30m cap). All Android system apps (Phone, Camera, Clock, Calculator, SMS, Settings, Files) are automatically excluded & allowed 24/7!",
            "Step 3: Set Your Focus Schedule" to "Choose your Focus Window hours (e.g., 9:00 AM – 6:00 PM or custom hours/days) BEFORE turning on the enforcement lock.",
            "Step 4: Enable Device Admin & Accessibility Shield Last" to "Turn on the final shields last with our 3-minute Setup Grace Window so Android Settings never blocks you during setup."
        )

        for ((stepTitle, stepDesc) in steps) {
            val stepCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = UiThemePrefs.createCardBackground(this@OnboardingActivity, cornerDp = 14f, isHero = false)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10f) }
            }
            stepCard.addView(TextView(this).apply {
                text = stepTitle
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.secondaryColor)
            })
            stepCard.addView(TextView(this).apply {
                text = stepDesc
                textSize = 12f
                setTextColor(Color.parseColor("#CBD5E1"))
                setPadding(0, dp(3f), 0, 0)
                setLineSpacing(0f, 1.18f)
            })
            root.addView(stepCard)
        }

        val startBtn = Button(this).apply {
            id = R.id.getStartedButton
            text = "Open Guided Setup & Help Hub →"
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            minHeight = dp(52f)
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(palette.accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52f)
            ).apply { topMargin = dp(14f) }
            setOnClickListener {
                markDone(this@OnboardingActivity)
                goToMain()
            }
        }
        root.addView(startBtn)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun goToMain() {
        try {
            FocusForegroundMonitorService.start(this)
            FocusMonitorWorker.schedule(this)
            WatchdogService.start(this)
        } catch (_: Exception) {}
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
