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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_ENABLE_ADMIN = 1001
        private const val REQUEST_CODE_DEACTIVATE = 1002
    }

    private var homeRefreshJob: Job? = null
    private lateinit var appListView: RecyclerView
    private var headerGearButton: LinearLayout? = null
    private var covenantBannerView: TextView? = null
    private var focusLayerStatusBannerView: LinearLayout? = null
    private var focusLayerStatusTitle: TextView? = null
    private var focusLayerStatusSubtitle: TextView? = null
    private var focusStudioCardView: LinearLayout? = null

    // Digital Wellbeing Hero Card Telemetry Views
    private var heroDeltaBadge: TextView? = null
    private var heroSegStudy: View? = null
    private var heroSegImprove: View? = null
    private var heroSegDistract: View? = null
    private var heroSegUtility: View? = null
    private var heroKpiUnlocks: TextView? = null
    private var heroKpiLaunches: TextView? = null
    private var heroKpiPeakHour: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        setContentView(R.layout.activity_main)

        appListView = findViewById(R.id.appListView)
        appListView.layoutManager = LinearLayoutManager(this)

        injectHeaderGearIconAndBanners()

        // Asynchronously warm up app labels and icons on Dispatchers.IO so Home renders with zero lag
        lifecycleScope.launch(Dispatchers.IO) {
            AppMetadataCache.warmUp(this@MainActivity)
        }

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
        applyDynamicThemeToHome()
        updatePermissionButtonStates()
        // Fast-path: immediately paint latest memory-cached snapshot in 0ms (no blank delay)
        bindCachedSnapshotFast()
        // Asynchronously fetch fresh telemetry, app limits and audit in a single coordinated pass
        refreshHomeData()
        SmartNotificationManager.refreshNow(this)
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
                            startActivity(Intent(this@MainActivity, EliteCalendarActivity::class.java))
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



        // Inject Digital Wellbeing Structural Telemetry Strip inside screenTimeCard
        val heroCardGroup = screenTimeCard as? LinearLayout
        if (heroCardGroup != null && heroDeltaBadge == null) {
            val deltaBadge = TextView(this).apply {
                text = "✓ Digital Wellbeing Engine"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, dp(4f), 0, dp(8f))
            }
            heroDeltaBadge = deltaBadge
            heroCardGroup.addView(deltaBadge)

            // Multi-segment Category Proportion Bar (Study / Improve / Distract / Utility)
            val segRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(7f)
                ).apply {
                    bottomMargin = dp(12f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(4f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                clipToOutline = true
            }
            val sBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#10B981"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val iBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#38BDF8"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val dBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#F43F5E"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val uBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#64748B"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            heroSegStudy = sBar
            heroSegImprove = iBar
            heroSegDistract = dBar
            heroSegUtility = uBar
            segRow.addView(sBar)
            segRow.addView(iBar)
            segRow.addView(dBar)
            segRow.addView(uBar)
            heroCardGroup.addView(segRow)

            // 3-Column KPI Strip: Unlocks, App Opens, Peak Hour
            val kpiStrip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            fun makeKpiPill(label: String, color: Int): Pair<LinearLayout, TextView> {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(6f), dp(7f), dp(6f), dp(7f))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(3f)
                        marginStart = dp(3f)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(Color.parseColor("#151E2E"))
                        setStroke(dp(1f), Color.parseColor("#26354D"))
                    }
                }
                val vTv = TextView(this).apply {
                    text = "0"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color)
                }
                val lTv = TextView(this).apply {
                    text = label
                    textSize = 9.5f
                    setTextColor(Color.parseColor("#94A3B8"))
                }
                cell.addView(vTv)
                cell.addView(lTv)
                return cell to vTv
            }

            val (uCell, uTv) = makeKpiPill("UNLOCKS", Color.parseColor("#38BDF8"))
            val (oCell, oTv) = makeKpiPill("APP OPENS", Color.parseColor("#A78BFA"))
            val (pCell, pTv) = makeKpiPill("PEAK HOUR", Color.parseColor("#34D399"))
            heroKpiUnlocks = uTv
            heroKpiLaunches = oTv
            heroKpiPeakHour = pTv
            kpiStrip.addView(uCell)
            kpiStrip.addView(oCell)
            kpiStrip.addView(pCell)
            heroCardGroup.addView(kpiStrip)
        }
    }

    private fun applyDynamicThemeToHome() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val isWhiteBg = UiThemePrefs.isWhiteBackgroundActive(this)
        val screenTimeCard = findViewById<LinearLayout>(R.id.screenTimeCard)
        val mainColumn = screenTimeCard?.parent as? LinearLayout
        val rootScroll = mainColumn?.parent as? NestedScrollView
        rootScroll?.setBackgroundColor(UiThemePrefs.getScreenBackgroundColor(this))

        // Adapt top-level header & section titles sitting directly on the screen background
        val headerTitleColor = UiThemePrefs.getScreenHeaderTextColor(this)
        val headerSubColor = UiThemePrefs.getScreenSubHeaderTextColor(this)
        val sectionAccentColor = UiThemePrefs.getAdaptiveSecondaryAccentColor(this)

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

        // Update KPI cell backgrounds inside Hero Card for Pure White / Pure Black / Theme
        val kpiParent = heroKpiUnlocks?.parent?.parent as? LinearLayout
        if (kpiParent != null) {
            for (i in 0 until kpiParent.childCount) {
                val cell = kpiParent.getChildAt(i) as? LinearLayout ?: continue
                cell.background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(UiThemePrefs.getKpiCellBackgroundColor(this@MainActivity))
                    setStroke(dp(1f), UiThemePrefs.getKpiCellStrokeColor(this@MainActivity))
                }
                (cell.getChildAt(1) as? TextView)?.setTextColor(headerSubColor)
            }
        }

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
            setColor(UiThemePrefs.getCardSurfaceColor(this@MainActivity))
            setStroke(dp(1.5f), palette.accentColor)
        }
        (headerGearButton?.getChildAt(0) as? ImageView)?.setColorFilter(sectionAccentColor)
        (headerGearButton?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)

        // Update Focus Layer Criteria & Real-Time Status Banner
        val exemptionDetails = FocusLayerManager.getCalendarExemptionDetails(this)
        val isLocked = FocusLayerManager.isLockedUntilCalendarHoliday(this)
        val isScheduled = FocusLayerManager.isScheduledActiveNow(this)
        val windowSummary = FocusLayerManager.formatWindowSummary(this)
        val currentTimeStr = FocusLayerManager.getCurrentDeviceTimeFormatted()
        focusLayerStatusBannerView?.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
        (focusLayerStatusBannerView?.getChildAt(0) as? View)?.background = UiThemePrefs.createAccentCircleBackground(this)
        (focusLayerStatusBannerView?.getChildAt(2) as? TextView)?.setTextColor(sectionAccentColor)

        focusStudioCardView?.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
        (focusStudioCardView?.getChildAt(0) as? View)?.background = UiThemePrefs.createAccentCircleBackground(this)
        (focusStudioCardView?.getChildAt(2) as? TextView)?.setTextColor(sectionAccentColor)

        val graceMins = FocusLayerManager.getPreWindowGraceRemainingMinutes(this)
        val sameDayRejection = FocusLayerManager.getSameDayHolidayRejectionNotice(this)
        val clockTampered = SettingsTamperGuard.isClockTamperActive(this)

        val isWebOffDayPaused = BlockPolicyEngine.isWebShieldPausedForOffDay(this)
        val browserWin = BlockPolicyEngine.getBrowserAccessWindowStatus(this)
        val browserWinSuffix = if (browserWin.isActiveNow && !isWebOffDayPaused) {
            " • 🎁 Free Browser (${browserWin.remainingMinutes}m left)"
        } else {
            ""
        }
        val isMuteActiveNow = FocusNotificationMuteService.isMutingRightNow(this) &&
            FocusNotificationMuteService.isNotificationListenerEnabled(this)
        val mutedToday = FocusNotificationMuteService.getMutedCountToday(this)
        val muteSuffix = when {
            isMuteActiveNow && mutedToday > 0 -> " • 🔕 Smart Mute ($mutedToday muted)"
            isMuteActiveNow -> " • 🔕 Smart Mute On"
            else -> ""
        }

        when {
            exemptionDetails != null -> {
                focusLayerStatusTitle?.text = exemptionDetails
                focusLayerStatusTitle?.setTextColor(if (isWhiteBg) Color.parseColor("#0284C7") else Color.parseColor("#38BDF8"))
                focusLayerStatusSubtitle?.text = "Focus Layer & Web Shield paused • $currentTimeStr"
            }
            isWebOffDayPaused -> {
                focusLayerStatusTitle?.text = "🌿 Off-Day: Focus Layer & Web Shield Paused"
                focusLayerStatusTitle?.setTextColor(if (isWhiteBg) Color.parseColor("#059669") else Color.parseColor("#34D399"))
                focusLayerStatusSubtitle?.text = "Schedule ($windowSummary) rests today • All sites open ($currentTimeStr)"
            }
            browserWin.isActiveNow -> {
                focusLayerStatusTitle?.text = "🌤️ Break Time • Browser Access (${browserWin.remainingMinutes}m/${browserWin.durationMinutes}m left)"
                focusLayerStatusTitle?.setTextColor(if (isWhiteBg) Color.parseColor("#0284C7") else Color.parseColor("#38BDF8"))
                focusLayerStatusSubtitle?.text = "${browserWin.scheduleLabel} ($currentTimeStr)"
            }
            graceMins > 0 -> {
                focusLayerStatusTitle?.text = "⚡ Pre-Window Grace Period ($graceMins min left)"
                focusLayerStatusTitle?.setTextColor(if (isWhiteBg) Color.parseColor("#0284C7") else Color.parseColor("#38BDF8"))
                focusLayerStatusSubtitle?.text = "Adjust categories & schedule freely before Fortress Lock engages"
            }
            isLocked -> {
                focusLayerStatusTitle?.text = if (clockTampered) {
                    "🛡️ Fortress Lock Active (Monotonic Clock Guarded)"
                } else {
                    "🔒 Fortress Lock Active ($windowSummary)"
                }
                focusLayerStatusTitle?.setTextColor(if (isWhiteBg) Color.parseColor("#D97706") else Color.parseColor("#F59E0B"))
                focusLayerStatusSubtitle?.text = sameDayRejection
                    ?: "Zero-escape active ($currentTimeStr)$muteSuffix • Tap for Proof-of-Effort"
            }
            isScheduled -> {
                focusLayerStatusTitle?.text = "🟢 Focus Layer Active ($windowSummary)"
                focusLayerStatusTitle?.setTextColor(if (isWhiteBg) Color.parseColor("#059669") else Color.parseColor("#34D399"))
                focusLayerStatusSubtitle?.text = "Active right now ($currentTimeStr)$muteSuffix • Tap to customize"
            }
            else -> {
                focusLayerStatusTitle?.text = "Focus Layer: $windowSummary"
                focusLayerStatusTitle?.setTextColor(headerTitleColor)
                focusLayerStatusSubtitle?.text = "$currentTimeStr (Outside Window)$muteSuffix$browserWinSuffix • Tap to configure"
            }
        }
        focusLayerStatusSubtitle?.setTextColor(headerSubColor)

        if (UiThemePrefs.isCovenantSealed(this)) {
            val hours = UiThemePrefs.getCovenantHours(this)
            val wakingDays = (hours * 365f / 16f).roundToInt()
            covenantBannerView?.apply {
                visibility = View.VISIBLE
                text = "⚡ Architect of Time Covenant Active: Reclaiming ${String.format("%.1f", hours)}h/day (+$wakingDays Waking Days/Yr) • Tap for Fractal Tree"
                setTextColor(if (isWhiteBg) Color.parseColor("#B45309") else Color.parseColor("#FDE047"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(UiThemePrefs.getCardSurfaceColor(this@MainActivity))
                    setStroke(dp(1f), Color.parseColor("#F59E0B"))
                }
            }
        } else {
            covenantBannerView?.visibility = View.GONE
        }

        // Apply universal Light/Dark view tree adaptation for all card TextViews & system bars
        UiThemePrefs.applyWindowAndViewTreeTheme(this, rootScroll)
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
            if (!hasUsageStatsPermission()) {
                SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } else {
                startActivity(Intent(this, UsageStatsActivity::class.java))
            }
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
        findViewById<View>(R.id.calendarLink)?.setOnClickListener { v ->
            SensoryFeedbackEngine.tap(this, v)
            startActivity(Intent(this, EliteCalendarActivity::class.java))
        }
        findViewById<View>(R.id.websiteBlockLink)?.setOnClickListener { v ->
            SensoryFeedbackEngine.tap(this, v)
            startActivity(Intent(this, EliteWebShieldActivity::class.java))
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
        lifecycleScope.launch(Dispatchers.IO) {
            val textToSet = try {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis
                val now = System.currentTimeMillis()
                val awayRecords = DaySummaryHelper.getAwayRecords(this@MainActivity, startOfDay, now)
                val totalAwayMins = DaySummaryHelper.awayStudyMinutes(awayRecords) +
                    DaySummaryHelper.awayImproveMinutes(awayRecords) +
                    DaySummaryHelper.awayDistractMinutes(awayRecords) +
                    DaySummaryHelper.awayIgnoreMinutes(awayRecords) +
                    DaySummaryHelper.awaySleepMinutes(awayRecords)

                if (totalAwayMins > 0L) {
                    "${DaySummaryHelper.formatMinutes(totalAwayMins)} logged today • Tap to open Daily Time Notebook"
                } else {
                    "Tap to open Daily Time Notebook & log activities"
                }
            } catch (_: Throwable) {
                "Log offline hours, track habits & reflect daily"
            }

            withContext(Dispatchers.Main) {
                subtitle.text = textToSet
            }
        }
    }

    private fun updatePermissionButtonStates() {
        val usageBtn = findViewById<Button>(R.id.usageAccessButton) ?: return
        val accessBtn = findViewById<Button>(R.id.accessibilityButton) ?: return
        val batteryBtn = findViewById<Button>(R.id.batteryOptButton) ?: return
        val adminBtn = findViewById<Button>(R.id.btnDeviceAdmin) ?: return
        val allSetText = findViewById<TextView>(R.id.allPermissionsSetText) ?: return

        val hasUsage = hasUsageStatsPermission()
        val hasAccessSetting = isAccessibilityServiceEnabled()
        val isAccessLive = BlockerAccessibilityService.instance != null
        val hasAccess = hasAccessSetting && isAccessLive
        val hasBattery = isIgnoringBatteryOptimizations()
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
        val hasAdmin = dpm.isAdminActive(adminComponent)

        if (hasAccessSetting && !isAccessLive) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (PorterBridge.isEnhancedModeActive()) {
                        PorterBridge.ensureAccessibilityServiceEnabled(this@MainActivity)
                    }
                } catch (_: Throwable) {}
            }
            accessBtn.postDelayed({
                if (!isFinishing && !isDestroyed && BlockerAccessibilityService.instance != null) {
                    updatePermissionButtonStates()
                }
            }, 800L)
        }

        usageBtn.text = "1. Enable Usage Access (Step 1)"
        adminBtn.text = "4A. Enable Device Admin Lock"
        accessBtn.text = if (hasAccessSetting && !isAccessLive) {
            "⚠️ Accessibility Paused by MIUI/OS — Tap & Toggle OFF then ON"
        } else {
            "4B. Enable Accessibility Shield (Enable Last)"
        }
        batteryBtn.text = "Optional: Disable Battery Optimization"

        usageBtn.visibility = if (hasUsage) View.GONE else View.VISIBLE
        adminBtn.visibility = if (hasAdmin) View.GONE else View.VISIBLE
        accessBtn.visibility = if (hasAccess) View.GONE else View.VISIBLE
        batteryBtn.visibility = if (hasBattery) View.GONE else View.VISIBLE

        val hasNotifPerm = SmartNotificationManager.hasNotificationPermission(this)
        val coreReady = hasUsage && hasAdmin && hasAccess

        // Trigger the Android 13+ Notification Permission Popup automatically right after all core permissions are set up!
        if (coreReady && !hasNotifPerm) {
            SmartNotificationManager.requestNotificationPermissionIfNeeded(
                activity = this,
                onlyAfterCoreSetup = true,
                openSettingsIfDenied = false
            )
        }

        val deactivateLink = findViewById<TextView>(R.id.deactivateShieldLink)
        if (hasUsage && hasAccess && hasBattery && hasAdmin) {
            allSetText.visibility = View.VISIBLE
            if (!hasNotifPerm) {
                allSetText.text = "🔔 Tap here to enable Smart Notifications & 1-Tap Pay Safe"
                allSetText.setOnClickListener {
                    SmartNotificationManager.requestNotificationPermissionIfNeeded(
                        activity = this,
                        onlyAfterCoreSetup = false,
                        openSettingsIfDenied = true
                    )
                }
            } else {
                allSetText.text = "✓ All System Shields, Sensors & Smart Notifications Active"
                allSetText.setOnClickListener(null)
            }
            deactivateLink?.visibility = View.VISIBLE
        } else {
            allSetText.visibility = View.VISIBLE
            val nextHint = when {
                !hasUsage -> "Guided Setup: Step 1 → Grant Usage Access below"
                hasAccessSetting && !isAccessLive -> "⚠️ After APK update, MIUI/OS paused Accessibility. Tap the button below, turn Focus Elite Accessibility OFF then ON."
                !UiThemePrefs.isSetupCategoriesConfigured(this) -> "Guided Setup: Step 2 → Tap 'Category Settings' above to define Study/Improve/Ignore apps"
                !UiThemePrefs.isSetupScheduleConfigured(this) -> "Guided Setup: Step 3 → Tap 'Focus Layer' banner above to confirm your schedule"
                coreReady && !hasNotifPerm -> "🔔 Final Step: Tap here to enable Smart Notifications & 1-Tap Pay Safe"
                else -> "Guided Setup: Step 4 → Enable Device Admin & Accessibility Shield below"
            }
            allSetText.text = nextHint
            if (coreReady && !hasNotifPerm) {
                allSetText.setOnClickListener {
                    SmartNotificationManager.requestNotificationPermissionIfNeeded(
                        activity = this,
                        onlyAfterCoreSetup = false,
                        openSettingsIfDenied = true
                    )
                }
            } else {
                allSetText.setOnClickListener(null)
            }
            deactivateLink?.visibility = if (hasAdmin) View.VISIBLE else View.GONE
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SmartNotificationManager.REQUEST_CODE_POST_NOTIFICATIONS) {
            if (SmartNotificationManager.hasNotificationPermission(this)) {
                FocusForegroundMonitorService.start(this)
                SmartNotificationManager.refreshNow(this)
                Toast.makeText(
                    this,
                    "🔔 Smart Notifications Enabled! Live Goals, Progress & 1-Tap Pay Safe are now in your shade.",
                    Toast.LENGTH_LONG
                ).show()
            }
            updatePermissionButtonStates()
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

    private fun bindCachedSnapshotFast() {
        val cached = UsageStatsHelper.getCachedTodaySnapshot(this) ?: return
        val screenTimeText = findViewById<TextView>(R.id.screenTimeText) ?: return
        screenTimeText.text = UsageStatsHelper.formatPreciseDuration(cached.totalScreenTimeMs)
        heroKpiUnlocks?.text = cached.unlockCount.toString()
        heroKpiLaunches?.text = cached.totalAppLaunches.toString()
        heroKpiPeakHour?.text = if (cached.peakHourMinutes > 0) {
            UsageStatsHelper.formatHourLabel(cached.peakHourIndex)
        } else {
            "—"
        }
        val totalCatMs = kotlin.math.max(1L, cached.studyMs + cached.improveMs + cached.distractMs + cached.utilityMs).toFloat()
        (heroSegStudy?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (cached.studyMs / totalCatMs).coerceAtLeast(if (cached.studyMs > 0L) 0.06f else 0f)
        (heroSegImprove?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (cached.improveMs / totalCatMs).coerceAtLeast(if (cached.improveMs > 0L) 0.06f else 0f)
        (heroSegDistract?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (cached.distractMs / totalCatMs).coerceAtLeast(if (cached.distractMs > 0L) 0.06f else 0f)
        (heroSegUtility?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (cached.utilityMs / totalCatMs).coerceAtLeast(if (cached.utilityMs > 0L) 0.06f else 0f)
        heroSegStudy?.parent?.requestLayout()
    }

    private fun refreshHomeData() {
        homeRefreshJob?.cancel()
        homeRefreshJob = lifecycleScope.launch(Dispatchers.IO) {
            val hasUsage = hasUsageStatsPermission()
            val snapshot = if (hasUsage) {
                UsageStatsHelper.getTodayWellbeingSnapshot(this@MainActivity)
            } else {
                null
            }

            // 1. Digital Wellbeing Screen Time & KPI Telemetry
            val timeText = if (snapshot != null) {
                UsageStatsHelper.formatPreciseDuration(snapshot.totalScreenTimeMs)
            } else if (!hasUsage) {
                "Grant Permission"
            } else {
                "—"
            }
            val unlocksText = snapshot?.unlockCount?.toString() ?: "—"
            val launchesText = snapshot?.totalAppLaunches?.toString() ?: "—"
            val peakHourText = if (snapshot != null && snapshot.peakHourMinutes > 0) {
                UsageStatsHelper.formatHourLabel(snapshot.peakHourIndex)
            } else {
                "—"
            }

            val diffMs = if (snapshot != null) snapshot.totalScreenTimeMs - snapshot.yesterdayScreenTimeMs else 0L
            val absDiffStr = UsageStatsHelper.formatDuration(kotlin.math.abs(diffMs))
            val studyMins = (snapshot?.studyMs ?: 0L) / 60_000L
            val distractMins = (snapshot?.distractMs ?: 0L) / 60_000L

            val (badgeText, badgeColor) = when {
                !hasUsage -> "Step 1: Grant Usage Access to unlock exact Digital Wellbeing tracking" to Color.parseColor("#38BDF8")
                snapshot == null || snapshot.yesterdayScreenTimeMs <= 0L -> {
                    "✓ Digital Wellbeing Exact • Study ${studyMins}m · Distract ${distractMins}m" to Color.parseColor("#38BDF8")
                }
                diffMs < -60_000L -> {
                    "▼ $absDiffStr vs yesterday • Study ${studyMins}m · Distract ${distractMins}m" to Color.parseColor("#34D399")
                }
                diffMs > 60_000L -> {
                    "▲ $absDiffStr vs yesterday • Study ${studyMins}m · Distract ${distractMins}m" to Color.parseColor("#F59E0B")
                }
                else -> {
                    "≈ Same pace as yesterday • Study ${studyMins}m · Distract ${distractMins}m" to Color.parseColor("#38BDF8")
                }
            }

            val totalCatMs = if (snapshot != null) {
                kotlin.math.max(1L, snapshot.studyMs + snapshot.improveMs + snapshot.distractMs + snapshot.utilityMs).toFloat()
            } else 1f
            val weightStudy = if (snapshot != null) (snapshot.studyMs / totalCatMs).coerceAtLeast(if (snapshot.studyMs > 0L) 0.06f else 0f) else 1f
            val weightImprove = if (snapshot != null) (snapshot.improveMs / totalCatMs).coerceAtLeast(if (snapshot.improveMs > 0L) 0.06f else 0f) else 0f
            val weightDistract = if (snapshot != null) (snapshot.distractMs / totalCatMs).coerceAtLeast(if (snapshot.distractMs > 0L) 0.06f else 0f) else 0f
            val weightUtility = if (snapshot != null) (snapshot.utilityMs / totalCatMs).coerceAtLeast(if (snapshot.utilityMs > 0L) 0.06f else 0f) else 0f

            // 2. Daily Time Notebook (Away Records)
            val dailyAuditSubtitle = try {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis
                val now = System.currentTimeMillis()
                val awayRecords = DaySummaryHelper.getAwayRecords(this@MainActivity, startOfDay, now)
                val totalAwayMins = DaySummaryHelper.awayStudyMinutes(awayRecords) +
                    DaySummaryHelper.awayImproveMinutes(awayRecords) +
                    DaySummaryHelper.awayDistractMinutes(awayRecords) +
                    DaySummaryHelper.awayIgnoreMinutes(awayRecords) +
                    DaySummaryHelper.awaySleepMinutes(awayRecords)

                if (totalAwayMins > 0L) {
                    "${DaySummaryHelper.formatMinutes(totalAwayMins)} logged today • Tap to open Daily Time Notebook"
                } else {
                    "Tap to open Daily Time Notebook & log activities"
                }
            } catch (_: Throwable) {
                "Log offline hours, track habits & reflect daily"
            }

            // 3. Pre-read configuration maps and category sets in RAM
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = try { pm.queryIntentActivities(mainIntent, 0) } catch (_: Throwable) { emptyList() }
            val studyApps = try { AppCategoryPrefs.getStudyApps(this@MainActivity) } catch (_: Throwable) { emptySet() }
            val improveApps = try { AppCategoryPrefs.getImprovementApps(this@MainActivity) } catch (_: Throwable) { emptySet() }
            val ignoredApps = try { AppCategoryPrefs.getIgnoredApps(this@MainActivity) } catch (_: Throwable) { emptySet() }
            val hardBlockedApps = try { HardBlockManager.getHardBlockedApps(this@MainActivity) } catch (_: Throwable) { emptySet() }
            val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this@MainActivity)

            val usedMinsMap = HashMap<String, Int>()
            val totalBrowserMinsMap = HashMap<String, Int>()
            val launchesMap = HashMap<String, Int>()
            val limitMinsByPkg = HashMap<String, Int>()
            val unblockedTodayPkgs = HashSet<String>()

            val appModels = resolveInfos.mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                if (SystemExclusions.isExcludedPackage(this@MainActivity, pkg)) return@mapNotNull null

                // High-performance cached lookup for label & icon (0ms in RAM)
                val name = AppMetadataCache.getLabel(pm, pkg)
                val icon = AppMetadataCache.getIcon(pm, pkg) ?: return@mapNotNull null

                val isBrowser = BlockPolicyEngine.isBrowserPackage(pkg)
                val stat = snapshot?.perAppStats?.get(pkg)
                val rawPkgMins = ((stat?.durationMs ?: 0L) / 60_000L).toInt()
                val usedMins = if (isBrowser) {
                    BlockPolicyEngine.getNonStudyBrowserMinutesForPackage(this@MainActivity, pkg).toInt()
                } else {
                    rawPkgMins
                }
                usedMinsMap[pkg] = usedMins
                totalBrowserMinsMap[pkg] = rawPkgMins
                launchesMap[pkg] = stat?.launchCount ?: 0

                val limitMins = TimeLimitManager.getLimitMinutes(this@MainActivity, pkg)
                if (limitMins > 0) {
                    limitMinsByPkg[pkg] = limitMins
                }
                val hardBlocked = hardBlockedApps.contains(pkg)
                val isUnblocked = BlockPolicyEngine.isUnblockedForToday(this@MainActivity, pkg)
                if (isUnblocked) {
                    unblockedTodayPkgs.add(pkg)
                }

                val isIgnored = ignoredApps.contains(pkg)
                val isWhitelisted = ((studyApps.contains(pkg) || improveApps.contains(pkg)) && !isIgnored) || isUnblocked
                val effectiveCap = when {
                    isIgnored -> if (limitMins in 1..29) limitMins else 30
                    limitMins > 0 -> limitMins
                    else -> defaultCap
                }
                val reachedCap = usedMins >= effectiveCap && !isWhitelisted

                if (reachedCap && limitMins <= 0) {
                    try {
                        TimeLimitManager.setLimitMinutes(this@MainActivity, pkg, effectiveCap)
                        limitMinsByPkg[pkg] = effectiveCap
                    } catch (_: Exception) {}
                }

                val isBlockedNow = hardBlocked || reachedCap
                AppModel(name, pkg, icon, isBlockedNow)
            }
                .distinctBy { it.packageName }
                .sortedWith(
                    compareByDescending<AppModel> { it.isBlocked }
                        .thenByDescending { usedMinsMap[it.packageName] ?: 0 }
                        .thenBy { it.appName.lowercase() }
                )

            // 4. Suggestion candidate
            val suggestionCandidate = if (hasUsage) {
                try {
                    SmartSuggestionEngine.findCandidates(this@MainActivity)
                        .firstOrNull { !hardBlockedApps.contains(it.packageName) }
                } catch (_: Throwable) { null }
            } else null

            // 5. Atomic single-pass UI dispatch on Dispatchers.Main
            withContext(Dispatchers.Main) {
                // Screen Time Hero
                val screenTimeText = findViewById<TextView>(R.id.screenTimeText)
                screenTimeText?.text = timeText
                heroKpiUnlocks?.text = unlocksText
                heroKpiLaunches?.text = launchesText
                heroKpiPeakHour?.text = peakHourText

                heroDeltaBadge?.text = badgeText
                heroDeltaBadge?.setTextColor(badgeColor)

                (heroSegStudy?.layoutParams as? LinearLayout.LayoutParams)?.weight = weightStudy
                (heroSegImprove?.layoutParams as? LinearLayout.LayoutParams)?.weight = weightImprove
                (heroSegDistract?.layoutParams as? LinearLayout.LayoutParams)?.weight = weightDistract
                (heroSegUtility?.layoutParams as? LinearLayout.LayoutParams)?.weight = weightUtility
                if (snapshot?.totalScreenTimeMs ?: 0L <= 0L) {
                    (heroSegStudy?.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
                }
                heroSegStudy?.parent?.requestLayout()

                // Daily Audit
                findViewById<TextView>(R.id.tvDailyAuditSubtitle)?.text = dailyAuditSubtitle

                // App List RecyclerView: reuse adapter if already present to preserve view pool and scroll state
                val existingAdapter = appListView.adapter as? AppAdapter
                if (existingAdapter != null) {
                    existingAdapter.updateData(
                        newApps = appModels,
                        newUsedMinutesByPkg = usedMinsMap,
                        newLaunchCountByPkg = launchesMap,
                        newTotalBrowserMinutesByPkg = totalBrowserMinsMap,
                        newStudyApps = studyApps,
                        newImproveApps = improveApps,
                        newIgnoredApps = ignoredApps,
                        newHardBlockedApps = hardBlockedApps,
                        newLimitMinsByPkg = limitMinsByPkg,
                        newUnblockedTodayPkgs = unblockedTodayPkgs,
                        newDefaultCap = defaultCap
                    )
                } else {
                    appListView.adapter = AppAdapter(
                        apps = appModels,
                        usedMinutesByPkg = usedMinsMap,
                        launchCountByPkg = launchesMap,
                        totalBrowserMinutesByPkg = totalBrowserMinsMap,
                        studyApps = studyApps,
                        improveApps = improveApps,
                        ignoredApps = ignoredApps,
                        hardBlockedApps = hardBlockedApps,
                        limitMinsByPkg = limitMinsByPkg,
                        unblockedTodayPkgs = unblockedTodayPkgs,
                        defaultCap = defaultCap,
                        onLimitClick = { app -> showLimitDialog(app, usedMinsMap[app.packageName] ?: 0) },
                        onToggle = { app, isChecked -> onAppToggleClicked(app, isChecked) }
                    )
                }

                // Suggestion Card
                bindSuggestionCard(suggestionCandidate)

                // Sync Widgets
                try {
                    FocusWidgetService.syncTelemetryNow(this@MainActivity)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun loadAppList() {
        refreshHomeData()
    }

    private fun refreshSuggestionCard() {
        refreshHomeData()
    }

    private fun onAppToggleClicked(app: AppModel, isChecked: Boolean) {
        val cap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
        val isBrowser = BlockPolicyEngine.isBrowserPackage(app.packageName)
        if (!isChecked && FocusLayerManager.isLockedUntilCalendarHoliday(this)) {
            refreshHomeData()
            CognitiveFrictionGate.show(
                context = this,
                title = "Unblock ${app.appName}",
                reasonPrompt = "Fortress Lock is active. Complete physical proof-of-effort (25 steps or 10 squats) to unblock ${app.appName}."
            ) {
                BlockPolicyEngine.setAppBlocked(this, app.packageName, false, cap)
                Toast.makeText(this, "🔓 ${app.appName} unblocked via Proof-of-Effort", Toast.LENGTH_SHORT).show()
                refreshHomeData()
            }
        } else {
            BlockPolicyEngine.setAppBlocked(this, app.packageName, isChecked, cap)
            val msg = when {
                isBrowser && isChecked ->
                    "✓ ${app.appName}: Non-Study Websites locked (Study Websites work 24/7)"
                isBrowser ->
                    "${app.appName}: Study Websites work 24/7 • Non-Study 30m cap active"
                isChecked ->
                    "✓ ${app.appName} blocked (${TimeLimitManager.getLimitMinutes(this, app.packageName)}m cap)"
                else ->
                    "${app.appName} unblocked for today"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            refreshHomeData()
        }
    }

    private fun bindSuggestionCard(target: SmartSuggestionEngine.Candidate?) {
        val card = findViewById<LinearLayout>(R.id.suggestionCard) ?: return
        if (target == null) {
            card.visibility = View.GONE
            return
        }
        val text = findViewById<TextView>(R.id.suggestionText) ?: return
        val acceptBtn = findViewById<Button>(R.id.suggestionAcceptButton) ?: return
        val dismissBtn = findViewById<Button>(R.id.suggestionDismissButton) ?: return

        val targetPkg = target.packageName
        val appLabel = target.appLabel
        val avgMins = target.avgMinutes
        val capMins = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)

        card.visibility = View.VISIBLE
        text.text = "You used $appLabel for ${avgMins}m.\nBlock ONLY $appLabel & apply a ${capMins}m daily cap with Blue Tick (✓)?"
        acceptBtn.text = "✓ Block $appLabel (${capMins}m)"
        dismissBtn.text = "Skip $appLabel"

        acceptBtn.setOnClickListener {
            HardBlockManager.setHardBlocked(this, targetPkg, true)
            TimeLimitManager.setLimitMinutes(this, targetPkg, capMins)
            BlockPolicyEngine.setUnblockedForToday(this, targetPkg, false)
            SmartSuggestionEngine.dismiss(this, targetPkg)
            Toast.makeText(
                this,
                "✓ Blocked $appLabel (${capMins}m cap + Blue Tick applied)!",
                Toast.LENGTH_SHORT
            ).show()
            refreshHomeData()
        }

        dismissBtn.setOnClickListener {
            SmartSuggestionEngine.dismiss(this, targetPkg)
            card.visibility = View.GONE
        }
    }

    private fun showLimitDialog(app: AppModel, usedMinutesToday: Int) {
        val currentLimit = TimeLimitManager.getLimitMinutes(this, app.packageName)
        val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
        val isBrowser = BlockPolicyEngine.isBrowserPackage(app.packageName)
        val stat = UsageStatsHelper.getTodayWellbeingSnapshot(this).perAppStats[app.packageName]
        val exactTimeStr = UsageStatsHelper.formatPreciseDuration(stat?.durationMs ?: (usedMinutesToday * 60_000L))
        val opensCount = stat?.launchCount ?: 0
        val longestStr = UsageStatsHelper.formatDuration(stat?.longestSessionMs ?: 0L)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(8f))
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutes per day (default $defaultCap)"
            setText((if (currentLimit > 0) currentLimit else defaultCap).toString())
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }

        // Quick Preset Chips (15m, 30m, 45m, 60m)
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10f)
            }
        }
        listOf(15, 30, 45, 60).forEach { presetMins ->
            val chip = TextView(this).apply {
                text = "${presetMins}m"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3f)
                    marginEnd = dp(3f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1f), Color.parseColor("#38BDF8"))
                }
                setOnClickListener {
                    input.setText(presetMins.toString())
                    input.setSelection(input.text.length)
                }
            }
            presetRow.addView(chip)
        }
        container.addView(presetRow)
        container.addView(input)

        val dialogMessage = if (isBrowser) {
            "Digital Wellbeing Telemetry Today:\n" +
                "• Non-Study Web Time: $usedMinutesToday min\n" +
                "• Total Browser Time: $exactTimeStr ($opensCount opens)\n" +
                "• Study Websites: Allowed 24/7 with no limit\n\n" +
                "Select a preset or enter daily Non-Study Website cap (minutes):"
        } else {
            "Digital Wellbeing Telemetry Today:\n" +
                "• Screen Time: $exactTimeStr ($opensCount opens · longest $longestStr)\n\n" +
                "Select a preset or enter daily cap in minutes:"
        }

        AlertDialog.Builder(this)
            .setTitle("Daily Time Limit — ${app.appName}")
            .setMessage(dialogMessage)
            .setView(container)
            .setPositiveButton("Save & Apply ✓") { _, _ ->
                val minutes = input.text.toString().toIntOrNull() ?: 0
                val isLooseningLimit = (currentLimit > 0 && (minutes == 0 || minutes > currentLimit))
                if (isLooseningLimit && FocusLayerManager.isLockedUntilCalendarHoliday(this)) {
                    CognitiveFrictionGate.show(
                        context = this,
                        title = "Increase Limit — ${app.appName}",
                        reasonPrompt = "Increasing or clearing a daily cap during active Fortress Lock requires Physical Proof-of-Effort."
                    ) {
                        BlockPolicyEngine.setTimeLimit(this, app.packageName, minutes)
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
                    BlockPolicyEngine.setTimeLimit(this, app.packageName, minutes)
                    if (!isBrowser && minutes > 0 && usedMinutesToday >= minutes) {
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
                        BlockPolicyEngine.setUnblockedForToday(this, app.packageName, true)
                        loadAppList()
                    }
                } else {
                    TimeLimitManager.setLimitMinutes(this, app.packageName, 0)
                    HardBlockManager.setHardBlocked(this, app.packageName, false)
                    BlockPolicyEngine.setUnblockedForToday(this, app.packageName, true)
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
