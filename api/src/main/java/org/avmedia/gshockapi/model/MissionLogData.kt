package org.avmedia.gshockapi.model

import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets

/** Raw, lossless Module 5594 Mission Log download pending final record decoding. */
data class MissionLogData(
    val state: GgB100ProtocolPackets.MissionLogState,
    val altitudeData: ByteArray,
    val exerciseData: ByteArray,
) {
    /** Null only when the watch returns an as-yet unknown altitude block layout. */
    val altitude: MissionLogAltitudeData?
        get() = GgB100ProtocolPackets.decodeMissionLogAltitude(altitudeData)

    /** Null only when the watch returns an unknown exercise block layout. */
    val exercise: MissionLogExerciseData?
        get() = GgB100ProtocolPackets.decodeMissionLogExercise(exerciseData)
}
