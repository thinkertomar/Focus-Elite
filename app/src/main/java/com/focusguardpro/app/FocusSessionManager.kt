package com.focusguardpro.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Manages active deep-work focus sessions, countdown state, intentions,
 * and daily focus session metrics for the executive dashboard.
 */
object FocusSessionManager {

    private const val PREFS_NAME = "focus_sessions_engine_prefs"
    private const val KEY_TODAY_SESSIONS_COUNT = "today_sessions_count"
    private const val KEY_TODAY_FOCUS_MINUTES = "today_focus_minutes"
    private const val KEY_LAST_DATE = "last_session_date"
    private const val KEY_SESSION_HISTORY_JSON = "session_history_json"

    enum class SessionState {
        IDLE, RUNNING, PAUSED, COMPLETED
    }

    data class SessionRecord(
        val id: String,
        val timestampMs: Long,
        val durationMinutes: Int,
        val elapsedSeconds: Int,
        val intention: String,
        val soundTrack: String,
        val completed: Boolean
    )

    data class SessionSnapshot(
        val state: SessionState = SessionState.IDLE,
        val intention: String = "",
        val totalSeconds: Int = 1500, // Default 25 min
        val remainingSeconds: Int = 1500,
        val soundMode: AmbientFocusAudioEngine.SoundMode = AmbientFocusAudioEngine.SoundMode.OFF
    ) {
        val progress: Float
            get() = if (totalSeconds > 0) (totalSeconds - remainingSeconds).toFloat() / totalSeconds else 0f

        val formattedRemaining: String
            get() {
                val m = remainingSeconds / 60
                val s = remainingSeconds % 60
                return String.format(Locale.US, "%02d:%02d", m, s)
            }
    }

    private val _snapshot = MutableStateFlow(SessionSnapshot())
    val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        checkAndResetDailyStats()
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun startSession(
        context: Context,
        durationMinutes: Int,
        intention: String,
        soundMode: AmbientFocusAudioEngine.SoundMode = AmbientFocusAudioEngine.SoundMode.OFF
    ) {
        init(context)
        val totalSecs = (durationMinutes * 60).coerceAtLeast(60)
        _snapshot.value = SessionSnapshot(
            state = SessionState.RUNNING,
            intention = intention.trim().ifBlank { "Deep Work Session" },
            totalSeconds = totalSecs,
            remainingSeconds = totalSecs,
            soundMode = soundMode
        )

        AmbientFocusAudioEngine.setSoundMode(soundMode)
        startTicker()
        EliteCommandWidgetProvider.updateAllWidgets(context)
    }

    fun pauseSession() {
        if (_snapshot.value.state == SessionState.RUNNING) {
            stopTicker()
            AmbientFocusAudioEngine.stop()
            _snapshot.value = _snapshot.value.copy(state = SessionState.PAUSED)
            appContext?.let { EliteCommandWidgetProvider.updateAllWidgets(it) }
        }
    }

    fun resumeSession() {
        if (_snapshot.value.state == SessionState.PAUSED) {
            _snapshot.value = _snapshot.value.copy(state = SessionState.RUNNING)
            AmbientFocusAudioEngine.setSoundMode(_snapshot.value.soundMode)
            startTicker()
            appContext?.let { EliteCommandWidgetProvider.updateAllWidgets(it) }
        }
    }

    fun stopSession(context: Context, completed: Boolean = false) {
        init(context)
        val current = _snapshot.value
        stopTicker()
        AmbientFocusAudioEngine.stop()

        val elapsed = (current.totalSeconds - current.remainingSeconds).coerceAtLeast(0)
        val record = SessionRecord(
            id = UUID.randomUUID().toString().take(8),
            timestampMs = System.currentTimeMillis(),
            durationMinutes = current.totalSeconds / 60,
            elapsedSeconds = elapsed,
            intention = current.intention,
            soundTrack = current.soundMode.displayName,
            completed = completed
        )
        saveSessionToHistory(record)

        if (completed) {
            val completedMinutes = (current.totalSeconds / 60).coerceAtLeast(1)
            recordCompletedSession(completedMinutes)
            _snapshot.value = _snapshot.value.copy(
                state = SessionState.COMPLETED,
                remainingSeconds = 0
            )
        } else {
            _snapshot.value = SessionSnapshot(state = SessionState.IDLE)
        }
        EliteCommandWidgetProvider.updateAllWidgets(context)
    }

