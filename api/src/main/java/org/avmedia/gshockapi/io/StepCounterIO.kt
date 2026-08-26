package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.model.StepCounterData
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets
import timber.log.Timber

// ============================================================================
// Pure Functional Core: Step Counter Decoding
// ============================================================================

/**
 * Pure functional core for step counter processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles step count extraction from a fully-reassembled activity-record
 * (life-log) payload -- see StepCounterIO below for why reassembly is
 * required before this can be called.
 *
 * ### Payload Layout (0x26 Activity Record)
 * Confirmed from a real HCI capture (request/ack/first-fragment sequence
 * for category 0x11 = EXERCISE_DATA on ABL-100WE), cross-checked against
 * an independent reference implementation:
 *
 * | Offset | Size | Description |
 * | :--- | :--- | :--- |
 * | 0 | 1 | Header (must be 0x26) |
 * | 1 | 1 | Day of Week |
 * | 2 | 1 | Month |
 * | 3 | 1 | Day of Month (UNCONFIRMED -- see note below) |
 * | 4-5 | 2 | Padding/Unknown |
 * | 6 | 288 | 144 hourly slots (2 bytes each, LE; 0xFFFE = unavailable) |
 * | 294 | 24 | Between-history padding |
 * | 318 | 56 | 14 daily slots (4 bytes each, LE) |
 * | 374 | 4 | Current day total steps (4 bytes, LE) -- CONFIRMED from two
 * |   |   | independent sources (this file's own prior version, and a
 * |   |   | working Python reference implementation), both giving the
 * |   |   | same offset and matching decode against real capture data.
 *
 * UNCONFIRMED:
 *   - The 0xFFFE / 0xFFFFFFFE "unavailable" sentinel convention IS
 *     independently verified: the captured hourly slots (repeated
 *     "FE FF" pairs) decode to exactly 0xFFFE as claimed here.
 *   - byte[3] as "Day of Month" is NOT confirmed -- an earlier version
 *     of this file read the same byte as "hourly slot count" instead
 *     (both are consistent with the one captured value, 0x18=24, since
 *     24 is a plausible value for either interpretation). Only matters
 *     if you need that field; doesn't affect the step-count offsets.
 *   - The layout above only accounts for 378 of the 400 bytes the watch
 *     actually advertises as the total transfer length (confirmed from
 *     the real ack: 0x000190 = 400). The remaining 22 trailing bytes are
 *     NOT modeled here -- unknown content (checksum? reserved? additional
 *     fields?). Doesn't block step-count parsing since that offset (374)
 *     is well within the first 378 bytes, but worth resolving before
 *     treating this layout as fully understood.
 */
@RequiresApi(Build.VERSION_CODES.O)
object StepCounterIOFunctional {
    private const val HEADER_SIZE = 6
    private const val HOURLY_SLOT_COUNT = 144
    private const val HOURLY_SLOT_SIZE = 2
    private const val BETWEEN_HISTORY_PADDING_SIZE = 24
    private const val DAILY_SLOT_COUNT = 14
    private const val DAILY_SLOT_SIZE = 4

    fun parse(payload: ByteArray): StepCounterData? {
        val dailyHistoryOffset = HEADER_SIZE + HOURLY_SLOT_COUNT * HOURLY_SLOT_SIZE +
                BETWEEN_HISTORY_PADDING_SIZE
        val currentDayOffset = dailyHistoryOffset + DAILY_SLOT_COUNT * DAILY_SLOT_SIZE
        if (payload.size < currentDayOffset + DAILY_SLOT_SIZE || payload.firstOrNull()?.toInt() != 0x26) {
            return null
        }

        val hourlySteps = List(HOURLY_SLOT_COUNT) { index ->
            payload.readUnsignedShortOrNull(HEADER_SIZE + index * HOURLY_SLOT_SIZE)
        }
        val dailyHistory = List(DAILY_SLOT_COUNT) { index ->
            payload.readUnsignedIntOrNull(dailyHistoryOffset + index * DAILY_SLOT_SIZE)
        }

        return StepCounterData(
            dayOfWeek = payload[1].toInt() and 0xFF,
            month = payload[2].toInt() and 0xFF,
            dayOfMonth = payload[3].toInt() and 0xFF,
            hourlySteps = hourlySteps,
            dailyHistory = dailyHistory,
            currentDaySteps = payload.readUnsignedIntOrNull(currentDayOffset),
        )
    }

    private fun ByteArray.readUnsignedShortOrNull(offset: Int): Int? {
        if (offset + 2 > size) return null
        val value = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
        return value.takeUnless { it == 0xFFFE } // confirmed against real capture, see doc comment above
    }

    private fun ByteArray.readUnsignedIntOrNull(offset: Int): Int? {
        if (offset + 4 > size) return null
        val value = (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
        return value.takeUnless { it == -2 } // 0xFFFFFFFE
    }
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

@RequiresApi(Build.VERSION_CODES.O)
object StepCounterIO {

    private const val DRSP_CATEGORY_EXERCISE = 0x11

    suspend fun request(): StepCounterData {
        if (!WatchInfo.hasStepCounter) {
            Timber.i("Step counter not supported on watch model: ${WatchInfo.model}")
            return StepCounterData.unavailable()
        }
        return getStepCount()
    }

    private suspend fun getStepCount(): StepCounterData {
        return runCatching {
            val payload = ConvoyTransferIO.request(DRSP_CATEGORY_EXERCISE)
            when (WatchInfo.model) {
                WatchInfo.WatchModel.GG_B100 ->
                    GgB100ProtocolPackets.decodeMissionLogExercise(payload)?.toStepCounterData()
                else -> StepCounterIOFunctional.parse(payload)
            }
                ?: error("failed to parse ${payload.size}B activity record")
        }.onSuccess { stepData ->
            Timber.i("Step count parsed: $stepData")
        }.onFailure { error ->
            Timber.w(error, "StepCounterIO transfer failed")
        }.getOrElse {
            StepCounterData.unavailable()
        }
    }

    /** Legacy entry point retained for binary/source compatibility. */
    fun onReceived(data: String) = Unit
}
