package me.visztpeter.thermalprint.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import me.visztpeter.thermalprint.Settings
import me.visztpeter.thermalprint.escpos.EscPos
import me.visztpeter.thermalprint.pdf.MonoBitmap

/** Sends rendered pages to the paired printer. Blocking; call it off the main thread. */
@SuppressLint("MissingPermission")
object ThermalPrinter {

    private const val TAG = "ThermalPrinter"

    /** Rows per ESC/POS raster command. Small enough to survive a printer with a tiny buffer. */
    private const val BAND_ROWS = 64

    /**
     * Roughly how long the head needs per dot row. These printers rarely apply real flow
     * control, so we pace the writes to the mechanism instead of trusting the socket.
     */
    private const val MS_PER_ROW = 2L

    fun adapterOrNull(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun resolveDevice(context: Context, settings: Settings): BluetoothDevice {
        val adapter = adapterOrNull(context)
            ?: throw PrinterException("This phone has no Bluetooth adapter")
        if (!adapter.isEnabled) throw PrinterException("Bluetooth is turned off")
        val mac = settings.printerMac
            ?: throw PrinterException("No printer selected yet — open Thermal Print and pick one")
        return try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            throw PrinterException("Saved printer address is invalid: $mac", e)
        }
    }

    fun openTransport(context: Context, settings: Settings): Transport {
        val adapter = adapterOrNull(context)
            ?: throw PrinterException("This phone has no Bluetooth adapter")
        val device = resolveDevice(context, settings)

        // BLE-only devices can't do RFCOMM at all; everything else gets SPP first.
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            return BleTransport.connect(context, device)
        }
        return try {
            SppTransport.connect(adapter, device)
        } catch (sppFailure: PrinterException) {
            if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) throw sppFailure
            Log.w(TAG, "SPP failed, trying BLE", sppFailure)
            try {
                BleTransport.connect(context, device)
            } catch (bleFailure: Exception) {
                throw sppFailure // the classic path's message is the more useful one
            }
        }
    }

    /**
     * @param onProgress called with 0f..1f as bands go out.
     */
    fun print(
        context: Context,
        pages: List<MonoBitmap>,
        settings: Settings,
        onProgress: (Float) -> Unit = {},
    ) {
        if (pages.isEmpty()) throw PrinterException("Nothing to print — the document looks blank")

        openTransport(context, settings).use { transport ->
            Log.i(TAG, "Printing ${pages.size} page(s) over ${transport.description}")
            transport.write(EscPos.INIT)
            transport.write(EscPos.ALIGN_LEFT)
            transport.write(EscPos.LINE_SPACING_ZERO)
            transport.flush()

            val totalBands = pages.sumOf { (it.height + BAND_ROWS - 1) / BAND_ROWS }
            var sent = 0

            pages.forEachIndexed { index, page ->
                for (band in EscPos.rasterBands(page, BAND_ROWS)) {
                    transport.write(band)
                    transport.flush()
                    // (band length - 8 byte header) / bytesPerRow == rows in this band
                    val rows = (band.size - 8) / page.bytesPerRow
                    Thread.sleep(rows * MS_PER_ROW)
                    sent++
                    onProgress(sent.toFloat() / totalBands)
                }
                if (index != pages.lastIndex) {
                    transport.write(EscPos.feed(2)) // gap so pages are tearable apart
                    transport.flush()
                }
            }

            transport.write(EscPos.LINE_SPACING_DEFAULT)
            transport.write(EscPos.feed(settings.feedLines))
            transport.flush()
            // Give the mechanism a moment to drain before the socket drops.
            Thread.sleep(300)
        }
        onProgress(1f)
    }
}
