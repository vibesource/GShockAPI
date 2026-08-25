/*
 * Created by Ivo Zivkov (izivkov@gmail.com) on 2022-03-30, 12:06 a.m.
 * Copyright (c) 2022 . All rights reserved.
 * Last modified 2022-03-14, 1:48 p.m.
 */

package org.avmedia.gshockapi.ble

import java.util.UUID

fun interface IDataReceived {
    fun dataReceived(data: String?)
}

/** Optional source-aware extension; the original callback remains unchanged. */
interface ICharacteristicDataReceived : IDataReceived {
    fun dataReceived(source: UUID, data: ByteArray)
}
