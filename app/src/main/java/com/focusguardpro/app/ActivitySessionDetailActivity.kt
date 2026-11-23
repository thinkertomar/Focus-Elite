package com.focusguardpro.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Clean, minimalist Physical Activity Session Detail screen combining effortless at-a-glance
 * readability (Display title, date/time range, centered Heart/Active Points + Steps hero,
 * borderless key-value metrics, crisp horizontal split pace bars) with Focus Elite's expandable
 * Strava-grade deep telemetry (Pace Zones, Moving/Elapsed/Paused, Pace Trend graph, GPS status).
 */
class ActivitySessionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ACTIVITY_TYPE = "extra_activity_type"
        const val EXTRA_TIME_RANGE = "extra_time_range"
        const val EXTRA_START_MS = "extra_start_ms"
        const val EXTRA_END_MS = "extra_end_ms"
        const val EXTRA_DISTANCE_KM = "extra_distance_km"
        const val EXTRA_CALORIES = "extra_calories"
        const val EXTRA_MOVE_MIN = "extra_move_min"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_HEART_PTS = "extra_heart_pts"
        const val EXTRA_AVG_PACE_SEC = "extra_avg_pace_sec"
        const val EXTRA_AVG_SPEED_KMH = "extra_avg_speed_kmh"
        const val EXTRA_CADENCE = "extra_cadence"
        const val EXTRA_MOVING_SEC = "extra_moving_sec"
        const val EXTRA_ELAPSED_SEC = "extra_elapsed_sec"
        const val EXTRA_PAUSED_SEC = "extra_paused_sec"
        const val EXTRA_PAUSE_COUNT = "extra_pause_count"
        const val EXTRA_GPS_RECORDED = "extra_gps_recorded"
        const val EXTRA_SOURCE_TYPE = "extra_source_type"
        const val EXTRA_SESSION_KEY = "extra_session_key"

        private const val PREFS_CUSTOM_TITLES = "FocusElite_ActivitySessionTitles_v1"

        fun createIntent(
            context: android.content.Context,
            session: ActivityTelemetryEngine.AutoDetectedJogSession
        ): Intent {
            return Intent(context, ActivitySessionDetailActivity::class.java).apply {
                putExtra(EXTRA_TITLE, session.title)
                putExtra(EXTRA_ACTIVITY_TYPE, session.activityType)
                putExtra(EXTRA_TIME_RANGE, session.timeRangeLabel)
                putExtra(EXTRA_START_MS, session.startTimeMs)
                putExtra(EXTRA_END_MS, session.endTimeMs)
                putExtra(EXTRA_DISTANCE_KM, session.distanceKm)
                putExtra(EXTRA_CALORIES, session.caloriesKcal)
                putExtra(EXTRA_MOVE_MIN, session.moveMinutes)
                putExtra(EXTRA_STEPS, session.steps)
                putExtra(EXTRA_HEART_PTS, session.heartPoints)
                putExtra(EXTRA_AVG_PACE_SEC, session.avgPaceSecPerKm)
                putExtra(EXTRA_AVG_SPEED_KMH, session.avgSpeedKmh)
                putExtra(EXTRA_CADENCE, session.cadenceSpm)
                putExtra(EXTRA_MOVING_SEC, session.movingSeconds)
                putExtra(EXTRA_ELAPSED_SEC, session.elapsedSeconds)
                putExtra(EXTRA_PAUSED_SEC, session.pausedSeconds)
                putExtra(EXTRA_PAUSE_COUNT, session.pauseCount)
                putExtra(EXTRA_GPS_RECORDED, session.gpsRecorded)
                putExtra(EXTRA_SOURCE_TYPE, session.sourceType)
                putExtra(EXTRA_SESSION_KEY, "${session.startTimeMs}_${session.timeRangeLabel}")
            }
        }

        fun getSavedCustomTitle(context: android.content.Context, key: String): String? {
            if (key.isBlank()) return null
            return context.getSharedPreferences(PREFS_CUSTOM_TITLES, MODE_PRIVATE)
                .getString(key, null)
                ?.takeIf { it.isNotBlank() }
        }
    }

    private var isDeepTelemetryExpanded = false
    private lateinit var tvSessionTitle: TextView
    private var sessionKey: String = ""
    private var currentTitle: String = "Walk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val isWhite = UiThemePrefs.isWhiteBackgroundActive(this)
        val isBlack = UiThemePrefs.isBlackBackgroundActive(this)

        val bgColor = when {
            isWhite -> Color.parseColor("#FFFFFF")
            isBlack -> Color.parseColor("#000000")
            else -> Color.parseColor("#111318")
        }
        val textPrimary = if (isWhite) Color.parseColor("#0F172A") else Color.parseColor("#F1F3F4")
        val textSecondary = if (isWhite) Color.parseColor("#475569") else Color.parseColor("#9AA0A6")
        val dividerColor = if (isWhite) Color.parseColor("#E2E8F0") else Color.parseColor("#242832")
        val splitBarBg = if (isWhite) Color.parseColor("#CBD5E1") else Color.parseColor("#354154")
        val splitBarFastBg = if (isWhite) Color.parseColor("#10B981") else Color.parseColor("#1F5A52")
        val surfaceBg = if (isWhite) Color.parseColor("#F8FAFC") else Color.parseColor("#181C24")
        val accentTeal = Color.parseColor("#00D99B")
        val accentBlue = Color.parseColor("#8AB4F8")

        window?.statusBarColor = bgColor
        window?.navigationBarColor = bgColor

        sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY) ?: ""
        val defaultTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Walk"
        currentTitle = getSavedCustomTitle(this, sessionKey) ?: defaultTitle
        val activityType = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: "Walking"
        val timeRange = intent.getStringExtra(EXTRA_TIME_RANGE) ?: ""
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, 0L)
        val distanceKm = intent.getDoubleExtra(EXTRA_DISTANCE_KM, 0.0)
        val calories = intent.getIntExtra(EXTRA_CALORIES, 0)
        val moveMin = intent.getIntExtra(EXTRA_MOVE_MIN, 0)
        val steps = intent.getIntExtra(EXTRA_STEPS, 0)
        val heartPts = intent.getIntExtra(EXTRA_HEART_PTS, 0)
        val avgPaceSec = intent.getFloatExtra(EXTRA_AVG_PACE_SEC, 0f)
        val avgSpeedKmh = intent.getFloatExtra(EXTRA_AVG_SPEED_KMH, 0f)
        val cadence = intent.getIntExtra(EXTRA_CADENCE, 0)
        val movingSec = intent.getIntExtra(EXTRA_MOVING_SEC, moveMin * 60)
        val elapsedSec = intent.getIntExtra(EXTRA_ELAPSED_SEC, movingSec)
        val pausedSec = intent.getIntExtra(EXTRA_PAUSED_SEC, 0)
        val pauseCount = intent.getIntExtra(EXTRA_PAUSE_COUNT, 0)
        val gpsRecorded = intent.getBooleanExtra(EXTRA_GPS_RECORDED, false)
        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE) ?: "Auto-detected"

        val formattedDateSubtitle = if (startMs > 0L && endMs > startMs) {
            val datePart = SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date(startMs))
            val startClock = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(startMs))
            val endClock = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(endMs))
            "$datePart, $startClock – $endClock"
        } else {
            timeRange
        }

        val iconGlyph = when {
            activityType.lowercase(Locale.US).contains("run") || activityType.lowercase(Locale.US).contains("jog") -> "🏃"
            activityType.lowercase(Locale.US).contains("cycl") -> "🚴"
            else -> "🚶"
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
            fitsSystemWindows = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (22 * density).toInt()
            setPadding(padH, (14 * density).toInt(), padH, (28 * density).toInt())
        }
        scroll.addView(root)

        // 1. Minimalist Top Toolbar (← back, ✎ edit title, ↗ share)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            ).apply { bottomMargin = (14 * density).toInt() }
        }
        topBar.addView(TextView(this).apply {
            text = "←"
            textSize = 23f
            setTextColor(textPrimary)
            minWidth = (44 * density).toInt()
            minHeight = (44 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        })
        topBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        topBar.addView(TextView(this).apply {
            text = "✎"
            textSize = 20f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            minWidth = (44 * density).toInt()
            minHeight = (44 * density).toInt()
            contentDescription = "Rename activity"
            setOnClickListener { showRenameDialog() }
        })
        topBar.addView(TextView(this).apply {
            text = "↗"
            textSize = 20f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            minWidth = (44 * density).toInt()
            minHeight = (44 * density).toInt()
            contentDescription = "Share activity summary"
            setOnClickListener {
                val shareText = buildString {
                    append("$currentTitle ($formattedDateSubtitle)\n")
                    append(String.format(Locale.US, "• Distance: %.2f km\n", distanceKm))
                    if (steps > 0) append("• Steps: ${NumberFormat.getIntegerInstance(Locale.US).format(steps)}\n")
                    append("• Move Minutes: $moveMin min · Energy: $calories Cal\n")
                    if (avgPaceSec > 0f) append("• Avg Pace: ${StravaPaceChartView.formatPace(avgPaceSec)}/km")
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Activity"))
            }
        })
        root.addView(topBar)

        // 2. Display Title ("Evening walk") + Date/Time Subtitle
        tvSessionTitle = TextView(this).apply {
            text = currentTitle
            textSize = 31f
            setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * density).toInt() }
        }
        root.addView(tvSessionTitle)

        root.addView(TextView(this).apply {
            text = "$iconGlyph  $formattedDateSubtitle"
            textSize = 14.5f
            setTextColor(textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (26 * density).toInt() }
        })

        // 3. Centered Dual Hero Metrics (Active/Heart Points + Steps)
        val dualHero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (28 * density).toInt() }
        }

        // Heart / Active Intensity Points
        dualHero.addView(TextView(this).apply {
            text = "💚 "
            textSize = 20f
        })
        dualHero.addView(TextView(this).apply {
            text = heartPts.toString()
            textSize = 28f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(0, 0, (24 * density).toInt(), 0)
        })

        // Steps
        dualHero.addView(TextView(this).apply {
            text = "👟 "
            textSize = 20f
        })
        dualHero.addView(TextView(this).apply {
            text = if (steps > 0) NumberFormat.getIntegerInstance(Locale.US).format(steps) else "—"
            textSize = 28f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        root.addView(dualHero)

        // 4. Clean Borderless Key-Value Metric Rows (matching screenshot's simplicity)
        fun addKeyValueRow(icon: String, label: String, value: String, subline: String? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(TextView(this).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (32 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (14 * density).toInt() }
            })
            row.addView(TextView(this).apply {
                text = label
                textSize = 15.5f
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val rightCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            rightCol.addView(TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(textSecondary)
                gravity = Gravity.END
            })
            if (!subline.isNullOrBlank()) {
                rightCol.addView(TextView(this).apply {
                    text = subline
                    textSize = 12.5f
                    setTextColor(textSecondary)
                    gravity = Gravity.END
                })
            }
            row.addView(rightCol)
            root.addView(row)
        }

        addKeyValueRow("◈", "Distance", String.format(Locale.US, "%.2f km", distanceKm))
        addKeyValueRow("🔥", "Energy expended", "$calories Cal")
        addKeyValueRow("🚶", "Move Minutes", moveMin.toString())
        val paceStr = if (avgPaceSec > 0f) "${StravaPaceChartView.formatPace(avgPaceSec)}/km" else "—"
        addKeyValueRow("⏱", "Pace", paceStr, if (avgPaceSec > 0f) "(average)" else null)
        if (cadence > 0) {
            addKeyValueRow("⚡", "Cadence & Speed", "$cadence spm", String.format(Locale.US, "%.1f km/h", avgSpeedKmh))
        }

        // 5. Minimalist Horizontal Splits Table (Distance | Pace (/km) bar | Active time)
        if (distanceKm >= 0.08 && avgPaceSec > 0f) {
            val splitHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (20 * density).toInt()
                    bottomMargin = (10 * density).toInt()
                }
            }
            splitHeader.addView(TextView(this).apply {
                text = "Distance"
                textSize = 13f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams((72 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            splitHeader.addView(TextView(this).apply {
                text = "Pace (/km)"
                textSize = 13f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            splitHeader.addView(TextView(this).apply {
                text = "Active time"
                textSize = 13f
                setTextColor(textSecondary)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((78 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            root.addView(splitHeader)

            val splits = buildCleanKmSplits(distanceKm, avgPaceSec, movingSec, cadence)
            val fastestPace = splits.minOfOrNull { it.paceSec } ?: avgPaceSec
            val slowestPace = (splits.maxOfOrNull { it.paceSec } ?: avgPaceSec).coerceAtLeast(fastestPace + 15f)

            splits.forEach { sp ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * density).toInt() }
                }
                row.addView(TextView(this).apply {
                    text = sp.distLabel
                    textSize = 13.5f
                    setTextColor(textSecondary)
                    layoutParams = LinearLayout.LayoutParams((72 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                })

                // Horizontal slate/emerald bar with pace label inside (matching screenshot!)
                val barWrap = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, (28 * density).toInt(), 1f).apply {
                        marginEnd = (12 * density).toInt()
                    }
                }
                val isFastest = splits.size > 1 && sp.paceSec <= fastestPace + 1f
                val fillFrac = if (splits.size == 1) {
                    0.96f
                } else {
                    (0.55f + 0.43f * ((slowestPace - sp.paceSec) / (slowestPace - fastestPace).coerceAtLeast(1f)))
                        .coerceIn(0.45f, 0.98f)
                }
                val barPill = TextView(this).apply {
                    text = StravaPaceChartView.formatPace(sp.paceSec)
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((10 * density).toInt(), 0, (8 * density).toInt(), 0)
                    background = GradientDrawable().apply {
                        cornerRadius = 5f * density
                        setColor(if (isFastest) splitBarFastBg else splitBarBg)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fillFrac)
                }
                barWrap.addView(barPill)
                if (fillFrac < 0.99f) {
                    barWrap.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fillFrac)
                    })
                }
                row.addView(barWrap)

                row.addView(TextView(this).apply {
                    text = formatClockMinSec(sp.activeSec)
                    textSize = 13.5f
                    setTextColor(textSecondary)
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams((68 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                root.addView(row)
            }
        }

        // 6. Expandable Deep Telemetry Section (Focus Elite's Strava depth without clutter!)
        root.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                topMargin = (20 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
        })

        val deepDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(surfaceBg)
            }
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * density).toInt() }
        }

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val tvToggleLabel = TextView(this).apply {
            text = "Advanced Telemetry & Pace Trend"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentBlue)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvToggleChevron = TextView(this).apply {
            text = "Show ⌄"
            textSize = 13f
            setTextColor(textSecondary)
        }
        toggleRow.addView(tvToggleLabel)
        toggleRow.addView(tvToggleChevron)
        toggleRow.setOnClickListener {
            isDeepTelemetryExpanded = !isDeepTelemetryExpanded
            deepDrawer.visibility = if (isDeepTelemetryExpanded) View.VISIBLE else View.GONE
            tvToggleChevron.text = if (isDeepTelemetryExpanded) "Hide ⌃" else "Show ⌄"
        }
        root.addView(toggleRow)

        // Populate Deep Drawer contents (Moving/Elapsed/Paused + Pace Trend chart + Source metadata)
        val timingLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        }
        fun addDeepPill(label: String, value: String, color: Int) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = value
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color)
            })
            col.addView(TextView(this).apply {
                text = label
                textSize = 10.5f
                setTextColor(textSecondary)
            })
            timingLine.addView(col)
        }
        addDeepPill("Moving Time", formatCompactDur(movingSec), accentTeal)
        addDeepPill("Elapsed Time", formatCompactDur(elapsedSec), accentBlue)
        val pauseText = if (pausedSec > 0) {
            "${formatCompactDur(pausedSec)}${if (pauseCount > 0) " ($pauseCount)" else ""}"
        } else "None"
        addDeepPill("Auto-Paused", pauseText, if (pausedSec > 0) Color.parseColor("#F59E0B") else textSecondary)
        deepDrawer.addView(timingLine)

        if (distanceKm >= 0.25 && avgPaceSec > 0f) {
            val stravaSplits = ActivityTelemetryEngine.generateSplitsForSession(
                distanceKm,
                avgPaceSec,
                if (cadence > 0) cadence else 100
            )
            if (stravaSplits.isNotEmpty()) {
                val chart = StravaPaceChartView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (140 * density).toInt()
                    ).apply { bottomMargin = (10 * density).toInt() }
                    setSplits(stravaSplits, "PACE TREND (min/km · lower is faster)")
                }
                deepDrawer.addView(chart)
            }
        }

        deepDrawer.addView(TextView(this).apply {
            val gpsStatus = if (gpsRecorded) "GPS Satellite Verified" else "Hardware Step & Stride Calibrated"
            text = "Source: $sourceType  •  $gpsStatus"
            textSize = 12f
            setTextColor(textSecondary)
        })
        root.addView(deepDrawer)

        // 7. Minimalist GPS & Location Footer Note (matches screenshot's clean bottom prompt)
        val hasGpsPerm = GpsWalkTracker.hasPermission(this)
        val gpsFooter = TextView(this).apply {
            textSize = 14f
            setTextColor(textSecondary)
            setLineSpacing(4f * density, 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        }
        if (hasGpsPerm) {
            gpsFooter.text = if (gpsRecorded) {
                "✓ GPS distance verification was active for this activity."
            } else {
                "✓ Outdoor GPS tracking is enabled and verifies satellite distance automatically on outdoor walks."
            }
        } else {
            val fullText = "To include GPS-verified distance & maps for automatically tracked activities, turn this feature on in Settings"
            val spannable = SpannableString(fullText)
            val start = fullText.indexOf("Settings")
            if (start >= 0) {
                spannable.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        } catch (_: Exception) {}
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = accentBlue
                        ds.isUnderlineText = false
                    }
                }, start, start + "Settings".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            gpsFooter.text = spannable
            gpsFooter.movementMethod = LinkMovementMethod.getInstance()
            gpsFooter.highlightColor = Color.TRANSPARENT
        }
        root.addView(gpsFooter)

        setContentView(scroll)
    }

    private data class CleanSplitRow(
        val distLabel: String,
        val paceSec: Float,
        val activeSec: Int
    )

    private fun buildCleanKmSplits(
        totalKm: Double,
        avgPaceSec: Float,
        movingSec: Int,
        cadence: Int
    ): List<CleanSplitRow> {
        if (totalKm < 1.15) {
            val splitPace = (avgPaceSec * 0.96f).coerceIn(150f, 1800f)
            return listOf(
                CleanSplitRow(
                    distLabel = String.format(Locale.US, "%.2f km", totalKm),
                    paceSec = splitPace,
                    activeSec = movingSec.coerceAtLeast(15)
                )
            )
        }
        val fullKms = totalKm.toInt().coerceAtMost(15)
        val remainder = totalKm - fullKms
        val rows = mutableListOf<CleanSplitRow>()
        val generated = ActivityTelemetryEngine.generateSplitsForSession(totalKm, avgPaceSec, if (cadence > 0) cadence else 100)
        for (i in 0 until fullKms) {
            val p = generated.getOrNull(i)?.paceSecondsPerKm ?: avgPaceSec
            rows.add(CleanSplitRow("${i + 1}.00 km", p, p.roundToInt()))
        }
        if (remainder >= 0.08) {
            val p = generated.lastOrNull()?.paceSecondsPerKm ?: avgPaceSec
            rows.add(
                CleanSplitRow(
                    distLabel = String.format(Locale.US, "%.2f km", remainder),
                    paceSec = p,
                    activeSec = (p * remainder).roundToInt().coerceAtLeast(15)
                )
            )
        }
        return rows
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(currentTitle)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Activity")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    currentTitle = newTitle
                    tvSessionTitle.text = newTitle
                    if (sessionKey.isNotBlank()) {
                        getSharedPreferences(PREFS_CUSTOM_TITLES, MODE_PRIVATE)
                            .edit()
                            .putString(sessionKey, newTitle)
                            .apply()
                    }
                    Toast.makeText(this, "Renamed to $newTitle", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatClockMinSec(totalSec: Int): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    private fun formatCompactDur(totalSec: Int): String {
        if (totalSec <= 0) return "—"
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }
}
