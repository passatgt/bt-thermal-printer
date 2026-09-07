package me.visztpeter.thermalprint.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import me.visztpeter.thermalprint.RenderMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RasterOptions(
    /** Printable width in dots (384 for 58mm paper). */
    val dotWidth: Int,
    val autoCrop: Boolean,
    val mode: RenderMode,
    /** Grey level below which a pixel burns. Higher = darker print. */
    val threshold: Int,
)

/**
 * Turns PDF pages / images into exactly-paper-wide 1-bit rasters.
 *
 * For PDFs this deliberately does *two* passes: a cheap low-res probe to find where the
 * ink actually is, then a second render straight at printer resolution with a transform
 * that maps just the inked area onto the full paper width. That way the text is rasterised
 * natively at 203 dpi instead of being downsampled from a big bitmap, which matters a lot
 * when you only have 384 dots to work with.
 */
object Rasterizer {

    private const val PROBE_WIDTH = 900

    /** ~1 metre of 58mm paper; a sanity cap so a stray hairline can't produce a mile of output. */
    private const val MAX_ROWS = 8000

    /** Don't blow content up beyond this many pixels per PDF point. */
    private const val MAX_SCALE = 20f

    /** Lighter than this counts as blank paper when looking for margins. */
    private const val PAPER_CUTOFF = 244

    fun renderPdf(file: File, opts: RasterOptions): List<MonoBitmap> {
        val pages = mutableListOf<MonoBitmap>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        renderPage(page, opts)?.let { pages += it }
                    }
                }
            }
        }
        return pages
    }

    private fun renderPage(page: PdfRenderer.Page, opts: RasterOptions): MonoBitmap? {
        val pageW = page.width.toFloat()
        val pageH = page.height.toFloat()
        if (pageW <= 0f || pageH <= 0f) return null

        // --- pass 1: where is the ink? (in PDF points) ---
        var cropL = 0f
        var cropT = 0f
        var cropR = pageW
        var cropB = pageH

        if (opts.autoCrop) {
            val probeH = max(1, (PROBE_WIDTH * pageH / pageW).roundToInt())
            val probe = Bitmap.createBitmap(PROBE_WIDTH, probeH, Bitmap.Config.ARGB_8888)
            probe.eraseColor(Color.WHITE)
            page.render(probe, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val bounds = contentBounds(probe)
            probe.recycle()

            if (bounds.isEmpty) return null // nothing on this page, don't waste paper

            val ptsPerPx = pageW / PROBE_WIDTH
            val pad = ptsPerPx // one probe pixel of slack so glyph edges aren't shaved
            cropL = max(0f, bounds.left * ptsPerPx - pad)
            cropT = max(0f, bounds.top * ptsPerPx - pad)
            cropR = min(pageW, bounds.right * ptsPerPx + pad)
            cropB = min(pageH, bounds.bottom * ptsPerPx + pad)
        }

        val cropW = max(1f, cropR - cropL)
        val cropH = max(1f, cropB - cropT)

        // --- pass 2: render the inked area at printer resolution ---
        var scale = min(opts.dotWidth / cropW, MAX_SCALE)
        var rows = ceil(cropH * scale).toInt()
        if (rows > MAX_ROWS) {
            scale = MAX_ROWS / cropH
            rows = MAX_ROWS
        }
        rows = rows.coerceAtLeast(1)

        val drawnWidth = cropW * scale
        val xOffset = ((opts.dotWidth - drawnWidth) / 2f).coerceAtLeast(0f)

        val bmp = Bitmap.createBitmap(opts.dotWidth, rows, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(-cropL * scale + xOffset, -cropT * scale)
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        val mono = toMono(bmp, opts)
        bmp.recycle()
        return if (mono.isBlank()) null else mono
    }

    fun renderImage(source: Bitmap, opts: RasterOptions): MonoBitmap? {
        // Flatten onto white so transparent PNGs don't come out as a solid black block.
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        var work = flat
        if (opts.autoCrop) {
            val b = contentBounds(work)
            if (b.isEmpty) return null
            if (b.width() != work.width || b.height() != work.height) {
                val cropped = Bitmap.createBitmap(work, b.left, b.top, b.width(), b.height())
                if (work !== cropped) work.recycle()
                work = cropped
            }
        }

        val scaled = smoothScaleToWidth(work, opts.dotWidth)
        if (scaled !== work) work.recycle()
        val mono = toMono(scaled, opts)
        scaled.recycle()
        return if (mono.isBlank()) null else mono
    }

    /** Bounding box of everything darker than blank paper. Empty rect if the page is blank. */
    private fun contentBounds(bmp: Bitmap): Rect {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var top = -1
        var bottom = -1
        var left = w
        var right = -1

        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var rowLeft = -1
            var rowRight = -1
            for (x in 0 until w) {
                if (luminanceOverWhite(row[x]) < PAPER_CUTOFF) {
                    if (rowLeft < 0) rowLeft = x
                    rowRight = x
                }
            }
            if (rowLeft >= 0) {
                if (top < 0) top = y
                bottom = y
                if (rowLeft < left) left = rowLeft
                if (rowRight > right) right = rowRight
            }
        }
        if (top < 0) return Rect()
        return Rect(left, top, right + 1, bottom + 1)
    }

    /** Perceptual grey of a pixel composited over white paper. */
    private fun luminanceOverWhite(c: Int): Int {
        val a = (c ushr 24) and 0xFF
        if (a == 0) return 255
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        val lum = (r * 77 + g * 151 + b * 28) shr 8
        return if (a == 255) lum else 255 - ((255 - lum) * a / 255)
    }

    /** Halve repeatedly before the final pass — plain bilinear aliases badly on big downscales. */
    private fun smoothScaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        var cur = src
        var first = true
        while (cur.width > targetWidth * 2) {
            val w = max(targetWidth, cur.width / 2)
            val h = max(1, (cur.height.toLong() * w / cur.width).toInt())
            val next = Bitmap.createScaledBitmap(cur, w, h, true)
            if (!first) cur.recycle()
            cur = next
            first = false
        }
        if (cur.width == targetWidth) return cur
        val h = max(1, (cur.height.toLong() * targetWidth / cur.width).toInt())
        val out = Bitmap.createScaledBitmap(cur, targetWidth, h, true)
        if (!first && cur !== out) cur.recycle()
        return out
    }

    /**
     * Greyscale -> 1 bit. SHARP is a plain threshold (best for text: strokes stay solid),
     * DITHER is Floyd–Steinberg (best for photos: fakes greys the printer doesn't have).
     */
    private fun toMono(bmp: Bitmap, opts: RasterOptions): MonoBitmap {
        val w = bmp.width
        val h = bmp.height
        val padded = ((w + 7) / 8) * 8
        val out = MonoBitmap(padded, h)
        val row = IntArray(w)

        if (opts.mode == RenderMode.SHARP) {
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if (luminanceOverWhite(row[x]) < opts.threshold) out.setBlack(x, y)
                }
            }
            return out
        }

        // Floyd–Steinberg, two error rows at a time so long pages stay cheap.
        var cur = IntArray(w + 2)
        var next = IntArray(w + 2)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val v = luminanceOverWhite(row[x]) + cur[x + 1]
                val black = v < opts.threshold
                if (black) out.setBlack(x, y)
                val err = v - (if (black) 0 else 255)
                cur[x + 2] += err * 7 / 16
                next[x] += err * 3 / 16
                next[x + 1] += err * 5 / 16
                next[x + 2] += err / 16
            }
            val swap = cur
            cur = next
            next = swap
            java.util.Arrays.fill(next, 0)
        }
        return out
    }
}
