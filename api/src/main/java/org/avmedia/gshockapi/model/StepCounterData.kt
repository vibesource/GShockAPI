package org.avmedia.gshockapi.model

/**
 * Shared step-counter view used by supported watch-specific record decoders.
 *
 * Slot and history lengths vary by model. `null` represents the watch's
 * unavailable sentinel rather than a genuine zero-step period.
 */
data class StepCounterData(
    val dayOfWeek: Int,
    val month: Int,
    val dayOfMonth: Int,
    val hourlySteps: List<Int?>,
    val dailyHistory: List<Int?>,
    val currentDaySteps: Int?,
) {
    companion object {
        fun unavailable() = StepCounterData(0, 0, 0, emptyList(), emptyList(), null)
    }
}
