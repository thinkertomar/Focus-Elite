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
            startActivity(
                Intent(this, AuxiliarySettingsActivity::class.java).apply {
                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_INSTRUCTIONS)
                    putExtra(AuxiliarySettingsActivity.EXTRA_FIRST_TIME_WELCOME, true)
                }
            )
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

        val gearContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(palette.cardSurfaceColor)
                setStroke(dp(1.5f), palette.accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8f)
            }
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                        putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_INSTRUCTIONS)
                    }
                )
            }
        }

        val gearIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_gear)
            setColorFilter(palette.secondaryColor)
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
                marginEnd = dp(6f)
            }
            contentDescription = "Auxiliary Settings and Instructions"
        }

        val gearLabel = TextView(this).apply {
            text = "Hub & Guide"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        gearContainer.addView(gearIcon)
        gearContainer.addView(gearLabel)
        headerRow.addView(gearContainer)
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16f)
            }
            setOnClickListener {
                if (FocusLayerManager.isLockedUntilCalendarHoliday(this@MainActivity)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🔒 Focus Layer Active")
                        .setMessage(
                            "Your Focus Layer (${FocusLayerManager.formatWindowSummary(this@MainActivity)}) is currently active.\n\n" +
                                "Strict Lock is enabled. You can take a quick 15-minute break, mark a holiday/event in Calendar, or open Settings."
                        )
                        .setPositiveButton("⚙️ Open Settings") { _, _ ->
                            startActivity(
                                Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_AUXILIARY)
                                }
                            )
                        }
                        .setNeutralButton("☕ 15m Break") { _, _ ->
                            FocusLayerManager.grantAdaptiveBreak(this@MainActivity, 15)
                            Toast.makeText(this@MainActivity, "15-Minute Adaptive Break granted. Relax and return fresh!", Toast.LENGTH_SHORT).show()
                            applyDynamicThemeToHome()
                        }
                        .setNegativeButton("📅 Calendar", { _, _ ->
                            try {
                                startActivity(
                                    Intent(this@MainActivity, CalendarDayEditorActivity::class.java).apply {
                                        putExtra(CalendarDayEditorActivity.EXTRA_DATE_KEY, FocusLayerManager.todayKey())
                                    }
                                )
                            } catch (_: Throwable) {
                                startActivity(Intent(this@MainActivity, CalendarActivity::class.java))
                            }
                        })
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
        val fTitle = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val fSub = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(3f), 0, 0)
        }
        focusBanner.addView(fTitle)
        focusBanner.addView(fSub)

        // Insert right after screenTimeCard
        val screenTimeIdx = mainColumn.indexOfChild(screenTimeCard)
        mainColumn.addView(focusBanner, (screenTimeIdx + 1).coerceAtMost(mainColumn.childCount))
        focusLayerStatusBannerView = focusBanner
        focusLayerStatusTitle = fTitle
        focusLayerStatusSubtitle = fSub
    }

    private fun applyDynamicThemeToHome() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val screenTimeCard = findViewById<LinearLayout>(R.id.screenTimeCard)
        val rootScroll = screenTimeCard?.parent?.parent as? NestedScrollView
        rootScroll?.setBackgroundColor(palette.backgroundColor)

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
        focusLayerStatusBannerView?.background = UiThemePrefs.createCardBackground(this, cornerDp = 14f, isHero = false)
        when {
            exemptionDetails != null -> {
                focusLayerStatusTitle?.text = exemptionDetails
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#38BDF8"))
                focusLayerStatusSubtitle?.text = "Schedule ($windowSummary) is paused • $currentTimeStr • Tap to customize"
            }
            isLocked -> {
                focusLayerStatusTitle?.text = "🔒 Focus Layer Active: $windowSummary"
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#F59E0B"))
                focusLayerStatusSubtitle?.text = "Strict session in progress ($currentTimeStr) • Tap for break or settings"
            }
            isScheduled -> {
                focusLayerStatusTitle?.text = "🟢 Focus Layer Active: $windowSummary"
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#34D399"))
                focusLayerStatusSubtitle?.text = "Session active right now ($currentTimeStr) • Tap to customize"
            }
            else -> {
                focusLayerStatusTitle?.text = "🛡️ Focus Layer: $windowSummary"
                focusLayerStatusTitle?.setTextColor(Color.WHITE)
                focusLayerStatusSubtitle?.text = "Current: $currentTimeStr (Outside Window) • Tap to configure"
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
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.accessibilityButton)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.batteryOptButton)?.setOnClickListener {
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
            startActivity(Intent(this, TimeSplitterActivity::class.java))
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val appWidgetManager = getSystemService(AppWidgetManager::class.java)
                val provider = ComponentName(this, FocusWidgetProvider::class.java)
                if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                    appWidgetManager.requestPinAppWidget(provider, null, null)
                } else {
                    Toast.makeText(
                        this,
                        "Long-press on your home screen → Widgets → Focus Elite to add.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    this,
                    "Long-press on your home screen → Widgets → Focus Elite to add.",
                    Toast.LENGTH_LONG
                ).show()
            }
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

            val popupStatus = if (!AwayTimeTracker.isAutoAuditPopupEnabled(this)) "Auto-popup off" else "Auto-popup on"
            if (totalAwayMins > 0L) {
                subtitle.text = "${DaySummaryHelper.formatMinutes(totalAwayMins)} logged today ($popupStatus) • Tap to open"
            } else {
                subtitle.text = "Manual notebook ($popupStatus) • Log offline hours & notes"
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

        usageBtn.visibility = if (hasUsage) View.GONE else View.VISIBLE
        accessBtn.visibility = if (hasAccess) View.GONE else View.VISIBLE
        batteryBtn.visibility = if (hasBattery) View.GONE else View.VISIBLE
        adminBtn.visibility = if (hasAdmin) View.GONE else View.VISIBLE

        val deactivateLink = findViewById<TextView>(R.id.deactivateShieldLink)
        if (hasUsage && hasAccess && hasBattery && hasAdmin) {
            allSetText.visibility = View.VISIBLE
            allSetText.text = "✓ All System Shields & Sensors Active"
            deactivateLink?.visibility = View.VISIBLE
        } else {
            allSetText.visibility = View.GONE
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
                        BlockPolicyEngine.setAppBlocked(this@MainActivity, app.packageName, isChecked, cap)
                        val msg = if (isChecked) {
                            "✓ ${app.appName} blocked (${TimeLimitManager.getLimitMinutes(this@MainActivity, app.packageName)}m cap)"
                        } else {
                            "${app.appName} unblocked"
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        refreshSuggestionCard()
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
                TimeLimitManager.setLimitMinutes(this, app.packageName, minutes)
                if (minutes > 0 && usedMinutesToday >= minutes) {
                    HardBlockManager.setHardBlocked(this, app.packageName, true)
                }
                Toast.makeText(
                    this,
                    if (minutes > 0) "✓ ${app.appName} limit set to ${minutes}m/day" else "Custom limit cleared for ${app.appName}",
                    Toast.LENGTH_SHORT
                ).show()
                loadAppList()
            }
            .setNeutralButton("Clear Limit & Unblock") { _, _ ->
                TimeLimitManager.setLimitMinutes(this, app.packageName, 0)
                HardBlockManager.setHardBlocked(this, app.packageName, false)
                loadAppList()
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
