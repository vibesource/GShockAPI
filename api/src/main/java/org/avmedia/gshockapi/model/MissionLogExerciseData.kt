package org.avmedia.gshockapi.model

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
}
