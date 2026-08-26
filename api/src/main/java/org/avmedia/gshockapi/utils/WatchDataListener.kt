/*
 * Created by Ivo Zivkov (izivkov@gmail.com) on 2022-03-30, 12:06 a.m.
 * Copyright (c) 2022 . All rights reserved.
 * Last modified 2022-03-20, 10:29 a.m.
 */

package org.avmedia.gshockapi.utils

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ble.Connection
import org.avmedia.gshockapi.ble.ICharacteristicDataReceived
import org.avmedia.gshockapi.ble.IDataReceived
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.MessageDispatcher
import org.avmedia.gshockapi.io.ConvoyTransferIO
import org.avmedia.gshockapi.io.FeatureRequestIO
import java.util.UUID

/*
This class accepts data from the watch and calls dataReceived() method on MessageDispatcher class.
From there, the appropriate onReceived() method is called for the corresponding IO class.
 */
@RequiresApi(Build.VERSION_CODES.O)
object WatchDataListener {
    private data class State(
        val dataCallback: IDataReceived? = null
    )

    private var state = State()

    fun init() {
        state = state.copy(
            dataCallback = object : ICharacteristicDataReceived {
                override fun dataReceived(data: String?) {
                    data?.let { MessageDispatcher.onReceived(it, WatchInfo.protocol) }
                }

                override fun dataReceived(source: UUID, data: ByteArray) {
                    when (source) {
                        CasioConstants.CASIO_DATA_REQUEST_CHARACTERISTIC_UUID ->
                            ConvoyTransferIO.onDrspReceived(data)
                        CasioConstants.CASIO_CONVOY_CHARACTERISTIC_UUID ->
                            ConvoyTransferIO.onConvoyReceived(data)
                        CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID ->
                            if (!FeatureRequestIO.onReceived(data)) {
                                dataReceived(data.toHexNotification())
                            }
                        else -> dataReceived(
                            data.toHexNotification(),
                        )
                    }
                }
            },
        )
        setupConnectionListener()
    }

    private fun setupConnectionListener() {
        val eventActions = arrayOf(
            EventAction("ConnectionSetupComplete") {
                state.dataCallback?.let { Connection.setDataCallback(it) }
            },
            EventAction("Disconnect") {
                ConvoyTransferIO.cancel("watch disconnected during convoy transfer")
                FeatureRequestIO.cancel("watch disconnected during feature request")
            },
        )

        ProgressEvents.subscriber.runEventActions(this.javaClass.name, eventActions)
    }

    private fun ByteArray.toHexNotification(): String =
        joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }
}
