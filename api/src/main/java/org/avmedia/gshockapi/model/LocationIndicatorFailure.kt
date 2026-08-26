package org.avmedia.gshockapi.model

enum class LocationIndicatorFailure(val code: Int) {
    CURRENT_LOCATION_UNAVAILABLE(1),
    UNKNOWN(2),
    NO_SAVED_DESTINATION(3),
}
