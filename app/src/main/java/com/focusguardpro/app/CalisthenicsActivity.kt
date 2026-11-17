package com.focusguardpro.app

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguardpro.app.calisthenics.ActiveWorkoutSet
import com.focusguardpro.app.calisthenics.CalisthenicsCatalogueAdapter
import com.focusguardpro.app.calisthenics.CalisthenicsExercise
import com.focusguardpro.app.calisthenics.CalisthenicsPrefs
import com.focusguardpro.app.calisthenics.CalisthenicsRepository
import com.focusguardpro.app.calisthenics.ExerciseDemoView
import com.focusguardpro.app.calisthenics.HistoryBuilder
import com.focusguardpro.app.calisthenics.HistoryEntry
import com.focusguardpro.app.calisthenics.WorkoutHistoryAdapter
import com.focusguardpro.app.calisthenics.WorkoutSetAdapter
import com.focusguardpro.app.calisthenics.categoryLabel
import com.focusguardpro.app.calisthenics.formatInstructions
import com.focusguardpro.app.calisthenics.isHoldBased
import com.focusguardpro.app.calisthenics.muscleGroup
import com.focusguardpro.app.calisthenics.MuscleGroups
import com.focusguardpro.app.calisthenics.styleEquipmentBadge
import com.focusguardpro.app.calisthenics.styleLevelBadge
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.ExerciseLog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class CalisthenicsActivity : AppCompatActivity() {

    /** One exercise inside the in-progress workout. */
    private class SessionExercise(
        val exercise: CalisthenicsExercise,
        val sets: MutableList<ActiveWorkoutSet>,
        var prefilledFromHistory: Boolean = false
    )

    // Tabs / containers
    private lateinit var btnTabLog: Button
    private lateinit var btnTabCatalogue: Button
    private lateinit var btnTabHistory: Button
    private lateinit var scrollLogWorkout: View
    private lateinit var layoutCatalogue: View
    private lateinit var layoutHistory: View

    // Top bar
    private lateinit var btnBack: ImageButton
    private lateinit var tvTopSubtitle: TextView

    // Hero card
    private lateinit var demoView: ExerciseDemoView
    private lateinit var tvSelectedExerciseName: TextView
    private lateinit var tvFavoriteActive: TextView
    private lateinit var tvSelectedLevel: TextView
    private lateinit var tvSelectedMuscle: TextView
    private lateinit var tvSelectedEquipment: TextView
    private lateinit var tvSelectedCategory: TextView
    private lateinit var tvSelectedTargets: TextView
    private lateinit var btnChangeExercise: Button
    private lateinit var layoutToggleInstructions: View
    private lateinit var tvSelectedInstructionsToggle: TextView
    private lateinit var tvSelectedInstructionsChevron: TextView
    private lateinit var tvSelectedInstructionsBody: TextView

    // Session strip + sets
    private lateinit var scrollSessionChips: HorizontalScrollView
    private lateinit var llSessionChips: LinearLayout
    private lateinit var llRestChips: LinearLayout
    private lateinit var rvWorkoutSets: RecyclerView
    private lateinit var btnAddSetRow: Button
    private lateinit var setAdapter: WorkoutSetAdapter

    // Rest timer
    private lateinit var layoutRestTimer: View
    private lateinit var tvRestTimerStatus: TextView
    private lateinit var pbRest: ProgressBar
    private lateinit var btnRestPlus15: Button
    private lateinit var btnRestSkip: Button
    private var restTimerJob: Job? = null
    private var remainingRestSeconds = 0
    private var totalRestSeconds = 60

    // Summary
    private lateinit var tvStatExercises: TextView
    private lateinit var tvStatSets: TextView
    private lateinit var tvStatReps: TextView
    private lateinit var tvStatTime: TextView
    private lateinit var btnFinishSession: Button
    private lateinit var btnDiscardSession: Button

    // Catalogue
    private lateinit var etSearchExercises: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var btnFilterEquipAll: Button
    private lateinit var btnFilterEquipBodyweight: Button
    private lateinit var btnFilterEquipHomeItems: Button
    private lateinit var llQuickChips: LinearLayout
    private lateinit var llFilterChips: LinearLayout
    private lateinit var tvCatalogueCount: TextView
    private lateinit var rvCatalogue: RecyclerView
    private lateinit var catalogueAdapter: CalisthenicsCatalogueAdapter

    // History
    private lateinit var tvHistoryWorkouts: TextView
    private lateinit var tvHistoryTotalSets: TextView
    private lateinit var tvHistoryTotalReps: TextView
    private lateinit var tvHistoryStreak: TextView
    private lateinit var llWeekStrip: LinearLayout
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var rvWorkoutHistory: RecyclerView
    private lateinit var historyAdapter: WorkoutHistoryAdapter

    // State
    private var allExercises: List<CalisthenicsExercise> = emptyList()
    private var currentExercise: CalisthenicsExercise? = null
    private val session = LinkedHashMap<String, SessionExercise>()
    private var activeSets: MutableList<ActiveWorkoutSet> = mutableListOf()
    private var sessionStartMs = 0L
    private var isInstructionsExpanded = false

    private var activeEquipmentFilter = "All"
    private var activeLevelFilter = "All"
    private var activeMuscleFilter = "All"
    private var activeCategoryFilter = "All"
    private var favoritesOnly = false

    private val levelCycle = listOf("All", "Beginner", "Intermediate", "Expert")
    private val categoryOptions = listOf(
        "Strength" to "strength",
        "Stretching" to "stretching",
        "Plyometrics" to "plyometrics"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calisthenics)

        totalRestSeconds = CalisthenicsPrefs.restSeconds(this)

        initViews()
        setupListeners()
        setupSetTable()
        setupRestChips()
        loadExerciseCatalogue()
        setupCatalogueUi()
        setupHistoryUi()
        selectTab(0)
        startSessionClock()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (completedSetCount() > 0) {
                    AlertDialog.Builder(this@CalisthenicsActivity)
                        .setTitle("Leave workout?")
                        .setMessage("You have completed sets that haven't been logged yet.")
                        .setPositiveButton("Keep training", null)
                        .setNegativeButton("Discard & leave") { _, _ -> finish() }
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        restTimerJob?.cancel()
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvTopSubtitle = findViewById(R.id.tvTopSubtitle)

        btnTabLog = findViewById(R.id.btnTabLog)
        btnTabCatalogue = findViewById(R.id.btnTabCatalogue)
        btnTabHistory = findViewById(R.id.btnTabHistory)

        scrollLogWorkout = findViewById(R.id.scrollLogWorkout)
        layoutCatalogue = findViewById(R.id.layoutCatalogue)
        layoutHistory = findViewById(R.id.layoutHistory)

        demoView = findViewById(R.id.demoView)
        tvSelectedExerciseName = findViewById(R.id.tvSelectedExerciseName)
        tvFavoriteActive = findViewById(R.id.tvFavoriteActive)
        tvSelectedLevel = findViewById(R.id.tvSelectedLevel)
        tvSelectedMuscle = findViewById(R.id.tvSelectedMuscle)
        tvSelectedEquipment = findViewById(R.id.tvSelectedEquipment)
        tvSelectedCategory = findViewById(R.id.tvSelectedCategory)
        tvSelectedTargets = findViewById(R.id.tvSelectedTargets)
        btnChangeExercise = findViewById(R.id.btnChangeExercise)
        layoutToggleInstructions = findViewById(R.id.layoutToggleInstructions)
        tvSelectedInstructionsToggle = findViewById(R.id.tvSelectedInstructionsToggle)
        tvSelectedInstructionsChevron = findViewById(R.id.tvSelectedInstructionsChevron)
        tvSelectedInstructionsBody = findViewById(R.id.tvSelectedInstructionsBody)

        scrollSessionChips = findViewById(R.id.scrollSessionChips)
        llSessionChips = findViewById(R.id.llSessionChips)
        llRestChips = findViewById(R.id.llRestChips)
        rvWorkoutSets = findViewById(R.id.rvWorkoutSets)
        btnAddSetRow = findViewById(R.id.btnAddSetRow)

        layoutRestTimer = findViewById(R.id.layoutRestTimer)
        tvRestTimerStatus = findViewById(R.id.tvRestTimerStatus)
        pbRest = findViewById(R.id.pbRest)
        btnRestPlus15 = findViewById(R.id.btnRestPlus15)
        btnRestSkip = findViewById(R.id.btnRestSkip)

        tvStatExercises = findViewById(R.id.tvStatExercises)
        tvStatSets = findViewById(R.id.tvStatSets)
        tvStatReps = findViewById(R.id.tvStatReps)
        tvStatTime = findViewById(R.id.tvStatTime)
        btnFinishSession = findViewById(R.id.btnFinishSession)
        btnDiscardSession = findViewById(R.id.btnDiscardSession)

        etSearchExercises = findViewById(R.id.etSearchExercises)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnFilterEquipAll = findViewById(R.id.btnFilterEquipAll)
        btnFilterEquipBodyweight = findViewById(R.id.btnFilterEquipBodyweight)
        btnFilterEquipHomeItems = findViewById(R.id.btnFilterEquipHomeItems)
        llQuickChips = findViewById(R.id.llQuickChips)
        llFilterChips = findViewById(R.id.llFilterChips)
        tvCatalogueCount = findViewById(R.id.tvCatalogueCount)
        rvCatalogue = findViewById(R.id.rvCatalogue)

        tvHistoryWorkouts = findViewById(R.id.tvHistoryWorkouts)
        tvHistoryTotalSets = findViewById(R.id.tvHistoryTotalSets)
        tvHistoryTotalReps = findViewById(R.id.tvHistoryTotalReps)
        tvHistoryStreak = findViewById(R.id.tvHistoryStreak)
        llWeekStrip = findViewById(R.id.llWeekStrip)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        rvWorkoutHistory = findViewById(R.id.rvWorkoutHistory)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        btnTabLog.setOnClickListener { selectTab(0) }
        btnTabCatalogue.setOnClickListener { selectTab(1) }
        btnTabHistory.setOnClickListener { selectTab(2) }

        btnChangeExercise.setOnClickListener { selectTab(1) }

        tvFavoriteActive.setOnClickListener {
            val ex = currentExercise ?: return@setOnClickListener
            CalisthenicsPrefs.toggleFavorite(this, ex.id)
            renderActiveFavorite(ex)
        }

        layoutToggleInstructions.setOnClickListener {
            isInstructionsExpanded = !isInstructionsExpanded
            renderInstructionsState()
        }

        btnAddSetRow.setOnClickListener {
            val last = activeSets.lastOrNull()
            activeSets.add(
                ActiveWorkoutSet(
                    setNumber = activeSets.size + 1,
                    previousSummary = "—",
                    reps = last?.reps ?: 12,
                    holdSeconds = last?.holdSeconds ?: 0
                )
            )
            setAdapter.notifyItemInserted(activeSets.size - 1)
            refreshSessionUi()
        }

        btnRestPlus15.setOnClickListener {
            remainingRestSeconds += 15
            if (remainingRestSeconds > totalRestSeconds) {
                totalRestSeconds = remainingRestSeconds
                pbRest.max = totalRestSeconds
            }
            updateRestTimerDisplay()
        }
        btnRestSkip.setOnClickListener { stopRestTimer() }

        btnFinishSession.setOnClickListener { finishAndSaveSession() }

        btnDiscardSession.setOnClickListener {
            if (session.values.none { it.sets.any { s -> s.isCompleted } } && session.size <= 1) {
                resetWorkout()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Clear this workout?")
                .setMessage("Everything entered in this workout will be removed. Logged history is not affected.")
                .setPositiveButton("Clear") { _, _ -> resetWorkout() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnFilterEquipAll.setOnClickListener { setEquipmentFilter("All") }
        btnFilterEquipBodyweight.setOnClickListener { setEquipmentFilter("Bodyweight") }
        btnFilterEquipHomeItems.setOnClickListener { setEquipmentFilter("Home Items") }
    }

    private fun selectTab(index: Int) {
        val tabs = listOf(btnTabLog, btnTabCatalogue, btnTabHistory)
        tabs.forEachIndexed { i, b ->
            val on = i == index
            b.background = ContextCompat.getDrawable(
                this, if (on) R.drawable.bg_button_primary else R.drawable.bg_button_secondary
            )
            b.setTextColor(ContextCompat.getColor(this, if (on) R.color.text_primary else R.color.text_secondary))
        }
        scrollLogWorkout.visibility = if (index == 0) View.VISIBLE else View.GONE
        layoutCatalogue.visibility = if (index == 1) View.VISIBLE else View.GONE
        layoutHistory.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index == 2) loadHistoryData()
    }

    // ------------------------------------------------------------------
    // Exercise selection / session model
    // ------------------------------------------------------------------

    private fun loadExerciseCatalogue() {
        allExercises = CalisthenicsRepository.getExercises(this)
        tvTopSubtitle.text = "${allExercises.size} exercises · Bodyweight & home essentials"
        btnTabCatalogue.text = "Exercises (${allExercises.size})"

        if (allExercises.isNotEmpty()) {
            val recentId = CalisthenicsPrefs.recents(this).firstOrNull()
            val start = recentId?.let { id -> allExercises.find { it.id == id } }
                ?: allExercises.find { it.name.contains("Push-Up", ignoreCase = true) }
                ?: allExercises.first()
            selectExercise(start, remember = false)
        }
    }

    private fun defaultSets(ex: CalisthenicsExercise): MutableList<ActiveWorkoutSet> {
        val list = mutableListOf<ActiveWorkoutSet>()
        if (ex.isHoldBased) {
            repeat(3) { list.add(ActiveWorkoutSet(setNumber = it + 1, previousSummary = "—", reps = 0, holdSeconds = 30)) }
        } else {
            val reps = when {
                ex.category.equals("plyometrics", true) -> listOf(8, 8, 8)
                ex.level.equals("expert", true) -> listOf(10, 8, 6)
                ex.level.equals("intermediate", true) -> listOf(12, 10, 8)
                else -> listOf(15, 12, 10)
            }
            reps.forEachIndexed { i, r ->
                list.add(ActiveWorkoutSet(setNumber = i + 1, previousSummary = "—", reps = r, holdSeconds = 0))
            }
        }
        return list
    }

    private fun selectExercise(exercise: CalisthenicsExercise, remember: Boolean = true) {
        currentExercise = exercise
        if (remember) CalisthenicsPrefs.pushRecent(this, exercise.id)

        val entry = session.getOrPut(exercise.id) { SessionExercise(exercise, defaultSets(exercise)) }
        activeSets = entry.sets
        if (::setAdapter.isInitialized) setAdapter.submitSets(activeSets)

        tvSelectedExerciseName.text = exercise.name
        styleLevelBadge(tvSelectedLevel, exercise.level)
        tvSelectedMuscle.text = exercise.muscleGroup
        styleEquipmentBadge(tvSelectedEquipment, exercise)
        tvSelectedCategory.text = exercise.categoryLabel
        renderActiveFavorite(exercise)

        val primary = exercise.primaryMuscles.joinToString(", ") { it.replaceFirstChar(Char::titlecase) }
            .ifEmpty { "Full body" }
        val secondary = exercise.secondaryMuscles.joinToString(", ") { it.replaceFirstChar(Char::titlecase) }
        tvSelectedTargets.text =
            if (secondary.isEmpty()) "Targets: $primary" else "Targets: $primary · Also works: $secondary"

        tvSelectedInstructionsBody.text = formatInstructions(exercise.instructions)
        renderInstructionsState()
        demoView.bind(exercise.images)

        if (!entry.prefilledFromHistory) {
            entry.prefilledFromHistory = true
            loadPreviousStatsForExercise(exercise, entry)
        }

        refreshSessionUi()
    }

    private fun renderActiveFavorite(ex: CalisthenicsExercise) {
        val fav = CalisthenicsPrefs.isFavorite(this, ex.id)
        tvFavoriteActive.text = if (fav) "★" else "☆"
        tvFavoriteActive.setTextColor(Color.parseColor(if (fav) "#FFD54F" else "#9AA3B2"))
    }

    private fun renderInstructionsState() {
        tvSelectedInstructionsBody.visibility = if (isInstructionsExpanded) View.VISIBLE else View.GONE
        tvSelectedInstructionsChevron.text = if (isInstructionsExpanded) "▲" else "▼"
        tvSelectedInstructionsToggle.text =
            if (isInstructionsExpanded) "Hide steps" else "Form & step-by-step"
    }

    private fun loadPreviousStatsForExercise(exercise: CalisthenicsExercise, entry: SessionExercise) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.Companion.getDatabase(this@CalisthenicsActivity)
                val logs = db.exerciseDao().getAllLogs()
                    .filter { it.exerciseType.equals(exercise.name, ignoreCase = true) }
                if (logs.isEmpty()) return@launch

                val lastTs = logs.maxOf { it.timestamp }
                val lastSets = logs.filter { it.timestamp == lastTs }.sortedBy { it.id }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val untouched = entry.sets.none { it.isCompleted }

                    // Match set count to last time (capped) and pre-fill values, only for untouched sets.
                    if (untouched) {
                        val target = lastSets.size.coerceIn(1, 6)
                        while (entry.sets.size < target) {
                            entry.sets.add(ActiveWorkoutSet(setNumber = entry.sets.size + 1, previousSummary = "—"))
                        }
                    }
                    entry.sets.forEachIndexed { idx, set ->
                        val prev = lastSets.getOrNull(idx)
                        if (prev != null) {
                            set.prevReps = prev.reps
                            set.prevHold = prev.durationSeconds
                            set.previousSummary = summarise(prev.reps, prev.durationSeconds)
                            if (untouched && !set.isCompleted) {
                                set.reps = prev.reps
                                set.holdSeconds = prev.durationSeconds
                            }
                        } else {
                            set.previousSummary = "—"
                        }
                    }
                    if (currentExercise?.id == exercise.id && ::setAdapter.isInitialized) {
                        setAdapter.submitSets(entry.sets)
                    }
                    refreshSessionUi()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun summarise(reps: Int, hold: Int): String = when {
        reps > 0 && hold > 0 -> "${reps}r\n${hold}s"
        reps > 0 -> "${reps}r"
        hold > 0 -> "${hold}s"
        else -> "—"
    }

    // ------------------------------------------------------------------
    // Sets table, rest timer, session stats
    // ------------------------------------------------------------------

    private fun setupSetTable() {
        rvWorkoutSets.layoutManager = LinearLayoutManager(this)
        rvWorkoutSets.itemAnimator = null

        setAdapter = WorkoutSetAdapter(
            sets = activeSets,
            onSetToggled = { set, isCompleted ->
                if (isCompleted) {
                    if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
                    val ts = sessionStartMs
                    val exName = currentExercise?.name ?: "Calisthenics"
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            AppDatabase.Companion.getDatabase(this@CalisthenicsActivity).exerciseDao().insertLog(
                                ExerciseLog(
                                    id = 0,
                                    timestamp = ts,
                                    exerciseType = exName,
                                    reps = set.reps,
                                    durationSeconds = set.holdSeconds,
                                    notes = "Set ${set.setNumber}"
                                )
                            )
                        } catch (_: Exception) {}
                    }
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    Toast.makeText(this@CalisthenicsActivity, "Set ${set.setNumber} saved! (${set.reps} reps · ${set.holdSeconds}s)", Toast.LENGTH_SHORT).show()
                    startRestTimer(totalRestSeconds)
                }
                refreshSessionUi()
            },
            onSetRemoved = { position -> removeSetAt(position) },
            onValuesChanged = { refreshSessionStats() }
        )
        rvWorkoutSets.adapter = setAdapter

        val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                if (!removeSetAt(pos)) setAdapter.notifyItemChanged(pos)
            }
        }
        ItemTouchHelper(swipe).attachToRecyclerView(rvWorkoutSets)
    }

    /** @return true if the set was removed */
    private fun removeSetAt(position: Int): Boolean {
        if (activeSets.size <= 1) {
            Toast.makeText(this, "A workout needs at least one set", Toast.LENGTH_SHORT).show()
            return false
        }
        if (position !in activeSets.indices) return false
        val removed = activeSets.removeAt(position)
        activeSets.forEachIndexed { idx, s -> s.setNumber = idx + 1 }
        setAdapter.notifyItemRemoved(position)
        setAdapter.notifyItemRangeChanged(position, activeSets.size - position)
        refreshSessionUi()

        Snackbar.make(rvWorkoutSets, "Set removed", Snackbar.LENGTH_LONG)
            .setAction("Undo") {
                val at = position.coerceAtMost(activeSets.size)
                activeSets.add(at, removed)
                activeSets.forEachIndexed { idx, s -> s.setNumber = idx + 1 }
                setAdapter.notifyDataSetChanged()
                refreshSessionUi()
            }
            .show()
        return true
    }

    private fun setupRestChips() {
        llRestChips.removeAllViews()
        val density = resources.displayMetrics.density
        CalisthenicsPrefs.REST_OPTIONS.forEach { secs ->
            val selected = secs == totalRestSeconds
            val chip = TextView(this).apply {
                text = if (secs >= 120) "${secs / 60}m" else "${secs}s"
                textSize = 11f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#9AA3B2"))
                background = ContextCompat.getDrawable(
                    this@CalisthenicsActivity, if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip_off
                )
                layoutParams = LinearLayout.LayoutParams(0, (30 * density).toInt(), 1f).apply {
                    marginEnd = (5 * density).toInt()
                }
                setOnClickListener {
                    totalRestSeconds = secs
                    CalisthenicsPrefs.setRestSeconds(this@CalisthenicsActivity, secs)
                    setupRestChips()
                }
            }
            llRestChips.addView(chip)
        }
    }

    private fun startRestTimer(seconds: Int) {
        restTimerJob?.cancel()
        remainingRestSeconds = seconds
        pbRest.max = seconds
        layoutRestTimer.visibility = View.VISIBLE
        updateRestTimerDisplay()

        restTimerJob = lifecycleScope.launch {
            while (remainingRestSeconds > 0) {
                delay(1000)
                remainingRestSeconds--
                updateRestTimerDisplay()
            }
            window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            tvRestTimerStatus.text = "Go! ✓"
            delay(2000)
            layoutRestTimer.visibility = View.GONE
        }
    }

    private fun stopRestTimer() {
        restTimerJob?.cancel()
        remainingRestSeconds = 0
        layoutRestTimer.visibility = View.GONE
    }

    private fun updateRestTimerDisplay() {
        val m = remainingRestSeconds / 60
        val s = remainingRestSeconds % 60
        tvRestTimerStatus.text = String.format(Locale.US, "%02d:%02d", m, s)
        pbRest.progress = remainingRestSeconds.coerceAtLeast(0)
    }

    private fun startSessionClock() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderElapsed()
                    delay(1000)
                }
            }
        }
    }

    private fun renderElapsed() {
        if (sessionStartMs == 0L) {
            tvStatTime.text = "0:00"
            return
        }
        val total = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        tvStatTime.text =
            if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun completedSetCount(): Int = session.values.sumOf { it.sets.count { s -> s.isCompleted } }

    private fun refreshSessionUi() {
        refreshSessionStats()
        renderSessionChips()
    }

    private fun refreshSessionStats() {
        val doneExercises = session.values.count { it.sets.any { s -> s.isCompleted } }
        val sets = completedSetCount()
        val reps = session.values.sumOf { it.sets.filter { s -> s.isCompleted }.sumOf { s -> s.reps } }
        tvStatExercises.text = doneExercises.toString()
        tvStatSets.text = sets.toString()
        tvStatReps.text = reps.toString()
        renderElapsed()
    }

    private fun renderSessionChips() {
        llSessionChips.removeAllViews()
        val density = resources.displayMetrics.density
        val currentId = currentExercise?.id

        session.values.forEach { entry ->
            val done = entry.sets.count { it.isCompleted }
            val selected = entry.exercise.id == currentId
            val chip = TextView(this).apply {
                text = "${entry.exercise.name}  ·  $done/${entry.sets.size}"
                textSize = 12f
                maxLines = 1
                setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(
                    when {
                        selected -> Color.WHITE
                        done == entry.sets.size && done > 0 -> Color.parseColor("#3DDC97")
                        else -> Color.parseColor("#9AA3B2")
                    }
                )
                background = ContextCompat.getDrawable(
                    this@CalisthenicsActivity, if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip_off
                )
                setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
                setOnClickListener { selectExercise(entry.exercise) }
                setOnLongClickListener {
                    confirmRemoveFromSession(entry)
                    true
                }
            }
            llSessionChips.addView(chip)
        }

        val add = TextView(this).apply {
            text = "+ Add exercise"
            textSize = 12f
            setTextColor(Color.parseColor("#8E87FF"))
            background = ContextCompat.getDrawable(this@CalisthenicsActivity, R.drawable.bg_chip_off)
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { selectTab(1) }
        }
        llSessionChips.addView(add)
    }

    private fun confirmRemoveFromSession(entry: SessionExercise) {
        if (session.size <= 1) {
            Toast.makeText(this, "Keep at least one exercise in the workout", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove ${entry.exercise.name}?")
            .setMessage("Its sets will be dropped from this workout.")
            .setPositiveButton("Remove") { _, _ ->
                session.remove(entry.exercise.id)
                if (currentExercise?.id == entry.exercise.id) {
                    selectExercise(session.values.first().exercise, remember = false)
                } else {
                    refreshSessionUi()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetWorkout() {
        stopRestTimer()
        val keep = currentExercise
        session.clear()
        sessionStartMs = 0L
        if (keep != null) selectExercise(keep, remember = false) else refreshSessionUi()
    }

    private fun finishAndSaveSession() {
        val toSave = session.values
            .map { entry -> entry.exercise to entry.sets.filter { it.isCompleted } }
            .filter { it.second.isNotEmpty() }

        if (toSave.isEmpty()) {
            Toast.makeText(this, "Tick at least one set (✓) before logging.", Toast.LENGTH_LONG).show()
            return
        }

        val now = System.currentTimeMillis()
        val elapsedSeconds = if (sessionStartMs > 0) ((now - sessionStartMs) / 1000).toInt() else 0

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.Companion.getDatabase(this@CalisthenicsActivity)
                toSave.forEach { (exercise, sets) ->
                    sets.forEach { s ->
                        db.exerciseDao().insertLog(
                            ExerciseLog(
                                id = 0,
                                timestamp = now,
                                exerciseType = exercise.name,
                                reps = s.reps,
                                durationSeconds = s.holdSeconds,
                                notes = "Set ${s.setNumber}"
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    stopRestTimer()

                    val totalSets = toSave.sumOf { it.second.size }
                    val totalReps = toSave.sumOf { it.second.sumOf { s -> s.reps } }
                    val lines = toSave.joinToString("\n") { (ex, sets) ->
                        val reps = sets.sumOf { it.reps }
                        val hold = sets.sumOf { it.holdSeconds }
                        val detail = buildString {
                            append("${sets.size} ${if (sets.size == 1) "set" else "sets"}")
                            if (reps > 0) append(" · $reps reps")
                            if (hold > 0) append(" · ${hold}s hold")
                        }
                        "• ${ex.name} — $detail"
                    }
                    val time = if (elapsedSeconds >= 60) "${elapsedSeconds / 60} min" else "${elapsedSeconds}s"

                    AlertDialog.Builder(this@CalisthenicsActivity)
                        .setTitle("Workout saved")
                        .setMessage("$lines\n\n${toSave.size} exercises · $totalSets sets · $totalReps reps · $time")
                        .setPositiveButton("View history") { _, _ ->
                            resetWorkout()
                            selectTab(2)
                        }
                        .setNegativeButton("Done") { _, _ -> resetWorkout() }
                        .setCancelable(false)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CalisthenicsActivity, "Error saving workout: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Exercises tab
    // ------------------------------------------------------------------

    private fun setupCatalogueUi() {
        rvCatalogue.layoutManager = LinearLayoutManager(this)
        catalogueAdapter = CalisthenicsCatalogueAdapter(
            exercises = emptyList(),
            isFavorite = { id -> CalisthenicsPrefs.isFavorite(this, id) },
            onToggleFavorite = { ex -> CalisthenicsPrefs.toggleFavorite(this, ex.id) },
            onSelectExercise = { selectedEx ->
                selectExercise(selectedEx)
                selectTab(0)
                Toast.makeText(this, "Added: ${selectedEx.name}", Toast.LENGTH_SHORT).show()
            }
        )
        rvCatalogue.adapter = catalogueAdapter

        setupQuickChips()
        setupMuscleChips()
        filterCatalogue()

        etSearchExercises.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterCatalogue()
            }
        })

        btnClearSearch.setOnClickListener { etSearchExercises.text.clear() }
    }

    private fun setEquipmentFilter(filter: String) {
        activeEquipmentFilter = filter
        val on = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
        val off = ContextCompat.getDrawable(this, R.drawable.bg_button_secondary)
        val onText = ContextCompat.getColor(this, R.color.text_primary)
        val offText = ContextCompat.getColor(this, R.color.text_secondary)

        listOf(
            btnFilterEquipAll to "All",
            btnFilterEquipBodyweight to "Bodyweight",
            btnFilterEquipHomeItems to "Home Items"
        ).forEach { (btn, key) ->
            btn.background = if (filter == key) on else off
            btn.setTextColor(if (filter == key) onText else offText)
        }
        filterCatalogue()
    }

    private fun makeChip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 12f
            maxLines = 1
            setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(if (selected) Color.WHITE else Color.parseColor("#9AA3B2"))
            background = ContextCompat.getDrawable(
                this@CalisthenicsActivity, if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip_off
            )
            setPadding((14 * density).toInt(), (7 * density).toInt(), (14 * density).toInt(), (7 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun setupQuickChips() {
        llQuickChips.removeAllViews()

        llQuickChips.addView(makeChip("★ Favourites", favoritesOnly) {
            favoritesOnly = !favoritesOnly
            setupQuickChips()
            filterCatalogue()
        })

        categoryOptions.forEach { (label, key) ->
            llQuickChips.addView(makeChip(label, activeCategoryFilter == key) {
                activeCategoryFilter = if (activeCategoryFilter == key) "All" else key
                setupQuickChips()
                filterCatalogue()
            })
        }

        llQuickChips.addView(makeChip("Level: $activeLevelFilter", activeLevelFilter != "All") {
            val next = (levelCycle.indexOf(activeLevelFilter) + 1) % levelCycle.size
            activeLevelFilter = levelCycle[next]
            setupQuickChips()
            filterCatalogue()
        })
    }

    private fun setupMuscleChips() {
        llFilterChips.removeAllViews()
        (listOf("All") + MuscleGroups.ALL).forEach { group ->
            llFilterChips.addView(makeChip(group, group == activeMuscleFilter) {
                activeMuscleFilter = group
                setupMuscleChips()
                filterCatalogue()
            })
        }
    }

    private fun filterCatalogue() {
        val filtered = CalisthenicsRepository.filterExercises(
            context = this,
            query = etSearchExercises.text.toString().trim(),
            levelFilter = activeLevelFilter,
            muscleFilter = activeMuscleFilter,
            equipmentFilter = activeEquipmentFilter,
            categoryFilter = activeCategoryFilter,
            favoritesOnly = favoritesOnly,
            favoriteIds = CalisthenicsPrefs.favorites(this)
        )
        catalogueAdapter.updateList(filtered)
        tvCatalogueCount.text =
            if (favoritesOnly && filtered.isEmpty()) "No favourites yet — tap ☆ on any exercise"
            else "Showing ${filtered.size} exercises"
    }

    // ------------------------------------------------------------------
    // History tab
    // ------------------------------------------------------------------

    private fun setupHistoryUi() {
        rvWorkoutHistory.layoutManager = LinearLayoutManager(this)
        historyAdapter = WorkoutHistoryAdapter(emptyList()) { entry ->
            showHistoryDetailDialog(entry)
        }
        rvWorkoutHistory.adapter = historyAdapter
    }

    private fun showHistoryDetailDialog(entry: HistoryEntry) {
        val lines = entry.sets.mapIndexed { idx, s ->
            val repsStr = if (s.reps > 0) "${s.reps} reps" else ""
            val holdStr = if (s.durationSeconds > 0) "${s.durationSeconds}s hold" else ""
            val detail = listOf(repsStr, holdStr).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Completed" }
            "Set ${idx + 1}: $detail"
        }.joinToString("\n")

        val totalStr = "${entry.sets.size} sets · ${entry.totalReps} total reps · ${entry.totalHold}s total hold"

        AlertDialog.Builder(this)
            .setTitle(entry.exerciseName)
            .setMessage("$lines\n\n$totalStr")
            .setPositiveButton("Repeat Workout") { _, _ ->
                val ex = CalisthenicsRepository.findByName(this, entry.exerciseName)
                if (ex != null) {
                    selectExercise(ex)
                    selectTab(0)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadHistoryData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.Companion.getDatabase(this@CalisthenicsActivity)
                val summary = HistoryBuilder.build(db.exerciseDao().getAllLogs())

                withContext(Dispatchers.Main) {
                    tvHistoryWorkouts.text = summary.workouts.toString()
                    tvHistoryTotalSets.text = summary.totalSets.toString()
                    tvHistoryTotalReps.text = summary.totalReps.toString()
                    tvHistoryStreak.text = summary.streakDays.toString()
                    renderWeekStrip(summary.last7DaysSets)

                    if (summary.entries.isEmpty()) {
                        tvHistoryEmpty.visibility = View.VISIBLE
                        rvWorkoutHistory.visibility = View.GONE
                    } else {
                        tvHistoryEmpty.visibility = View.GONE
                        rvWorkoutHistory.visibility = View.VISIBLE
                        historyAdapter.updateList(summary.entries.take(100))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun renderWeekStrip(counts: List<Int>) {
        llWeekStrip.removeAllViews()
        val density = resources.displayMetrics.density
        val maxCount = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        val today = LocalDate.now(ZoneId.systemDefault())
        val maxBar = 56 * density

        counts.forEachIndexed { i, c ->
            val day = today.minusDays((counts.size - 1 - i).toLong())
            val isToday = i == counts.lastIndex

            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            column.addView(TextView(this).apply {
                text = if (c > 0) c.toString() else ""
                textSize = 10f
                setTextColor(Color.parseColor("#C3CAD6"))
                gravity = Gravity.CENTER
                minHeight = (14 * density).toInt()
            })

            val barHeight = if (c == 0) (4 * density).toInt() else ((c.toFloat() / maxCount) * maxBar).toInt().coerceAtLeast((8 * density).toInt())
            column.addView(View(this).apply {
                background = ContextCompat.getDrawable(
                    this@CalisthenicsActivity, if (c > 0) R.drawable.bg_chip_on else R.drawable.bg_chip_off
                )
                layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), barHeight).apply {
                    topMargin = (4 * density).toInt()
                    bottomMargin = (6 * density).toInt()
                }
            })

            column.addView(TextView(this).apply {
                text = day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                textSize = 11f
                setTypeface(typeface, if (isToday) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (isToday) Color.WHITE else Color.parseColor("#9AA3B2"))
                gravity = Gravity.CENTER
            })

            llWeekStrip.addView(column)
        }
    }
}
