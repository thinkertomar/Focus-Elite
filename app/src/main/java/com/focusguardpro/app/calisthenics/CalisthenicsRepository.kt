package com.focusguardpro.app.calisthenics

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

object CalisthenicsRepository {

    private var cachedExercises: List<CalisthenicsExercise>? = null
    private var byName: Map<String, CalisthenicsExercise>? = null
    private var byId: Map<String, CalisthenicsExercise>? = null

    fun getExercises(context: Context): List<CalisthenicsExercise> {
        cachedExercises?.let { return it }

        return try {
            context.applicationContext.assets.open("calisthenics_exercises.json").use { inputStream ->
                InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                    val type = object : TypeToken<List<CalisthenicsExercise>>() {}.type
                    val list: List<CalisthenicsExercise> = Gson().fromJson(reader, type)
                    cachedExercises = list
                    byName = list.associateBy { it.name.lowercase() }
                    byId = list.associateBy { it.id }
                    list
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun findByName(context: Context, name: String): CalisthenicsExercise? {
        getExercises(context)
        return byName?.get(name.lowercase())
    }

    fun findById(context: Context, id: String): CalisthenicsExercise? {
        getExercises(context)
        return byId?.get(id)
    }

    fun filterExercises(
        context: Context,
        query: String = "",
        levelFilter: String = "All",
        muscleFilter: String = "All",
        equipmentFilter: String = "All",
        categoryFilter: String = "All",
        favoritesOnly: Boolean = false,
        favoriteIds: Set<String> = emptySet()
    ): List<CalisthenicsExercise> {
        val all = getExercises(context)
        val cleanQuery = query.trim().lowercase()

        return all.filter { ex ->
            val matchesQuery = cleanQuery.isEmpty() ||
                    ex.name.lowercase().contains(cleanQuery) ||
                    ex.primaryMuscles.any { it.lowercase().contains(cleanQuery) } ||
                    ex.secondaryMuscles.any { it.lowercase().contains(cleanQuery) } ||
                    MuscleGroups.of(ex.primaryMuscles.firstOrNull()).lowercase().contains(cleanQuery)

            val matchesLevel = levelFilter == "All" ||
                    ex.level.equals(levelFilter, ignoreCase = true)

            val matchesMuscle = muscleFilter == "All" ||
                    ex.primaryMuscles.any { MuscleGroups.of(it).equals(muscleFilter, ignoreCase = true) } ||
                    ex.secondaryMuscles.any { MuscleGroups.of(it).equals(muscleFilter, ignoreCase = true) }

            val matchesEquipment = when (equipmentFilter) {
                "Bodyweight" -> ex.isBodyweight
                "Home Items" -> !ex.isBodyweight
                else -> true
            }

            val matchesCategory = categoryFilter == "All" ||
                    ex.category.equals(categoryFilter, ignoreCase = true)

            val matchesFavorite = !favoritesOnly || favoriteIds.contains(ex.id)

            matchesQuery && matchesLevel && matchesMuscle && matchesEquipment && matchesCategory && matchesFavorite
        }.sortedWith(
            compareByDescending<CalisthenicsExercise> { favoriteIds.contains(it.id) }.thenBy { it.name }
        )
    }

    fun getAllMuscles(context: Context): List<String> {
        val muscles = mutableSetOf<String>()
        getExercises(context).forEach { ex ->
            muscles.addAll(ex.primaryMuscles.map { it.replaceFirstChar(Char::titlecase) })
        }
        return muscles.sorted()
    }
}
