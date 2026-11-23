package com.focusguardpro.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisciplineDataLogicTest {

    @Test
    fun wordCountHandlesWhitespaceAndBlankText() {
        assertEquals(0, DisciplineDataRepository.countWords(""))
        assertEquals(0, DisciplineDataRepository.countWords("   \n\t  "))
        assertEquals(1, DisciplineDataRepository.countWords("Discipline"))
        assertEquals(5, DisciplineDataRepository.countWords("What went well   today?\nGreat!"))
    }

    @Test
    fun dateKeyArithmeticCrossesMonthAndYearBoundaries() {
        assertEquals("20261201", DisciplineDataRepository.addDays("20261130", 1))
        assertEquals("20270101", DisciplineDataRepository.addDays("20261231", 1))
        assertEquals("20261231", DisciplineDataRepository.addDays("20270101", -1))
    }

    @Test
    fun recurrenceComputesDailyWeekdaysAndWeeklyCorrectly() {
        // 2026-11-20 is a Friday
        assertEquals(
            "20261121",
            DisciplineDataRepository.computeNextRecurrenceDate(
                "20261120",
                DisciplineDataRepository.TaskRecurrence.DAILY
            )
        )
        // Friday + WEEKDAYS skips Saturday & Sunday -> Monday 2026-11-23
        assertEquals(
            "20261123",
            DisciplineDataRepository.computeNextRecurrenceDate(
                "20261120",
                DisciplineDataRepository.TaskRecurrence.WEEKDAYS
            )
        )
        // Weekly -> +7 days
        assertEquals(
            "20261127",
            DisciplineDataRepository.computeNextRecurrenceDate(
                "20261120",
                DisciplineDataRepository.TaskRecurrence.WEEKLY
            )
        )
    }

    @Test
    fun monthlySummaryTaskCompletionPercentHandlesZeroTasksGracefully() {
        val noTasks = DisciplineDataRepository.MonthlyDisciplineSummary(
            monthLabel = "November 2026",
            daysInMonth = 30,
            activeDays = 10,
            stepGoalDays = 6,
            recordedStepDays = 12,
            workoutDays = 5,
            totalTasks = 0,
            completedTasks = 0,
            journalDays = 4,
            holidayDays = 1
        )
        assertNull(noTasks.taskCompletionPercent)

        val halfDone = noTasks.copy(totalTasks = 10, completedTasks = 7)
        assertEquals(70, halfDone.taskCompletionPercent)
    }

    @Test
    fun enumParsingFallsBackSafely() {
        assertEquals(
            DisciplineDataRepository.TaskPriority.HIGH,
            DisciplineDataRepository.TaskPriority.fromCode("high")
        )
        assertEquals(
            DisciplineDataRepository.TaskPriority.NORMAL,
            DisciplineDataRepository.TaskPriority.fromCode("unknown")
        )
        assertEquals(
            DisciplineDataRepository.TaskRecurrence.WEEKDAYS,
            DisciplineDataRepository.TaskRecurrence.fromCode("WEEKDAYS")
        )
        assertEquals(
            DisciplineDataRepository.TaskRecurrence.NONE,
            DisciplineDataRepository.TaskRecurrence.fromCode(null)
        )
    }
}
