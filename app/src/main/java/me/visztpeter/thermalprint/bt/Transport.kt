package me.visztpeter.thermalprint.bt

import java.io.Closeable

/** A byte pipe to the printer. Classic SPP and BLE GATT look the same from outside. */
interface Transport : Closeable {
    fun write(bytes: ByteArray)
    fun flush()
    val description: String
}

class PrinterException(message: String, cause: Throwable? = null) : Exception(message, cause)
