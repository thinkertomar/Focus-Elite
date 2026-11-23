package com.focusguardpro.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Day One-inspired distraction-free Daily Journal & Searchable Chronological History.
 *
 * Strictly preserves `EliteDailyNotebookActivity` (Smart Sleep, Time Breakdown, Away Logs)
 * and syncs directly with Room `DiaryEntry` + `SystemState` metadata & auto-drafts.
 */
class EliteJournalActivity : AppCompatActivity(), SelfThemedDisciplineActivity {

    companion object {
        const val EXTRA_DATE_KEY = "EXTRA_DATE_KEY"
        const val EXTRA_INITIAL_MODE = "EXTRA_INITIAL_MODE" // "write" | "history"
        const val MODE_WRITE = "write"
        const val MODE_HISTORY = "history"

        private val MOOD_OPTIONS = listOf("Focused", "Calm", "Energized", "Neutral", "Tired", "Stressed")
        private val DEFAULT_TAGS = listOf("Deep Work", "Workout", "Discipline", "Gratitude", "Lesson", "Health")
        private val REFLECTION_PROMPTS = listOf(
            "What went well today?",
            "What could I improve?",
            "What is tomorrow's priority?"
        )
    }

    private lateinit var theme: DisciplineTheme.Tokens
    private var selectedDateKey: String = ""
    private var currentMode: String = MODE_WRITE

    // Editor state
    private var selectedMood: String = ""
    private var selectedEnergy: Int = 0
    private val selectedTags = LinkedHashSet<String>()
    private val availableTags = LinkedHashSet<String>(DEFAULT_TAGS)
    private var lastSavedNote: String = ""
    private var lastSavedMood: String = ""
    private var lastSavedEnergy: Int = 0
    private var lastSavedTags: Set<String> = emptySet()
    private var isHydratingEditor: Boolean = false
    private var hasUnsavedChanges: Boolean = false
    private var showPrompts: Boolean = false

    // History state
    private var searchQuery: String = ""
    private var historyMoodFilter: String? = null
    private var historyTaggedOnly: Boolean = false

    // Views
    private lateinit var rootLayout: LinearLayout
    private lateinit var btnTabWrite: TextView
    private lateinit var btnTabHistory: TextView
    private lateinit var writeContainer: ScrollView
    private lateinit var historyContainer: LinearLayout

    // Write views
    private lateinit var tvDatePrimary: TextView
    private lateinit var tvDateSub: TextView
    private lateinit var tvHolidayBadge: TextView
    private lateinit var tvStatusPill: TextView
    private lateinit var tvWordCount: TextView
    private lateinit var tvDiscardDraft: TextView
    private lateinit var etJournalBody: EditText
    private lateinit var moodRow: LinearLayout
    private lateinit var energyRow: LinearLayout
    private lateinit var tagsRow: LinearLayout
    private lateinit var promptsPanel: LinearLayout
    private lateinit var btnTogglePrompts: TextView
    private lateinit var btnSaveEntry: TextView
    private lateinit var btnDeleteEntry: TextView

    // History views
    private lateinit var etSearch: EditText
    private lateinit var historyFilterRow: LinearLayout
    private lateinit var historyListContainer: LinearLayout
    private lateinit var tvHistoryCount: TextView

    private val autoDraftHandler = Handler(Looper.getMainLooper())
    private val autoDraftRunnable = Runnable { persistDraftAsync() }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = DisciplineTheme.resolve(this)

        selectedDateKey = intent?.getStringExtra(EXTRA_DATE_KEY)
            ?.takeIf { it.length == 8 }
            ?: DisciplineDataRepository.todayKey(this)
        currentMode = intent?.getStringExtra(EXTRA_INITIAL_MODE) ?: MODE_WRITE

