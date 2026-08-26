package org.avmedia.gshockapi.model

import java.time.LocalDate

/** Decoded Module 5594 exercise history (DRSP category 0x11). */
data class MissionLogExerciseData(
    /** Twenty-four watch-provided step slots for the current day. */
    val stepSlots: List<Int?>,
    /** Twenty-four watch-provided exercise slots for the current day. */
    val exerciseSlots: List<Int?>,
    /** Current day followed by up to seven previous daily totals. */
    val dailyTotals: List<DailyTotal>,
) {
    val currentDay: DailyTotal
        get() = dailyTotals.first()

    data class DailyTotal(
        val dayIndex: Int,
        val steps: Long?,
        /** Casio's exercise value; its unit is not identified as calories. */
        val exercise: Long?,
    )

    /** Adapts Module 5594 activity data to the API's shared step model. */
    fun toStepCounterData(date: LocalDate = LocalDate.now()): StepCounterData = StepCounterData(
        dayOfWeek = date.dayOfWeek.value,
        month = date.monthValue,
        dayOfMonth = date.dayOfMonth,
        hourlySteps = stepSlots,
        // QW5594 sends today first, followed by older days. The shared model
        // exposes history in chronological order and keeps today separate.
        dailyHistory = dailyTotals.drop(1).asReversed().map { it.steps.asStepCount() },
        currentDaySteps = currentDay.steps.asStepCount(),
    )

    private fun Long?.asStepCount(): Int? =
        this?.takeIf { it <= Int.MAX_VALUE }?.toInt()
}
