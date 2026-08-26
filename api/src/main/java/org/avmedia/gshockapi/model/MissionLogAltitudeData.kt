package org.avmedia.gshockapi.model

import java.time.LocalDateTime

/** Decoded, live-verified Module 5594 altitude history (DRSP category 0x19). */
data class MissionLogAltitudeData(
    val startTimeUtc: LocalDateTime?,
    val samples: List<Sample>,
    val points: List<Point>,
    val endMarkerIndex: Int?,
) {
    data class Sample(
        val index: Int,
        val altitudeMetres: Int,
        val timestampUtc: LocalDateTime?,
    )

    data class Point(
        val slot: Int,
        val altitudeMetres: Int,
        val timestampUtc: LocalDateTime,
        val metadataHex: String,
    )
}
