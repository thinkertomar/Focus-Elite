package com.focusguardpro.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Upgraded Web Shield Hub:
 * 1. Live Shield Status (Active / Off-Day Paused / Browser Free-Access Window Active)
 * 2. Browser Free-Access Window Controls (30/45/60m Before Start, After End, or Both)
 * 3. Dedicated "Today's Browsing" section listing ALL websites visited today with exact time spent & 1-tap Study/Block
 * 4. Study/Fair Websites (24/7) & Blocked Websites domain management
 */
class EliteWebShieldActivity : AppCompatActivity() {

    private lateinit var rootScroll: ScrollView
    private lateinit var contentColumn: LinearLayout

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(32f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootScroll.addView(contentColumn)
        setContentView(rootScroll)
    }

    override fun onResume() {
        super.onResume()
        renderScreen()
    }

    private fun renderScreen() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val bgColor = UiThemePrefs.getScreenBackgroundColor(this)
        val textPrimary = UiThemePrefs.getScreenHeaderTextColor(this)
        val textSecondary = UiThemePrefs.getScreenSubHeaderTextColor(this)

        rootScroll.setBackgroundColor(bgColor)
        contentColumn.removeAllViews()

        // 1. Header Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }
        headerRow.addView(TextView(this).apply {
            text = "←"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isLight) Color.parseColor("#0284C7") else palette.secondaryColor)
            gravity = Gravity.CENTER
            minimumWidth = dp(44f)
            minimumHeight = dp(44f)
            setOnClickListener {
                SensoryFeedbackEngine.tap(this@EliteWebShieldActivity, it)
                finish()
            }
        })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4f)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = "Web Shield & Browsing"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = "Study 24/7 • Post-Focus Browser Access • Today's Browsing"
            textSize = 11.5f
            setTextColor(textSecondary)
        })
        headerRow.addView(titleCol)
        contentColumn.addView(headerRow)

        // 2. Live Web Shield Status Banner
        val offDayPaused = BlockPolicyEngine.isWebShieldPausedForOffDay(this)
        val winStatus = BlockPolicyEngine.getBrowserAccessWindowStatus(this)
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@EliteWebShieldActivity, cornerDp = 16f, isHero = true)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }
        val (statusTitle, statusSub, statusColor) = when {
            offDayPaused -> Triple(
                "🌿 Off-Day: Web Shield Rules Paused",
                "Today is a scheduled off-day / Holiday — all Web Shield blocking rules rest automatically.",
                "#34D399"
            )
            winStatus.isActiveNow -> Triple(
                "🎁 Free-Access Browser Window Active (${winStatus.remainingMinutes}m left)",
                "Non-study sites are temporarily open during your reward window (${winStatus.scheduleLabel}).",
                "#38BDF8"
            )
            else -> Triple(
                "🛡️ Web Shield Active • Study Sites Open 24/7",
                if (winStatus.isEnabled) {
                    "Next Browser Free-Access Window: ${winStatus.scheduleLabel}"
                } else {
                    "Study/Fair sites always allowed • Non-study sites guarded by Focus Layer & daily cap"
                },
                "#16D99A"
            )
        }
        statusCard.addView(TextView(this).apply {
            text = statusTitle
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(statusColor))
        })
        statusCard.addView(TextView(this).apply {
            text = statusSub
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(4f), 0, 0)
        })
        contentColumn.addView(statusCard)

        // 3. Browser Free-Access Window Config Card (30–60m before start / after end of Focus Layer)
        buildBrowserAccessWindowCard(palette, winStatus, isLight, textPrimary, textSecondary)

        // 4. TODAY'S BROWSING SECTION (All websites accessed today with exact time & 1-tap Study/Block)
        buildTodaysBrowsingSection(palette, isLight, textPrimary, textSecondary)

        // 5. Add Domain Rule Card
        buildAddDomainCard(palette, isLight, textPrimary, textSecondary)

        // 6. Study / Fair Websites (24/7 Allowed) & Blocked Websites Lists
        buildDomainListSection(
            title = "🟢 Study / Fair Websites (Allowed 24/7)",
            subtitle = "Never blocked and never counted toward your non-study daily cap",
            domains = WebsitePrefs.getFairDomains(this).sorted(),
            chipColorHex = "#10B981",
            emptyMsg = "No custom Study sites added yet. Tap '✓ Study' on any site in Today's Browsing above or add one.",
            isLight = isLight,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ) { domain ->
            WebsitePrefs.removeFairDomain(this, domain)
            SensoryFeedbackEngine.tap(this)
            renderScreen()
        }

        buildDomainListSection(
            title = "🚫 Blocked Websites",
            subtitle = "Blocked on Focus Days (pauses automatically on scheduled Off-Days)",
            domains = WebsitePrefs.getBlockedDomains(this).sorted(),
            chipColorHex = "#F43F5E",
            emptyMsg = "No custom blocked sites added yet.",
            isLight = isLight,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ) { domain ->
            WebsitePrefs.removeDomain(this, domain)
            SensoryFeedbackEngine.tap(this)
            renderScreen()
        }

        UiThemePrefs.applyWindowAndViewTreeTheme(this, rootScroll)
    }

    private fun buildBrowserAccessWindowCard(
        palette: UiThemePrefs.ThemePalette,
        status: BlockPolicyEngine.BrowserWindowStatus,
        isLight: Boolean,
        textPrimary: Int,
        textSecondary: Int
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@EliteWebShieldActivity, cornerDp = 16f, isHero = false)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "⏱️ Post / Pre-Focus Browser Access Window"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = "Unlock non-study websites for 30–60 min right after Focus Hours finish (or before they start) so you can browse guilt-free after studying."
            textSize = 11.5f
            setTextColor(textSecondary)
            setPadding(0, dp(3f), dp(8f), 0)
        })
        topRow.addView(titleCol)
        topRow.addView(Switch(this).apply {
            isChecked = status.isEnabled
            setOnCheckedChangeListener { v, checked ->
                SensoryFeedbackEngine.toggle(this@EliteWebShieldActivity, v)
                BlockPolicyEngine.setBrowserAccessWindowEnabled(this@EliteWebShieldActivity, checked)
                renderScreen()
            }
        })
        card.addView(topRow)

        if (status.isEnabled) {
            // Duration Chips (30m / 45m / 60m)
            card.addView(TextView(this).apply {
                text = "ACCESS DURATION"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textSecondary)
                setPadding(0, dp(10f), 0, dp(6f))
            })
            val durRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (mins in listOf(30, 45, 60)) {
                val sel = status.durationMinutes == mins
                durRow.addView(TextView(this).apply {
                    text = "${mins} min"
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (sel) Color.WHITE else textSecondary)
                    setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6f)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        if (sel) {
                            setColor(palette.accentColor)
                        } else {
                            setColor(if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#131E2E"))
                            setStroke(dp(1f), Color.parseColor("#2A3A52"))
                        }
                    }
                    setOnClickListener { v ->
                        SensoryFeedbackEngine.toggle(this@EliteWebShieldActivity, v)
                        BlockPolicyEngine.setBrowserAccessWindowMinutes(this@EliteWebShieldActivity, mins)
                        renderScreen()
                    }
                })
            }
            card.addView(durRow)

            // Timing Mode Chips (After Focus / Before Focus / Both)
            card.addView(TextView(this).apply {
                text = "WHEN TO UNLOCK"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textSecondary)
                setPadding(0, dp(10f), 0, dp(6f))
            })
            val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val modes = listOf(
                BlockPolicyEngine.WINDOW_MODE_AFTER_END to "After Focus",
                BlockPolicyEngine.WINDOW_MODE_BEFORE_START to "Before Focus",
                BlockPolicyEngine.WINDOW_MODE_BOTH to "Before & After"
            )
            for ((key, label) in modes) {
                val sel = status.mode == key
                modeRow.addView(TextView(this).apply {
                    text = label
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (sel) Color.WHITE else textSecondary)
                    setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6f)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        if (sel) {
                            setColor(Color.parseColor("#0EA5E9"))
                        } else {
                            setColor(if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#131E2E"))
                            setStroke(dp(1f), Color.parseColor("#2A3A52"))
                        }
                    }
                    setOnClickListener { v ->
                        SensoryFeedbackEngine.toggle(this@EliteWebShieldActivity, v)
                        BlockPolicyEngine.setBrowserAccessWindowMode(this@EliteWebShieldActivity, key)
                        renderScreen()
                    }
                })
            }
            card.addView(modeRow)

            card.addView(TextView(this).apply {
                text = "Active Window: ${status.scheduleLabel}"
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, dp(10f), 0, 0)
            })
        }

        contentColumn.addView(card)
    }

    private fun buildTodaysBrowsingSection(
        palette: UiThemePrefs.ThemePalette,
        isLight: Boolean,
        textPrimary: Int,
        textSecondary: Int
    ) {
        val summary = BrowserWebsiteTracker.getTodaySummary(this)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@EliteWebShieldActivity, cornerDp = 16f, isHero = false)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }

        card.addView(TextView(this).apply {
            text = "🌐 Today's Browsing (${summary.sites.size} ${if (summary.sites.size == 1) "site" else "sites"})"
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        card.addView(TextView(this).apply {
            text = "All websites accessed across your browsers today with exact time spent"
            textSize = 11.5f
            setTextColor(textSecondary)
            setPadding(0, dp(2f), 0, dp(10f))
        })

        // 3-Stat Strip
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        fun addMiniStat(label: String, value: String, colorHex: String) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#111B29"))
                    setStroke(dp(1f), if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#22324A"))
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(5f)
                }
            }
            box.addView(TextView(this).apply {
                text = value
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(colorHex))
            })
            box.addView(TextView(this).apply {
                text = label
                textSize = 9.5f
                setTextColor(textSecondary)
            })
            statsRow.addView(box)
        }
        addMiniStat("TOTAL WEB", if (summary.totalMs > 0L) BrowserWebsiteTracker.formatSiteDuration(summary.totalMs) else "0m", "#38BDF8")
        addMiniStat("STUDY", if (summary.studyMs > 0L) BrowserWebsiteTracker.formatSiteDuration(summary.studyMs) else "0m", "#10B981")
        addMiniStat("NON-STUDY", if (summary.nonStudyMs > 0L) BrowserWebsiteTracker.formatSiteDuration(summary.nonStudyMs) else "0m", "#F43F5E")
        card.addView(statsRow)

        if (summary.sites.isEmpty()) {
            card.addView(TextView(this).apply {
                text = "No websites recorded yet today. As you browse in Chrome, Brave, Firefox, or any browser, every domain and its exact time will appear here automatically."
                textSize = 12f
                setTextColor(textSecondary)
                setPadding(dp(4f), dp(6f), dp(4f), dp(4f))
            })
        } else {
            val pm = packageManager
            summary.sites.take(25).forEach { site ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(11f).toFloat()
                        setColor(if (isLight) Color.parseColor("#F8FAFC") else Color.parseColor("#0F1826"))
                        setStroke(dp(1f), if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1F2F46"))
                    }
                    setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(7f) }
                }

                val leftCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8f)
                    }
                }
                leftCol.addView(TextView(this).apply {
                    text = site.host
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(textPrimary)
                    maxLines = 1
                })
                val browserName = try { AppMetadataCache.getLabel(pm, site.browserPackage) } catch (_: Throwable) { "Browser" }
                val catColor = when (site.category) {
                    "STUDY" -> "#10B981"
                    "BLOCKED" -> "#F43F5E"
                    "NEUTRAL" -> "#94A3B8"
                    else -> "#F59E0B"
                }
                leftCol.addView(TextView(this).apply {
                    text = "${BrowserWebsiteTracker.formatSiteDuration(site.durationMs)} • ${site.visits} visits • ${site.category} • $browserName"
                    textSize = 11f
                    setTextColor(Color.parseColor(catColor))
                    setPadding(0, dp(2f), 0, 0)
                })
                row.addView(leftCol)

                // Quick 1-Tap Study / Block buttons
                val isStudy = WebsitePrefs.isFair(this, site.host)
                val isBlocked = WebsitePrefs.isBlocked(this, site.host)

                val btnStudy = TextView(this).apply {
                    text = if (isStudy) "✓ Study" else "+ Study"
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isStudy) Color.WHITE else Color.parseColor("#10B981"))
                    setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8f).toFloat()
                        setColor(if (isStudy) Color.parseColor("#10B981") else Color.parseColor("#0D2822"))
                        setStroke(dp(1f), Color.parseColor("#10B981"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(5f) }
                    setOnClickListener { v ->
                        SensoryFeedbackEngine.toggle(this@EliteWebShieldActivity, v)
                        if (isStudy) {
                            WebsitePrefs.removeFairDomain(this@EliteWebShieldActivity, site.host)
                        } else {
                            WebsitePrefs.removeDomain(this@EliteWebShieldActivity, site.host)
                            WebsitePrefs.addFairDomain(this@EliteWebShieldActivity, site.host)
                        }
                        renderScreen()
                    }
                }
                val btnBlock = TextView(this).apply {
                    text = if (isBlocked) "Blocked" else "Block"
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isBlocked) Color.WHITE else Color.parseColor("#F43F5E"))
                    setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8f).toFloat()
                        setColor(if (isBlocked) Color.parseColor("#F43F5E") else Color.parseColor("#2B121B"))
                        setStroke(dp(1f), Color.parseColor("#F43F5E"))
                    }
                    setOnClickListener { v ->
                        SensoryFeedbackEngine.toggle(this@EliteWebShieldActivity, v)
                        if (isBlocked) {
                            WebsitePrefs.removeDomain(this@EliteWebShieldActivity, site.host)
                        } else {
                            WebsitePrefs.removeFairDomain(this@EliteWebShieldActivity, site.host)
                            WebsitePrefs.addDomain(this@EliteWebShieldActivity, site.host)
                        }
                        renderScreen()
                    }
                }
                row.addView(btnStudy)
                row.addView(btnBlock)
                card.addView(row)
            }
        }

        contentColumn.addView(card)
    }

    private fun buildAddDomainCard(
        palette: UiThemePrefs.ThemePalette,
        isLight: Boolean,
        textPrimary: Int,
        textSecondary: Int
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@EliteWebShieldActivity, cornerDp = 16f, isHero = false)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }
        card.addView(TextView(this).apply {
            text = "➕ Add Website Rule"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(0, 0, 0, dp(8f))
        })

        val input = EditText(this).apply {
            hint = "Enter domain (e.g. pw.live or instagram.com)"
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            textSize = 13.5f
            isSingleLine = true
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#0F172A"))
                setStroke(dp(1f), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        card.addView(input)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "✓ Add Study (24/7)"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#10B981"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8f)
            }
            setOnClickListener { v ->
                val raw = BlockPolicyEngine.normalizeHost(input.text?.toString())
                if (raw.isBlank() || !raw.contains('.')) {
                    Toast.makeText(this@EliteWebShieldActivity, "Enter a valid domain like pw.live", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SensoryFeedbackEngine.toggle(this@EliteWebShieldActivity, v)
                WebsitePrefs.removeDomain(this@EliteWebShieldActivity, raw)
                WebsitePrefs.addFairDomain(this@EliteWebShieldActivity, raw)
                Toast.makeText(this@EliteWebShieldActivity, "Added $raw to Study (24/7)", Toast.LENGTH_SHORT).show()
                renderScreen()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "🚫 Block Website"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#F43F5E"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { v ->
                val raw = BlockPolicyEngine.normalizeHost(input.text?.toString())
                if (raw.isBlank() || !raw.contains('.')) {
                    Toast.makeText(this@EliteWebShieldActivity, "Enter a valid domain like instagram.com", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SensoryFeedbackEngine.toggle(this@EliteWebShieldActivity, v)
                WebsitePrefs.removeFairDomain(this@EliteWebShieldActivity, raw)
                WebsitePrefs.addDomain(this@EliteWebShieldActivity, raw)
                Toast.makeText(this@EliteWebShieldActivity, "Blocked $raw", Toast.LENGTH_SHORT).show()
                renderScreen()
            }
        })
        card.addView(btnRow)
        contentColumn.addView(card)
    }

    private fun buildDomainListSection(
        title: String,
        subtitle: String,
        domains: List<String>,
        chipColorHex: String,
        emptyMsg: String,
        isLight: Boolean,
        textPrimary: Int,
        textSecondary: Int,
        onRemove: (String) -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@EliteWebShieldActivity, cornerDp = 16f, isHero = false)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }
        card.addView(TextView(this).apply {
            text = "$title (${domains.size})"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(textSecondary)
            setPadding(0, dp(2f), 0, dp(10f))
        })

        if (domains.isEmpty()) {
            card.addView(TextView(this).apply {
                text = emptyMsg
                textSize = 12f
                setTextColor(textSecondary)
            })
        } else {
            val accent = Color.parseColor(chipColorHex)
            domains.forEach { d ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(if (isLight) Color.parseColor("#F8FAFC") else Color.parseColor("#0F1826"))
                        setStroke(dp(1f), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    }
                    setPadding(dp(12f), dp(8f), dp(10f), dp(8f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6f) }
                }
                row.addView(TextView(this).apply {
                    text = d
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = "Remove ✕"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(accent)
                    setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
                    setOnClickListener { onRemove(d) }
                })
                card.addView(row)
            }
        }
        contentColumn.addView(card)
    }
}
