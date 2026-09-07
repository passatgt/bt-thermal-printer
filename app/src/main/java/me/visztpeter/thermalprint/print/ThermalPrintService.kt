package me.visztpeter.thermalprint.print

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import me.visztpeter.thermalprint.R
import me.visztpeter.thermalprint.Settings
import me.visztpeter.thermalprint.bt.ThermalPrinter
import me.visztpeter.thermalprint.pdf.DocumentRasterizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Makes the paired thermal printer a first-class Android printer, so anything with a
 * "Print" menu (Chrome, Drive, Gmail, Photos…) can reach it.
 *
 * The framework hands us a PDF already laid out for the media size we advertise, which
 * is why the advertised width is the *printable* 48mm rather than the 58mm of the paper.
 */
class ThermalPrintService : PrintService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Session()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val settings = Settings(this)
        if (!settings.hasPrinter) {
            printJob.fail("No printer paired in Thermal Print")
            return
        }

        val fd: ParcelFileDescriptor? = printJob.document.data
        if (fd == null) {
            printJob.fail("The document could not be read")
            return
        }
        if (printJob.isQueued) printJob.start()

        worker.execute {
            var spool: File? = null
            try {
                spool = File.createTempFile("job", ".pdf", cacheDir).also { file ->
                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                        FileOutputStream(file).use { input.copyTo(it) }
                    }
                }
                val pages = DocumentRasterizer.rasterizeFile(spool, settings)
                ThermalPrinter.print(this, pages, settings)
                main.post { if (printJob.isStarted) printJob.complete() }
            } catch (t: Throwable) {
                Log.e(TAG, "Print job failed", t)
                val message = t.message ?: t.javaClass.simpleName
                main.post { if (!printJob.isCancelled) printJob.fail(message) }
            } finally {
                spool?.delete()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private inner class Session : PrinterDiscoverySession() {

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) = publish()

        override fun onStartPrinterStateTracking(printerId: PrinterId) = publish()

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = publish()

        override fun onStopPrinterDiscovery() = Unit
        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit
        override fun onDestroy() = Unit

        private fun publish() {
            val settings = Settings(this@ThermalPrintService)
            val mac = settings.printerMac
            if (mac == null) {
                addPrinters(emptyList())
                return
            }
            val id = generatePrinterId(mac)
            val name = settings.printerName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.print_service_name)

            val mm = settings.dotWidth / 8 // 203 dpi == 8 dots per mm
            val caps = PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(roll(mm, 297), true)
                .addMediaSize(roll(mm, 210), false)
                .addMediaSize(roll(mm, 100), false)
                .addResolution(
                    PrintAttributes.Resolution("thermal", "203 dpi", 203, 203), true
                )
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                )
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            addPrinters(
                listOf(
                    PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
                        .setCapabilities(caps)
                        .build()
                )
            )
        }

        /** A continuous-roll "page": exact printable width, generous length. */
        private fun roll(widthMm: Int, lengthMm: Int) = PrintAttributes.MediaSize(
            "roll_${widthMm}x$lengthMm",
            "$widthMm × $lengthMm mm",
            mils(widthMm),
            mils(lengthMm),
        )

        /** Thousandths of an inch, which is what PrintAttributes speaks. */
        private fun mils(millimetres: Int) = millimetres * 10000 / 254
    }

    companion object {
        private const val TAG = "ThermalPrintService"
    }
}
