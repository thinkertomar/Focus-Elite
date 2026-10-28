package com.focusguardpro.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.focusguardpro.app.calisthenics.CalisthenicsPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DataExportManager
 * Local-first private database exporter for Focus Elite.
 * Compiles sessions, habits, discipline covenant, and focus rules into JSON and CSV.
 */
object DataExportManager {

    enum class ExportFormat {
        JSON,
        CSV
    }

    fun exportAndShare(context: Context, format: ExportFormat) {
        try {
            val file = when (format) {
                ExportFormat.JSON -> generateJsonExport(context)
                ExportFormat.CSV -> generateCsvExport(context)
            }

            if (file.exists()) {
                val mimeType = if (format == ExportFormat.JSON) "application/json" else "text/csv"
                shareFile(context, file, mimeType)
            } else {
                Toast.makeText(context, "Export file generation failed.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateJsonExport(context: Context): File {
        val root = JSONObject()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        root.put("export_version", 2)
        root.put("app", "Focus Elite Idealist Edition")
        root.put("export_timestamp", timestamp)
        root.put("device_time_epoch_ms", System.currentTimeMillis())

        // 1. Focus Session History
        val sessionsArr = JSONArray()
        val sessions: List<FocusSessionManager.SessionRecord> = FocusSessionManager.getSessionHistory(context)
        for (s in sessions) {
            val obj = JSONObject().apply {
                put("session_id", s.id)
                put("intention", s.intention)
                put("duration_minutes", s.durationMinutes)
                put("elapsed_seconds", s.elapsedSeconds)
                put("completed", s.completed)
                put("timestamp_ms", s.timestampMs)
                put("sound_track", s.soundTrack)
                put("date_formatted", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.timestampMs)))
            }
            sessionsArr.put(obj)
        }
        root.put("focus_sessions", sessionsArr)

        // 2. Focus Layer Config
        val startMins = FocusLayerManager.getStartMinutes(context)
        val endMins = FocusLayerManager.getEndMinutes(context)
        val layerObj = JSONObject().apply {
            put("start_time", FocusLayerManager.formatMinutesOfDay(startMins))
            put("end_time", FocusLayerManager.formatMinutesOfDay(endMins))
            put("window_summary", FocusLayerManager.formatWindowSummary(context))
            put("strict_lock_enabled", FocusLayerManager.isStrictLockEnabled(context))
            put("active_days_mode", FocusLayerManager.getActiveDaysMode(context))
            put("adaptive_calendar_sync_enabled", FocusLayerManager.isAdaptiveCalendarSyncEnabled(context))
        }
        root.put("focus_layer_config", layerObj)

        // 3. Discipline Covenant & Theme
        val covenantObj = JSONObject().apply {
            put("covenant_sealed", UiThemePrefs.isCovenantSealed(context))
            put("reclaimed_hours_per_day", UiThemePrefs.getCovenantHours(context))
            put("theme_palette", UiThemePrefs.getSelectedPalette(context).name)
        }
        root.put("discipline_covenant", covenantObj)

        // 4. Calisthenics Favorites & Settings
        val calisthenicsObj = JSONObject().apply {
            val favsArr = JSONArray()
            for (f in CalisthenicsPrefs.favorites(context)) {
                favsArr.put(f)
            }
            put("favorites", favsArr)
            put("recents", JSONArray(CalisthenicsPrefs.recents(context)))
            put("rest_seconds", CalisthenicsPrefs.restSeconds(context))
        }
        root.put("calisthenics_profile", calisthenicsObj)

        // Write to Cache Dir
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "focus_elite_backup_$timestamp.json")
        FileWriter(file).use { writer ->
            writer.write(root.toString(2))
        }
        return file
    }

    private fun generateCsvExport(context: Context): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "focus_elite_sessions_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.append("SessionID,Date,DurationMinutes,ElapsedSeconds,Completed,SoundTrack,Intention\n")
            val sessions: List<FocusSessionManager.SessionRecord> = FocusSessionManager.getSessionHistory(context)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            for (s in sessions) {
                val cleanIntention = s.intention.replace("\"", "\"\"")
                writer.append("\"${s.id}\",")
                writer.append("\"${sdf.format(Date(s.timestampMs))}\",")
                writer.append("${s.durationMinutes},")
                writer.append("${s.elapsedSeconds},")
                writer.append("${s.completed},")
                writer.append("\"${s.soundTrack}\",")
                writer.append("\"$cleanIntention\"\n")
            }
        }
        return file
    }

    private fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Focus Elite Data Export (${file.name})")
            putExtra(Intent.EXTRA_TEXT, "Exported personal productivity and focus records from Focus Elite.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Export Focus Data"))
    }
}
