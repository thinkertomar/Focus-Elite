package com.focusguardpro.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguardpro.app.insights.InsightsDashboardActivity
import com.focusguardpro.app.notification.DailySummaryScheduler
import com.focusguardpro.app.widget.FocusWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_ENABLE_ADMIN = 1001
        private const val REQUEST_CODE_DEACTIVATE = 1002
    }

    private lateinit var appListView: RecyclerView
    private var headerGearButton: LinearLayout? = null
    private var covenantBannerView: TextView? = null
    private var focusLayerStatusBannerView: LinearLayout? = null
    private var focusLayerStatusTitle: TextView? = null
    private var focusLayerStatusSubtitle: TextView? = null
    private var focusStudioCardView: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        setContentView(R.layout.activity_main)

        appListView = findViewById(R.id.appListView)
        appListView.layoutManager = LinearLayoutManager(this)

        injectHeaderGearIconAndBanners()

        try {
            WatchdogService.start(this)
            FocusForegroundMonitorService.start(this)
            FocusMonitorWorker.schedule(this)
        } catch (_: Throwable) {}

        setupPermissionsUI()
        setupLinks()

        try {
            DailySummaryScheduler.scheduleDailySummary(this)
        } catch (_: Throwable) {}

        // First-time open redirect to Instruction Tab inside Gear Icon Hub
        if (!UiThemePrefs.hasSeenFirstTimeInstructions(this)) {
            UiThemePrefs.markFirstTimeInstructionsSeen(this)
            SystemDiagnosticsAndUpdatesManager.markCurrentVersionSeen(this)
            startActivity(
                Intent(this, AuxiliarySettingsActivity::class.java).apply {
                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_INSTRUCTIONS)
                    putExtra(AuxiliarySettingsActivity.EXTRA_FIRST_TIME_WELCOME, true)
                }
            )
        } else if (SystemDiagnosticsAndUpdatesManager.hasUnseenVersionUpdate(this)) {
            SystemDiagnosticsAndUpdatesManager.postUpdateNotificationIfNeeded(this)
            val palette = UiThemePrefs.getSelectedPalette(this)
            UpdatesHealthAndFeedbackSectionBuilder.showWhatsNewDialog(
                activity = this,
                palette = palette
            ) {
                startActivity(
                    Intent(this, AuxiliarySettingsActivity::class.java).apply {
                        putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_UPDATES_HEALTH)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        applyDynamicThemeToHome()
        updatePermissionButtonStates()
        updateScreenTimeSummary()
        refreshDailyAuditCard()
        loadAppList()
        refreshSuggestionCard()
        FocusWidgetService.syncTelemetryNow(this)
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    /**
     * Dynamically injects:
     * 1) Gear Icon button ("Hub & Guide") in the top header row
     * 2) Covenant Badge (when sealed)
     * 3) Interactive Focus Layer Schedule & Calendar Holiday Lock Card right above Focus Tools
     */
    private fun injectHeaderGearIconAndBanners() {
        val screenTimeCard = findViewById<View>(R.id.screenTimeCard) ?: return
        val mainColumn = screenTimeCard.parent as? LinearLayout ?: return
        val headerRow = mainColumn.getChildAt(0) as? LinearLayout ?: return

        if (headerGearButton != null) return

        val palette = UiThemePrefs.getSelectedPalette(this)

        val existingGear = findViewById<LinearLayout>(R.id.btnHeaderHubGuide)
        val gearContainer = existingGear ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8f)
            }
            val gearIcon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_settings_gear)
                setColorFilter(palette.secondaryColor)
                layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
                    marginEnd = dp(6f)
                }
                contentDescription = "Auxiliary Settings and Instructions"
            }
            val gearLabel = TextView(this@MainActivity).apply {
                text = "Hub & Guide"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            addView(gearIcon)
            addView(gearLabel)
            headerRow.addView(this)
        }

        gearContainer.background = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(palette.cardSurfaceColor)
            setStroke(dp(1.5f), palette.accentColor)
        }
        (gearContainer.getChildAt(0) as? ImageView)?.setColorFilter(palette.secondaryColor)
        gearContainer.setOnClickListener {
            startActivity(
                Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_INSTRUCTIONS)
                }
            )
        }
        headerGearButton = gearContainer

        // Optional Covenant Badge right below header
        val covenantTv = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FDE047"))
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                        putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_INSPIRE)
                    }
                )
            }
        }
        mainColumn.addView(covenantTv, 1)
        covenantBannerView = covenantTv

        // Interactive Focus Layer Criteria & Holiday Lock Banner Card
        val focusBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
            setOnClickListener {
                if (FocusLayerManager.isLockedUntilCalendarHoliday(this@MainActivity)) {
                    val rejectionMsg = FocusLayerManager.getSameDayHolidayRejectionNotice(this@MainActivity)
                    val extraHolidayRule = if (rejectionMsg != null) "\n\n$rejectionMsg" else "\n\n• Calendar Holidays must be scheduled ≥24 hours in advance."
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🔒 Fortress Focus Lock Active")
                        .setMessage(
                            "Your Focus Layer (${FocusLayerManager.formatWindowSummary(this@MainActivity)}) is strictly locked.\n" +
                                "• Emergency 15m breaks & rule changes require Physical Proof-of-Effort (25 steps or 10 squats).$extraHolidayRule"
                        )
                        .setPositiveButton("⚙️ Focus Hub") { _, _ ->
                            startActivity(
                                Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_AUXILIARY)
                                }
                            )
                        }
                        .setNeutralButton("🏋️ 15m Break (Proof)") { _, _ ->
                            CognitiveFrictionGate.show(
                                context = this@MainActivity,
                                title = "Unlock 15-Minute Adaptive Break",
                                reasonPrompt = "Complete physical proof-of-effort (25 verified steps or 10 squats) + commitment phrase to earn a 15-minute break."
                            ) {
                                FocusLayerManager.grantAdaptiveBreak(this@MainActivity, 15)
                                Toast.makeText(this@MainActivity, "🔓 Proof verified! 15-Minute Break granted.", Toast.LENGTH_SHORT).show()
                                applyDynamicThemeToHome()
                            }
                        }
                        .setNegativeButton("📅 Plan Future Holiday") { _, _ ->
                            startActivity(Intent(this@MainActivity, CalendarActivity::class.java))
                        }
                        .show()
                } else {
                    startActivity(
                        Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                            putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_AUXILIARY)
                        }
                    )
                }
            }
        }

        val shieldCircle = TextView(this).apply {
            text = "🛡️"
            textSize = 18f
            gravity = Gravity.CENTER
            background = UiThemePrefs.createAccentCircleBackground(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                marginEnd = dp(14f)
            }
        }
        val fTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fTitle = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val fSub = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2f), 0, 0)
        }
        fTextCol.addView(fTitle)
        fTextCol.addView(fSub)
        val fArrow = TextView(this).apply {
            text = "→"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            setPadding(dp(8f), 0, 0, 0)
        }
        focusBanner.addView(shieldCircle)
        focusBanner.addView(fTextCol)
        focusBanner.addView(fArrow)

        // Insert right after screenTimeCard
        val screenTimeIdx = mainColumn.indexOfChild(screenTimeCard)
        mainColumn.addView(focusBanner, (screenTimeIdx + 1).coerceAtMost(mainColumn.childCount))
        focusLayerStatusBannerView = focusBanner
        focusLayerStatusTitle = fTitle
        focusLayerStatusSubtitle = fSub

        // Quick Deep Focus Studio Launch Card (matching Home card design language)
        val focusStudioCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = UiThemePrefs.createCardBackground(this@MainActivity, cornerDp = 16f, isHero = false)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, FocusSessionActivity::class.java))
            }
        }
        val iconCircle = TextView(this).apply {
            text = "🎯"
            textSize = 18f
            gravity = Gravity.CENTER
            background = UiThemePrefs.createAccentCircleBackground(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                marginEnd = dp(14f)
            }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = "Deep Focus Studio"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        textCol.addView(TextView(this).apply {
            text = "40Hz Gamma soundscape • Pomodoro • Mindful gate"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2f), 0, 0)
        })
        val startLabel = TextView(this).apply {
            text = "Start →"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            setPadding(dp(8f), dp(4f), 0, dp(4f))
        }
        focusStudioCard.addView(iconCircle)
        focusStudioCard.addView(textCol)
        focusStudioCard.addView(startLabel)

        mainColumn.addView(focusStudioCard, (screenTimeIdx + 2).coerceAtMost(mainColumn.childCount))
        focusStudioCardView = focusStudioCard
    }

    private fun applyDynamicThemeToHome() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val screenTimeCard = findViewById<LinearLayout>(R.id.screenTimeCard)
        val mainColumn = screenTimeCard?.parent as? LinearLayout
        val rootScroll = mainColumn?.parent as? NestedScrollView
        rootScroll?.setBackgroundColor(UiThemePrefs.getScreenBackgroundColor(this))

        // Adapt top-level header & section titles sitting directly on the screen background
        val headerTitleColor = UiThemePrefs.getScreenHeaderTextColor(this)
        val headerSubColor = UiThemePrefs.getScreenSubHeaderTextColor(this)
        val sectionAccentColor = if (UiThemePrefs.isWhiteBackgroundActive(this)) {
            Color.parseColor("#0284C7")
        } else {
            palette.accentColor
        }
        val headerRow = mainColumn?.getChildAt(0) as? LinearLayout
        val headerTextCol = headerRow?.getChildAt(1) as? LinearLayout
        (headerTextCol?.getChildAt(0) as? TextView)?.setTextColor(headerTitleColor)
        (headerTextCol?.getChildAt(1) as? TextView)?.setTextColor(headerSubColor)
        findViewById<TextView>(R.id.deactivateShieldLink)?.setTextColor(headerSubColor)
        findViewById<TextView>(R.id.backupRestoreLink)?.setTextColor(headerSubColor)

        if (mainColumn != null) {
            for (i in 0 until mainColumn.childCount) {
                val child = mainColumn.getChildAt(i)
                if (child is TextView && child != covenantBannerView && child.id != R.id.deactivateShieldLink && child.id != R.id.backupRestoreLink) {
                    if (child.textSize / resources.displayMetrics.scaledDensity <= 13f) {
                        child.setTextColor(sectionAccentColor)
                    } else {
                        child.setTextColor(headerTitleColor)
                    }
                } else if (child is LinearLayout && child.childCount == 2 && child.getChildAt(1) is TextView && child.background == null) {
                    (child.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
                }
            }
        }

        screenTimeCard?.background = UiThemePrefs.createCardBackground(this, cornerDp = 18f, isHero = true)

        val cardIds = listOf(
            R.id.dailyTimeAuditCard,
            R.id.practiceSessionLink,
            R.id.activityLink,
            R.id.auditSummaryLink,
            R.id.insightsDashboardLink,
            R.id.pinWidgetLink,
            R.id.auditSettingsLink,
            R.id.websiteBlockLink,
            R.id.calendarLink,
            R.id.suggestionCard
        )
        for (id in cardIds) {
            findViewById<View>(id)?.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
        }

        headerGearButton?.background = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(palette.cardSurfaceColor)
            setStroke(dp(1.5f), palette.accentColor)
        }
        (headerGearButton?.getChildAt(0) as? ImageView)?.setColorFilter(palette.secondaryColor)

        // Update Focus Layer Criteria & Real-Time Status Banner
        val exemptionDetails = FocusLayerManager.getCalendarExemptionDetails(this)
        val isLocked = FocusLayerManager.isLockedUntilCalendarHoliday(this)
        val isScheduled = FocusLayerManager.isScheduledActiveNow(this)
        val windowSummary = FocusLayerManager.formatWindowSummary(this)
        val currentTimeStr = FocusLayerManager.getCurrentDeviceTimeFormatted()
        focusLayerStatusBannerView?.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
        (focusLayerStatusBannerView?.getChildAt(0) as? View)?.background = UiThemePrefs.createAccentCircleBackground(this)
        (focusLayerStatusBannerView?.getChildAt(2) as? TextView)?.setTextColor(palette.secondaryColor)

        focusStudioCardView?.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
        (focusStudioCardView?.getChildAt(0) as? View)?.background = UiThemePrefs.createAccentCircleBackground(this)
        (focusStudioCardView?.getChildAt(2) as? TextView)?.setTextColor(palette.secondaryColor)

        val graceMins = FocusLayerManager.getPreWindowGraceRemainingMinutes(this)
        val sameDayRejection = FocusLayerManager.getSameDayHolidayRejectionNotice(this)
        val clockTampered = SettingsTamperGuard.isClockTamperActive(this)

        when {
            exemptionDetails != null -> {
                focusLayerStatusTitle?.text = exemptionDetails
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#38BDF8"))
                focusLayerStatusSubtitle?.text = "Schedule ($windowSummary) paused • $currentTimeStr"
            }
            graceMins > 0 -> {
                focusLayerStatusTitle?.text = "⚡ Pre-Window Grace Period ($graceMins min left)"
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#38BDF8"))
                focusLayerStatusSubtitle?.text = "Adjust categories & schedule freely before Fortress Lock engages"
            }
            isLocked -> {
                focusLayerStatusTitle?.text = if (clockTampered) {
                    "🛡️ Fortress Lock Active (Monotonic Clock Guarded)"
                } else {
                    "🔒 Fortress Lock Active ($windowSummary)"
                }
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#F59E0B"))
                focusLayerStatusSubtitle?.text = sameDayRejection
                    ?: "Zero-escape active ($currentTimeStr) • Tap for Proof-of-Effort break"
            }
            isScheduled -> {
                focusLayerStatusTitle?.text = "🟢 Focus Layer Active ($windowSummary)"
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#34D399"))
                focusLayerStatusSubtitle?.text = "Active right now ($currentTimeStr) • Tap to customize"
            }
            else -> {
                focusLayerStatusTitle?.text = "Focus Layer: $windowSummary"
                focusLayerStatusTitle?.setTextColor(Color.WHITE)
                focusLayerStatusSubtitle?.text = "$currentTimeStr (Outside Window) • Tap to configure"
            }
        }

        if (UiThemePrefs.isCovenantSealed(this)) {
            val hours = UiThemePrefs.getCovenantHours(this)
            val wakingDays = (hours * 365f / 16f).roundToInt()
            covenantBannerView?.apply {
                visibility = View.VISIBLE
                text = "⚡ Architect of Time Covenant Active: Reclaiming ${String.format("%.1f", hours)}h/day (+$wakingDays Waking Days/Yr) • Tap for Fractal Tree"
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(palette.cardSurfaceColor)
                    setStroke(dp(1f), Color.parseColor("#F59E0B"))
                }
            }
        } else {
            covenantBannerView?.visibility = View.GONE
        }
    }

    private fun setupPermissionsUI() {
        findViewById<Button>(R.id.usageAccessButton)?.setOnClickListener {
            SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.accessibilityButton)?.setOnClickListener {
            val criteriaDone = UiThemePrefs.isSetupCategoriesConfigured(this)
            val scheduleDone = UiThemePrefs.isSetupScheduleConfigured(this)
            if (!criteriaDone || !scheduleDone) {
                AlertDialog.Builder(this)
                    .setTitle("Recommended 4-Step Setup Order")
                    .setMessage(
                        "To ensure Smooth First-Time Setup without any Focus Lock popups:\n\n" +
                            "1. Usage Access (${if (hasUsageStatsPermission()) "✓ Done" else "Pending"})\n" +
                            "2. Define App Criteria — Study / Improve / Ignore (${if (criteriaDone) "✓ Done" else "Next Step"})\n" +
                            "3. Set Focus Schedule (${if (scheduleDone) "✓ Done" else "Pending"})\n" +
                            "4. Enable Device Admin & Accessibility Shield LAST\n\n" +
                            "Would you like to configure your App Criteria & Focus Schedule first, or enable the Accessibility Shield now?"
                    )
                    .setPositiveButton("Configure Step ${if (!criteriaDone) "2 (Apps)" else "3 (Schedule)"} First") { _, _ ->
                        if (!criteriaDone) {
                            startActivity(
                                Intent(this, CategorySettingsActivity::class.java).apply {
                                    putExtra("from_guided_setup", true)
                                }
                            )
                        } else {
                            startActivity(
                                Intent(this, AuxiliarySettingsActivity::class.java).apply {
                                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_AUXILIARY)
                                }
                            )
                        }
                    }
                    .setNeutralButton("Enable Shield Now (Grace Active)") { _, _ ->
                        UiThemePrefs.markSetupCategoriesConfigured(this)
                        UiThemePrefs.markSetupScheduleConfigured(this)
                        SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        findViewById<Button>(R.id.batteryOptButton)?.setOnClickListener {
            SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
            if (!isIgnoringBatteryOptimizations()) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Already unrestricted!", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnDeviceAdmin)?.setOnClickListener {
            SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
            if (!dpm.isAdminActive(adminComponent)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required to prevent unauthorized uninstallation during focus sessions."
                    )
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN)
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(this, DeactivationActivity::class.java),
                    REQUEST_CODE_DEACTIVATE
                )
            }
        }
    }

    private fun setupLinks() {
        findViewById<View>(R.id.screenTimeCard)?.setOnClickListener {
            startActivity(Intent(this, UsageStatsActivity::class.java))
        }
        findViewById<View>(R.id.dailyTimeAuditCard)?.setOnClickListener {
            // Directly open Daily Time Notebook (AuditActivity) instead of the "Welcome Back" TimeSplitter popup
            startActivity(Intent(this, AuditActivity::class.java))
        }
        findViewById<View>(R.id.auditSettingsLink)?.setOnClickListener {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }
        findViewById<View>(R.id.auditSummaryLink)?.setOnClickListener {
            startActivity(Intent(this, AuditSummaryActivity::class.java))
        }
        findViewById<View>(R.id.calendarLink)?.setOnClickListener {
            startActivity(Intent(this, CalendarActivity::class.java))
        }
        findViewById<View>(R.id.websiteBlockLink)?.setOnClickListener {
            startActivity(Intent(this, WebsiteBlockActivity::class.java))
        }
        findViewById<View>(R.id.activityLink)?.setOnClickListener {
            startActivity(Intent(this, ActivityStatsActivity::class.java))
        }
        findViewById<View>(R.id.practiceSessionLink)?.setOnClickListener {
            startActivity(Intent(this, PracticeSessionActivity::class.java))
        }
        findViewById<View>(R.id.insightsDashboardLink)?.setOnClickListener {
            startActivity(Intent(this, InsightsDashboardActivity::class.java))
        }
        findViewById<View>(R.id.pinWidgetLink)?.setOnClickListener {
            startActivity(Intent(this, WidgetStudioActivity::class.java))
        }
        findViewById<View>(R.id.deactivateShieldLink)?.setOnClickListener {
            startActivity(Intent(this, DeactivationActivity::class.java))
        }
        findViewById<View>(R.id.backupRestoreLink)?.setOnClickListener {
            startActivity(Intent(this, BackupRestoreActivity::class.java))
        }
    }

    private fun refreshDailyAuditCard() {
        val subtitle = findViewById<TextView>(R.id.tvDailyAuditSubtitle) ?: return
        try {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = cal.timeInMillis
            val now = System.currentTimeMillis()
            val awayRecords = DaySummaryHelper.getAwayRecords(this, startOfDay, now)
            val totalAwayMins = DaySummaryHelper.awayStudyMinutes(awayRecords) +
                DaySummaryHelper.awayImproveMinutes(awayRecords) +
                DaySummaryHelper.awayDistractMinutes(awayRecords) +
                DaySummaryHelper.awayIgnoreMinutes(awayRecords) +
                DaySummaryHelper.awaySleepMinutes(awayRecords)

            if (totalAwayMins > 0L) {
                subtitle.text = "${DaySummaryHelper.formatMinutes(totalAwayMins)} logged today • Tap to open Daily Time Notebook"
            } else {
                subtitle.text = "Tap to open Daily Time Notebook & log activities"
            }
        } catch (_: Throwable) {
            subtitle.text = "Log offline hours, track habits & reflect daily"
        }
    }

    private fun updatePermissionButtonStates() {
        val usageBtn = findViewById<Button>(R.id.usageAccessButton) ?: return
        val accessBtn = findViewById<Button>(R.id.accessibilityButton) ?: return
        val batteryBtn = findViewById<Button>(R.id.batteryOptButton) ?: return
        val adminBtn = findViewById<Button>(R.id.btnDeviceAdmin) ?: return
        val allSetText = findViewById<TextView>(R.id.allPermissionsSetText) ?: return

        val hasUsage = hasUsageStatsPermission()
        val hasAccess = isAccessibilityServiceEnabled()
        val hasBattery = isIgnoringBatteryOptimizations()
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
        val hasAdmin = dpm.isAdminActive(adminComponent)

        usageBtn.text = "1. Enable Usage Access (Step 1)"
        adminBtn.text = "4A. Enable Device Admin Lock"
        accessBtn.text = "4B. Enable Accessibility Shield (Enable Last)"
        batteryBtn.text = "Optional: Disable Battery Optimization"

        usageBtn.visibility = if (hasUsage) View.GONE else View.VISIBLE
        adminBtn.visibility = if (hasAdmin) View.GONE else View.VISIBLE
        accessBtn.visibility = if (hasAccess) View.GONE else View.VISIBLE
        batteryBtn.visibility = if (hasBattery) View.GONE else View.VISIBLE

        val deactivateLink = findViewById<TextView>(R.id.deactivateShieldLink)
        if (hasUsage && hasAccess && hasBattery && hasAdmin) {
            allSetText.visibility = View.VISIBLE
            allSetText.text = "✓ All System Shields & Sensors Active (100% Offline • Zero Internet)"
            deactivateLink?.visibility = View.VISIBLE
        } else {
            allSetText.visibility = View.VISIBLE
            val nextHint = when {
                !hasUsage -> "Guided Setup: Step 1 → Grant Usage Access below"
                !UiThemePrefs.isSetupCategoriesConfigured(this) -> "Guided Setup: Step 2 → Tap 'Category Settings' above to define Study/Improve/Ignore apps"
                !UiThemePrefs.isSetupScheduleConfigured(this) -> "Guided Setup: Step 3 → Tap 'Focus Layer' banner above to confirm your schedule"
                else -> "Guided Setup: Step 4 → Enable Device Admin & Accessibility Shield below"
            }
            allSetText.text = nextHint
            deactivateLink?.visibility = if (hasAdmin) View.VISIBLE else View.GONE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            updatePermissionButtonStates()
        } else if (requestCode == REQUEST_CODE_DEACTIVATE && resultCode == RESULT_OK) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
            dpm.removeActiveAdmin(adminComponent)
            Toast.makeText(this, "Protection Disabled. Waiting for sync...", Toast.LENGTH_SHORT).show()
            findViewById<Button>(R.id.btnDeviceAdmin)?.postDelayed({
                updatePermissionButtonStates()
            }, 600L)
        }
    }

    private fun updateScreenTimeSummary() {
        val screenTimeText = findViewById<TextView>(R.id.screenTimeText) ?: return
        if (!hasUsageStatsPermission()) {
            screenTimeText.text = "Grant Permission"
            return
        }
        val totalMs = UsageStatsHelper.getTodayScreenTimeMillis(this)
        screenTimeText.text = UsageStatsHelper.formatDuration(totalMs)
    }

    private fun loadAppList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val usageMsMap: Map<String, Long> = if (hasUsageStatsPermission()) {
                val (start, end) = UsageStatsHelper.todayRange()
                UsageStatsHelper.getPerAppUsage(this@MainActivity, start, end)
            } else {
                emptyMap()
            }

            val usedMinsMap = HashMap<String, Int>()
            val studyApps = AppCategoryPrefs.getStudyApps(this@MainActivity)
            val improveApps = AppCategoryPrefs.getImprovementApps(this@MainActivity)
            val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this@MainActivity)

            val appModels = resolveInfos.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) return@mapNotNull null
                // Exclude all Android system & utility apps so system features always work 24/7
                if (SystemExclusions.isExcludedPackage(this@MainActivity, pkg)) return@mapNotNull null
                val name = ri.loadLabel(pm).toString()
                val icon = ri.loadIcon(pm)
                val usedMins = ((usageMsMap[pkg] ?: 0L) / 60000L).toInt()
                usedMinsMap[pkg] = usedMins

                val limitMins = TimeLimitManager.getLimitMinutes(this@MainActivity, pkg)
                val hardBlocked = HardBlockManager.isHardBlocked(this@MainActivity, pkg)
                val isWhitelisted = studyApps.contains(pkg) || improveApps.contains(pkg)
                val effectiveCap = if (limitMins > 0) limitMins else defaultCap
                val reachedCap = usedMins >= effectiveCap && !isWhitelisted

                val isBlockedNow = hardBlocked || reachedCap
                AppModel(name, pkg, icon, isBlockedNow)
            }
                .distinctBy { it.packageName }
                .sortedWith(
                    compareByDescending<AppModel> { it.isBlocked }
                        .thenByDescending { usedMinsMap[it.packageName] ?: 0 }
                        .thenBy { it.appName.lowercase() }
                )

            withContext(Dispatchers.Main) {
                appListView.adapter = AppAdapter(
                    apps = appModels,
                    usedMinutesByPkg = usedMinsMap,
                    onLimitClick = { app -> showLimitDialog(app, usedMinsMap[app.packageName] ?: 0) },
                    onToggle = { app, isChecked ->
                        val cap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this@MainActivity)
                        if (!isChecked && FocusLayerManager.isLockedUntilCalendarHoliday(this@MainActivity)) {
                            // Reverting the switch immediately until Proof-of-Effort is completed
                            loadAppList()
                            CognitiveFrictionGate.show(
                                context = this@MainActivity,
                                title = "Unblock ${app.appName}",
                                reasonPrompt = "Fortress Lock is active. Complete physical proof-of-effort (25 steps or 10 squats) to unblock ${app.appName}."
                            ) {
                                BlockPolicyEngine.setAppBlocked(this@MainActivity, app.packageName, false, cap)
                                Toast.makeText(this@MainActivity, "🔓 ${app.appName} unblocked via Proof-of-Effort", Toast.LENGTH_SHORT).show()
                                loadAppList()
                                refreshSuggestionCard()
                            }
                        } else {
                            BlockPolicyEngine.setAppBlocked(this@MainActivity, app.packageName, isChecked, cap)
                            val msg = if (isChecked) {
                                "✓ ${app.appName} blocked (${TimeLimitManager.getLimitMinutes(this@MainActivity, app.packageName)}m cap)"
                            } else {
                                "${app.appName} unblocked"
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            refreshSuggestionCard()
                        }
                    }
                )
            }
        }
    }

    private fun refreshSuggestionCard() {
        val card = findViewById<LinearLayout>(R.id.suggestionCard) ?: return
        if (!hasUsageStatsPermission()) {
            card.visibility = View.GONE
            return
        }

        val text = findViewById<TextView>(R.id.suggestionText) ?: return
        val acceptBtn = findViewById<Button>(R.id.suggestionAcceptButton) ?: return
        val dismissBtn = findViewById<Button>(R.id.suggestionDismissButton) ?: return

        if (UiThemePrefs.isAutoEnforceSuggestions(this)) {
            val newlyEnforced = SmartSuggestionEngine.autoEnforce(this)
            if (newlyEnforced.isEmpty()) {
                card.visibility = View.GONE
                return
            }
            val firstCandidate = newlyEnforced.first()
            val firstPkg = firstCandidate.packageName
            val firstName = firstCandidate.appLabel
            val firstMins = firstCandidate.avgMinutes
            val capMins = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
            TimeLimitManager.setLimitMinutes(this, firstPkg, capMins)

            card.visibility = View.VISIBLE
            text.text = "✓ Auto-Blocked $firstName (${firstMins}m avg/day). It now displays a Blue Tick (✓) in your Apps Blocklist."
            acceptBtn.text = "Undo $firstName"
            dismissBtn.text = "Keep Blocked ✓"

            acceptBtn.setOnClickListener {
                SmartSuggestionEngine.undoAutoEnforce(this, firstPkg)
                BlockPolicyEngine.setAppBlocked(this, firstPkg, false)
                Toast.makeText(this, "Unblocked $firstName for today", Toast.LENGTH_SHORT).show()
                card.visibility = View.GONE
                loadAppList()
            }
            dismissBtn.setOnClickListener {
                SmartSuggestionEngine.dismiss(this, firstPkg)
                card.visibility = View.GONE
                loadAppList()
            }
            return
        }

        val candidates = SmartSuggestionEngine.findCandidates(this)
            .filter { candidate -> !HardBlockManager.isHardBlocked(this, candidate.packageName) }

        if (candidates.isEmpty()) {
            card.visibility = View.GONE
            return
        }

        val target = candidates.first()
        val targetPkg = target.packageName
        val appLabel = target.appLabel
        val avgMins = target.avgMinutes
        val capMins = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
        val remainingCount = candidates.size - 1

        card.visibility = View.VISIBLE
        val extraNote = if (remainingCount > 0) " (+$remainingCount more app suggestion)" else ""
        text.text = "You used $appLabel for ${avgMins}m+$extraNote.\nBlock ONLY $appLabel & apply a ${capMins}m daily cap with Blue Tick (✓)?"

        acceptBtn.text = "✓ Block $appLabel (${capMins}m)"
        dismissBtn.text = "Skip $appLabel"

        acceptBtn.setOnClickListener {
            HardBlockManager.setHardBlocked(this, targetPkg, true)
            TimeLimitManager.setLimitMinutes(this, targetPkg, capMins)
            SmartSuggestionEngine.dismiss(this, targetPkg)
            Toast.makeText(
                this,
                "✓ Blocked $appLabel (${capMins}m cap + Blue Tick applied)!",
                Toast.LENGTH_SHORT
            ).show()
            loadAppList()
            refreshSuggestionCard()
        }

        dismissBtn.setOnClickListener {
            SmartSuggestionEngine.dismiss(this, targetPkg)
            refreshSuggestionCard()
        }
    }

    private fun showLimitDialog(app: AppModel, usedMinutesToday: Int) {
        val currentLimit = TimeLimitManager.getLimitMinutes(this, app.packageName)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutes per day (0 = no custom limit)"
            if (currentLimit > 0) {
                setText(currentLimit.toString())
            }
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Daily Time Limit — ${app.appName}")
            .setMessage("Used today: $usedMinutesToday min\nSet a daily cap in minutes (e.g. 30):")
            .setView(input)
            .setPositiveButton("Save & Apply ✓") { _, _ ->
                val minutes = input.text.toString().toIntOrNull() ?: 0
                val isLooseningLimit = (currentLimit > 0 && (minutes == 0 || minutes > currentLimit))
                if (isLooseningLimit && FocusLayerManager.isLockedUntilCalendarHoliday(this)) {
                    CognitiveFrictionGate.show(
                        context = this,
                        title = "Increase Limit — ${app.appName}",
                        reasonPrompt = "Increasing or clearing a daily cap during active Fortress Lock requires Physical Proof-of-Effort."
                    ) {
                        TimeLimitManager.setLimitMinutes(this, app.packageName, minutes)
                        if (minutes > 0 && usedMinutesToday < minutes) {
                            HardBlockManager.setHardBlocked(this, app.packageName, false)
                        }
                        Toast.makeText(
                            this,
                            if (minutes > 0) "🔓 ${app.appName} limit updated to ${minutes}m/day" else "🔓 Custom limit cleared for ${app.appName}",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadAppList()
                    }
                } else {
                    TimeLimitManager.setLimitMinutes(this, app.packageName, minutes)
                    if (minutes > 0 && usedMinutesToday >= minutes) {
                        HardBlockManager.setHardBlocked(this, app.packageName, true)
                    } else if (minutes > 0 && usedMinutesToday < minutes) {
                        HardBlockManager.setHardBlocked(this, app.packageName, false)
                    }
                    Toast.makeText(
                        this,
                        if (minutes > 0) "✓ ${app.appName} limit set to ${minutes}m/day" else "Custom limit cleared for ${app.appName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadAppList()
                }
            }
            .setNeutralButton("Clear Limit & Unblock") { _, _ ->
                if (FocusLayerManager.isLockedUntilCalendarHoliday(this)) {
                    CognitiveFrictionGate.show(
                        context = this,
                        title = "Clear Limit & Unblock — ${app.appName}",
                        reasonPrompt = "Unblocking ${app.appName} during active Fortress Lock requires Physical Proof-of-Effort."
                    ) {
                        TimeLimitManager.setLimitMinutes(this, app.packageName, 0)
                        HardBlockManager.setHardBlocked(this, app.packageName, false)
                        loadAppList()
                    }
                } else {
                    TimeLimitManager.setLimitMinutes(this, app.packageName, 0)
                    HardBlockManager.setHardBlocked(this, app.packageName, false)
                    loadAppList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, BlockerAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
