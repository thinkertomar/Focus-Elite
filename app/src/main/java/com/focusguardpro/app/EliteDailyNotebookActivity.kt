package com.focusguardpro.app

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.AuditRecord
import com.focusguardpro.app.db.DiaryEntry
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Upgraded Daily Time Notebook featuring the Multi-Signal Smart Sleep & Awakening Engine
 * while preserving 100% of Daily Time Breakdown, Offline Activity Logging, and Daily Reflection features.
 */
class EliteDailyNotebookActivity : AppCompatActivity() {

    private val calendar: Calendar = Calendar.getInstance()
    private val dateKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val displayDateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    private var initialAwayMillis: Long = 0L
    private var currentSleepResult: SmartSleepEngine.SleepDetectionResult? = null
    private var isAwakeningsExpanded: Boolean = true

    private lateinit var tvAuditDate: TextView
    private lateinit var cardMorningSleep: LinearLayout
    private lateinit var tvTimelineSummary: TextView
    private lateinit var llLoggedActivitiesList: LinearLayout
    private lateinit var tvNoActivitiesYet: TextView
    private lateinit var cgCategories: ChipGroup
    private lateinit var etActivityDuration: EditText
    private lateinit var etActivityNote: EditText
    private lateinit var etNotebookEntry: EditText
    private lateinit var tvNotebookStatus: TextView
    private lateinit var tvRecentReflectionsContent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audit)

        initialAwayMillis = intent?.getLongExtra("AWAY_TIME_MS", 0L) ?: 0L

        tvAuditDate = findViewById(R.id.tvAuditDate)
        cardMorningSleep = findViewById(R.id.cardMorningSleep)
        tvTimelineSummary = findViewById(R.id.tvTimelineSummary)
        llLoggedActivitiesList = findViewById(R.id.llLoggedActivitiesList)
        tvNoActivitiesYet = findViewById(R.id.tvNoActivitiesYet)
        cgCategories = findViewById(R.id.cgCategories)
        etActivityDuration = findViewById(R.id.etActivityDuration)
        etActivityNote = findViewById(R.id.etActivityNote)
        etNotebookEntry = findViewById(R.id.etNotebookEntry)
        tvNotebookStatus = findViewById(R.id.tvNotebookStatus)
        tvRecentReflectionsContent = findViewById(R.id.tvRecentReflectionsContent)

        setupNavigation()
        setupQuickDurationButtons()
        setupTags()
        setupActionButtons()

        UiThemePrefs.applyToActivity(this)
        loadDayData()
        loadRecentReflections()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val away = intent.getLongExtra("AWAY_TIME_MS", 0L)
        if (away > 0L) initialAwayMillis = away
        loadDayData()
    }

    override fun onResume() {
        super.onResume()
        UiThemePrefs.applyToActivity(this)
        loadDayData()
    }

    private fun startOfDayMs(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun setupNavigation() {
        findViewById<View>(R.id.auditBackButton)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnPrevDay)?.setOnClickListener {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            loadDayData()
        }
        findViewById<View>(R.id.btnNextDay)?.setOnClickListener {
            val today = Calendar.getInstance()
            if (startOfDayMs(calendar) < startOfDayMs(today)) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                loadDayData()
            }
        }
    }

    private fun setupQuickDurationButtons() {
        findViewById<View>(R.id.btnQuick15)?.setOnClickListener { etActivityDuration.setText("15") }
        findViewById<View>(R.id.btnQuick30)?.setOnClickListener { etActivityDuration.setText("30") }
        findViewById<View>(R.id.btnQuick45)?.setOnClickListener { etActivityDuration.setText("45") }
        findViewById<View>(R.id.btnQuick60)?.setOnClickListener { etActivityDuration.setText("60") }
        findViewById<View>(R.id.btnAbhiTime)?.setOnClickListener {
            val pendingMin = (AwayTimeTracker.getPendingAwayMs(this) / 60_000L).toInt()
            if (pendingMin in 1..720) {
                etActivityDuration.setText(pendingMin.toString())
                Toast.makeText(this, "Filled ${pendingMin}m from recent away time", Toast.LENGTH_SHORT).show()
            } else {
                val now = Calendar.getInstance()
                val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now.time)
                val existing = etActivityNote.text.toString().trim()
                etActivityNote.setText(if (existing.isEmpty()) "At $timeStr" else "$existing · $timeStr")
                Toast.makeText(this, "Stamped current time ($timeStr)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTags() {
        fun appendTag(tag: String) {
            val cur = etNotebookEntry.text.toString().trim()
            if (cur.contains(tag)) return
            val updated = if (cur.isEmpty()) "$tag — " else "$cur\n$tag "
            etNotebookEntry.setText(updated)
            etNotebookEntry.setSelection(updated.length)
        }
        findViewById<View>(R.id.tagLaserFocused)?.setOnClickListener { appendTag("🎯 Focused") }
        findViewById<View>(R.id.tagProductive)?.setOnClickListener { appendTag("⚡ High Output") }
        findViewById<View>(R.id.tagDistracted)?.setOnClickListener { appendTag("⚠️ Had Distractions") }
        findViewById<View>(R.id.tagRestDay)?.setOnClickListener { appendTag("🔋 Rest & Recover") }
    }

    private fun selectedCategoryEventType(): String {
        return when (cgCategories.checkedChipId) {
            R.id.chipWorkout -> "AWAY_WORKOUT"
            R.id.chipStudy -> "AWAY_STUDY"
            R.id.chipDistract -> "AWAY_DISTRACT"
            R.id.chipIgnore -> "AWAY_IGNORE"
            R.id.chipSleep -> "AWAY_SLEPT"
            R.id.chipFood -> "AWAY_FOOD"
            R.id.chipChores -> "AWAY_CHORES"
            R.id.chipBreak -> "AWAY_BREAK"
            else -> "AWAY_OTHER"
        }
    }

    private fun setupActionButtons() {
        findViewById<Button>(R.id.btnAddActivity)?.setOnClickListener {
            val mins = etActivityDuration.text.toString().trim().toIntOrNull() ?: 0
            if (mins <= 0) {
                Toast.makeText(this, "Enter duration in minutes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val eventType = selectedCategoryEventType()
            val note = etActivityNote.text.toString().trim().ifEmpty { formatEventTypeLabel(eventType) }
            val dayStart = startOfDayMs(calendar)
            val todayStart = startOfDayMs(Calendar.getInstance())
            val recordTs = if (dayStart == todayStart) {
                System.currentTimeMillis()
            } else {
                dayStart + 12L * 3_600_000L
            }

            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@EliteDailyNotebookActivity)
                        .systemDao()
                        .insertAuditRecord(
                            AuditRecord(
                                id = 0,
                                eventType = eventType,
                                timestamp = recordTs,
                                details = note,
                                durationMillis = mins * 60_000L
                            )
                        )
                }
                etActivityNote.setText("")
                Toast.makeText(this@EliteDailyNotebookActivity, "Added ${mins}m to timeline", Toast.LENGTH_SHORT).show()
                loadDayData()
            }
        }

        val saveBtn = findViewById<Button>(R.id.btnSaveNotebook)
        saveBtn?.setOnClickListener {
            val text = etNotebookEntry.text.toString().trim()
            val dKey = dateKeyFormat.format(calendar.time)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val dao = AppDatabase.getDatabase(this@EliteDailyNotebookActivity).systemDao()
                    val existing = dao.getDiaryEntry(dKey)
                    val isHoliday = existing?.isHoliday ?: false
                    dao.upsertDiaryEntry(
                        DiaryEntry(
                            dateKey = dKey,
                            note = text,
                            isHoliday = isHoliday,
                            lastUpdated = System.currentTimeMillis()
                        )
                    )
                }
                tvNotebookStatus.visibility = View.VISIBLE
                tvNotebookStatus.text = "✓ Saved reflection for ${displayDateFormat.format(calendar.time)}"
                loadRecentReflections()
            }
        }
        val parentGroup = saveBtn?.parent as? LinearLayout
        if (parentGroup != null) {
            val d = resources.displayMetrics.density
            val openJournalBtn = TextView(this).apply {
                text = "Open Full Journal Editor & Searchable Archive ›"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#00B8D4"))
                gravity = Gravity.CENTER
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * d).toInt() }
                background = GradientDrawable().apply {
                    cornerRadius = 10f * d
                    setColor(Color.parseColor("#1400B8D4"))
                    setStroke((1 * d).toInt(), Color.parseColor("#4400B8D4"))
                }
                setOnClickListener {
                    val dKey = dateKeyFormat.format(calendar.time)
                    startActivity(Intent(this@EliteDailyNotebookActivity, EliteJournalActivity::class.java).apply {
                        putExtra(EliteJournalActivity.EXTRA_DATE_KEY, dKey)
                        putExtra(EliteJournalActivity.EXTRA_INITIAL_MODE, EliteJournalActivity.MODE_WRITE)
                    })
                }
            }
            val idx = parentGroup.indexOfChild(saveBtn)
            parentGroup.addView(openJournalBtn, idx + 1)
        }
    }

    private fun loadDayData() {
        val dayStart = startOfDayMs(calendar)
        val dayEnd = dayStart + 86_400_000L
        val todayStart = startOfDayMs(Calendar.getInstance())
        val dKey = dateKeyFormat.format(calendar.time)

        tvAuditDate.text = when (dayStart) {
            todayStart -> "Today · ${displayDateFormat.format(calendar.time)}"
            todayStart - 86_400_000L -> "Yesterday · ${displayDateFormat.format(calendar.time)}"
            else -> displayDateFormat.format(calendar.time)
        }
        tvNotebookStatus.visibility = View.GONE

        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(this@EliteDailyNotebookActivity).systemDao()
                SmartSleepEngine.syncRecentNightsArchive(this@EliteDailyNotebookActivity, lookbackDays = 14)
                val sleepResult = SmartSleepEngine.analyzeSleepForDate(
                    context = this@EliteDailyNotebookActivity,
                    targetDayStartMs = dayStart,
                    fallbackAwayMs = if (dayStart == todayStart) initialAwayMillis else 0L
                )
                val weeklySleep = SmartSleepEngine.getWeeklySleepSummary(
                    context = this@EliteDailyNotebookActivity,
                    endDayStartMs = dayStart
                )
                val records = dao.getAuditRecordsBetween(dayStart, dayEnd)
                    .filter { it.eventType.startsWith("AWAY_") }
                    .sortedByDescending { it.timestamp }
                val diary = dao.getDiaryEntry(dKey)
                val wellbeing = try {
                    UsageStatsHelper.getDayWellbeingSnapshot(
                        this@EliteDailyNotebookActivity,
                        dayStart,
                        if (dayStart == todayStart) System.currentTimeMillis() else dayEnd
                    )
                } catch (_: Throwable) {
                    null
                }
                DayPayload(sleepResult, weeklySleep, records, diary, wellbeing)
            }

            currentSleepResult = payload.sleep
            renderSmartSleepCard(payload.sleep, payload.weeklySleep, dayStart)
            renderTimeBreakdown(payload.wellbeing, payload.records)
            etNotebookEntry.setText(payload.diary?.note ?: "")
        }
    }

    private data class DayPayload(
        val sleep: SmartSleepEngine.SleepDetectionResult,
        val weeklySleep: SmartSleepEngine.WeeklySleepSummary,
        val records: List<AuditRecord>,
        val diary: DiaryEntry?,
        val wellbeing: UsageStatsHelper.WellbeingSnapshot?
    )

    private fun renderSmartSleepCard(
        sleep: SmartSleepEngine.SleepDetectionResult,
        weekly: SmartSleepEngine.WeeklySleepSummary,
        dayStartMs: Long
    ) {
        cardMorningSleep.removeAllViews()
        val density = resources.displayMetrics.density
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        val textSecondary = if (isLight) Color.parseColor("#475569") else Color.parseColor("#94A3B8")
        val surfaceBg = if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#141C2F")
        val dividerColor = if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#243049")
        val accentIndigo = Color.parseColor("#818CF8")
        val accentGreen = Color.parseColor("#10B981")
        val accentAmber = Color.parseColor("#F59E0B")

        // Header Row: Title + Quality/Confidence Badge
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
        }
        headerRow.addView(TextView(this).apply {
            text = "🌙"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
        })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "Smart Sleep & Recovery"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = "${sleep.confidenceLabel} · ${sleep.signalSource}"
            textSize = 11f
            setTextColor(textSecondary)
        })
        headerRow.addView(titleCol)

        if (sleep.hasValidSleep) {
            val scoreColor = when {
                sleep.qualityScore >= 84 -> accentGreen
                sleep.qualityScore >= 68 -> accentIndigo
                else -> accentAmber
            }
            headerRow.addView(TextView(this).apply {
                text = "Grade ${sleep.grade} · ${sleep.qualityScore}/100"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(scoreColor)
                background = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(Color.argb(32, Color.red(scoreColor), Color.green(scoreColor), Color.blue(scoreColor)))
                    setStroke((1f * density).toInt(), scoreColor)
                }
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            })
        }
        cardMorningSleep.addView(headerRow)

        if (sleep.hasValidSleep) {
            // Hero Effective Sleep + Bedtime -> Wake-up Line
            val heroRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() }
            }
            heroRow.addView(TextView(this).apply {
                text = SmartSleepEngine.formatDuration(sleep.effectiveSleepMs)
                textSize = 30f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            heroRow.addView(TextView(this).apply {
                text = " effective sleep"
                textSize = 13f
                setTextColor(accentIndigo)
                setTypeface(typeface, Typeface.BOLD)
                setPadding((4 * density).toInt(), 0, 0, (4 * density).toInt())
            })
            cardMorningSleep.addView(heroRow)

            cardMorningSleep.addView(TextView(this).apply {
                text = "${SmartSleepEngine.formatClock(sleep.bedtimeMs)}  →  ${SmartSleepEngine.formatClock(sleep.wakeTimeMs)}   •   Time in bed: ${SmartSleepEngine.formatDuration(sleep.timeInBedMs)}"
                textSize = 13f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * density).toInt() }
            })

            // Interactive Sleep Hypnogram Timeline Bar (Asleep vs Mid-Night Awakenings)
            val timelineBar = SleepTimelineBarView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (74 * density).toInt()
                ).apply { bottomMargin = (10 * density).toInt() }
                setSleepData(sleep)
            }
            cardMorningSleep.addView(timelineBar)

            // 3-Column Clean Metric Strip (Efficiency / Awakenings / Time Awake)
            val metricStrip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * density).toInt() }
            }
            fun addMetricBox(label: String, value: String, valueColor: Int, addEndMargin: Boolean) {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = 11f * density
                        setColor(surfaceBg)
                    }
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (addEndMargin) marginEnd = (6 * density).toInt()
                    }
                }
                box.addView(TextView(this).apply {
                    text = value
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(valueColor)
                })
                box.addView(TextView(this).apply {
                    text = label
                    textSize = 10.5f
                    setTextColor(textSecondary)
                })
                metricStrip.addView(box)
            }
            addMetricBox(
                "Efficiency",
                "${sleep.efficiencyPct}%",
                if (sleep.efficiencyPct >= 88) accentGreen else accentAmber,
                true
            )
            addMetricBox(
                "Wake-ups",
                sleep.awakenings.size.toString(),
                if (sleep.awakenings.isEmpty()) accentGreen else accentAmber,
                true
            )
            addMetricBox(
                "Awake Time",
                if (sleep.awakeTotalMs >= 60_000L) SmartSleepEngine.formatDuration(sleep.awakeTotalMs) else "0m",
                if (sleep.awakeTotalMs == 0L) accentGreen else accentAmber,
                false
            )
            cardMorningSleep.addView(metricStrip)

            // Insight caption explaining the stitched overnight calculation
            cardMorningSleep.addView(TextView(this).apply {
                text = sleep.insightText
                textSize = 12f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
            })

            // Mid-Night Awakenings Itemized Breakdown (collapsible when present)
            if (sleep.awakenings.isNotEmpty()) {
                val awHeader = TextView(this).apply {
                    text = if (isAwakeningsExpanded) {
                        "Mid-Night Awakenings Detected (${sleep.awakenings.size}) ▴"
                    } else {
                        "Mid-Night Awakenings Detected (${sleep.awakenings.size}) ▾"
                    }
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(accentAmber)
                    setPadding(0, (4 * density).toInt(), 0, (6 * density).toInt())
                    setOnClickListener {
                        isAwakeningsExpanded = !isAwakeningsExpanded
                        renderSmartSleepCard(sleep, weekly, dayStartMs)
                    }
                }
                cardMorningSleep.addView(awHeader)

                if (isAwakeningsExpanded) {
                    sleep.awakenings.forEach { aw ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = GradientDrawable().apply {
                                cornerRadius = 9f * density
                                setColor(if (isLight) Color.parseColor("#FEF3C7") else Color.parseColor("#2A1E0C"))
                            }
                            setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = (5 * density).toInt() }
                        }
                        row.addView(TextView(this).apply {
                            text = "${SmartSleepEngine.formatClock(aw.startMs)} – ${SmartSleepEngine.formatClock(aw.endMs)}"
                            textSize = 12f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(accentAmber)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = (8 * density).toInt() }
                        })
                        row.addView(TextView(this).apply {
                            text = aw.reason
                            textSize = 11.5f
                            setTextColor(textPrimary)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        cardMorningSleep.addView(row)
                    }
                }
            }
        } else {
            cardMorningSleep.addView(TextView(this).apply {
                text = sleep.insightText
                textSize = 13f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (12 * density).toInt() }
            })
        }

        // Action Row: Primary "Log Effective Sleep" + Secondary "Adjust Times"
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }

        val btnLog = Button(this).apply {
            text = when {
                !sleep.hasValidSleep -> "Set & Log Sleep"
                sleep.isAlreadyLogged -> "✓ Logged · Update Sleep"
                else -> "Log ${SmartSleepEngine.formatDuration(sleep.effectiveSleepMs)} to Timeline"
            }
            isAllCaps = false
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@EliteDailyNotebookActivity, R.drawable.bg_button_primary)
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1.45f).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                if (!sleep.hasValidSleep) {
                    showAdjustSleepDialog(sleep, dayStartMs)
                } else {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            SmartSleepEngine.logSleepToTimeline(this@EliteDailyNotebookActivity, sleep, dayStartMs)
                        }
                        Toast.makeText(
                            this@EliteDailyNotebookActivity,
                            "Logged ${SmartSleepEngine.formatDuration(sleep.effectiveSleepMs)} effective sleep",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadDayData()
                    }
                }
            }
        }
        actionRow.addView(btnLog)

        val btnAdjust = Button(this).apply {
            text = "Adjust"
            isAllCaps = false
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentIndigo)
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(surfaceBg)
                setStroke((1.2f * density).toInt(), accentIndigo)
            }
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, (44 * density).toInt(), 0.75f)
            setOnClickListener { showAdjustSleepDialog(sleep, dayStartMs) }
        }
        actionRow.addView(btnAdjust)
        cardMorningSleep.addView(actionRow)

        // Subtle divider + 7-Night Consistency Footer & History Link
        cardMorningSleep.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        })

        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val weeklyText = if (weekly.nightsLogged > 0) {
            "7-Day Avg: ${SmartSleepEngine.formatDuration(weekly.avgEffectiveSleepMs)} · Grade ${weekly.recoveryGrade} (${weekly.nightsLogged}/7 nights · ${weekly.consistencyLabel})"
        } else {
            "7-Day Sleep Tracking · Auto-tracks & archives across days"
        }
        footerRow.addView(TextView(this).apply {
            text = weeklyText
            textSize = 11.5f
            setTextColor(textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        footerRow.addView(TextView(this).apply {
            text = "Sleep Chart ›"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentIndigo)
            setPadding((8 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
            setOnClickListener {
                startActivity(Intent(this@EliteDailyNotebookActivity, EliteSleepHistoryActivity::class.java))
            }
        })
        cardMorningSleep.addView(footerRow)
    }

    private fun showAdjustSleepDialog(
        current: SmartSleepEngine.SleepDetectionResult,
        dayStartMs: Long
    ) {
        val density = resources.displayMetrics.density
        val bedCal = Calendar.getInstance().apply {
            timeInMillis = if (current.bedtimeMs > 0L) current.bedtimeMs else (dayStartMs - 1L * 3_600_000L) // 11:00 PM prev
        }
        val wakeCal = Calendar.getInstance().apply {
            timeInMillis = if (current.wakeTimeMs > 0L) current.wakeTimeMs else (dayStartMs + 6L * 3_600_000L) // 6:00 AM
        }
        var bedHour = bedCal.get(Calendar.HOUR_OF_DAY)
        var bedMin = bedCal.get(Calendar.MINUTE)
        var wakeHour = wakeCal.get(Calendar.HOUR_OF_DAY)
        var wakeMin = wakeCal.get(Calendar.MINUTE)
        var awakeMins = (current.awakeTotalMs / 60_000L).toInt().coerceAtLeast(0)

        fun computeInstants(): Pair<Long, Long> {
            val wake = Calendar.getInstance().apply {
                timeInMillis = dayStartMs
                set(Calendar.HOUR_OF_DAY, wakeHour)
                set(Calendar.MINUTE, wakeMin)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val bed = Calendar.getInstance().apply {
                timeInMillis = dayStartMs
                set(Calendar.HOUR_OF_DAY, bedHour)
                set(Calendar.MINUTE, bedMin)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis >= wake.timeInMillis) {
                    add(Calendar.DAY_OF_YEAR, -1)
                }
            }
            return bed.timeInMillis to wake.timeInMillis
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (12 * density).toInt(), pad, (8 * density).toInt())
        }

        val tvPreview = TextView(this).apply {
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#818CF8"))
            setPadding(0, 0, 0, (12 * density).toInt())
        }

        val btnBed = Button(this).apply { isAllCaps = false }
        val btnWake = Button(this).apply { isAllCaps = false }
        val etAwake = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Total mid-night awake minutes (e.g. 20)"
            setText(awakeMins.toString())
        }

        fun refreshPreview() {
            val (b, w) = computeInstants()
            val aw = etAwake.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: awakeMins
            val inBed = (w - b).coerceAtLeast(0L)
            val eff = (inBed - aw * 60_000L).coerceAtLeast(0L)
            btnBed.text = "Bedtime: ${SmartSleepEngine.formatClock(b)}"
            btnWake.text = "Final Wake-up: ${SmartSleepEngine.formatClock(w)}"
            tvPreview.text = "Effective Sleep: ${SmartSleepEngine.formatDuration(eff)}  (In bed ${SmartSleepEngine.formatDuration(inBed)} − ${aw}m awake)"
        }

        btnBed.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                bedHour = h
                bedMin = m
                refreshPreview()
            }, bedHour, bedMin, false).show()
        }

        btnWake.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                wakeHour = h
                wakeMin = m
                refreshPreview()
            }, wakeHour, wakeMin, false).show()
        }

        container.addView(tvPreview)
        container.addView(btnBed)
        container.addView(btnWake)
        container.addView(TextView(this).apply {
            text = "Mid-night awake interruptions (minutes):"
            textSize = 12.5f
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
        })
        container.addView(etAwake)
        refreshPreview()

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle("Fine-Tune Overnight Sleep")
            .setView(container)
            .setPositiveButton("Save & Log") { _, _ ->
                val (b, w) = computeInstants()
                val aw = etAwake.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                if (w - b < 20L * 60_000L) {
                    Toast.makeText(this, "Sleep span must be at least 20 minutes", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        SmartSleepEngine.saveManualAdjustment(
                            this@EliteDailyNotebookActivity,
                            current.dateKey,
                            b,
                            w,
                            aw
                        )
                        val updated = SmartSleepEngine.analyzeSleepForDate(
                            this@EliteDailyNotebookActivity,
                            dayStartMs
                        )
                        SmartSleepEngine.logSleepToTimeline(
                            this@EliteDailyNotebookActivity,
                            updated,
                            dayStartMs
                        )
                    }
                    Toast.makeText(this@EliteDailyNotebookActivity, "Sleep updated & logged", Toast.LENGTH_SHORT).show()
                    loadDayData()
                }
            }
            .setNegativeButton("Cancel", null)

        if (current.isManualAdjusted) {
            dialogBuilder.setNeutralButton("Reset Auto") { _, _ ->
                SmartSleepEngine.clearManualAdjustment(this, current.dateKey)
                loadDayData()
            }
        }
        dialogBuilder.show()
    }

    private fun renderTimeBreakdown(
        wellbeing: UsageStatsHelper.WellbeingSnapshot?,
        records: List<AuditRecord>
    ) {
        val studyMs = (wellbeing?.studyMs ?: 0L) + records.filter { it.eventType == "AWAY_STUDY" }.sumOf { it.durationMillis }
        val improveMs = (wellbeing?.improveMs ?: 0L) + records.filter { it.eventType == "AWAY_WORKOUT" }.sumOf { it.durationMillis }
        val distractMs = (wellbeing?.distractMs ?: 0L) + records.filter { it.eventType == "AWAY_DISTRACT" }.sumOf { it.durationMillis }
        val sleepMs = records.filter { it.eventType == "AWAY_SLEPT" }.sumOf { it.durationMillis }

        val sleepPart = if (sleepMs > 0L) " · Sleep ${SmartSleepEngine.formatDuration(sleepMs)}" else ""
        tvTimelineSummary.text =
            "Study ${SmartSleepEngine.formatDuration(studyMs)} · Improve ${SmartSleepEngine.formatDuration(improveMs)} · Distract ${SmartSleepEngine.formatDuration(distractMs)}$sleepPart"

        llLoggedActivitiesList.removeAllViews()
        if (records.isEmpty()) {
            tvNoActivitiesYet.visibility = View.VISIBLE
            return
        }
        tvNoActivitiesYet.visibility = View.GONE

        val density = resources.displayMetrics.density
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val textSecondary = if (isLight) Color.parseColor("#475569") else Color.parseColor("#94A3B8")
        val rowBg = if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#141B2B")

        records.forEach { rec ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(rowBg)
                }
                setPadding((12 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this).apply {
                text = "${formatEventTypeLabel(rec.eventType)}  •  ${SmartSleepEngine.formatDuration(rec.durationMillis)}"
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            if (rec.details.isNotBlank()) {
                infoCol.addView(TextView(this).apply {
                    text = rec.details
                    textSize = 11.5f
                    setTextColor(textSecondary)
                })
            }
            row.addView(infoCol)

            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(Color.parseColor("#EF4444"))
                gravity = Gravity.CENTER
                minWidth = (36 * density).toInt()
                minHeight = (36 * density).toInt()
                setOnClickListener {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            AppDatabase.getDatabase(this@EliteDailyNotebookActivity)
                                .systemDao()
                                .deleteAuditRecord(rec.id)
                        }
                        loadDayData()
                    }
                }
            })

            llLoggedActivitiesList.addView(row)
        }
    }

    private fun loadRecentReflections() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                try {
                    AppDatabase.getDatabase(this@EliteDailyNotebookActivity)
                        .systemDao()
                        .getAllDiaryEntries()
                        .filter { it.note.isNotBlank() }
                        .sortedByDescending { it.dateKey }
                        .take(7)
                } catch (_: Throwable) {
                    emptyList()
                }
            }

            if (entries.isEmpty()) {
                tvRecentReflectionsContent.text = "No saved reflections yet. Write your first daily note above!"
            } else {
                val outFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
                tvRecentReflectionsContent.text = entries.joinToString("\n\n") { entry ->
                    val label = try {
                        val parsed = dateKeyFormat.parse(entry.dateKey)
                        if (parsed != null) outFmt.format(parsed) else entry.dateKey
                    } catch (_: Exception) {
                        entry.dateKey
                    }
                    "📅 $label\n${entry.note.trim()}"
                }
            }
        }
    }

    private fun formatEventTypeLabel(eventType: String): String {
        return when (eventType) {
            "AWAY_SLEPT" -> "😴 Sleep"
            "AWAY_WORKOUT" -> "🏋️ Workout"
            "AWAY_STUDY" -> "📚 Study / Work"
            "AWAY_DISTRACT" -> "🎮 Distract"
            "AWAY_IGNORE" -> "⚪ Ignore"
            "AWAY_FOOD" -> "🍽️ Meal"
            "AWAY_CHORES" -> "🧹 Chores"
            "AWAY_BREAK" -> "☕ Break"
            else -> "✍️ Activity"
        }
    }
}
