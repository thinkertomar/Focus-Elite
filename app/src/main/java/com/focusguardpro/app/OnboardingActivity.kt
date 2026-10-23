package com.focusguardpro.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOnboardingDone(this)) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_onboarding)

        val btn = findViewById<Button>(R.id.getStartedButton)
        if (btn != null) {
            btn.setOnClickListener {
                markDone(this)
                goToMain()
            }
        } else {
            // Failsafe in case layout doesn't bind getStartedButton
            markDone(this)
            goToMain()
        }
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
