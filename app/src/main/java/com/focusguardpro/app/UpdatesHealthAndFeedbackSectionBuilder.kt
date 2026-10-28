package com.focusguardpro.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object UpdatesHealthAndFeedbackSectionBuilder {

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

    /**
     * Shows a rich, dark-themed "What's New in Focus Elite" showcase modal dialog.
     */
    fun showWhatsNewDialog(
        activity: Activity,
        palette: UiThemePrefs.ThemePalette,
        onOpenUpdatesTab: (() -> Unit)? = null
    ) {
        val latest = SystemDiagnosticsAndUpdatesManager.RELEASE_HISTORY.first()
        SystemDiagnosticsAndUpdatesManager.markCurrentVersionSeen(activity)

        val scroll = ScrollView(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20f), dp(activity, 18f), dp(activity, 20f), dp(activity, 14f))
            setBackgroundColor(Color.parseColor("#0B1120"))
        }
        scroll.addView(root)

        root.addView(TextView(activity).apply {
            text = "🎉 NEW UPDATE INSTALLED • FOCUS ELITE v${latest.versionName}"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
        })

        root.addView(TextView(activity).apply {
            text = latest.headline
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(activity, 6f), 0, dp(activity, 10f))
        })

        for (item in latest.highlights) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 12f), dp(activity, 10f), dp(activity, 12f), dp(activity, 10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 12f).toFloat()
                    setColor(Color.parseColor("#131C31"))
                    setStroke(dp(activity, 1f), Color.parseColor("#2A3655"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(activity, 8f) }
            }
            row.addView(TextView(activity).apply {
                text = item
                textSize = 12.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                setLineSpacing(0f, 1.2f)
            })
            root.addView(row)
        }

        root.addView(TextView(activity).apply {
            text = "🔒 Privacy Guarantee: Focus Elite remains 100% offline with ZERO Internet permission. Updates are downloaded cleanly via your system browser from GitHub Releases and installed in-place over the existing app without losing your settings or data."
            textSize = 11.5f
            setTextColor(Color.parseColor("#34D399"))
            setPadding(0, dp(activity, 6f), 0, dp(activity, 4f))
            setLineSpacing(0f, 1.2f)
        })

        val builder = AlertDialog.Builder(activity)
            .setView(scroll)
            .setPositiveButton("Got It ✓") { d, _ -> d.dismiss() }
            .setNeutralButton("🌐 GitHub Releases") { _, _ ->
                SystemDiagnosticsAndUpdatesManager.openGitHubReleasesInBrowser(activity)
            }

        if (onOpenUpdatesTab != null) {
            builder.setNegativeButton("🛠️ Open Health & Feedback") { _, _ ->
                onOpenUpdatesTab()
            }
        }

        builder.show()
    }

    /**
     * Compact banner for the top of the Guide & Tree tab so users immediately see
     * the new Update Showcase, Health Scanner, and Feedback Studio.
     */
    fun buildCompactBannerCard(
        activity: Activity,
        palette: UiThemePrefs.ThemePalette,
        onOpenUpdatesHealthTab: () -> Unit
    ): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 16f).toFloat()
                setColor(Color.parseColor("#111A2E"))
                setStroke(dp(activity, 1.5f), palette.accentColor)
            }
            setPadding(dp(activity, 16f), dp(activity, 14f), dp(activity, 16f), dp(activity, 14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 16f) }
        }

        card.addView(TextView(activity).apply {
            text = "🎉 NEW IN v${SystemDiagnosticsAndUpdatesManager.CURRENT_VERSION_NAME} • UPDATES, HEALTH SCANNER & FEEDBACK STUDIO"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
        })

        card.addView(TextView(activity).apply {
            text = "What's New Showcase • Offline Error Diagnosis • Share Upgrade Ideas"
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(activity, 4f), 0, dp(activity, 4f))
        })

        card.addView(TextView(activity).apply {
            text = "Explore new features in v${SystemDiagnosticsAndUpdatesManager.CURRENT_VERSION_NAME}, download new APK updates from your Focus-Elite GitHub Releases without losing data, run the 7-point offline error scanner, or generate a professional Feedback / Upgrade report to paste here!"
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(activity, 10f))
        })

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val whatsNewBtn = Button(activity).apply {
            text = "🎉 What's New"
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 42f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 8f)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 10f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(activity, 1f), palette.secondaryColor)
            }
            setOnClickListener {
                showWhatsNewDialog(activity, palette, onOpenUpdatesHealthTab)
            }
        }

        val openStudioBtn = Button(activity).apply {
            text = "🛠️ Updates, Health & Feedback →"
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 42f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 10f).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener { onOpenUpdatesHealthTab() }
        }

        btnRow.addView(whatsNewBtn)
        btnRow.addView(openStudioBtn)
        card.addView(btnRow)
        return card
    }

    /**
     * Populates the full "🛠️ Updates, Health & Feedback" tab inside AuxiliarySettingsActivity.
     */
    fun populateFullTab(
        activity: Activity,
        container: LinearLayout,
        palette: UiThemePrefs.ThemePalette,
        onRefresh: () -> Unit
    ) {
        SystemDiagnosticsAndUpdatesManager.markCurrentVersionSeen(activity)

        // =====================================================================
        // SECTION 1: WHAT'S NEW PRESENTATION & GITHUB RELEASE UPDATE CENTER
        // =====================================================================
        val updateCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(activity, cornerDp = 18f, isHero = true)
            setPadding(dp(activity, 18f), dp(activity, 16f), dp(activity, 18f), dp(activity, 16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 16f) }
        }

        updateCard.addView(TextView(activity).apply {
            text = "🚀 STEP 1 • APP UPDATE CENTER & 'WHAT'S NEW' PRESENTATION"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
        })

        updateCard.addView(TextView(activity).apply {
            text = "Focus Elite v${SystemDiagnosticsAndUpdatesManager.CURRENT_VERSION_NAME} (Build ${SystemDiagnosticsAndUpdatesManager.CURRENT_VERSION_CODE})"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(activity, 4f), 0, dp(activity, 4f))
        })

        updateCard.addView(TextView(activity).apply {
            text = "• How Zero-Internet Updating Works: Tapping 'Check & Download Latest APK on GitHub' opens your phone's browser to your Focus-Elite repository releases.\n" +
                "• No Uninstall Needed: When you download and tap the new APK, Android installs it right over your current version — keeping all your schedules, categories, and logs intact.\n" +
                "• Automatic Feature Showcase: Whenever a new version is installed, Focus Elite posts an update notification and presents the new features automatically."
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(0f, 1.22f)
            setPadding(0, 0, 0, dp(activity, 12f))
        })

        val topUpdateBtns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 8f) }
        }

        val showcaseBtn = Button(activity).apply {
            text = "🎉 Present 'What's New' Popup"
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 44f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 8f)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 12f).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener {
                SystemDiagnosticsAndUpdatesManager.postUpdateNotificationIfNeeded(activity)
                showWhatsNewDialog(activity, palette, null)
            }
        }

        val releaseBtn = Button(activity).apply {
            text = "🌐 Download Official Release"
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 44f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 12f).toFloat()
                setColor(Color.parseColor("#14532D"))
                setStroke(dp(activity, 1f), Color.parseColor("#22C55E"))
            }
            setOnClickListener {
                SystemDiagnosticsAndUpdatesManager.openGitHubReleasesInBrowser(activity)
            }
        }
        topUpdateBtns.addView(showcaseBtn)
        topUpdateBtns.addView(releaseBtn)
        updateCard.addView(topUpdateBtns)

        val publishHelperBtn = Button(activity).apply {
            text = "🎯 Copy Termux Command: Publish Official Release v${SystemDiagnosticsAndUpdatesManager.CURRENT_VERSION_NAME}"
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 42f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 8f) }
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 12f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(activity, 1f), palette.secondaryColor)
            }
            setOnClickListener {
                val cmd = "git tag -f v${SystemDiagnosticsAndUpdatesManager.CURRENT_VERSION_NAME} && git push origin v${SystemDiagnosticsAndUpdatesManager.CURRENT_VERSION_NAME} --force"
                SystemDiagnosticsAndUpdatesManager.copyReportToClipboard(activity, cmd)
            }
        }
        updateCard.addView(publishHelperBtn)

        // Expandable / visible release notes history
        for (rel in SystemDiagnosticsAndUpdatesManager.RELEASE_HISTORY) {
            val relBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 12f), dp(activity, 10f), dp(activity, 12f), dp(activity, 10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 12f).toFloat()
                    setColor(Color.parseColor("#111827"))
                    setStroke(
                        dp(activity, 1f),
                        if (rel.versionCode == SystemDiagnosticsAndUpdatesManager.CURRENT_VERSION_CODE) palette.accentColor
                        else Color.parseColor("#2A324B")
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 8f) }
            }

            relBox.addView(TextView(activity).apply {
                text = "v${rel.versionName} (${rel.dateLabel}) — ${rel.headline}"
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(activity, 4f))
            })

            relBox.addView(TextView(activity).apply {
                text = rel.highlights.joinToString("\n") { "• $it" }
                textSize = 11.5f
                setTextColor(Color.parseColor("#CBD5E1"))
                setLineSpacing(0f, 1.18f)
            })
            updateCard.addView(relBox)
        }

        container.addView(updateCard)

        // =====================================================================
        // SECTION 2: 7-POINT OFFLINE SYSTEM HEALTH & ERROR SELF-DIAGNOSIS
        // =====================================================================
        val checks = SystemDiagnosticsAndUpdatesManager.runSevenPointHealthScan(activity)
        val passCount = checks.count { it.status == "PASS" }
        val healthPercent = ((passCount * 100f) / checks.size.coerceAtLeast(1)).roundToInt()

        val healthCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(activity, cornerDp = 18f, isHero = false)
            setPadding(dp(activity, 16f), dp(activity, 16f), dp(activity, 16f), dp(activity, 16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 16f) }
        }

        healthCard.addView(TextView(activity).apply {
            text = "🩺 STEP 2 • OFFLINE ERROR DETECTOR & 7-POINT HEALTH SCANNER"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accentColor)
        })

        healthCard.addView(TextView(activity).apply {
            text = "System Health Score: $healthPercent% ($passCount/${checks.size} Checks Passing)"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (healthPercent >= 85) Color.parseColor("#4ADE80") else Color.parseColor("#FBBF24"))
            setPadding(0, dp(activity, 4f), 0, dp(activity, 4f))
        })

        healthCard.addView(TextView(activity).apply {
            text = "Automatically inspects permissions, background services, SQLite database integrity, and clock tampering locally on your device without any Internet connection."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(activity, 10f))
        })

        val repairBtn = Button(activity).apply {
            text = "🔧 Run 1-Tap Auto-Repair & Rebind All Focus Services"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 44f)
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 12f).toFloat()
                setColor(Color.parseColor("#1E3A8A"))
                setStroke(dp(activity, 1f), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 10f) }
            setOnClickListener {
                val result = SystemDiagnosticsAndUpdatesManager.performAutoRepair(activity)
                Toast.makeText(activity, "✓ Auto-Repair Complete: $result", Toast.LENGTH_LONG).show()
                onRefresh()
            }
        }
        healthCard.addView(repairBtn)

        for (item in checks) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 12f), dp(activity, 10f), dp(activity, 12f), dp(activity, 10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 12f).toFloat()
                    setColor(Color.parseColor("#111827"))
                    setStroke(
                        dp(activity, 1f),
                        when (item.status) {
                            "PASS" -> Color.parseColor("#22C55E")
                            "WARN" -> Color.parseColor("#F59E0B")
                            else -> Color.parseColor("#EF4444")
                        }
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(activity, 8f) }
            }

            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val statusIcon = when (item.status) {
                "PASS" -> "✓ PASS"
                "WARN" -> "⚠️ WARN"
                else -> "⚡ ACTION"
            }
            val statusColor = when (item.status) {
                "PASS" -> Color.parseColor("#4ADE80")
                "WARN" -> Color.parseColor("#FBBF24")
                else -> Color.parseColor("#F87171")
            }

            col.addView(TextView(activity).apply {
                text = "[$statusIcon] ${item.title}"
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(statusColor)
            })
            col.addView(TextView(activity).apply {
                text = item.detail
                textSize = 11.5f
                setTextColor(Color.parseColor("#CBD5E1"))
                setPadding(0, dp(activity, 2f), 0, 0)
            })
            row.addView(col)

            if (item.actionLabel != null) {
                val fixBtn = Button(activity).apply {
                    text = item.actionLabel
                    textSize = 11f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    minHeight = dp(activity, 36f)
                    setPadding(dp(activity, 10f), dp(activity, 4f), dp(activity, 10f), dp(activity, 4f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(activity, 10f).toFloat()
                        setColor(palette.accentColor)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(activity, 8f) }
                    setOnClickListener {
                        SettingsTamperGuard.grantPermissionSetupGrace(activity, 180_000L)
                        when (item.id) {
                            "usage" -> activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            "criteria" -> activity.startActivity(Intent(activity, CategorySettingsActivity::class.java))
                            "admin" -> {
                                val comp = ComponentName(activity, MyAdminReceiver::class.java)
                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                                    putExtra(
                                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "Protects Focus Elite from uninstallation during active focus hours."
                                    )
                                }
                                activity.startActivity(intent)
                            }
                            "accessibility" -> activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            "battery_clock" -> {
                                try {
                                    activity.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${activity.packageName}")
                                        )
                                    )
                                } catch (_: Throwable) {
                                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }
                            }
                        }
                    }
                }
                row.addView(fixBtn)
            }
            healthCard.addView(row)
        }

        // Recent Diagnostic & Crash Log Viewer
        val logs = SystemDiagnosticsAndUpdatesManager.getDiagnosticLogs(activity)
        healthCard.addView(TextView(activity).apply {
            text = if (logs.isEmpty()) {
                "✓ Local Error & Crash Log: Zero errors or crashes detected!"
            } else {
                "📋 Recent Diagnostic & Repair Events (${logs.size} recorded):"
            }
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (logs.isEmpty()) Color.parseColor("#4ADE80") else Color.WHITE)
            setPadding(0, dp(activity, 6f), 0, dp(activity, 4f))
        })

        val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US)
        for (log in logs.take(4)) {
            healthCard.addView(TextView(activity).apply {
                text = "• [${log.level}] ${sdf.format(Date(log.timestampMs))} — ${log.title}: ${log.details}"
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(activity, 2f), 0, dp(activity, 2f))
            })
        }

        if (logs.isNotEmpty()) {
            val clearLogsBtn = TextView(activity).apply {
                text = "🗑️ Clear Local Diagnostic Logs"
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.secondaryColor)
                setPadding(0, dp(activity, 6f), 0, dp(activity, 2f))
                setOnClickListener {
                    SystemDiagnosticsAndUpdatesManager.clearDiagnosticLogs(activity)
                    Toast.makeText(activity, "Diagnostic logs cleared.", Toast.LENGTH_SHORT).show()
                    onRefresh()
                }
            }
            healthCard.addView(clearLogsBtn)
        }

        container.addView(healthCard)

        // =====================================================================
        // SECTION 3: PRIVACY-DRIVEN FEEDBACK, UPGRADE IDEAS & BUG REPORT STUDIO
        // =====================================================================
        val feedbackCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(activity, cornerDp = 18f, isHero = false)
            setPadding(dp(activity, 16f), dp(activity, 16f), dp(activity, 16f), dp(activity, 16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 18f) }
        }

        feedbackCard.addView(TextView(activity).apply {
            text = "💬 STEP 3 • PRIVACY-DRIVEN FEEDBACK, UPGRADE IDEAS & BUG REPORTER"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
        })

        feedbackCard.addView(TextView(activity).apply {
            text = "Share Your Opinion, Request a Feature, or Report an Issue (100% Offline)"
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(activity, 4f), 0, dp(activity, 4f))
        })

        feedbackCard.addView(TextView(activity).apply {
            text = "Write what new feature you want to add, your self-opinion on an upgrade, or any bug you noticed. Tap 'Copy Professional Report' and share it directly in GitHub Issues or your developer workspace — zero background network permissions required!"
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(activity, 10f))
        })

        val categories = listOf(
            "🚀 Feature / Upgrade Idea",
            "💡 Self Opinion / UX Upgrade",
            "🐞 Bug / Error Report"
        )
        var selectedCategory = categories[0]
        val catRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 10f) }
        }

        val catPills = mutableListOf<TextView>()
        fun styleCatPills() {
            catPills.forEachIndexed { idx, tv ->
                val active = categories[idx] == selectedCategory
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(tv.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 12f).toFloat()
                    if (active) {
                        setColor(palette.accentColor)
                    } else {
                        setColor(Color.parseColor("#111827"))
                        setStroke(dp(activity, 1f), Color.parseColor("#334155"))
                    }
                }
            }
        }

        categories.forEach { catLabel ->
            val pill = TextView(activity).apply {
                text = catLabel
                textSize = 10.5f
                gravity = Gravity.CENTER
                setPadding(dp(activity, 8f), dp(activity, 8f), dp(activity, 8f), dp(activity, 8f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(activity, 4f)
                }
                setOnClickListener {
                    selectedCategory = catLabel
                    styleCatPills()
                }
            }
            catPills.add(pill)
            catRow.addView(pill)
        }
        styleCatPills()
        feedbackCard.addView(catRow)

        val subjectInput = EditText(activity).apply {
            hint = "Short Title (e.g., Add Pomodoro Sound or Fix Schedule Toggle)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(activity, 12f), dp(activity, 10f), dp(activity, 12f), dp(activity, 10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 10f).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(activity, 1f), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 8f) }
        }
        feedbackCard.addView(subjectInput)

        val bodyInput = EditText(activity).apply {
            hint = "Describe your upgrade idea, self-opinion, or what happened in detail..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(activity, 12f), dp(activity, 10f), dp(activity, 12f), dp(activity, 10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 10f).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(activity, 1f), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 8f) }
        }
        feedbackCard.addView(bodyInput)

        val attachDiagCheck = CheckBox(activity).apply {
            text = "Attach 7-Point System Health & Error Log Snapshot (Recommended)"
            isChecked = true
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
        }
        feedbackCard.addView(attachDiagCheck)

        val submitBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8f) }
        }

        val copyReportBtn = Button(activity).apply {
            text = "📋 Copy Report (Paste Here)"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 46f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                marginEnd = dp(activity, 8f)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 12f).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener {
                val sub = SystemDiagnosticsAndUpdatesManager.saveAndFormatFeedbackReport(
                    context = activity,
                    category = selectedCategory,
                    subject = subjectInput.text?.toString() ?: "",
                    body = bodyInput.text?.toString() ?: "",
                    includeDiagnostics = attachDiagCheck.isChecked
                )
                SystemDiagnosticsAndUpdatesManager.copyReportToClipboard(activity, sub.formattedReport)
                subjectInput.setText("")
                bodyInput.setText("")
                onRefresh()
            }
        }

        val shareReportBtn = Button(activity).apply {
            text = "📤 Share Report"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 46f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 12f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(activity, 1f), palette.secondaryColor)
            }
            setOnClickListener {
                val sub = SystemDiagnosticsAndUpdatesManager.saveAndFormatFeedbackReport(
                    context = activity,
                    category = selectedCategory,
                    subject = subjectInput.text?.toString() ?: "",
                    body = bodyInput.text?.toString() ?: "",
                    includeDiagnostics = attachDiagCheck.isChecked
                )
                SystemDiagnosticsAndUpdatesManager.shareReportViaSystemChooser(
                    activity,
                    sub.subject,
                    sub.formattedReport
                )
                onRefresh()
            }
        }

        submitBtnRow.addView(copyReportBtn)
        submitBtnRow.addView(shareReportBtn)
        feedbackCard.addView(submitBtnRow)

        // Show recent saved feedback items so user can copy again anytime
        val savedItems = SystemDiagnosticsAndUpdatesManager.getSavedFeedbackList(activity)
        if (savedItems.isNotEmpty()) {
            feedbackCard.addView(TextView(activity).apply {
                text = "🗂️ Saved Feedback & Upgrade Notes (${savedItems.size} — Tap to Copy Again):"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.secondaryColor)
                setPadding(0, dp(activity, 12f), 0, dp(activity, 6f))
            })

            for (item in savedItems.take(3)) {
                val itemRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(activity, 10f), dp(activity, 8f), dp(activity, 10f), dp(activity, 8f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(activity, 10f).toFloat()
                        setColor(Color.parseColor("#111827"))
                        setStroke(dp(activity, 1f), Color.parseColor("#2A324B"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(activity, 6f) }
                    setOnClickListener {
                        SystemDiagnosticsAndUpdatesManager.copyReportToClipboard(activity, item.formattedReport)
                    }
                }
                itemRow.addView(TextView(activity).apply {
                    text = "${item.category} • ${item.subject}"
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                itemRow.addView(TextView(activity).apply {
                    text = item.body.ifBlank { "(Tap to copy full diagnostic report)" }
                    textSize = 11f
                    maxLines = 2
                    setTextColor(Color.parseColor("#94A3B8"))
                })
                feedbackCard.addView(itemRow)
            }
        }

        container.addView(feedbackCard)
    }
}
