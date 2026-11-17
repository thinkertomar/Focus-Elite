package com.focusguardpro.app

import com.focusguardpro.app.calisthenics.ActiveWorkoutSet
import com.focusguardpro.app.calisthenics.CalisthenicsExercise
import com.focusguardpro.app.calisthenics.LoggedSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalisthenicsTest {

    @Test
    fun testActiveWorkoutSetInitializationAndState() {
        val set = ActiveWorkoutSet(
            setNumber = 1,
            previousSummary = "15r",
            reps = 15,
            holdSeconds = 30,
            isCompleted = false
        )

        assertEquals(1, set.setNumber)
        assertEquals("15r", set.previousSummary)
        assertEquals(15, set.reps)
        assertEquals(30, set.holdSeconds)
        assertFalse(set.isCompleted)

        // Test mutating reps & completion
        set.reps += 5
        assertEquals(20, set.reps)

        set.isCompleted = true
        assertTrue(set.isCompleted)
    }

    @Test
    fun testCalisthenicsExerciseDefaults() {
        val exercise = CalisthenicsExercise(
            id = "backpack_squat",
            name = "Backpack Squat",
            level = "beginner",
            equipment = "home item",
            primaryMuscles = listOf("quadriceps", "glutes"),
            instructions = listOf("Wear backpack.", "Squat deep.", "Stand up.")
        )

        assertEquals("backpack_squat", exercise.id)
        assertEquals("Backpack Squat", exercise.name)
        assertEquals("home item", exercise.equipment)
        assertEquals(2, exercise.primaryMuscles.size)
        assertEquals(3, exercise.instructions.size)
        assertEquals("strength", exercise.category)
    }

    @Test
    fun testLoggedSetData() {
        val logged = LoggedSet(
            exerciseName = "Towel Doorframe Row",
            setNumber = 2,
            reps = 12,
            durationSeconds = 0,
            notes = "Set 2"
        )

        assertEquals("Towel Doorframe Row", logged.exerciseName)
        assertEquals(2, logged.setNumber)
        assertEquals(12, logged.reps)
        assertEquals(0, logged.durationSeconds)
        assertEquals("Set 2", logged.notes)
    }
}
