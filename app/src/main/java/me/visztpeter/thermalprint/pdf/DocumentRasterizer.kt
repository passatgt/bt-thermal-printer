package me.visztpeter.thermalprint.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import me.visztpeter.thermalprint.RenderMode
import me.visztpeter.thermalprint.Settings
import java.io.File
import java.io.FileOutputStream

/** Front door for "turn this thing the user handed me into printable pages". */
object DocumentRasterizer {

    fun optionsFrom(settings: Settings) = RasterOptions(
        dotWidth = settings.dotWidth,
        autoCrop = settings.autoCrop,
        mode = settings.mode,
        threshold = settings.threshold,
    )

    /** Copies the content Uri somewhere seekable — PdfRenderer insists on a real file. */
    fun copyToCache(context: Context, uri: Uri): File {
        val out = File.createTempFile("doc", ".bin", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        } ?: throw java.io.FileNotFoundException("Could not open $uri")
        return out
    }

    private fun looksLikePdf(file: File): Boolean =
        file.inputStream().use { input ->
            val head = ByteArray(5)
            val n = input.read(head)
            n == 5 && String(head, Charsets.US_ASCII) == "%PDF-"
        }

    fun rasterize(context: Context, uri: Uri, settings: Settings): List<MonoBitmap> {
        val file = copyToCache(context, uri)
        try {
            return rasterizeFile(file, settings)
        } finally {
            file.delete()
        }
    }

    fun rasterizeFile(file: File, settings: Settings): List<MonoBitmap> {
        val opts = optionsFrom(settings)
        if (looksLikePdf(file)) return Rasterizer.renderPdf(file, opts)

        val bmp = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalArgumentException("Not a PDF, and not an image this phone can decode")
        return try {
            listOfNotNull(Rasterizer.renderImage(bmp, opts))
        } finally {
            bmp.recycle()
        }
    }

    /** A self-test strip: head width, resolution, and how the darkness setting is landing. */
    fun testPage(settings: Settings): MonoBitmap {
        val w = settings.dotWidth
        val h = 300
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = 30f
        c.drawText("Thermal Print", 4f, 30f, p)

        p.typeface = Typeface.DEFAULT
        p.textSize = 18f
        c.drawText("${settings.printerName ?: "printer"}", 4f, 54f, p)
        c.drawText("$w dots  ·  ${w / 8} mm  ·  ${settings.mode.name.lowercase()}", 4f, 74f, p)
        c.drawText("darkness ${settings.darkness}  (cut ${settings.threshold})", 4f, 94f, p)

        // Solid bar: every element of the head should fire, edge to edge.
        c.drawRect(0f, 104f, w.toFloat(), 124f, p)

        // 1-dot vertical comb: if this greys out, the head or the width is off.
        for (x in 0 until w step 2) c.drawRect(x.toFloat(), 130f, x + 1f, 150f, p)

        // Grey ramp: shows where your darkness threshold actually cuts.
        val steps = 16
        val stepW = w.toFloat() / steps
        for (i in 0 until steps) {
            val v = 255 - (i * 255 / (steps - 1))
            p.color = Color.rgb(v, v, v)
            c.drawRect(i * stepW, 156f, (i + 1) * stepW, 186f, p)
        }
        p.color = Color.BLACK

        // Millimetre ruler across the full printable width.
        val dotsPerMm = 8
        var mm = 0
        while (mm * dotsPerMm <= w) {
            val x = (mm * dotsPerMm).toFloat()
            val tall = mm % 10 == 0
            c.drawRect(x, 194f, x + 1f, if (tall) 214f else 204f, p)
            if (tall && mm > 0) {
                p.textSize = 14f
                c.drawText("$mm", x - 6f, 230f, p)
            }
            mm++
        }

        p.textSize = 18f
        c.drawText("<- full printable width ->", 4f, 258f, p)
        c.drawText("If the ruler is cut off, lower the width.", 4f, 280f, p)

        val mono = Rasterizer.renderImage(
            bmp,
            optionsFrom(settings).copy(autoCrop = false, mode = RenderMode.DITHER),
        )
        bmp.recycle()
        return mono ?: MonoBitmap(w, 1)
    }
}
