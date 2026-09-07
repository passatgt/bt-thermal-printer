package me.visztpeter.thermalprint.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fallback for printers that only expose a BLE "transparent serial" characteristic
 * instead of Classic SPP. Same bytes, just dribbled out one MTU at a time.
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class BleTransport private constructor(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
    private val chunkSize: Int,
    private val session: Session,
) : Transport {

    override val description: String = "Bluetooth LE (GATT)"

    private val writeType: Int =
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (session.disconnected) throw PrinterException("Printer disconnected mid-job")
            val n = minOf(chunkSize, bytes.size - offset)
            writeChunk(bytes.copyOfRange(offset, offset + n))
            offset += n
        }
    }

    private fun writeChunk(piece: ByteArray) {
        session.acks.clear()
        characteristic.writeType = writeType
        characteristic.value = piece

        var accepted = false
        for (attempt in 0 until 25) {
            if (gatt.writeCharacteristic(characteristic)) {
                accepted = true
                break
            }
            Thread.sleep(20) // stack busy, back off briefly
        }
        if (!accepted) throw PrinterException("Bluetooth LE stack refused the write")

        val ok = session.acks.poll(5, TimeUnit.SECONDS)
            ?: throw PrinterException("Timed out waiting for the printer to accept data")
        if (!ok) throw PrinterException("Printer rejected a write")
    }

    override fun flush() = Unit

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    /** Shared between the GATT callback and the transport it ends up producing. */
    private class Session {
        val ready = CountDownLatch(1)
        val acks = ArrayBlockingQueue<Boolean>(1)
        @Volatile var characteristic: BluetoothGattCharacteristic? = null
        @Volatile var mtu = 23
        @Volatile var failure: String? = null
        @Volatile var disconnected = false
    }

    companion object {
        private const val TAG = "BleTransport"

        /** Vendor "serial over BLE" characteristics seen on these printers, best first. */
        private val PREFERRED = listOf(
            "0000ff02-0000-1000-8000-00805f9b34fb", // service ff00
            "00002af1-0000-1000-8000-00805f9b34fb", // service 18f0
            "0000ffe1-0000-1000-8000-00805f9b34fb", // HM-10 style
            "49535343-8841-43f4-a8d4-ecbe34729bb3", // ISSC transparent UART
        ).map { UUID.fromString(it) }

        fun connect(context: Context, device: BluetoothDevice): BleTransport {
            val session = Session()
            val callback = object : BluetoothGattCallback() {

                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED ->
                            if (!g.requestMtu(247)) g.discoverServices()

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            session.disconnected = true
                            session.acks.offer(false) // unblock any in-flight write
                            if (session.characteristic == null) {
                                session.failure =
                                    "Disconnected before the printer service was found"
                                session.ready.countDown()
                            }
                        }
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) session.mtu = mtu
                    g.discoverServices()
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        session.failure = "GATT service discovery failed ($status)"
                    } else {
                        session.characteristic = pickWritable(g)
                        if (session.characteristic == null) {
                            session.failure = "This device exposes no writable BLE " +
                                "characteristic — it may not be a printer"
                        }
                    }
                    session.ready.countDown()
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    session.acks.offer(status == BluetoothGatt.GATT_SUCCESS)
                }
            }

            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw PrinterException("Could not start a BLE connection")

            if (!session.ready.await(20, TimeUnit.SECONDS)) {
                runCatching { gatt.close() }
                throw PrinterException("Timed out connecting to the printer over Bluetooth LE")
            }
            val ch = session.characteristic
            if (ch == null) {
                runCatching { gatt.close() }
                throw PrinterException(session.failure ?: "Could not talk to the printer over BLE")
            }

            val chunk = (session.mtu - 3).coerceIn(20, 512)
            Log.i(TAG, "BLE ready on ${ch.uuid}, chunk $chunk")
            return BleTransport(gatt, ch, chunk, session)
        }

        private fun pickWritable(g: BluetoothGatt): BluetoothGattCharacteristic? {
            val writable = g.services.flatMap { it.characteristics }.filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            return PREFERRED.firstNotNullOfOrNull { pref -> writable.firstOrNull { it.uuid == pref } }
                ?: writable.firstOrNull()
        }
    }
}
