package com.focusguardpro.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguardpro.app.widget.FocusWidgetProvider

class CategorySettingsActivity : AppCompatActivity() {

    private lateinit var adapter: CategoryAdapter
    private lateinit var rows: List<CategoryAppRow>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_settings)

        findViewById<TextView>(R.id.categoryBackButton).setOnClickListener {
            saveAll()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.categoryAppList)
        recyclerView.layoutManager = LinearLayoutManager(this)

        rows = buildRows()
        adapter = CategoryAdapter(rows)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.categorySaveButton).setOnClickListener {
            saveAll()
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

    override fun onBackPressed() {
        saveAll()
    }

    override fun onResume() {
        super.onResume()
        applyFocusLock()
    }

    private fun applyFocusLock() {
        val active = FocusLayerManager.isActiveNow(this)
        val noticeCard = findViewById<LinearLayout>(R.id.focusLockNoticeCard)
        noticeCard.visibility = if (active) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.categorySaveButton).isEnabled = true
        adapter.setLocked(false)
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
            if (SystemExclusions.isInputMethod(this, pkg)) continue
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

    private fun saveAll() {
        val newStudy = rows.filter { it.isStudy }.map { it.packageName }.toSet()
        val newImprovement = rows.filter { it.isImprovement }.map { it.packageName }.toSet()
        val newIgnored = rows.filter { it.isIgnored }.map { it.packageName }.toSet()

        AppCategoryPrefs.setStudyApps(this, newStudy)
        AppCategoryPrefs.setImprovementApps(this, newImprovement)
        AppCategoryPrefs.setIgnoredApps(this, newIgnored)

        try {
            EliteCommandWidgetProvider.updateAllWidgets(this)
        } catch (_: Exception) {}

        Toast.makeText(this, "Categories saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
