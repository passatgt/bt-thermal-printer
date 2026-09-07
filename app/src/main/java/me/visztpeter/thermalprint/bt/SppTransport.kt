package me.visztpeter.thermalprint.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.OutputStream
import java.util.UUID

/** Bluetooth Classic / RFCOMM — what almost every cheap ESC/POS printer speaks. */
@SuppressLint("MissingPermission")
class SppTransport private constructor(
    private val socket: BluetoothSocket,
    private val out: OutputStream,
    override val description: String,
) : Transport {

    override fun write(bytes: ByteArray) = out.write(bytes)

    override fun flush() = out.flush()

    override fun close() {
        runCatching { out.flush() }
        runCatching { out.close() }
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "SppTransport"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun connect(adapter: BluetoothAdapter, device: BluetoothDevice): SppTransport {
            // Discovery murders throughput on an active RFCOMM link.
            runCatching { adapter.cancelDiscovery() }

            val errors = mutableListOf<String>()

            // 1) The documented path: secure RFCOMM to the SPP service record.
            openOrNull({ device.createRfcommSocketToServiceRecord(SPP_UUID) }, errors)
                ?.let { return it }

            // 2) Insecure — some printers advertise SPP but refuse an authenticated link.
            openOrNull({ device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }, errors)
                ?.let { return it }

            // 3) The classic fallback: no SDP lookup at all, straight to channel 1.
            //    Plenty of these printers ship a broken/absent service record.
            openOrNull({ hiddenChannelSocket(device, 1) }, errors)?.let { return it }

            throw PrinterException(
                "Could not open a Bluetooth connection to ${device.name ?: device.address}. " +
                    "Is the printer on, in range and paired? (${errors.joinToString("; ")})"
            )
        }

        private fun openOrNull(
            factory: () -> BluetoothSocket,
            errors: MutableList<String>,
        ): SppTransport? = try {
            val socket = factory()
            socket.connect()
            SppTransport(socket, socket.outputStream, "Bluetooth Classic (SPP)")
        } catch (t: Throwable) {
            Log.w(TAG, "SPP connect attempt failed", t)
            errors += (t.message ?: t.javaClass.simpleName)
            null
        }

        private fun hiddenChannelSocket(device: BluetoothDevice, channel: Int): BluetoothSocket {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            return m.invoke(device, channel) as BluetoothSocket
        }
    }
}
