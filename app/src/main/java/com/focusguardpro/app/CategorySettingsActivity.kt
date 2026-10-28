package com.focusguardpro.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class CategorySettingsActivity : AppCompatActivity() {

    private lateinit var adapter: CategoryAdapter
    private lateinit var rows: List<CategoryAppRow>

    private var initialStudyApps: Set<String> = emptySet()
    private var initialImprovementApps: Set<String> = emptySet()
    private var initialIgnoredApps: Set<String> = emptySet()
    private var unlockedByProofOfEffort: Boolean = false
    private var proofUnlockBtn: Button? = null
    private var systemSafeBannerInjected: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_settings)

        initialStudyApps = AppCategoryPrefs.getStudyApps(this)
        initialImprovementApps = AppCategoryPrefs.getImprovementApps(this)
        initialIgnoredApps = AppCategoryPrefs.getIgnoredApps(this)

        injectSystemAppsExcludedBanner()

        findViewById<TextView>(R.id.categoryBackButton).setOnClickListener {
            attemptSaveAndExit()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.categoryAppList)
        recyclerView.layoutManager = LinearLayoutManager(this)

        rows = buildRows()
        adapter = CategoryAdapter(rows)
        recyclerView.adapter = adapter

        val saveBtn = findViewById<Button>(R.id.categorySaveButton)
        if (intent.getBooleanExtra("from_guided_setup", false)) {
            saveBtn.text = "✓ Save App Criteria & Continue to Step 3 (Focus Schedule) →"
        }
        saveBtn.setOnClickListener {
            attemptSaveAndExit()
        }

        findViewById<EditText>(R.id.etSearchApps).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        applyFocusLock()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        attemptSaveAndExit()
    }

    override fun onResume() {
        super.onResume()
        applyFocusLock()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun injectSystemAppsExcludedBanner() {
        if (systemSafeBannerInjected) return
        val searchBox = findViewById<View>(R.id.etSearchApps) ?: return
        val parent = searchBox.parent as? LinearLayout ?: return
        val searchIndex = parent.indexOfChild(searchBox)
        if (searchIndex < 0) return

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#0D2818"))
                setStroke(dp(1f), Color.parseColor("#22C55E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
        }

        val bannerTitle = TextView(this).apply {
            text = "🛡️ All Android System Apps Automatically Allowed & Excluded"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4ADE80"))
        }
        val bannerDesc = TextView(this).apply {
            text = "System utilities (Phone, SMS, Camera, Clock/Alarm, Calculator, Contacts, Files, Settings, Payments) are excluded from this list and always work 24/7. Classify your apps below into Study (always allowed), Improve (always allowed), or Ignored (30m/day cap)."
            textSize = 11.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(3f), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        banner.addView(bannerTitle)
        banner.addView(bannerDesc)

        parent.addView(banner, searchIndex)
        systemSafeBannerInjected = true
    }

    private fun applyFocusLock() {
        val isFortressLocked = FocusLayerManager.isLockedUntilCalendarHoliday(this)
        val graceMins = FocusLayerManager.getPreWindowGraceRemainingMinutes(this)
        val noticeCard = findViewById<LinearLayout>(R.id.focusLockNoticeCard)

        if (isFortressLocked && !unlockedByProofOfEffort) {
            noticeCard.visibility = View.VISIBLE
            ensureProofButtonInNoticeCard(noticeCard)
            adapter.setLocked(false) // Allow unchecking (demoting to Blocked), gate promotions on Save
        } else if (graceMins > 0) {
            noticeCard.visibility = View.VISIBLE
            ensureGraceBannerInNoticeCard(noticeCard, graceMins)
            adapter.setLocked(false)
        } else {
            noticeCard.visibility = View.GONE
            adapter.setLocked(false)
        }
        findViewById<Button>(R.id.categorySaveButton).isEnabled = true
    }

    private fun ensureProofButtonInNoticeCard(noticeCard: LinearLayout) {
        if (proofUnlockBtn != null && proofUnlockBtn?.parent == noticeCard) {
            proofUnlockBtn?.visibility = View.VISIBLE
            return
        }
        val btn = Button(this).apply {
            text = "🏋️ Unlock Category Promotions (Proof-of-Effort)"
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(40f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#DC2626"))
                setStroke(dp(1f), Color.parseColor("#FCA5A5"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
            setOnClickListener {
                CognitiveFrictionGate.show(
                    context = this@CategorySettingsActivity,
                    title = "Unlock Category Promotions",
                    reasonPrompt = "Fortress Lock freezes whitelisting new Study/Ignored apps during focus hours. Complete physical proof-of-effort to unlock category promotions."
                ) {
                    unlockedByProofOfEffort = true
                    Toast.makeText(
                        this@CategorySettingsActivity,
                        "🔓 Category promotions unlocked for this session!",
                        Toast.LENGTH_SHORT
                    ).show()
                    applyFocusLock()
                }
            }
        }
        noticeCard.addView(btn)
        proofUnlockBtn = btn
    }

    private fun ensureGraceBannerInNoticeCard(noticeCard: LinearLayout, graceMins: Int) {
        proofUnlockBtn?.visibility = View.GONE
        val firstTv = (0 until noticeCard.childCount)
            .mapNotNull { noticeCard.getChildAt(it) as? TextView }
            .firstOrNull { it != proofUnlockBtn }
        firstTv?.text = "⚡ Pre-Window Grace Period Open ($graceMins min left before Fortress Lock). You can freely edit categories right now."
    }

    private fun buildRows(): List<CategoryAppRow> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(intent, 0)
        val studyApps = AppCategoryPrefs.getStudyApps(this)
        val improvementApps = AppCategoryPrefs.getImprovementApps(this)
        val ignoredApps = AppCategoryPrefs.getIgnoredApps(this)

        val seen = HashSet<String>()
        val result = ArrayList<CategoryAppRow>()

        for (resolveInfo in activities) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg == packageName || pkg == "com.focusguardpro.elite" || pkg == "com.focusguardpro.app") continue
            // CRITICAL: Exclude all Android system & utility apps from the criteria list so
            // users never have to manually whitelist Camera, Clock, Calculator, Phone, SMS, Files, etc.
            if (SystemExclusions.isExcludedPackage(this, pkg)) continue
            if (!seen.add(pkg)) continue

            val appName = resolveInfo.loadLabel(pm)?.toString() ?: pkg
            val icon = resolveInfo.loadIcon(pm)
            val isStudy = studyApps.contains(pkg)
            val isImprovement = improvementApps.contains(pkg)
            val isIgnored = ignoredApps.contains(pkg)

            result.add(
                CategoryAppRow(
                    appName = appName,
                    packageName = pkg,
                    icon = icon,
                    isStudy = isStudy,
                    isImprovement = isImprovement,
                    isIgnored = isIgnored
                )
            )
        }

        return result.sortedBy { it.appName.lowercase() }
    }

    private fun attemptSaveAndExit() {
        val newStudy = rows.filter { it.isStudy }.map { it.packageName }.toSet()
        val newImprovement = rows.filter { it.isImprovement }.map { it.packageName }.toSet()
        val newIgnored = rows.filter { it.isIgnored }.map { it.packageName }.toSet()

        val promotedStudy = newStudy - initialStudyApps
        val promotedImprovement = newImprovement - initialImprovementApps
        val promotedIgnored = newIgnored - initialIgnoredApps
        val hasPromotions = promotedStudy.isNotEmpty() ||
            promotedImprovement.isNotEmpty() ||
            promotedIgnored.isNotEmpty()

        val isFortressLocked = FocusLayerManager.isLockedUntilCalendarHoliday(this)

        if (isFortressLocked && hasPromotions && !unlockedByProofOfEffort) {
            val promotedCount = promotedStudy.size + promotedImprovement.size + promotedIgnored.size
            CognitiveFrictionGate.show(
                context = this,
                title = "Authorize Category Promotion ($promotedCount App${if (promotedCount > 1) "s" else ""})",
                reasonPrompt = "You are promoting $promotedCount app(s) into a whitelisted/allowed category during active Focus hours. Complete physical proof-of-effort to authorize."
            ) {
                unlockedByProofOfEffort = true
                commitSaveAndFinish(newStudy, newImprovement, newIgnored)
            }
        } else {
            commitSaveAndFinish(newStudy, newImprovement, newIgnored)
        }
    }

    private fun commitSaveAndFinish(
        newStudy: Set<String>,
        newImprovement: Set<String>,
        newIgnored: Set<String>
    ) {
        AppCategoryPrefs.setStudyApps(this, newStudy)
        AppCategoryPrefs.setImprovementApps(this, newImprovement)
        AppCategoryPrefs.setIgnoredApps(this, newIgnored)
        UiThemePrefs.markSetupCategoriesConfigured(this)

        try {
            EliteCommandWidgetProvider.updateAllWidgets(this)
        } catch (_: Exception) {}

        val msg = if (intent.getBooleanExtra("from_guided_setup", false)) {
            "✓ Step 2 Complete! Next: Step 3 — Confirm your Focus Schedule."
        } else {
            "✓ App categories saved (all system apps automatically allowed)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }
}
