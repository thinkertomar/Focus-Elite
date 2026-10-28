package com.focusguardpro.app

import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import java.util.Calendar
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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
import com.focusguardpro.app.insights.InsightsDashboardActivity
import com.focusguardpro.app.widget.FocusWidgetProvider
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class AuxiliarySettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
        const val EXTRA_FIRST_TIME_WELCOME = "extra_first_time_welcome"

        const val TAB_INSTRUCTIONS = 0
        const val TAB_UI_COLOR = 1
        const val TAB_AUXILIARY = 2
        const val TAB_INSPIRE = 3
    }

    private var currentTab = TAB_INSTRUCTIONS
    private var showFirstTimeWelcome = false

    private lateinit var rootLayout: LinearLayout
    private lateinit var headerSubtitleView: TextView
    private lateinit var tabButtonsContainer: LinearLayout
    private lateinit var contentScrollView: ScrollView
    private lateinit var contentContainer: LinearLayout

    private var breathAnimator: ValueAnimator? = null
    private var treeGrowthAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardApp.ensureRoomMainThreadQueries(this)

        currentTab = intent.getIntExtra(EXTRA_INITIAL_TAB, TAB_INSTRUCTIONS)
        showFirstTimeWelcome = intent.getBooleanExtra(EXTRA_FIRST_TIME_WELCOME, false)
        if (showFirstTimeWelcome) {
            UiThemePrefs.markFirstTimeInstructionsSeen(this)
        }

        buildBaseScaffold()
        renderActiveTab()
    }

    override fun onResume() {
        super.onResume()
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        applyThemeToScaffold()
        renderActiveTab()
    }

    override fun onDestroy() {
        breathAnimator?.cancel()
        treeGrowthAnimator?.cancel()
        super.onDestroy()
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun buildBaseScaffold() {
        val palette = UiThemePrefs.getSelectedPalette(this)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(palette.backgroundColor)
        }

        // Top App Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(28f), dp(16f), dp(10f))
        }

        val backBtn = TextView(this).apply {
            text = "← Home"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(Color.parseColor("#1E2438"))
                setStroke(dp(1f), palette.accentColor)
            }
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12f)
            }
        }
        val titleView = TextView(this).apply {
            text = "Command & Studio Hub"
            textSize = 18.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        headerSubtitleView = TextView(this).apply {
            text = "Guide • UI Themes • Focus Schedule • Alchemy"
            textSize = 11.5f
            setTextColor(palette.secondaryColor)
        }
        titleCol.addView(titleView)
        titleCol.addView(headerSubtitleView)
        topBar.addView(titleCol)

        rootLayout.addView(topBar)

        // Horizontal Tab Bar
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(14f), dp(4f), dp(14f), dp(10f))
        }
        tabButtonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabScroll.addView(tabButtonsContainer)
        rootLayout.addView(tabScroll)

        // Content ScrollView
        contentScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(36f))
        }
        contentScrollView.addView(contentContainer)
        rootLayout.addView(contentScrollView)

        setContentView(rootLayout)
    }

    private fun applyThemeToScaffold() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        rootLayout.setBackgroundColor(palette.backgroundColor)
        headerSubtitleView.setTextColor(palette.secondaryColor)
        headerSubtitleView.text = "Theme: ${palette.name} • Tap any tab below"
    }

    private fun renderTabPills() {
        tabButtonsContainer.removeAllViews()
        val palette = UiThemePrefs.getSelectedPalette(this)
        val tabs = listOf(
            TAB_INSTRUCTIONS to "📖 Guide & Tree",
            TAB_UI_COLOR to "🎨 UI & Colors",
            TAB_AUXILIARY to "⚙️ Focus & Rules",
            TAB_INSPIRE to "✨ Inspire Me"
        )

        for ((tabId, label) in tabs) {
            val selected = currentTab == tabId
            val pill = TextView(this).apply {
                text = label
                textSize = 12.5f
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#94A3B8"))
                setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20f).toFloat()
                    if (selected) {
                        setColor(palette.accentColor)
                    } else {
                        setColor(palette.cardSurfaceColor)
                        setStroke(dp(1f), Color.parseColor("#2A324B"))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8f)
                }
                setOnClickListener {
                    if (currentTab != tabId) {
                        currentTab = tabId
                        renderActiveTab()
                        contentScrollView.smoothScrollTo(0, 0)
                    }
                }
            }
            tabButtonsContainer.addView(pill)
        }
    }

    private fun renderActiveTab() {
        breathAnimator?.cancel()
        treeGrowthAnimator?.cancel()
        applyThemeToScaffold()
        renderTabPills()
        contentContainer.removeAllViews()

        when (currentTab) {
            TAB_INSTRUCTIONS -> buildInstructionsTab()
            TAB_UI_COLOR -> buildUiAndColorTab()
            TAB_AUXILIARY -> buildAuxiliarySettingsTab()
            TAB_INSPIRE -> buildInspireMeTab()
        }
    }

    // =========================================================================
    // TAB 0: INSTRUCTION TAB (WITH SETUP TREE + BAR GRAPH AT TOP)
    // =========================================================================

    private fun buildInstructionsTab() {
        val palette = UiThemePrefs.getSelectedPalette(this)

        val usageReady = hasUsageStatsPermission()
        val studyCount = AppCategoryPrefs.getStudyApps(this).size
        val improveCount = AppCategoryPrefs.getImprovementApps(this).size
        val ignoredCount = AppCategoryPrefs.getIgnoredApps(this).size
        val criteriaCount = studyCount + improveCount + ignoredCount
        val criteriaReady = UiThemePrefs.isSetupCategoriesConfigured(this)
        val scheduleReady = UiThemePrefs.isSetupScheduleConfigured(this)
        val batteryReady = isIgnoringBatteryOptimizations()
        val adminReady = isDeviceAdminActive()
        val shieldReady = isAccessibilityServiceEnabled()
        val step4Ready = adminReady && shieldReady

        val currentStepNumber = when {
            !usageReady -> 1
            !criteriaReady -> 2
            !scheduleReady -> 3
            !step4Ready -> 4
            else -> 5
        }

        // 1. First-Time Welcome & Guided 4-Step Setup Wizard Card
        val welcomeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 18f, isHero = true)
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16f)
            }
        }

        val badgeText = TextView(this).apply {
            text = if (currentStepNumber <= 4) {
                "✨ GUIDED FIRST-TIME SETUP • STEP $currentStepNumber OF 4"
            } else {
                "✓ ALL 4 SETUP STEPS COMPLETE • FORTRESS READY"
            }
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (currentStepNumber <= 4) palette.secondaryColor else Color.parseColor("#4ADE80"))
        }
        val welcomeTitle = TextView(this).apply {
            text = if (currentStepNumber <= 4) {
                "Smooth 4-Step Setup (Zero Self-Lockout Order)"
            } else {
                "Your Focus Elite Fortress is 100% Configured!"
            }
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4f), 0, dp(4f))
        }
        val welcomeBody = TextView(this).apply {
            text = "Why this exact order matters: You configure Usage Access (Step 1), App Criteria with all system apps automatically allowed (Step 2), and your Focus Schedule (Step 3) FIRST — and enable Device Admin & Accessibility Shield LAST (Step 4). This guarantees the no-escape Focus Lock never blocks you inside Android Settings while you are still setting up!"
            textSize = 12.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(0f, 1.2f)
        }
        welcomeCard.addView(badgeText)
        welcomeCard.addView(welcomeTitle)
        welcomeCard.addView(welcomeBody)

        // Step-by-Step Interactive Wizard Cards inside the Welcome/Setup Hub
        // STEP 1: USAGE ACCESS
        welcomeCard.addView(
            buildGuidedStepRow(
                stepNum = 1,
                title = "Grant Usage Access Sensor",
                subtitle = if (usageReady) {
                    "✓ Usage Access granted — Focus Elite can measure screen time & app usage."
                } else {
                    "Required first so Focus Elite can see your installed apps and daily screen time."
                },
                isDone = usageReady,
                isCurrent = currentStepNumber == 1,
                buttonText = if (usageReady) "✓ Done" else "1. Grant Usage →",
                palette = palette
            ) {
                SettingsTamperGuard.grantPermissionSetupGrace(this)
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        )

        // STEP 2: DEFINE APP CRITERIA (STUDY / IMPROVE / IGNORE — SYSTEM APPS AUTO-EXCLUDED)
        welcomeCard.addView(
            buildGuidedStepRow(
                stepNum = 2,
                title = "Define Your App Criteria (Study • Improve • Ignore)",
                subtitle = if (criteriaReady) {
                    "✓ Configured ($studyCount Study, $improveCount Improve, $ignoredCount Ignored) • All Android system apps automatically allowed 24/7."
                } else {
                    "Classify your apps into Study/Improve (always allowed) or Ignored (30m cap). All Android system apps (Phone, Camera, Clock, Calculator, SMS, Settings, Files) are automatically excluded & allowed!"
                },
                isDone = criteriaReady,
                isCurrent = currentStepNumber == 2,
                buttonText = if (criteriaReady) "Edit Apps ($criteriaCount)" else "2. Define Apps →",
                palette = palette
            ) {
                startActivity(
                    Intent(this, CategorySettingsActivity::class.java).apply {
                        putExtra("from_guided_setup", true)
                    }
                )
            }
        )

        // STEP 3: CONFIGURE FOCUS SCHEDULE BEFORE ENABLING ACCESSIBILITY
        val currentScheduleSummary = FocusLayerManager.formatWindowSummary(this)
        welcomeCard.addView(
            buildGuidedStepRow(
                stepNum = 3,
                title = "Set Your Focus Schedule Window",
                subtitle = if (scheduleReady) {
                    "✓ Schedule confirmed: $currentScheduleSummary (Set before turning on Accessibility Shield)."
                } else {
                    "Current window: $currentScheduleSummary. Tap 'Confirm' to keep this window or tap 'Customize' to pick your own hours & days before enabling the shield."
                },
                isDone = scheduleReady,
                isCurrent = currentStepNumber == 3,
                buttonText = if (scheduleReady) "Customize Hours" else "3. Set Schedule →",
                palette = palette,
                secondaryButtonText = if (!scheduleReady) "✓ Keep 9AM–6PM" else null,
                onSecondaryClick = {
                    UiThemePrefs.markSetupScheduleConfigured(this)
                    Toast.makeText(
                        this,
                        "✓ Step 3 Complete! Focus schedule ($currentScheduleSummary) confirmed. Now complete Step 4!",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderActiveTab()
                }
            ) {
                UiThemePrefs.markSetupScheduleConfigured(this)
                currentTab = TAB_AUXILIARY
                renderActiveTab()
                contentScrollView.smoothScrollTo(0, 0)
                Toast.makeText(
                    this,
                    "Customize your Focus Schedule hours & days below, then return to Step 4!",
                    Toast.LENGTH_LONG
                ).show()
            }
        )

        // STEP 4: ENABLE FINAL ENFORCEMENT PERMISSIONS LAST (BATTERY + DEVICE ADMIN + ACCESSIBILITY SHIELD)
        val step4Container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#111827"))
                setStroke(
                    dp(if (currentStepNumber == 4) 2f else 1f),
                    when {
                        step4Ready -> Color.parseColor("#22C55E")
                        currentStepNumber == 4 -> palette.accentColor
                        else -> Color.parseColor("#334155")
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
        }

        step4Container.addView(TextView(this).apply {
            text = if (step4Ready) {
                "✓ STEP 4 OF 4: FINAL ENFORCEMENT SHIELDS ACTIVE"
            } else {
                "🛡️ STEP 4 OF 4 (FINAL STEP): ENABLE ADMIN & ACCESSIBILITY SHIELD"
            }
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (step4Ready) Color.parseColor("#4ADE80") else Color.WHITE)
        })

        step4Container.addView(TextView(this).apply {
            text = "Enable these LAST after Steps 1–3 are set. Tapping any button below activates a 3-minute Setup Grace Window so Android Settings will NEVER block you while turning on Device Admin or Accessibility!"
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(3f), 0, dp(8f))
        })

        val step4ButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        step4ButtonsRow.addView(
            createQuickSetupButton(
                label = if (batteryReady) "✓ 4A. Battery Set" else "⚡ 4A. Unrestricted Battery",
                isDone = batteryReady,
                palette = palette
            ) {
                SettingsTamperGuard.grantPermissionSetupGrace(this)
                requestBatteryExemption()
            }
        )
        step4ButtonsRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 1) })
        step4ButtonsRow.addView(
            createQuickSetupButton(
                label = if (adminReady) "✓ 4B. Admin Active" else "🛡️ 4B. Enable Device Admin",
                isDone = adminReady,
                palette = palette
            ) {
                SettingsTamperGuard.grantPermissionSetupGrace(this)
                requestDeviceAdmin()
            }
        )
        step4Container.addView(step4ButtonsRow)

        val accessFinalBtn = Button(this).apply {
            text = if (shieldReady) {
                "✓ 4C. Accessibility Blocker Shield Active (Setup Complete!)"
            } else {
                "🔒 4C. Enable Accessibility Blocker Shield (Enable Last →)"
            }
            textSize = 12.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(46f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                if (shieldReady) {
                    setColor(Color.parseColor("#14532D"))
                    setStroke(dp(1f), Color.parseColor("#22C55E"))
                } else {
                    setColor(palette.accentColor)
                }
            }
            setOnClickListener {
                // Mark Steps 2 & 3 seen if user jumps straight to Step 4 so they aren't stuck
                UiThemePrefs.markSetupCategoriesConfigured(this@AuxiliarySettingsActivity)
                UiThemePrefs.markSetupScheduleConfigured(this@AuxiliarySettingsActivity)
                SettingsTamperGuard.grantPermissionSetupGrace(this@AuxiliarySettingsActivity, 180_000L)
                Toast.makeText(
                    this@AuxiliarySettingsActivity,
                    "🛡️ Setup Grace Active: Enable 'Focus Elite' in Accessibility Settings.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        step4Container.addView(accessFinalBtn)
        welcomeCard.addView(step4Container)

        contentContainer.addView(welcomeCard)

        // 2. 100% OFFLINE & ZERO-INTERNET PRIVACY AUDIT CARD
        buildPrivacyAuditCard(palette)

        // 3. VISUAL SETUP TREE + READINESS BAR GRAPH CARD
        val treeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 18f, isHero = false)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16f)
            }
        }

        val treeTitle = TextView(this).apply {
            text = "🌳 4-STEP SETUP TREE & READINESS BAR GRAPH"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accentColor)
        }
        val treeSub = TextView(this).apply {
            text = "Live visual blueprint: 1. Usage Access → 2. App Criteria (System Apps Safe) → 3. Schedule → 4. Shields:"
            textSize = 12.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(4f), 0, dp(10f))
        }
        treeCard.addView(treeTitle)
        treeCard.addView(treeSub)

        val treeGraphView = SetupTreeAndBarGraphView(
            context = this,
            palette = palette,
            usageReady = usageReady,
            criteriaReady = criteriaReady,
            criteriaCount = criteriaCount,
            scheduleReady = scheduleReady,
            shieldReady = shieldReady,
            adminReady = adminReady,
            batteryReady = batteryReady
        )
        treeGraphView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(420f)
        )
        treeCard.addView(treeGraphView)
        contentContainer.addView(treeCard)

        // 4. EASY-TO-UNDERSTAND FUNCTION GUIDES
        addSectionTitle("EVERYDAY USER GUIDE — HOW EACH FUNCTION WORKS", palette)

        addInstructionGuideCard(
            stepBadge = "MODULE 1 • TIME AUDIT & NOTEBOOK (POPUP SHUT DOWN BY DEFAULT)",
            title = "How Daily Time Audit & Bar Graph Analytics Work",
            body = "• Screen-Off Popup Shut Down: The automatic Audit popup after screen-off is now shut down/paused by default so it won't interrupt you when unlocking your phone (you can re-enable it anytime in the '⚙️ Focus & Rules' tab).\n" +
                "• Manual Daily Time Notebook: Tap 'Daily Time Notebook' on the Home screen whenever you want to log or split your offline hours and write daily reflections.\n" +
                "• Interactive Bar Graph Breakdown: Open 'Time Analysis' on the Home screen to see your 7-day stacked bar chart and 24h pie chart. Tapping ANY bar opens that day's granular breakdown.",
            actionLabel = "Open Time Analysis Bar Graph →",
            palette = palette
        ) {
            startActivity(Intent(this, AuditSummaryActivity::class.java))
        }

        addInstructionGuideCard(
            stepBadge = "MODULE 2 • CUSTOMIZABLE FOCUS LAYER & CALENDAR HOLIDAY LOCK",
            title = "Flexible Focus Hours + 24h Advance Holiday Rule",
            body = "• Customize Your Criteria: In the '⚙️ Focus & Rules' tab, you can choose your own Focus Window hours (e.g. 9 AM–6 PM, 8 AM–4 PM, custom hours) and active days.\n" +
                "• 30-Minute Pre-Window Grace Period: You can freely adjust your schedule and categories up until your focus window starts.\n" +
                "• All System Apps Automatically Allowed: Phone, SMS, Camera, Clock/Alarm, Calculator, Contacts, Files, Settings, and Payment apps are excluded from blocking 24/7 so you never face any issue using device features.",
            actionLabel = "Configure Focus Layer Criteria →",
            palette = palette
        ) {
            currentTab = TAB_AUXILIARY
            renderActiveTab()
            contentScrollView.smoothScrollTo(0, 0)
        }

        addInstructionGuideCard(
            stepBadge = "MODULE 3 • SMART SUGGESTIONS & BLUE TICK (✓) BLOCKING",
            title = "How 30-Minute Limits, Home Suggestions & Blue Ticks Work",
            body = "• 30-Minute Threshold: When you use any non-study app for 30+ minutes in a day, Focus Elite flags it.\n" +
                "• Per-App Home Screen Suggestion: On the Home screen, the Suggestion Card recommends blocking that PARTICULAR app.\n" +
                "• The Blue Tick (✓) Indicator: Once you block an app from the suggestion card, toggle its switch, or once it hits its 30-minute daily cap, it displays a bright Blue Tick (✓) and blue switch in the Apps Blocklist.",
            actionLabel = "Configure App Categories & Study Whitelist →",
            palette = palette
        ) {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }

        addInstructionGuideCard(
            stepBadge = "MODULE 4 • PHYSICAL ACTIVITY & CALISTHENICS",
            title = "How Movement, Kilometre, Calories & Calisthenics Work",
            body = "• Step & Sensor Tracking: 'Physical Activity' tracks your daily Steps, Kilometres (Distance), Active Movement Minutes, and Calories burned using on-device hardware sensors.\n" +
                "• Tap Any Metric for 7-Day Breakdown: Tap Distance (km), Calories, Active Time, or the Weekly Steps chart inside Physical Activity to inspect your day-by-day history.\n" +
                "• Calisthenics Progression: Log push-ups, pull-ups, squats, and core progressions with built-in rest timers.",
            actionLabel = "Open Physical Activity & Breakdowns →",
            palette = palette
        ) {
            startActivity(Intent(this, ActivityStatsActivity::class.java))
        }
    }

    private fun buildGuidedStepRow(
        stepNum: Int,
        title: String,
        subtitle: String,
        isDone: Boolean,
        isCurrent: Boolean,
        buttonText: String,
        palette: UiThemePrefs.ThemePalette,
        secondaryButtonText: String? = null,
        onSecondaryClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View {
        val rowCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#111827"))
                setStroke(
                    dp(if (isCurrent) 2f else 1f),
                    when {
                        isDone -> Color.parseColor("#22C55E")
                        isCurrent -> palette.accentColor
                        else -> Color.parseColor("#334155")
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val stepBadge = TextView(this).apply {
            text = if (isDone) "✓ STEP $stepNum" else "STEP $stepNum"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(if (isDone) Color.parseColor("#15803D") else palette.accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8f) }
        }
        topRow.addView(stepBadge)

        val titleTv = TextView(this).apply {
            text = title
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(titleTv)
        rowCard.addView(topRow)

        val subTv = TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(4f), 0, dp(8f))
            setLineSpacing(0f, 1.15f)
        }
        rowCard.addView(subTv)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (secondaryButtonText != null && onSecondaryClick != null) {
            val secBtn = Button(this).apply {
                text = secondaryButtonText
                textSize = 11.5f
                isAllCaps = false
                setTextColor(Color.WHITE)
                minHeight = dp(40f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Color.parseColor("#14532D"))
                    setStroke(dp(1f), Color.parseColor("#22C55E"))
                }
                setOnClickListener { onSecondaryClick() }
            }
            actionRow.addView(secBtn)
        }

        val mainBtn = Button(this).apply {
            text = buttonText
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(40f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                if (isDone) {
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1f), Color.parseColor("#22C55E"))
                } else {
                    setColor(palette.accentColor)
                }
            }
            setOnClickListener { onClick() }
        }
        actionRow.addView(mainBtn)
        rowCard.addView(actionRow)

        return rowCard
    }

    private fun buildPrivacyAuditCard(palette: UiThemePrefs.ThemePalette) {
        val hasInternetPerm = try {
            val pkgInfo = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            val perms = pkgInfo.requestedPermissions?.toList() ?: emptyList()
            perms.contains("android.permission.INTERNET")
        } catch (_: Throwable) {
            false
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#0A1F17"))
                setStroke(dp(1.5f), Color.parseColor("#10B981"))
            }
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16f)
            }
        }

        card.addView(TextView(this).apply {
            text = if (!hasInternetPerm) {
                "🔒 PRIVACY AUDIT VERIFIED: ZERO INTERNET PERMISSION (100% OFFLINE)"
            } else {
                "🔒 PRIVACY & PERMISSIONS AUDIT"
            }
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
        })

        card.addView(TextView(this).apply {
            text = "Does Focus Elite have Internet Permission? NO!"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4f), 0, dp(4f))
        })

        card.addView(TextView(this).apply {
            text = "• Zero Network Access: Focus Elite does NOT request 'android.permission.INTERNET' in its AndroidManifest. The Android OS kernel blocks any app without this permission from opening network sockets. Zero bytes of your data can ever leave your phone.\n" +
                "• Zero Location / GPS Tracking: No location permissions are requested or used.\n" +
                "• Why Local Permissions Are Used (100% On-Device SQLite):\n" +
                "  1. Usage Access: Calculates your daily screen time and 30m app limits locally.\n" +
                "  2. Accessibility Service: Detects when a blocked distraction app or blocked browser URL opens so it can show the local Focus Shield.\n" +
                "  3. Device Admin: Prevents impulse uninstallation during active focus hours.\n" +
                "  4. Physical Activity Sensor: Counts hardware steps & squats locally for your fitness stats and Proof-of-Effort gate."
            textSize = 12f
            setTextColor(Color.parseColor("#D1FAE5"))
            setLineSpacing(0f, 1.22f)
        })

        contentContainer.addView(card)
    }

    private fun createQuickSetupButton(
        label: String,
        isDone: Boolean,
        palette: UiThemePrefs.ThemePalette,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(46f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                if (isDone) {
                    setColor(Color.parseColor("#14532D"))
                    setStroke(dp(1f), Color.parseColor("#22C55E"))
                } else {
                    setColor(Color.parseColor("#3B2510"))
                    setStroke(dp(1f), palette.accentColor)
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun addInstructionGuideCard(
        stepBadge: String,
        title: String,
        body: String,
        actionLabel: String,
        palette: UiThemePrefs.ThemePalette,
        onAction: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 16f, isHero = false)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14f)
            }
        }

        val badge = TextView(this).apply {
            text = stepBadge
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accentColor)
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4f), 0, dp(6f))
        }
        val bodyTv = TextView(this).apply {
            text = body
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(0f, 1.25f)
        }
        val actionBtn = TextView(this).apply {
            text = actionLabel
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            setPadding(0, dp(10f), 0, dp(2f))
            setOnClickListener { onAction() }
        }

        card.addView(badge)
        card.addView(titleTv)
        card.addView(bodyTv)
        card.addView(actionBtn)
        contentContainer.addView(card)
    }

    // =========================================================================
    // TAB 1: UI & COLOR STUDIO TAB
    // =========================================================================

    private fun buildUiAndColorTab() {
        val activePalette = UiThemePrefs.getSelectedPalette(this)

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 18f, isHero = true)
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18f)
            }
        }
        previewCard.addView(TextView(this).apply {
            text = "🎨 ACTIVE UI THEME PREVIEW"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activePalette.secondaryColor)
        })
        previewCard.addView(TextView(this).apply {
            text = activePalette.name
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4f), 0, dp(2f))
        })
        previewCard.addView(TextView(this).apply {
            text = "${activePalette.subtitle} • Applied across Home Screen & Blocklist"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
        })
        contentContainer.addView(previewCard)

        addSectionTitle("CHOOSE APP COLOR PALETTE (INSTANT APPLY)", activePalette)

        for (palette in UiThemePrefs.PALETTES) {
            val isSelected = palette.id == activePalette.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(palette.cardSurfaceColor)
                    setStroke(
                        dp(if (isSelected) 2.5f else 1f),
                        if (isSelected) palette.accentColor else Color.parseColor("#2A324B")
                    )
                }
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10f)
                }
                setOnClickListener {
                    UiThemePrefs.setSelectedPalette(this@AuxiliarySettingsActivity, palette.id)
                    renderActiveTab()
                    Toast.makeText(
                        this@AuxiliarySettingsActivity,
                        "Applied ${palette.name} theme!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply {
                    marginEnd = dp(14f)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.accentColor)
                    setStroke(dp(2f), palette.secondaryColor)
                }
            }
            row.addView(swatch)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = palette.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            textCol.addView(TextView(this).apply {
                text = palette.subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
            })
            row.addView(textCol)

            val statusBadge = TextView(this).apply {
                text = if (isSelected) "✓ ACTIVE" else "Apply"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isSelected) Color.WHITE else palette.secondaryColor)
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16f).toFloat()
                    if (isSelected) {
                        setColor(palette.accentColor)
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1f), palette.accentColor)
                    }
                }
            }
            row.addView(statusBadge)

            contentContainer.addView(row)
        }

        addSectionTitle("UI APPEARANCE & BLOCKLIST DISPLAY", activePalette)

        addToggleOptionCard(
            title = "Glowing Accent Borders on Cards",
            subtitle = "Tint Home screen & settings cards with your selected theme's neon border",
            checked = UiThemePrefs.isCardGlowEnabled(this),
            palette = activePalette
        ) { enabled ->
            UiThemePrefs.setCardGlowEnabled(this, enabled)
            renderActiveTab()
        }

        addToggleOptionCard(
            title = "Show Blue Tick (✓) & Usage Status on Blocklist",
            subtitle = "Display sky-blue ✓ badge, daily minutes used, and cap status on blocked apps",
            checked = UiThemePrefs.isShowBlueTickStatusEnabled(this),
            palette = activePalette
        ) { enabled ->
            UiThemePrefs.setShowBlueTickStatusEnabled(this, enabled)
        }

        addToggleOptionCard(
            title = "Compact App Blocklist Rows",
            subtitle = "Reduce vertical padding in the Home Screen app list to see more apps at once",
            checked = UiThemePrefs.isCompactListEnabled(this),
            palette = activePalette
        ) { enabled ->
            UiThemePrefs.setCompactListEnabled(this, enabled)
        }
    }

    // =========================================================================
    // TAB 2: AUXILIARY FUNCTIONS, SCREEN-OFF AUDIT SHUTDOWN & FOCUS LAYER CRITERIA
    // =========================================================================

    private fun buildAuxiliarySettingsTab() {
        val palette = UiThemePrefs.getSelectedPalette(this)

        // 1. SCREEN-OFF AUDIT POPUP SHUTDOWN CONTROL
        addSectionTitle("SCREEN-OFF AUDIT POPUP CONTROL (SHUT DOWN)", palette)

        val auditPopupEnabled = AwayTimeTracker.isAutoAuditPopupEnabled(this)
        val snoozeMins = AwayTimeTracker.getSnoozeRemainingMinutes(this)

        val auditCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 16f, isHero = false)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }

        val auditStatusHeader = TextView(this).apply {
            text = if (!auditPopupEnabled) {
                if (snoozeMins > 0) {
                    "⏸️ SCREEN-OFF AUDIT POPUP SNOOZED (${snoozeMins / 60}h ${snoozeMins % 60}m left)"
                } else {
                    "⏸️ SCREEN-OFF AUDIT POPUP IS SHUT DOWN (PAUSED)"
                }
            } else {
                "🔔 SCREEN-OFF AUDIT POPUP IS ACTIVE"
            }
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (!auditPopupEnabled) Color.parseColor("#38BDF8") else Color.parseColor("#22C55E"))
        }
        auditCard.addView(auditStatusHeader)

        val auditDesc = TextView(this).apply {
            text = "The automatic popup that appeared after 5+ minutes of screen-off time is shut down so it won't interrupt you. Your Daily Time Notebook and Audit features remain 100% intact for manual logging anytime."
            textSize = 12.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(4f), 0, dp(10f))
        }
        auditCard.addView(auditDesc)

        val auditBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val togglePopupBtn = Button(this).apply {
            text = if (auditPopupEnabled) "⏸️ Pause Auto Popup" else "🔔 Enable Auto Popup"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(46f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) }
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(if (auditPopupEnabled) Color.parseColor("#B91C1C") else Color.parseColor("#1E293B"))
                setStroke(dp(1f), palette.accentColor)
            }
            setOnClickListener {
                if (auditPopupEnabled) {
                    AwayTimeTracker.setAutoAuditPopupEnabled(this@AuxiliarySettingsActivity, false)
                    Toast.makeText(this@AuxiliarySettingsActivity, "Screen-off Audit popup shut down.", Toast.LENGTH_SHORT).show()
                } else {
                    AwayTimeTracker.clearSnooze(this@AuxiliarySettingsActivity)
                    AwayTimeTracker.setAutoAuditPopupEnabled(this@AuxiliarySettingsActivity, true)
                    Toast.makeText(this@AuxiliarySettingsActivity, "Screen-off Audit popup re-enabled.", Toast.LENGTH_SHORT).show()
                }
                renderActiveTab()
            }
        }
        val manualNotebookBtn = Button(this).apply {
            text = "📓 Open Notebook"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(46f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener {
                startActivity(Intent(this@AuxiliarySettingsActivity, TimeSplitterActivity::class.java))
            }
        }
        auditBtnRow.addView(togglePopupBtn)
        auditBtnRow.addView(manualNotebookBtn)
        auditCard.addView(auditBtnRow)
        contentContainer.addView(auditCard)

        // 2. FOCUS LAYER SCHEDULE CRITERIA & CALENDAR HOLIDAY LOCK
        addSectionTitle("FOCUS LAYER SCHEDULE CRITERIA & HOLIDAY LOCK", palette)

        buildFocusLayerCriteriaCard(palette)

        // 3. SMART SUGGESTION & BLOCKING CONTROLS
        addSectionTitle("SMART SUGGESTION & BLOCKING CONTROLS", palette)

        addToggleOptionCard(
            title = "Auto-Enforce 30m Suggestions Silently",
            subtitle = "When OFF (Recommended): Home screen lets you block each suggested app individually with a Blue Tick ✓. When ON: automatically blocks all 30m+ apps.",
            checked = UiThemePrefs.isAutoEnforceSuggestions(this),
            palette = palette
        ) { enabled ->
            UiThemePrefs.setAutoEnforceSuggestions(this, enabled)
        }

        val limitCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 16f, isHero = false)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }
        val currentDefaultMins = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
        limitCard.addView(TextView(this).apply {
            text = "Default Daily Cap When Blocking Suggested App: ${currentDefaultMins}m"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        limitCard.addView(TextView(this).apply {
            text = "Choose the daily time limit applied when you block a suggested app from the Home Screen:"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(4f), 0, dp(10f))
        })

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (mins in listOf(15, 30, 45, 60)) {
            val selected = currentDefaultMins == mins
            val btn = TextView(this).apply {
                text = "${mins}m"
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#CBD5E1"))
                setPadding(0, dp(8f), 0, dp(8f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    if (selected) {
                        setColor(palette.accentColor)
                    } else {
                        setColor(Color.parseColor("#1E2438"))
                        setStroke(dp(1f), Color.parseColor("#334155"))
                    }
                }
                setOnClickListener {
                    UiThemePrefs.setDefaultSuggestionLimitMinutes(this@AuxiliarySettingsActivity, mins)
                    renderActiveTab()
                }
            }
            presetRow.addView(btn)
        }
        limitCard.addView(presetRow)
        contentContainer.addView(limitCard)

        // 4. ALL AUXILIARY LAUNCHERS
        addSectionTitle("ALL AUXILIARY FUNCTIONS & MODULES", palette)

        addAuxiliaryLauncherRow(
            iconEmoji = "📚",
            title = "Category Settings & Study Whitelist",
            subtitle = "Classify apps and whitelist study apps from Focus Layer & 30m auto-blocking",
            palette = palette
        ) {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "📓",
            title = "Daily Time Notebook (Offline Audit)",
            subtitle = "Log offline hours, split unallocated time, and record daily notes",
            palette = palette
        ) {
            startActivity(Intent(this, TimeSplitterActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "📊",
            title = "Time Analysis & 7-Day Audit Bar Graph",
            subtitle = "Interactive stacked bar chart & 24-hour breakdown (tap any bar for details)",
            palette = palette
        ) {
            startActivity(Intent(this, AuditSummaryActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "🗓️",
            title = "30-Day Macro Audit Summary",
            subtitle = "Long-term monthly time allocation trends and category totals",
            palette = palette
        ) {
            startActivity(Intent(this, ThirtyDaySummaryActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "💡",
            title = "Discipline Insights & Score",
            subtitle = "Deep analytics on focus streaks, distraction triggers, and efficiency",
            palette = palette
        ) {
            startActivity(Intent(this, InsightsDashboardActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "🏃",
            title = "Physical Activity, Movement & Kilometres",
            subtitle = "Steps, km distance, calories, active minutes & 7-day history breakdowns",
            palette = palette
        ) {
            startActivity(Intent(this, ActivityStatsActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "🤸",
            title = "Calisthenics Progression Lab",
            subtitle = "Track bodyweight exercises, sets, reps, and recovery timers",
            palette = palette
        ) {
            startActivity(Intent(this, CalisthenicsActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "🌐",
            title = "Website Blocker & URL Rules",
            subtitle = "Block distracting domains and keywords inside browsers",
            palette = palette
        ) {
            startActivity(Intent(this, WebsiteBlockActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "📅",
            title = "Calendar Focus & Holiday Planner",
            subtitle = "Set holidays (required to unlock active Focus Layer), plan exams & deep-work days",
            palette = palette
        ) {
            startActivity(Intent(this, CalendarActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "😴",
            title = "Sleep Log & Recovery Tracker",
            subtitle = "Track bedtime, wake time, and sleep consistency",
            palette = palette
        ) {
            startActivity(Intent(this, SleepLogActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "🎨",
            title = "Command Deck Widget Studio & Skins",
            subtitle = "Interactive live widget preview, 4 skins, Dual Neon Rings calibrator & 1-tap pin",
            palette = palette
        ) {
            pinHomeWidget()
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "🎯",
            title = "Deep Focus Studio",
            subtitle = "Procedural soundscapes (40Hz Gamma), Pomodoro timer, and cognitive friction gate",
            palette = palette
        ) {
            startActivity(Intent(this, FocusSessionActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "📥",
            title = "Export Personal Data (CSV & JSON)",
            subtitle = "Local-first private data sovereignty: 1-tap export of sessions, rules, and workouts",
            palette = palette
        ) {
            val options = arrayOf("Export Complete JSON Backup", "Export Sessions CSV (Spreadsheet)")
            AlertDialog.Builder(this)
                .setTitle("Export Focus Data")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> DataExportManager.exportAndShare(this, DataExportManager.ExportFormat.JSON)
                        1 -> DataExportManager.exportAndShare(this, DataExportManager.ExportFormat.CSV)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "💾",
            title = "Backup & Restore Data",
            subtitle = "Export or import your full database, audit logs, and blocklist rules",
            palette = palette
        ) {
            startActivity(Intent(this, BackupRestoreActivity::class.java))
        }

        addAuxiliaryLauncherRow(
            iconEmoji = "🛡️",
            title = "Deactivate Shield / Emergency Cooldown",
            subtitle = "Protected by Physical Proof-of-Effort Gate during active focus hours",
            palette = palette
        ) {
            if (FocusLayerManager.isLockedUntilCalendarHoliday(this)) {
                CognitiveFrictionGate.show(
                    context = this,
                    title = "Access Shield Deactivation",
                    reasonPrompt = "Fortress Lock is active. Complete physical proof-of-effort (25 verified steps or 10 squats) to open Shield Deactivation."
                ) {
                    startActivity(Intent(this, DeactivationActivity::class.java))
                }
            } else {
                startActivity(Intent(this, DeactivationActivity::class.java))
            }
        }
    }

    /**
     * Helper that runs [onAuthorized] immediately if outside active Fortress Lock
     * (or inside the 30m Pre-Window Grace Period), or gates the action behind
     * Physical Proof-of-Effort when Fortress Lock is active.
     */
    private fun runWithFortressGateIfLocked(
        actionTitle: String,
        onCancelRevert: (() -> Unit)? = null,
        onAuthorized: () -> Unit
    ) {
        if (FocusLayerManager.isLockedUntilCalendarHoliday(this)) {
            onCancelRevert?.invoke()
            CognitiveFrictionGate.show(
                context = this,
                title = actionTitle,
                reasonPrompt = "Fortress Lock is active (${FocusLayerManager.formatWindowSummary(this)}). Complete physical proof-of-effort (25 steps or 10 squats) + commitment phrase to authorize '$actionTitle'."
            ) {
                onAuthorized()
            }
        } else {
            onAuthorized()
        }
    }

    /**
     * Builds the customizable Focus Layer Criteria card (hours, days, schedule toggle)
     * with strict enforcement of the Fortress Lock, 30-Minute Pre-Window Grace Period,
     * and 24-Hour Advance Calendar Holiday Rule.
     */
    private fun buildFocusLayerCriteriaCard(palette: UiThemePrefs.ThemePalette) {
        val exemptionDetails = FocusLayerManager.getCalendarExemptionDetails(this)
        val isHolidayToday = FocusLayerManager.isHolidayToday(this)
        val isLockedNow = FocusLayerManager.isLockedUntilCalendarHoliday(this)
        val isStrictLock = FocusLayerManager.isStrictLockEnabled(this)
        val scheduleEnabled = FocusLayerManager.isScheduleEnabled(this)
        val isScheduledNow = FocusLayerManager.isScheduledActiveNow(this)
        val graceMins = FocusLayerManager.getPreWindowGraceRemainingMinutes(this)
        val sameDayRejection = FocusLayerManager.getSameDayHolidayRejectionNotice(this)
        val clockTampered = SettingsTamperGuard.isClockTamperActive(this)
        val startMins = FocusLayerManager.getStartMinutes(this)
        val endMins = FocusLayerManager.getEndMinutes(this)
        val daysMode = FocusLayerManager.getActiveDaysMode(this)
        val customDays = FocusLayerManager.getCustomDays(this)
        val currentDeviceTime = FocusLayerManager.getCurrentDeviceTimeFormatted(FocusLayerManager.verifiedNowMs(this))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 16f, isHero = false)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }

        // 1. LIVE DEVICE TIME & ACTIVE STATUS BANNER
        val statusHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10f))
        }

        val statusColor = when {
            exemptionDetails != null -> Color.parseColor("#38BDF8")
            graceMins > 0 -> Color.parseColor("#38BDF8")
            isLockedNow -> Color.parseColor("#F59E0B")
            isScheduledNow -> Color.parseColor("#34D399")
            else -> Color.parseColor("#94A3B8")
        }

        val liveTimeView = TextView(this).apply {
            text = if (clockTampered) {
                "🛡️ Monotonic Hardware Clock: $currentDeviceTime (Device Clock Drift Blocked)"
            } else {
                "🕒 Verified Monotonic Clock: $currentDeviceTime"
            }
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (clockTampered) Color.parseColor("#FCA5A5") else palette.secondaryColor)
        }
        statusHeaderRow.addView(liveTimeView)

        val statusLabelView = TextView(this).apply {
            text = when {
                exemptionDetails != null -> "$exemptionDetails • Focus Layer Paused"
                graceMins > 0 -> "⚡ Pre-Window Grace Period Open ($graceMins min left to edit freely)"
                !scheduleEnabled -> "⚪ Focus Schedule Disabled (Flexible Manual Mode)"
                isLockedNow -> "🔒 Fortress Lock Engaged ($currentDeviceTime • Proof-of-Effort Gated)"
                isScheduledNow -> "🟢 Focus Window Active Right Now ($currentDeviceTime)"
                else -> "⚪ Inactive Right Now (Outside Focus Window — Edits Open)"
            }
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(statusColor)
            setPadding(0, dp(2f), 0, dp(2f))
        }
        statusHeaderRow.addView(statusLabelView)

        val criteriaSummaryView = TextView(this).apply {
            text = "Current Focus Window: ${FocusLayerManager.formatWindowSummary(this@AuxiliarySettingsActivity)}"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        statusHeaderRow.addView(criteriaSummaryView)

        if (sameDayRejection != null) {
            statusHeaderRow.addView(TextView(this).apply {
                text = sameDayRejection
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#FCA5A5"))
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(Color.parseColor("#450A0A"))
                    setStroke(dp(1f), Color.parseColor("#EF4444"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
            })
        }

        card.addView(statusHeaderRow)

        // 2. SCHEDULE ENABLE / DISABLE SWITCH
        val enableRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(10f))
        }
        val enableTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        enableTextCol.addView(TextView(this).apply {
            text = "Enable Focus Layer Schedule"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        enableTextCol.addView(TextView(this).apply {
            text = "Automatically blocks distraction apps during configured focus hours and days"
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        enableRow.addView(enableTextCol)

        val enableSwitch = Switch(this).apply {
            isChecked = scheduleEnabled
            setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) {
                    runWithFortressGateIfLocked(
                        actionTitle = "Disable Focus Schedule",
                        onCancelRevert = { renderActiveTab() }
                    ) {
                        FocusLayerManager.updateFocusScheduleCriteria(
                            this@AuxiliarySettingsActivity,
                            false,
                            startMins,
                            endMins,
                            daysMode
                        )
                        Toast.makeText(this@AuxiliarySettingsActivity, "Focus schedule disabled", Toast.LENGTH_SHORT).show()
                        renderActiveTab()
                    }
                } else {
                    FocusLayerManager.updateFocusScheduleCriteria(
                        this@AuxiliarySettingsActivity,
                        true,
                        startMins,
                        endMins,
                        daysMode
                    )
                    Toast.makeText(this@AuxiliarySettingsActivity, "Focus schedule enabled", Toast.LENGTH_SHORT).show()
                    renderActiveTab()
                }
            }
        }
        enableRow.addView(enableSwitch)
        card.addView(enableRow)

        // 3. QUICK SCHEDULE PRESETS (6 OPTIONS)
        card.addView(TextView(this).apply {
            text = "Quick Schedule Presets:"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(4f), 0, dp(6f))
        })

        data class FocusPreset(val title: String, val range: String, val s: Int, val e: Int)
        val presets = listOf(
            FocusPreset("Classic", "9AM–6PM", 540, 1080),
            FocusPreset("Morning", "6AM–12PM", 360, 720),
            FocusPreset("Student", "8AM–4PM", 480, 960),
            FocusPreset("Evening", "6PM–10PM", 1080, 1320),
            FocusPreset("Night", "10PM–6AM", 1320, 360),
            FocusPreset("All Day", "8AM–8PM", 480, 1200)
        )

        fun createPresetChip(p: FocusPreset): View {
            val selected = scheduleEnabled && startMins == p.s && endMins == p.e
            return TextView(this).apply {
                text = "${p.title}\n${p.range}"
                textSize = 11f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#CBD5E1"))
                minHeight = dp(48f)
                setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    if (selected) {
                        setColor(palette.accentColor)
                    } else {
                        setColor(Color.parseColor("#1E2438"))
                        setStroke(dp(1f), Color.parseColor("#334155"))
                    }
                }
                setOnClickListener {
                    runWithFortressGateIfLocked(actionTitle = "Change Preset to ${p.title}") {
                        FocusLayerManager.updateFocusScheduleCriteria(
                            this@AuxiliarySettingsActivity,
                            true,
                            p.s,
                            p.e,
                            daysMode
                        )
                        Toast.makeText(this@AuxiliarySettingsActivity, "Set focus hours to ${p.title} (${p.range})", Toast.LENGTH_SHORT).show()
                        renderActiveTab()
                    }
                }
            }
        }

        val gridRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) }
        }
        val gridRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12f) }
        }
        for (i in 0..2) gridRow1.addView(createPresetChip(presets[i]))
        for (i in 3..5) gridRow2.addView(createPresetChip(presets[i]))
        card.addView(gridRow1)
        card.addView(gridRow2)

        // 4. CUSTOM START & END TIME PICKERS
        card.addView(TextView(this).apply {
            text = "Custom Focus Time Window (Editable in 30m Grace Period or outside hours):"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, dp(6f))
        })

        val customTimeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        }

        val startBtn = Button(this).apply {
            text = "⏰ Start: ${FocusLayerManager.formatMinutesOfDay(startMins)}"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(44f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f) }
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1f), palette.accentColor)
            }
            setOnClickListener {
                runWithFortressGateIfLocked(actionTitle = "Edit Focus Start Time") {
                    TimePickerDialog(
                        this@AuxiliarySettingsActivity,
                        { _, hourOfDay, minute ->
                            val newStart = hourOfDay * 60 + minute
                            FocusLayerManager.updateFocusScheduleCriteria(
                                this@AuxiliarySettingsActivity,
                                true,
                                newStart,
                                endMins,
                                daysMode
                            )
                            Toast.makeText(this@AuxiliarySettingsActivity, "Start time set to ${FocusLayerManager.formatMinutesOfDay(newStart)}", Toast.LENGTH_SHORT).show()
                            renderActiveTab()
                        },
                        startMins / 60,
                        startMins % 60,
                        false
                    ).show()
                }
            }
        }

        val endBtn = Button(this).apply {
            text = "⏰ End: ${FocusLayerManager.formatMinutesOfDay(endMins)}"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(44f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1f), palette.accentColor)
            }
            setOnClickListener {
                runWithFortressGateIfLocked(actionTitle = "Edit Focus End Time") {
                    TimePickerDialog(
                        this@AuxiliarySettingsActivity,
                        { _, hourOfDay, minute ->
                            val newEnd = hourOfDay * 60 + minute
                            FocusLayerManager.updateFocusScheduleCriteria(
                                this@AuxiliarySettingsActivity,
                                true,
                                startMins,
                                newEnd,
                                daysMode
                            )
                            Toast.makeText(this@AuxiliarySettingsActivity, "End time set to ${FocusLayerManager.formatMinutesOfDay(newEnd)}", Toast.LENGTH_SHORT).show()
                            renderActiveTab()
                        },
                        endMins / 60,
                        endMins % 60,
                        false
                    ).show()
                }
            }
        }
        customTimeRow.addView(startBtn)
        customTimeRow.addView(endBtn)
        card.addView(customTimeRow)

        // 5. ACTIVE DAYS SELECTOR (QUICK MODES + INDIVIDUAL DAY PILLS)
        card.addView(TextView(this).apply {
            text = "Active Days (Mode: ${FocusLayerManager.getActiveDaysLabel(this@AuxiliarySettingsActivity)}):"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, dp(6f))
        })

        val dayModesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        val dayModes = listOf(
            FocusLayerManager.DAYS_MON_SAT to "Mon – Sat",
            FocusLayerManager.DAYS_MON_FRI to "Mon – Fri",
            FocusLayerManager.DAYS_EVERY_DAY to "Every Day"
        )
        for ((modeVal, modeLabel) in dayModes) {
            val selected = daysMode == modeVal
            val btn = TextView(this).apply {
                text = modeLabel
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#CBD5E1"))
                setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    if (selected) {
                        setColor(palette.accentColor)
                    } else {
                        setColor(Color.parseColor("#1E2438"))
                        setStroke(dp(1f), Color.parseColor("#334155"))
                    }
                }
                setOnClickListener {
                    runWithFortressGateIfLocked(actionTitle = "Change Active Days to $modeLabel") {
                        FocusLayerManager.updateFocusScheduleCriteria(
                            this@AuxiliarySettingsActivity,
                            scheduleEnabled,
                            startMins,
                            endMins,
                            modeVal
                        )
                        renderActiveTab()
                    }
                }
            }
            dayModesRow.addView(btn)
        }
        card.addView(dayModesRow)

        // Individual Day Pills Row: Mon, Tue, Wed, Thu, Fri, Sat, Sun
        val individualDaysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        }
        val weekDaysList = listOf(
            Calendar.MONDAY to "Mon",
            Calendar.TUESDAY to "Tue",
            Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu",
            Calendar.FRIDAY to "Fri",
            Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun"
        )
        for ((calDay, dayName) in weekDaysList) {
            val isDayActive = if (daysMode == FocusLayerManager.DAYS_CUSTOM) {
                customDays.contains(calDay)
            } else {
                when (daysMode) {
                    FocusLayerManager.DAYS_MON_FRI -> calDay != Calendar.SATURDAY && calDay != Calendar.SUNDAY
                    FocusLayerManager.DAYS_EVERY_DAY -> true
                    else -> calDay != Calendar.SUNDAY
                }
            }
            val pill = TextView(this).apply {
                text = dayName
                textSize = 11f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isDayActive) Color.WHITE else Color.parseColor("#64748B"))
                setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    if (isDayActive) {
                        setColor(palette.accentColor)
                    } else {
                        setColor(Color.parseColor("#111827"))
                        setStroke(dp(1f), Color.parseColor("#1F2937"))
                    }
                }
                setOnClickListener {
                    runWithFortressGateIfLocked(actionTitle = "Toggle $dayName Schedule") {
                        val toggled = FocusLayerManager.toggleCustomDay(this@AuxiliarySettingsActivity, calDay)
                        Toast.makeText(
                            this@AuxiliarySettingsActivity,
                            "$dayName ${if (toggled) "added to" else "removed from"} Focus schedule",
                            Toast.LENGTH_SHORT
                        ).show()
                        renderActiveTab()
                    }
                }
            }
            individualDaysRow.addView(pill)
        }
        card.addView(individualDaysRow)

        // 6. STRICT FORTRESS LOCK MODE TOGGLE (ON BY DEFAULT)
        val strictRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(10f))
        }
        val strictTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        strictTextCol.addView(TextView(this).apply {
            text = "Strict Fortress Lock (Default ON)"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        strictTextCol.addView(TextView(this).apply {
            text = if (isStrictLock) {
                "ON: Free edits in 30m Pre-Window Grace Period; mid-session edits require Physical Proof-of-Effort"
            } else {
                "OFF: Flexible mode — you can customize hours & days anytime"
            }
            textSize = 11.5f
            setTextColor(if (isStrictLock) Color.parseColor("#F59E0B") else Color.parseColor("#94A3B8"))
        })
        strictRow.addView(strictTextCol)

        val strictSwitch = Switch(this).apply {
            isChecked = isStrictLock
            setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) {
                    runWithFortressGateIfLocked(
                        actionTitle = "Turn Off Strict Fortress Lock",
                        onCancelRevert = { renderActiveTab() }
                    ) {
                        FocusLayerManager.setStrictLockEnabled(this@AuxiliarySettingsActivity, false)
                        Toast.makeText(
                            this@AuxiliarySettingsActivity,
                            "🔓 Strict Fortress Lock disabled via Proof-of-Effort",
                            Toast.LENGTH_SHORT
                        ).show()
                        renderActiveTab()
                    }
                } else {
                    FocusLayerManager.setStrictLockEnabled(this@AuxiliarySettingsActivity, true)
                    Toast.makeText(
                        this@AuxiliarySettingsActivity,
                        "🔒 Strict Fortress Lock enabled",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderActiveTab()
                }
            }
        }
        strictRow.addView(strictSwitch)
        card.addView(strictRow)

        // 7. ADAPTIVE BREAK & CALENDAR EVENT ACTION ROW
        val breakRemaining = FocusLayerManager.getAdaptiveBreakRemainingMinutes(this)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }

        if (breakRemaining > 0) {
            val breakActiveBtn = Button(this).apply {
                text = "☕ Break ($breakRemaining m) • Resume"
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                minHeight = dp(44f)
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Color.parseColor("#0369A1"))
                    setStroke(dp(1f), Color.parseColor("#38BDF8"))
                }
                setOnClickListener {
                    FocusLayerManager.cancelAdaptiveBreak(this@AuxiliarySettingsActivity)
                    Toast.makeText(this@AuxiliarySettingsActivity, "Adaptive break ended. Focus Layer resumed.", Toast.LENGTH_SHORT).show()
                    renderActiveTab()
                }
            }
            actionsRow.addView(breakActiveBtn)
        } else {
            val takeBreakBtn = Button(this).apply {
                text = if (isLockedNow) "🏋️ 15m Break (Proof)" else "☕ Take 15m Break"
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                minHeight = dp(44f)
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1f), palette.accentColor)
                }
                setOnClickListener {
                    runWithFortressGateIfLocked(actionTitle = "Unlock 15-Minute Adaptive Break") {
                        FocusLayerManager.grantAdaptiveBreak(this@AuxiliarySettingsActivity, 15)
                        Toast.makeText(this@AuxiliarySettingsActivity, "15-minute adaptive break granted.", Toast.LENGTH_SHORT).show()
                        renderActiveTab()
                    }
                }
            }
            actionsRow.addView(takeBreakBtn)
        }

        val calendarHolidayBtn = Button(this).apply {
            text = if (isHolidayToday) "📅 Edit in Calendar" else "📅 Plan Holiday (≥24h)"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(44f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1f), statusColor)
            }
            setOnClickListener {
                openTodayInCalendarEditor()
            }
        }
        actionsRow.addView(calendarHolidayBtn)
        card.addView(actionsRow)

        // 8. ADAPTIVE CALENDAR & EVENT SYNC TOGGLE ROW
        val syncRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, 0)
        }
        val syncCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        syncCol.addView(TextView(this).apply {
            text = "24h Advance Calendar Holiday Sync"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        syncCol.addView(TextView(this).apply {
            text = "Relaxes focus ONLY for holidays & events scheduled at least 24 hours in advance (prevents same-day self-exemption)"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        syncRow.addView(syncCol)

        val syncSwitch = Switch(this).apply {
            isChecked = FocusLayerManager.isAdaptiveCalendarSyncEnabled(this@AuxiliarySettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                FocusLayerManager.setAdaptiveCalendarSyncEnabled(this@AuxiliarySettingsActivity, isChecked)
                Toast.makeText(
                    this@AuxiliarySettingsActivity,
                    if (isChecked) "24h advance calendar sync enabled" else "Adaptive calendar sync disabled",
                    Toast.LENGTH_SHORT
                ).show()
                renderActiveTab()
            }
        }
        syncRow.addView(syncSwitch)
        card.addView(syncRow)

        contentContainer.addView(card)
    }

    private fun openTodayInCalendarEditor() {
        try {
            val intent = Intent(this, CalendarDayEditorActivity::class.java).apply {
                putExtra(CalendarDayEditorActivity.EXTRA_DATE_KEY, FocusLayerManager.todayKey())
            }
            startActivity(intent)
        } catch (_: Throwable) {
            startActivity(Intent(this, CalendarActivity::class.java))
        }
    }

    private fun addToggleOptionCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        palette: UiThemePrefs.ThemePalette,
        onToggle: (Boolean) -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 14f, isHero = false)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12f)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(3f), 0, 0)
        })
        card.addView(textCol)

        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        card.addView(sw)
        contentContainer.addView(card)
    }

    private fun addAuxiliaryLauncherRow(
        iconEmoji: String,
        title: String,
        subtitle: String,
        palette: UiThemePrefs.ThemePalette,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 14f, isHero = false)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
            setOnClickListener { onClick() }
        }

        val iconBox = TextView(this).apply {
            text = iconEmoji
            textSize = 20f
            gravity = Gravity.CENTER
            background = UiThemePrefs.createAccentCircleBackground(this@AuxiliarySettingsActivity)
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                marginEnd = dp(14f)
            }
        }
        row.addView(iconBox)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2f), 0, 0)
        })
        row.addView(textCol)

        val arrow = TextView(this).apply {
            text = "→"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            setPadding(dp(8f), 0, dp(4f), 0)
        }
        row.addView(arrow)

        contentContainer.addView(row)
    }

    // =========================================================================
    // TAB 3: INSPIRE ME — "FOCUS ALCHEMY & FRACTAL LIFE-TIME HORIZON"
    // =========================================================================

    private fun buildInspireMeTab() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        var reclaimedHoursPerDay = UiThemePrefs.getCovenantHours(this)

        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 18f, isHero = true)
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }
        heroCard.addView(TextView(this).apply {
            text = "✨ FOCUS ALCHEMY • THE UNEXPECTED SANCTUARY"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
        })
        heroCard.addView(TextView(this).apply {
            text = "\"You do not merely block apps. You sculpt decades of human potential.\""
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD_ITALIC)
            setTextColor(Color.WHITE)
            setPadding(0, dp(6f), 0, dp(6f))
        })
        heroCard.addView(TextView(this).apply {
            text = "Watch your Fractal Tree of Discipline grow in real time as you reclaim hours from mindless scrolling, reset dopamine cravings in 60 seconds, and seal your Architect of Time Covenant."
            textSize = 12.5f
            setTextColor(Color.parseColor("#CBD5E1"))
        })
        contentContainer.addView(heroCard)

        val fractalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 18f, isHero = false)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }

        fractalCard.addView(TextView(this).apply {
            text = "🌳 THE LIVING FRACTAL TREE OF RECLAIMED TIME"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accentColor)
        })

        val fractalView = FractalDisciplineTreeView(this, palette, reclaimedHoursPerDay).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(270f)
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(10f)
            }
        }
        fractalCard.addView(fractalView)

        val statsHeadline = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val statsSubline = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#A7F3D0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, dp(12f))
            setLineSpacing(0f, 1.25f)
        }

        fun updateFractalMetrics(hours: Float) {
            val yearlyHours = (hours * 365f).roundToInt()
            val wakingDaysPerYear = (yearlyHours / 16f * 10f).roundToInt() / 10f
            val tenYearHours = yearlyHours * 10
            val booksEquivalent = (yearlyHours / 6f).roundToInt()
            statsHeadline.text = "Reclaiming ${String.format("%.1f", hours)} hrs/day = $yearlyHours Hours / Year"
            statsSubline.text = "• +$wakingDaysPerYear FULL 16-hour waking days added to your life every single year\n" +
                "• $tenYearHours hours over 10 years (~${booksEquivalent} books/yr or 1 world-class mastery)"
            fractalView.setReclaimedHours(hours)
        }
        updateFractalMetrics(reclaimedHoursPerDay)

        fractalCard.addView(statsHeadline)
        fractalCard.addView(statsSubline)

        val sliderLabel = TextView(this).apply {
            text = "Slide to simulate daily hours rescued from distraction:"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        fractalCard.addView(sliderLabel)

        val seekBar = SeekBar(this).apply {
            max = 55
            progress = ((reclaimedHoursPerDay - 0.5f) * 10f).roundToInt().coerceIn(0, 55)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    reclaimedHoursPerDay = 0.5f + (progress / 10f)
                    updateFractalMetrics(reclaimedHoursPerDay)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        fractalCard.addView(seekBar)

        val isSealed = UiThemePrefs.isCovenantSealed(this)
        val covenantBtn = Button(this).apply {
            text = if (isSealed) {
                "⚡ Covenant Sealed (${String.format("%.1f", reclaimedHoursPerDay)}h/day) — Tap to Update Badge"
            } else {
                "⚡ Seal My 'Architect of Time' Covenant (Adds Home Screen Badge)"
            }
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(palette.accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46f)
            ).apply { topMargin = dp(12f) }
            setOnClickListener {
                UiThemePrefs.setCovenantSealed(this@AuxiliarySettingsActivity, true, reclaimedHoursPerDay)
                text = "✓ Covenant Sealed! (+${(reclaimedHoursPerDay * 365 / 16).roundToInt()} Waking Days/Yr Badge Active on Home)"
                Toast.makeText(
                    this@AuxiliarySettingsActivity,
                    "Architect of Time Covenant Sealed! Check your Main Home Screen header.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        fractalCard.addView(covenantBtn)
        contentContainer.addView(fractalCard)

        val breathCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = UiThemePrefs.createCardBackground(this@AuxiliarySettingsActivity, cornerDp = 18f, isHero = false)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
        }
        breathCard.addView(TextView(this).apply {
            text = "🧘 60-SECOND NEURO-RESET BREATH PACER"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accentColor)
        })
        breathCard.addView(TextView(this).apply {
            text = "Feeling the urge to unlock a blocked app? A 16-second box-breath cycle resets prefrontal cortex control."
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(4f), 0, dp(8f))
        })

        val breathOrbView = NeuroBreathOrbView(this, palette).apply {
            layoutParams = LinearLayout.LayoutParams(dp(180f), dp(180f))
        }
        breathCard.addView(breathOrbView)

        val breathBtn = Button(this).apply {
            text = "Start Neuro-Reset Breathing Pulse"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1f), palette.accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44f)
            ).apply { topMargin = dp(8f) }
            setOnClickListener {
                if (breathAnimator?.isRunning == true) {
                    breathAnimator?.cancel()
                    breathOrbView.setPhase(0f, "Tap Start Below")
                    text = "Start Neuro-Reset Breathing Pulse"
                } else {
                    text = "Stop Breathing Pacer"
                    startBreathingAnimation(breathOrbView)
                }
            }
        }
        breathCard.addView(breathBtn)
        contentContainer.addView(breathCard)
    }

    private fun startBreathingAnimation(orbView: NeuroBreathOrbView) {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, 16f).apply {
            duration = 16000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val (scale, label) = when {
                    t < 4f -> (t / 4f) to "INHALE SLOWLY • ${(4 - t.toInt()).coerceAtLeast(1)}s"
                    t < 8f -> 1f to "HOLD FOCUS • ${(8 - t.toInt()).coerceAtLeast(1)}s"
                    t < 12f -> (1f - (t - 8f) / 4f) to "EXHALE URGE • ${(12 - t.toInt()).coerceAtLeast(1)}s"
                    else -> 0f to "STILLNESS • ${(16 - t.toInt()).coerceAtLeast(1)}s"
                }
                orbView.setPhase(scale, label)
            }
            start()
        }
    }

    private fun addSectionTitle(title: String, palette: UiThemePrefs.ThemePalette) {
        val tv = TextView(this).apply {
            text = title
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accentColor)
            letterSpacing = 0.06f
            setPadding(dp(2f), dp(6f), dp(2f), dp(8f))
        }
        contentContainer.addView(tv)
    }

    // =========================================================================
    // PERMISSION & WIDGET HELPERS
    // =========================================================================

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

    private fun isDeviceAdminActive(): Boolean {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
            dpm.isAdminActive(adminComponent)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestDeviceAdmin() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
            if (!dpm.isAdminActive(adminComponent)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required to prevent unauthorized uninstallation during active focus hours."
                    )
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "✓ Device Admin Lock is already active!", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }

    private fun pinHomeWidget() {
        startActivity(Intent(this, WidgetStudioActivity::class.java))
    }

    // =========================================================================
    // CUSTOM CANVAS VIEW 1: SETUP TREE + READINESS BAR GRAPH (4-STEP ORDER)
    // =========================================================================

    private class SetupTreeAndBarGraphView(
        context: Context,
        private val palette: UiThemePrefs.ThemePalette,
        private val usageReady: Boolean,
        private val criteriaReady: Boolean,
        private val criteriaCount: Int,
        private val scheduleReady: Boolean,
        private val shieldReady: Boolean,
        private val adminReady: Boolean,
        private val batteryReady: Boolean
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textAlign = Paint.Align.CENTER
        }
        private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
        }
        private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val green = Color.parseColor("#22C55E")
            val amber = Color.parseColor("#F59E0B")

            val rootWidth = w * 0.66f
            val rootHeight = 40f * density
            val rootRect = RectF(
                (w - rootWidth) / 2f,
                8f * density,
                (w + rootWidth) / 2f,
                8f * density + rootHeight
            )

            boxPaint.color = palette.heroTopColor
            canvas.drawRoundRect(rootRect, 12f * density, 12f * density, boxPaint)
            strokePaint.color = palette.accentColor
            canvas.drawRoundRect(rootRect, 12f * density, 12f * density, strokePaint)

            textPaint.textSize = 12f * density
            canvas.drawText(
                "⚡ FOCUS ELITE 4-STEP SETUP FLOW",
                rootRect.centerX(),
                rootRect.centerY() + 4f * density,
                textPaint
            )

            val branchY = 82f * density
            val branchH = 54f * density
            val gap = 6f * density
            val branchW = (w - gap * 5f) / 4f

            val step4Ready = shieldReady && adminReady
            data class TreeNode(val title: String, val sub: String, val ready: Boolean)
            val nodes = listOf(
                TreeNode("1. Usage", if (usageReady) "✓ Granted" else "⚡ Step 1", usageReady),
                TreeNode("2. Criteria", if (criteriaReady) "✓ $criteriaCount Apps" else "⚡ Step 2", criteriaReady),
                TreeNode("3. Schedule", if (scheduleReady) "✓ Set" else "⚡ Step 3", scheduleReady),
                TreeNode("4. Shields", if (step4Ready) "✓ Locked" else "⚡ Step 4", step4Ready)
            )

            nodes.forEachIndexed { i, node ->
                val left = gap + i * (branchW + gap)
                val rect = RectF(left, branchY, left + branchW, branchY + branchH)

                val path = Path().apply {
                    moveTo(rootRect.centerX(), rootRect.bottom)
                    cubicTo(
                        rootRect.centerX(),
                        (rootRect.bottom + rect.top) / 2f,
                        rect.centerX(),
                        (rootRect.bottom + rect.top) / 2f,
                        rect.centerX(),
                        rect.top
                    )
                }
                linePaint.color = if (node.ready) green else palette.accentColor
                canvas.drawPath(path, linePaint)

                boxPaint.color = Color.parseColor("#161F33")
                canvas.drawRoundRect(rect, 10f * density, 10f * density, boxPaint)
                strokePaint.color = if (node.ready) green else amber
                canvas.drawRoundRect(rect, 10f * density, 10f * density, strokePaint)

                textPaint.textSize = 10.5f * density
                textPaint.color = Color.WHITE
                canvas.drawText(node.title, rect.centerX(), rect.top + 21f * density, textPaint)

                subTextPaint.textSize = 9.5f * density
                subTextPaint.color = if (node.ready) green else amber
                canvas.drawText(node.sub, rect.centerX(), rect.top + 39f * density, subTextPaint)
            }

            val leafTop = branchY + branchH + 22f * density
            val leafH = 38f * density
            val leafRect = RectF(w * 0.03f, leafTop, w * 0.97f, leafTop + leafH)

            nodes.forEachIndexed { i, node ->
                val cx = gap + i * (branchW + gap) + branchW / 2f
                linePaint.color = if (node.ready) green else Color.parseColor("#334155")
                canvas.drawLine(cx, branchY + branchH, leafRect.centerX(), leafRect.top, linePaint)
            }

            val step1Score = if (usageReady) 100 else 15
            val step2Score = if (criteriaReady) 100 else 25
            val step3Score = if (scheduleReady) 100 else 50
            val step4Score = (if (shieldReady) 50 else 0) + (if (adminReady) 35 else 0) + (if (batteryReady) 15 else 0)
            val totalScore = (
                (if (usageReady) 100 else 0) +
                    (if (criteriaReady) 100 else 0) +
                    (if (scheduleReady) 100 else 0) +
                    step4Score
                ) / 4

            boxPaint.color = Color.parseColor("#131C2E")
            canvas.drawRoundRect(leafRect, 12f * density, 12f * density, boxPaint)
            strokePaint.color = if (totalScore >= 85) green else palette.accentColor
            canvas.drawRoundRect(leafRect, 12f * density, 12f * density, strokePaint)

            val summaryText = "✓ System Apps Safe • Setup Readiness: $totalScore%"
            var fittedSize = 11.5f * density
            textPaint.textSize = fittedSize
            val maxAllowedWidth = leafRect.width() - 20f * density
            while (textPaint.measureText(summaryText) > maxAllowedWidth && fittedSize > 8.5f * density) {
                fittedSize -= 0.5f * density
                textPaint.textSize = fittedSize
            }
            textPaint.color = Color.WHITE
            canvas.drawText(
                summaryText,
                leafRect.centerX(),
                leafRect.centerY() + 4f * density,
                textPaint
            )

            val chartTitleY = leafRect.bottom + 24f * density
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 11.5f * density
            textPaint.color = palette.secondaryColor
            canvas.drawText("📊 4-STEP SETUP READINESS BAR GRAPH", 8f * density, chartTitleY, textPaint)

            data class BarItem(val label: String, val percent: Int, val color: Int)
            val bars = listOf(
                BarItem("1. Usage Access", step1Score, if (usageReady) green else amber),
                BarItem("2. App Criteria", step2Score, if (criteriaReady) green else amber),
                BarItem("3. Focus Schedule", step3Score, if (scheduleReady) green else amber),
                BarItem("4. Admin & Shield", step4Score.coerceAtLeast(15), if (step4Score >= 85) green else amber),
                BarItem("Total Readiness", totalScore.coerceAtLeast(15), palette.accentColor)
            )

            val startY = chartTitleY + 14f * density
            val rowH = 34f * density
            val labelW = w * 0.34f
            val barLeft = labelW + 8f * density
            val barRightMax = w - 48f * density
            val barMaxW = (barRightMax - barLeft).coerceAtLeast(40f)

            bars.forEachIndexed { index, item ->
                val y = startY + index * rowH
                val barTop = y + 6f * density
                val barBottom = y + 22f * density

                subTextPaint.textAlign = Paint.Align.LEFT
                subTextPaint.textSize = 11f * density
                subTextPaint.color = Color.parseColor("#E2E8F0")
                canvas.drawText(item.label, 8f * density, barBottom - 3f * density, subTextPaint)

                val bgRect = RectF(barLeft, barTop, barRightMax, barBottom)
                canvas.drawRoundRect(bgRect, 8f * density, 8f * density, barBgPaint)

                val fillW = barMaxW * (item.percent / 100f)
                val fillRect = RectF(barLeft, barTop, barLeft + fillW, barBottom)
                barFillPaint.color = item.color
                canvas.drawRoundRect(fillRect, 8f * density, 8f * density, barFillPaint)

                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.textSize = 11f * density
                textPaint.color = item.color
                canvas.drawText("${item.percent}%", w - 6f * density, barBottom - 3f * density, textPaint)
            }

            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    // =========================================================================
    // CUSTOM CANVAS VIEW 2: LIVING FRACTAL TREE OF DISCIPLINE
    // =========================================================================

    private class FractalDisciplineTreeView(
        context: Context,
        private val palette: UiThemePrefs.ThemePalette,
        private var reclaimedHours: Float
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val branchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        fun setReclaimedHours(hours: Float) {
            reclaimedHours = hours.coerceIn(0.5f, 6.0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val bgRect = RectF(0f, 0f, w, h)
            groundPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor("#090D18"),
                palette.cardSurfaceColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(bgRect, 14f * density, 14f * density, groundPaint)
            groundPaint.shader = null

            glowPaint.color = Color.argb(
                45,
                Color.red(palette.accentColor),
                Color.green(palette.accentColor),
                Color.blue(palette.accentColor)
            )
            canvas.drawCircle(w / 2f, h * 0.88f, w * 0.34f, glowPaint)

            val normalized = ((reclaimedHours - 0.5f) / 5.5f).coerceIn(0f, 1f)
            val maxDepth = (4 + (normalized * 5f).roundToInt()).coerceIn(4, 9)
            val trunkLen = h * (0.18f + normalized * 0.08f)
            val spreadAngle = 20f + normalized * 12f

            drawBranch(
                canvas = canvas,
                x1 = w / 2f,
                y1 = h - 18f * density,
                length = trunkLen,
                angleDeg = -90f,
                depth = maxDepth,
                maxDepth = maxDepth,
                spread = spreadAngle
            )
        }

        private fun drawBranch(
            canvas: Canvas,
            x1: Float,
            y1: Float,
            length: Float,
            angleDeg: Float,
            depth: Int,
            maxDepth: Int,
            spread: Float
        ) {
            if (depth <= 0 || length < 2f) return

            val rad = Math.toRadians(angleDeg.toDouble())
            val x2 = x1 + (cos(rad) * length).toFloat()
            val y2 = y1 + (sin(rad) * length).toFloat()

            val progress = 1f - (depth.toFloat() / maxDepth.toFloat())
            branchPaint.strokeWidth = (depth * 1.35f * density).coerceAtLeast(1.2f * density)
            branchPaint.color = if (depth > 3) {
                palette.accentColor
            } else {
                Color.parseColor("#34D399")
            }

            canvas.drawLine(x1, y1, x2, y2, branchPaint)

            if (depth <= 2) {
                glowPaint.color = if (depth == 1) {
                    Color.parseColor("#FDE047")
                } else {
                    palette.secondaryColor
                }
                canvas.drawCircle(x2, y2, (3.2f - depth * 0.6f) * density, glowPaint)
            }

            val nextLen = length * (0.72f + progress * 0.04f)
            drawBranch(canvas, x2, y2, nextLen, angleDeg - spread, depth - 1, maxDepth, spread)
            drawBranch(canvas, x2, y2, nextLen, angleDeg + spread, depth - 1, maxDepth, spread)
        }
    }

    // =========================================================================
    // CUSTOM CANVAS VIEW 3: NEURO-RESET BREATHING ORB
    // =========================================================================

    private class NeuroBreathOrbView(
        context: Context,
        private val palette: UiThemePrefs.ThemePalette
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private var breathScale = 0.25f
        private var phaseLabel = "Tap Start Below"

        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 12f * density
        }

        fun setPhase(scale: Float, label: String) {
            breathScale = scale.coerceIn(0f, 1f)
            phaseLabel = label
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxR = (width.coerceAtMost(height) / 2f) - 12f * density
            val minR = maxR * 0.38f
            val r = minR + (maxR - minR) * breathScale

            ringPaint.color = Color.parseColor("#334155")
            canvas.drawCircle(cx, cy, maxR, ringPaint)

            orbPaint.color = Color.argb(
                55,
                Color.red(palette.accentColor),
                Color.green(palette.accentColor),
                Color.blue(palette.accentColor)
            )
            canvas.drawCircle(cx, cy, r * 1.15f, orbPaint)

            orbPaint.color = Color.argb(
                185,
                Color.red(palette.accentColor),
                Color.green(palette.accentColor),
                Color.blue(palette.accentColor)
            )
            canvas.drawCircle(cx, cy, r, orbPaint)

            canvas.drawText(phaseLabel, cx, cy + 4f * density, labelPaint)
        }
    }
}
