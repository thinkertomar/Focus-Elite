package com.focusguardpro.app.calisthenics

import com.focusguardpro.app.db.ExerciseLog
import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CalisthenicsExercise(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("force") val force: String? = null,
    @SerializedName("level") val level: String? = "beginner",
    @SerializedName("mechanic") val mechanic: String? = null,
    @SerializedName("equipment") val equipment: String? = "body only",
    @SerializedName("primaryMuscles") val primaryMuscles: List<String> = emptyList(),
    @SerializedName("secondaryMuscles") val secondaryMuscles: List<String> = emptyList(),
    @SerializedName("instructions") val instructions: List<String> = emptyList(),
    @SerializedName("category") val category: String? = "strength",
    @SerializedName("images") val images: List<String> = emptyList()
)

val CalisthenicsExercise.isHoldBased: Boolean
    get() = category.equals("stretching", true) || force.equals("static", true)

val CalisthenicsExercise.isBodyweight: Boolean
    get() = equipment.equals("body only", true)

val CalisthenicsExercise.muscleGroup: String
    get() = MuscleGroups.of(primaryMuscles.firstOrNull())

val CalisthenicsExercise.categoryLabel: String
    get() = when (category?.lowercase()) {
        "stretching" -> "Stretch"
        "plyometrics" -> "Plyometric"
        else -> "Strength"
    }

object MuscleGroups {
    val ALL = listOf("Chest", "Back", "Shoulders", "Arms", "Core", "Legs", "Glutes")

    fun of(muscle: String?): String = when (muscle?.lowercase()) {
        "chest" -> "Chest"
        "lats", "middle back", "lower back", "traps" -> "Back"
        "shoulders", "neck" -> "Shoulders"
        "biceps", "triceps", "forearms" -> "Arms"
        "abdominals" -> "Core"
        "quadriceps", "hamstrings", "calves", "adductors", "abductors" -> "Legs"
        "glutes" -> "Glutes"
        else -> "Other"
    }
}

data class LoggedSet(
    val exerciseName: String,
    val setNumber: Int,
    val reps: Int,
    val durationSeconds: Int,
    val notes: String = ""
)

data class ActiveWorkoutSet(
    var setNumber: Int,
    var previousSummary: String = "-",
    var reps: Int = 12,
    var holdSeconds: Int = 0,
    var isCompleted: Boolean = false,
    var notes: String = "",
    var prevReps: Int = -1,
    var prevHold: Int = -1
)

/** One exercise performed inside one logged workout (all sets share a timestamp). */
data class HistoryEntry(
    val exerciseName: String,
    val timestamp: Long,
    val sets: List<ExerciseLog>,
    val isPersonalBest: Boolean
) {
    val totalReps: Int get() = sets.sumOf { it.reps }
    val totalHold: Int get() = sets.sumOf { it.durationSeconds }
}

data class HistorySummary(
    val entries: List<HistoryEntry>,
    val workouts: Int,
    val totalSets: Int,
    val totalReps: Int,
    val streakDays: Int,
    val last7DaysSets: List<Int> // oldest -> today
)

object HistoryBuilder {

    fun build(logs: List<ExerciseLog>): HistorySummary {
        val grouped = logs.groupBy { it.timestamp to it.exerciseType }
        val chronological = grouped.entries
            .map { (key, value) -> Triple(key.first, key.second, value.sortedBy { it.id }) }
            .sortedBy { it.first }

        val bestReps = HashMap<String, Int>()
        val bestHold = HashMap<String, Int>()
        val built = ArrayList<HistoryEntry>()

        for ((ts, name, sets) in chronological) {
            val key = name.lowercase()
            val maxReps = sets.maxOfOrNull { it.reps } ?: 0
            val maxHold = sets.maxOfOrNull { it.durationSeconds } ?: 0
            val prevReps = bestReps[key] ?: 0
            val prevHold = bestHold[key] ?: 0
            val hadPrevious = bestReps.containsKey(key)
            val pb = hadPrevious && ((maxReps > prevReps && prevReps > 0) || (maxHold > prevHold && prevHold > 0))
            bestReps[key] = maxOf(prevReps, maxReps)
            bestHold[key] = maxOf(prevHold, maxHold)
            built.add(HistoryEntry(name, ts, sets, pb))
        }

        val zone = ZoneId.systemDefault()
        fun dayOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

        val days = logs.map { dayOf(it.timestamp) }.toSet()
        val today = LocalDate.now(zone)
        var cursor = if (days.contains(today)) today else today.minusDays(1)
        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }

        val setsPerDay = logs.groupingBy { dayOf(it.timestamp) }.eachCount()
        val week = (6 downTo 0).map { setsPerDay[today.minusDays(it.toLong())] ?: 0 }

        return HistorySummary(
            entries = built.sortedByDescending { it.timestamp },
            workouts = logs.map { it.timestamp }.toSet().size,
            totalSets = logs.size,
            totalReps = logs.sumOf { it.reps },
            streakDays = streak,
            last7DaysSets = week
        )
    }
}