        buildUi()
        DisciplineTheme.applySystemBars(this, theme)
        switchMode(currentMode)
        loadEditorForDate(selectedDateKey)
        if (currentMode == MODE_HISTORY) {
            loadHistory()
        }
    }

    override fun onPause() {
        super.onPause()
        autoDraftHandler.removeCallbacks(autoDraftRunnable)
        if (hasUnsavedChanges) {
            persistDraftAsync()
        }
    }

    override fun onDestroy() {
        autoDraftHandler.removeCallbacks(autoDraftRunnable)
        super.onDestroy()
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bg)
            fitsSystemWindows = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Compact Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(8f))
        }

        val btnBack = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(44f)
            minHeight = dp(44f)
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        header.addView(btnBack)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4f), 0, dp(8f), 0)
        }
        titleCol.addView(TextView(this).apply {
            text = "Daily Journal"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = "Private offline notebook & archive"
            textSize = 11f
            setTextColor(theme.textSecondary)
        })
        header.addView(titleCol)

        // Mode Switcher (Write | History)
        val segWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 10f, d)
        }
        btnTabWrite = TextView(this).apply {
            text = "Write"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { switchMode(MODE_WRITE) }
        }
        btnTabHistory = TextView(this).apply {
            text = "History"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { switchMode(MODE_HISTORY) }
        }
        segWrap.addView(btnTabWrite)
        segWrap.addView(btnTabHistory)
        header.addView(segWrap)

        rootLayout.addView(header)

        // Subtle divider
        rootLayout.addView(View(this).apply {
            setBackgroundColor(theme.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f))
        })

        // 2. Write Container
        writeContainer = buildWriteView(d)
        rootLayout.addView(writeContainer)

        // 3. History Container
        historyContainer = buildHistoryView(d)
        rootLayout.addView(historyContainer)

        setContentView(rootLayout)
    }

    private fun buildWriteView(d: Float): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(24f))
        }

        // Date navigation bar
        val dateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }

        val btnPrev = TextView(this).apply {
            text = "‹"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(40f)
            minHeight = dp(40f)
            contentDescription = "Previous day"
            setOnClickListener { navigateDateBy(-1) }
        }
        dateBar.addView(btnPrev)

        val dateMid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvDatePrimary = TextView(this).apply {
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
        }
        tvDateSub = TextView(this).apply {
            textSize = 11f
            setTextColor(theme.textSecondary)
        }
        tvHolidayBadge = TextView(this).apply {
            text = "Holiday Scheduled"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.holidayAmber)
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            background = DisciplineTheme.pillDrawable(
                ColorUtils.setAlphaComponent(theme.holidayAmber, 30),
                ColorUtils.setAlphaComponent(theme.holidayAmber, 120),
                d
            )
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
        }
        dateMid.addView(tvDatePrimary)
        dateMid.addView(tvDateSub)
        dateMid.addView(tvHolidayBadge)
        dateBar.addView(dateMid)

        val btnToday = TextView(this).apply {
            text = "Today"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
            setOnClickListener {
                val tk = DisciplineDataRepository.todayKey(this@EliteJournalActivity)
                if (tk != selectedDateKey) {
                    if (hasUnsavedChanges) persistDraftAsync()
                    selectedDateKey = tk
                    loadEditorForDate(selectedDateKey)
                }
            }
        }
        dateBar.addView(btnToday)

        val btnNext = TextView(this).apply {
            text = "›"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(40f)
            minHeight = dp(40f)
            contentDescription = "Next day"
            setOnClickListener { navigateDateBy(1) }
        }
        dateBar.addView(btnNext)
        content.addView(dateBar)

        // Status + Word Count + Actions Row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }

        tvStatusPill = TextView(this).apply {
            text = "New entry"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textSecondary)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = DisciplineTheme.pillDrawable(theme.surfaceAlt, theme.border, d)
        }
        statusRow.addView(tvStatusPill)

        tvDiscardDraft = TextView(this).apply {
            text = "Discard draft"
            textSize = 11f
            setTextColor(theme.overdueCoral)
            setPadding(dp(10f), dp(3f), dp(6f), dp(3f))
            visibility = View.GONE
            setOnClickListener { discardDraftAndReload() }
        }
        statusRow.addView(tvDiscardDraft)

        statusRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1f), 1f)
        })

        tvWordCount = TextView(this).apply {
            text = "0 words"
            textSize = 11.5f
            setTextColor(theme.textMuted)
        }
        statusRow.addView(tvWordCount)
        content.addView(statusRow)

        // Distraction-Free Writing Editor Card
        val editorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 14f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }

        etJournalBody = EditText(this).apply {
            hint = "Write freely about today — wins, lessons, focus clarity, or next priorities..."
            setHintTextColor(theme.textMuted)
            setTextColor(theme.textPrimary)
            textSize = 15f
            setLineSpacing(dp(4f).toFloat(), 1.1f)
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateWordCountLabel(s?.toString() ?: "")
                    if (!isHydratingEditor) {
                        markEditorDirty()
                    }
                }
            })
        }
        editorCard.addView(etJournalBody)

        // Editor bottom action row (Prompts toggle, Delete, Save)
        val editorFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }

        btnTogglePrompts = TextView(this).apply {
            text = "+ Prompts"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
            setOnClickListener {
                showPrompts = !showPrompts
                promptsPanel.visibility = if (showPrompts) View.VISIBLE else View.GONE
                text = if (showPrompts) "− Prompts" else "+ Prompts"
            }
        }
        editorFooter.addView(btnTogglePrompts)

        editorFooter.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1f), 1f)
        })

        btnDeleteEntry = TextView(this).apply {
            text = "Delete"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.overdueCoral)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setOnClickListener { confirmDeleteEntry() }
        }
        editorFooter.addView(btnDeleteEntry)

        btnSaveEntry = TextView(this).apply {
            text = "Save Entry"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accentOnFill)
            setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
            background = DisciplineTheme.pillDrawable(theme.accent, null, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6f) }
            setOnClickListener { saveCurrentEntryExplicitly() }
        }
        editorFooter.addView(btnSaveEntry)

        editorCard.addView(editorFooter)

        // Collapsible Optional Reflection Prompts
        promptsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10f), 0, 0)
        }
        promptsPanel.addView(TextView(this).apply {
            text = "OPTIONAL REFLECTION PROMPTS (TAP TO INSERT)"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(theme.textMuted)
            setPadding(0, 0, 0, dp(6f))
        })
        for ((idx, prompt) in REFLECTION_PROMPTS.withIndex()) {
            promptsPanel.addView(TextView(this).apply {
                text = "${idx + 1}. $prompt"
                textSize = 12.5f
                setTextColor(theme.textPrimary)
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = DisciplineTheme.cardDrawable(theme.surfaceAlt, theme.border, 9f, d)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
                setOnClickListener { insertPromptIntoEditor(prompt) }
            })
        }
        editorCard.addView(promptsPanel)
        content.addView(editorCard)

        // Compact Optional Metadata Card (Mood, Energy 1-5, Tags)
        val metaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }

        // Mood Row
        metaCard.addView(TextView(this).apply {
            text = "MOOD (OPTIONAL)"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(theme.textMuted)
            setPadding(0, 0, 0, dp(6f))
        })
        val moodScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        moodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        moodScroll.addView(moodRow)
        metaCard.addView(moodScroll)

        // Energy 1-5 Row
        val energyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        energyHeader.addView(TextView(this).apply {
            text = "ENERGY LEVEL (1–5)"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(theme.textMuted)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        metaCard.addView(energyHeader)

        energyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        metaCard.addView(energyRow)

        // Tags Row
        val tagsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        tagsHeader.addView(TextView(this).apply {
            text = "TAGS"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(theme.textMuted)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        tagsHeader.addView(TextView(this).apply {
            text = "+ Custom Tag"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(8f), dp(2f), dp(4f), dp(2f))
            setOnClickListener { promptAddCustomTag() }
        })
        metaCard.addView(tagsHeader)

        val tagsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        tagsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tagsScroll.addView(tagsRow)
        metaCard.addView(tagsScroll)

        content.addView(metaCard)

        // Preserved Daily Time Notebook Shortcut (Sleep + Activity Breakdown)
        val notebookLink = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            setOnClickListener {
                startActivity(Intent(this@EliteJournalActivity, EliteDailyNotebookActivity::class.java))
            }
        }
        val nbCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nbCol.addView(TextView(this).apply {
            text = "Daily Time Notebook & Smart Sleep"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
        })
        nbCol.addView(TextView(this).apply {
            text = "Log offline activities, sleep timeline, and daily time breakdown"
            textSize = 11f
            setTextColor(theme.textSecondary)
        })
        notebookLink.addView(nbCol)
        notebookLink.addView(TextView(this).apply {
            text = "Open ›"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
        })
        content.addView(notebookLink)

        scroll.addView(content)
        return scroll
    }

    private fun buildHistoryView(d: Float): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(12f))
        }

        // Search input
        etSearch = EditText(this).apply {
            hint = "Search journal text, tags, moods, or dates..."
            setHintTextColor(theme.textMuted)
            setTextColor(theme.textPrimary)
            textSize = 13.5f
            isSingleLine = true
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 10f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString() ?: ""
                    loadHistory()
                }
            })
        }
        wrap.addView(etSearch)

        // Filter chips row
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        historyFilterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterScroll.addView(historyFilterRow)
        wrap.addView(filterScroll)

        tvHistoryCount = TextView(this).apply {
            textSize = 11f
            setTextColor(theme.textMuted)
            setPadding(dp(2f), 0, 0, dp(6f))
        }
        wrap.addView(tvHistoryCount)

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        historyListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20f))
        }
        listScroll.addView(historyListContainer)
        wrap.addView(listScroll)

        renderHistoryFilters()
        return wrap
    }

    private fun switchMode(mode: String) {
        val d = resources.displayMetrics.density
        if (currentMode == MODE_WRITE && mode == MODE_HISTORY && hasUnsavedChanges) {
            persistDraftAsync()
        }
        currentMode = mode
        val isWrite = mode == MODE_WRITE
        writeContainer.visibility = if (isWrite) View.VISIBLE else View.GONE
        historyContainer.visibility = if (isWrite) View.GONE else View.VISIBLE

        btnTabWrite.background = if (isWrite) DisciplineTheme.cardDrawable(theme.accent, theme.accent, 8f, d, 0f) else null
        btnTabWrite.setTextColor(if (isWrite) theme.accentOnFill else theme.textSecondary)

        btnTabHistory.background = if (!isWrite) DisciplineTheme.cardDrawable(theme.accent, theme.accent, 8f, d, 0f) else null
        btnTabHistory.setTextColor(if (!isWrite) theme.accentOnFill else theme.textSecondary)

        if (!isWrite) {
            loadHistory()
        }
    }

    private fun navigateDateBy(deltaDays: Int) {
        if (hasUnsavedChanges) persistDraftAsync()
        selectedDateKey = DisciplineDataRepository.addDays(selectedDateKey, deltaDays)
        loadEditorForDate(selectedDateKey)
    }

    private fun loadEditorForDate(dateKey: String) {
        autoDraftHandler.removeCallbacks(autoDraftRunnable)
        tvDatePrimary.text = DisciplineDataRepository.formatFriendlyDate(dateKey, this)
        tvDateSub.text = DisciplineDataRepository.formatFullDate(dateKey)

        lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val saved = DisciplineDataRepository.getJournalEntry(this@EliteJournalActivity, dateKey)
                    val draft = DisciplineDataRepository.loadJournalDraft(this@EliteJournalActivity, dateKey)
                    saved to draft
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null to null
            }
            if (isFinishing || isDestroyed) return@launch
            val saved = loaded.first
            val draft = loaded.second

            tvHolidayBadge.visibility = if (saved?.isHoliday == true) View.VISIBLE else View.GONE

            lastSavedNote = saved?.note ?: ""
            lastSavedMood = saved?.meta?.mood ?: ""
            lastSavedEnergy = saved?.meta?.energy ?: 0
            lastSavedTags = saved?.meta?.tags?.toSet() ?: emptySet()

            val useDraft = draft != null && (
                draft.text != lastSavedNote ||
                    draft.mood != lastSavedMood ||
                    draft.energy != lastSavedEnergy ||
                    draft.tags.toSet() != lastSavedTags
                )

            isHydratingEditor = true
            if (useDraft && draft != null) {
                etJournalBody.setText(draft.text)
                selectedMood = draft.mood
                selectedEnergy = draft.energy
                selectedTags.clear()
                selectedTags.addAll(draft.tags)
                availableTags.addAll(draft.tags)
                hasUnsavedChanges = true
                tvDiscardDraft.visibility = View.VISIBLE
                setStatusPill("Draft restored", theme.holidayAmber)
            } else {
                etJournalBody.setText(lastSavedNote)
                selectedMood = lastSavedMood
                selectedEnergy = lastSavedEnergy
                selectedTags.clear()
                selectedTags.addAll(lastSavedTags)
                availableTags.addAll(lastSavedTags)
                hasUnsavedChanges = false
                tvDiscardDraft.visibility = View.GONE
                if (lastSavedNote.isNotBlank() || lastSavedMood.isNotBlank() || lastSavedEnergy > 0 || lastSavedTags.isNotEmpty()) {
                    val savedTime = if ((saved?.lastUpdated ?: 0L) > 0L) {
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(saved!!.lastUpdated))
                    } else ""
                    setStatusPill(if (savedTime.isNotEmpty()) "Saved · $savedTime" else "Saved", theme.stepGreen)
                } else {
                    setStatusPill("New entry", theme.textSecondary)
                }
            }
            isHydratingEditor = false

            btnDeleteEntry.visibility = if (lastSavedNote.isNotBlank() || lastSavedMood.isNotBlank() || lastSavedEnergy > 0 || lastSavedTags.isNotEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }

            updateWordCountLabel(etJournalBody.text?.toString() ?: "")
            renderMoodChips()
            renderEnergyRow()
            renderTagChips()
        }
    }

    private fun setStatusPill(text: String, color: Int) {
        val d = resources.displayMetrics.density
        tvStatusPill.text = text
        tvStatusPill.setTextColor(color)
        tvStatusPill.background = DisciplineTheme.pillDrawable(
            ColorUtils.setAlphaComponent(color, 26),
            ColorUtils.setAlphaComponent(color, 90),
            d
        )
    }

    private fun updateWordCountLabel(text: String) {
        val wc = DisciplineDataRepository.countWords(text)
        tvWordCount.text = if (wc == 1) "1 word" else "$wc words"
    }

    private fun markEditorDirty() {
        val curText = etJournalBody.text?.toString() ?: ""
        val dirty = curText != lastSavedNote ||
            selectedMood != lastSavedMood ||
            selectedEnergy != lastSavedEnergy ||
            selectedTags != lastSavedTags
        hasUnsavedChanges = dirty
        if (dirty) {
            setStatusPill("Unsaved draft…", theme.holidayAmber)
            tvDiscardDraft.visibility = View.VISIBLE
            autoDraftHandler.removeCallbacks(autoDraftRunnable)
            autoDraftHandler.postDelayed(autoDraftRunnable, 1200L)
        } else {
            tvDiscardDraft.visibility = View.GONE
            setStatusPill(if (lastSavedNote.isNotBlank()) "Saved" else "New entry", if (lastSavedNote.isNotBlank()) theme.stepGreen else theme.textSecondary)
        }
    }

    private fun persistDraftAsync() {
        val dk = selectedDateKey
        val text = etJournalBody.text?.toString() ?: ""
        val mood = selectedMood
        val energy = selectedEnergy
        val tags = selectedTags.toList()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                DisciplineDataRepository.saveJournalDraft(
                    this@EliteJournalActivity, dk, text, mood, energy, tags
                )
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && dk == selectedDateKey && hasUnsavedChanges) {
                        setStatusPill("Draft saved locally", theme.holidayAmber)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {}
        }
    }

    private fun discardDraftAndReload() {
        val dk = selectedDateKey
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DisciplineDataRepository.clearJournalDraft(this@EliteJournalActivity, dk)
            }
            loadEditorForDate(dk)
            Toast.makeText(this@EliteJournalActivity, "Draft discarded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrentEntryExplicitly() {
        autoDraftHandler.removeCallbacks(autoDraftRunnable)
        val dk = selectedDateKey
        val text = etJournalBody.text?.toString()?.trim() ?: ""
        val mood = selectedMood
        val energy = selectedEnergy
        val tags = selectedTags.toList()

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                DisciplineDataRepository.saveJournalEntry(
                    this@EliteJournalActivity, dk, text, mood, energy, tags
                )
            }
            lastSavedNote = saved.note
            lastSavedMood = saved.meta.mood
            lastSavedEnergy = saved.meta.energy
            lastSavedTags = saved.meta.tags.toSet()
            hasUnsavedChanges = false
            tvDiscardDraft.visibility = View.GONE
            btnDeleteEntry.visibility = if (saved.note.isNotBlank() || saved.meta.mood.isNotBlank() || saved.meta.energy > 0) View.VISIBLE else View.GONE
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(saved.lastUpdated))
            setStatusPill("Saved · $timeStr", theme.stepGreen)
            Toast.makeText(this@EliteJournalActivity, "Journal entry saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteEntry() {
        AlertDialog.Builder(this)
            .setTitle("Delete Journal Entry?")
            .setMessage("Delete your journal note and tags for ${DisciplineDataRepository.formatFriendlyDate(selectedDateKey, this)}? Any scheduled holiday on this date will be preserved.")
            .setPositiveButton("Delete") { _, _ ->
                val dk = selectedDateKey
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        DisciplineDataRepository.deleteJournalEntry(this@EliteJournalActivity, dk)
                    }
                    Toast.makeText(this@EliteJournalActivity, "Journal entry deleted", Toast.LENGTH_SHORT).show()
                    loadEditorForDate(dk)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun insertPromptIntoEditor(prompt: String) {
        val current = etJournalBody.text?.toString() ?: ""
        val prefix = if (current.isBlank()) "" else if (current.endsWith("\n\n")) "" else if (current.endsWith("\n")) "\n" else "\n\n"
        val updated = "$current$prefix$prompt\n"
        etJournalBody.setText(updated)
        etJournalBody.setSelection(updated.length)
        etJournalBody.requestFocus()
    }

    private fun renderMoodChips() {
        val d = resources.displayMetrics.density
        moodRow.removeAllViews()
        for (mood in MOOD_OPTIONS) {
            val isSel = selectedMood.equals(mood, ignoreCase = true)
            moodRow.addView(TextView(this).apply {
                text = mood
                textSize = 12f
                setTypeface(null, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSel) theme.accentOnFill else theme.textSecondary)
                setPadding(dp(11f), dp(6f), dp(11f), dp(6f))
                background = if (isSel) {
                    DisciplineTheme.pillDrawable(theme.accent, null, d)
                } else {
                    DisciplineTheme.pillDrawable(theme.surfaceAlt, theme.border, d)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6f) }
                setOnClickListener {
                    selectedMood = if (isSel) "" else mood
                    renderMoodChips()
                    markEditorDirty()
                }
            })
        }
    }

    private fun renderEnergyRow() {
        val d = resources.displayMetrics.density
        energyRow.removeAllViews()
        val labels = listOf("1 · Low", "2", "3 · Steady", "4", "5 · Peak")
        for (level in 1..5) {
            val isSel = selectedEnergy == level
            energyRow.addView(TextView(this).apply {
                text = labels[level - 1]
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTypeface(null, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSel) theme.accentOnFill else theme.textSecondary)
                setPadding(dp(6f), dp(7f), dp(6f), dp(7f))
                background = if (isSel) {
                    DisciplineTheme.cardDrawable(theme.accent, theme.accent, 8f, d, 0f)
                } else {
                    DisciplineTheme.cardDrawable(theme.surfaceAlt, theme.border, 8f, d)
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (level < 5) marginEnd = dp(5f)
                }
                setOnClickListener {
                    selectedEnergy = if (isSel) 0 else level
                    renderEnergyRow()
                    markEditorDirty()
                }
            })
        }
    }

    private fun renderTagChips() {
        val d = resources.displayMetrics.density
        tagsRow.removeAllViews()
        for (tag in availableTags) {
            val isSel = selectedTags.contains(tag)
            tagsRow.addView(TextView(this).apply {
                text = "#$tag"
                textSize = 11.5f
                setTypeface(null, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSel) theme.diaryViolet else theme.textSecondary)
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                background = if (isSel) {
                    DisciplineTheme.pillDrawable(
                        ColorUtils.setAlphaComponent(theme.diaryViolet, 32),
                        theme.diaryViolet,
                        d
                    )
                } else {
                    DisciplineTheme.pillDrawable(theme.surfaceAlt, theme.border, d)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6f) }
                setOnClickListener {
                    if (isSel) selectedTags.remove(tag) else selectedTags.add(tag)
                    renderTagChips()
                    markEditorDirty()
                }
            })
        }
    }

    private fun promptAddCustomTag() {
        val input = EditText(this).apply {
            hint = "e.g. ExamPrep, MorningRun, Reading"
            isSingleLine = true
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }
        AlertDialog.Builder(this)
            .setTitle("Add Journal Tag")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val clean = input.text.toString().trim().removePrefix("#").take(24)
                if (clean.isNotEmpty()) {
                    availableTags.add(clean)
                    selectedTags.add(clean)
                    renderTagChips()
                    markEditorDirty()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderHistoryFilters() {
        val d = resources.displayMetrics.density
        historyFilterRow.removeAllViews()

        fun addChip(label: String, active: Boolean, onClick: () -> Unit) {
            historyFilterRow.addView(TextView(this).apply {
                text = label
                textSize = 11.5f
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) theme.accentOnFill else theme.textSecondary)
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                background = if (active) {
                    DisciplineTheme.pillDrawable(theme.accent, null, d)
                } else {
                    DisciplineTheme.pillDrawable(theme.surface, theme.border, d)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6f) }
                setOnClickListener { onClick() }
            })
        }

        addChip("All", historyMoodFilter == null && !historyTaggedOnly) {
            historyMoodFilter = null
            historyTaggedOnly = false
            renderHistoryFilters()
            loadHistory()
        }
        addChip("Tagged", historyTaggedOnly) {
            historyTaggedOnly = !historyTaggedOnly
            renderHistoryFilters()
            loadHistory()
        }
        for (mood in MOOD_OPTIONS) {
            addChip(mood, historyMoodFilter == mood) {
                historyMoodFilter = if (historyMoodFilter == mood) null else mood
                renderHistoryFilters()
                loadHistory()
            }
        }
    }

    private fun loadHistory() {
        val q = searchQuery
        val mood = historyMoodFilter
        val tagged = historyTaggedOnly
        lifecycleScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) {
                    DisciplineDataRepository.searchJournalRecords(
                        this@EliteJournalActivity, q, mood, tagged
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                emptyList()
            }
            if (isFinishing || isDestroyed) return@launch
            renderHistoryList(entries)
        }
    }

    private fun renderHistoryList(entries: List<DisciplineDataRepository.JournalRecord>) {
        val d = resources.displayMetrics.density
        historyListContainer.removeAllViews()
        tvHistoryCount.text = when (entries.size) {
            0 -> "No matching entries"
            1 -> "1 journal entry"
            else -> "${entries.size} journal entries"
        }

        if (entries.isEmpty()) {
            historyListContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20f), dp(36f), dp(20f), dp(36f))
                background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
                addView(TextView(this@EliteJournalActivity).apply {
                    text = if (searchQuery.isBlank() && historyMoodFilter == null && !historyTaggedOnly) {
                        "Your journal archive is empty."
                    } else {
                        "No entries match your search or filter."
                    }
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(theme.textPrimary)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@EliteJournalActivity).apply {
                    text = "Switch to Write to capture today's reflections."
                    textSize = 12f
                    setTextColor(theme.textSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6f), 0, 0)
                })
            })
            return
        }

        for (rec in entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
                setOnClickListener {
                    selectedDateKey = rec.dateKey
                    switchMode(MODE_WRITE)
                    loadEditorForDate(rec.dateKey)
                }
            }

            // Top row: Date + Metadata pills
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = DisciplineDataRepository.formatFriendlyDate(rec.dateKey, this@EliteJournalActivity)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(theme.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (rec.meta.mood.isNotBlank()) {
                top.addView(TextView(this).apply {
                    text = rec.meta.mood
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(theme.accent)
                    setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
                    background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(5f) }
                })
            }
            if (rec.meta.energy in 1..5) {
                top.addView(TextView(this).apply {
                    text = "E${rec.meta.energy}/5"
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(theme.stepGreen)
                    setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
                    background = DisciplineTheme.pillDrawable(
                        ColorUtils.setAlphaComponent(theme.stepGreen, 26), null, d
                    )
                })
            }
            card.addView(top)

            // Preview body
            if (rec.note.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = rec.note.trim()
                    textSize = 13f
                    maxLines = 3
                    setTextColor(theme.textSecondary)
                    setPadding(0, dp(5f), 0, 0)
                })
            }

            // Bottom row: Tags + Word count
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6f), 0, 0)
            }
            val tagsText = if (rec.meta.tags.isNotEmpty()) {
                rec.meta.tags.joinToString("  ") { "#$it" }
            } else {
                ""
            }
            bottom.addView(TextView(this).apply {
                text = tagsText
                textSize = 11f
                setTextColor(theme.diaryViolet)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            bottom.addView(TextView(this).apply {
                text = "${rec.meta.wordCount} words"
                textSize = 10.5f
                setTextColor(theme.textMuted)
            })
            card.addView(bottom)

            historyListContainer.addView(card)
        }
    }
}