    fun completeSession() {
        appContext?.let { stopSession(it, completed = true) } ?: run {
            stopTicker()
            AmbientFocusAudioEngine.stop()
            _snapshot.value = _snapshot.value.copy(
                state = SessionState.COMPLETED,
                remainingSeconds = 0
            )
        }
    }

    private fun startTicker() {
        stopTicker()
        tickerRunnable = object : Runnable {
            override fun run() {
                val current = _snapshot.value
                if (current.state == SessionState.RUNNING) {
                    val nextSecs = current.remainingSeconds - 1
                    if (nextSecs <= 0) {
                        completeSession()
                    } else {
                        _snapshot.value = current.copy(remainingSeconds = nextSecs)
                        if (nextSecs % 15 == 0) {
                            appContext?.let { EliteCommandWidgetProvider.updateAllWidgets(it) }
                        }
                        mainHandler.postDelayed(this, 1000L)
                    }
                }
            }
        }
        mainHandler.postDelayed(tickerRunnable!!, 1000L)
    }

    private fun stopTicker() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun checkAndResetDailyStats() {
        val p = prefs() ?: return
        val today = todayKey()
        val lastDate = p.getString(KEY_LAST_DATE, "")
        if (lastDate != today) {
            p.edit()
                .putString(KEY_LAST_DATE, today)
                .putInt(KEY_TODAY_SESSIONS_COUNT, 0)
                .putInt(KEY_TODAY_FOCUS_MINUTES, 0)
                .apply()
        }
    }

    private fun recordCompletedSession(minutes: Int) {
        checkAndResetDailyStats()
        val p = prefs() ?: return
        val currentCount = p.getInt(KEY_TODAY_SESSIONS_COUNT, 0) + 1
        val currentMins = p.getInt(KEY_TODAY_FOCUS_MINUTES, 0) + minutes
        p.edit()
            .putInt(KEY_TODAY_SESSIONS_COUNT, currentCount)
            .putInt(KEY_TODAY_FOCUS_MINUTES, currentMins)
            .apply()
    }

    private fun saveSessionToHistory(record: SessionRecord) {
        val p = prefs() ?: return
        val list = getSessionHistoryInternal().toMutableList()
        list.add(0, record)
        val capped = list.take(100) // Keep last 100 sessions
        val arr = JSONArray()
        for (item in capped) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestampMs", item.timestampMs)
                put("durationMinutes", item.durationMinutes)
                put("elapsedSeconds", item.elapsedSeconds)
                put("intention", item.intention)
                put("soundTrack", item.soundTrack)
                put("completed", item.completed)
            }
            arr.put(obj)
        }
        p.edit().putString(KEY_SESSION_HISTORY_JSON, arr.toString()).apply()
    }

    private fun getSessionHistoryInternal(): List<SessionRecord> {
        val p = prefs() ?: return emptyList()
        val raw = p.getString(KEY_SESSION_HISTORY_JSON, null) ?: return emptyList()
        val result = mutableListOf<SessionRecord>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    SessionRecord(
                        id = obj.getString("id"),
                        timestampMs = obj.getLong("timestampMs"),
                        durationMinutes = obj.getInt("durationMinutes"),
                        elapsedSeconds = obj.getInt("elapsedSeconds"),
                        intention = obj.getString("intention"),
                        soundTrack = obj.getString("soundTrack"),
                        completed = obj.getBoolean("completed")
                    )
                )
            }
        } catch (_: Exception) {}
        return result
    }

    fun getSessionHistory(context: Context): List<SessionRecord> {
        init(context)
        return getSessionHistoryInternal()
    }

    fun getTodaySessionsCount(context: Context): Int {
        init(context)
        return prefs()?.getInt(KEY_TODAY_SESSIONS_COUNT, 0) ?: 0
    }

    fun getTodayFocusMinutes(context: Context): Int {
        init(context)
        return prefs()?.getInt(KEY_TODAY_FOCUS_MINUTES, 0) ?: 0
    }
}
