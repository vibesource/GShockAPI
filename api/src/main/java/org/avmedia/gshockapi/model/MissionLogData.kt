package org.avmedia.gshockapi.model

import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets

/** Raw, lossless Module 5594 Mission Log download pending final record decoding. */
data class MissionLogData(
    val state: GgB100ProtocolPackets.MissionLogState,
    val altitudeData: ByteArray,
    val exerciseData: ByteArray,
)
