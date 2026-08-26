package org.avmedia.gshockapi.model

enum class LocationIndicatorCommand(val code: Int) {
    SAVE_CURRENT_LOCATION(0),
    DELETE_SAVED_LOCATION(1),
    CALCULATE_DISTANCE_AND_BEARING(2);

    companion object {
        fun fromCode(code: Int): LocationIndicatorCommand? = entries.firstOrNull { it.code == code }
    }
}
