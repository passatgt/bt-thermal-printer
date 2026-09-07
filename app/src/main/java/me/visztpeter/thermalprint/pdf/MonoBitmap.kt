package me.visztpeter.thermalprint.pdf

import android.graphics.Bitmap
import android.graphics.Color

/**
 * A 1-bit-per-pixel image in exactly the layout ESC/POS raster commands want:
 * row-major, MSB is the leftmost dot, a set bit means "burn this dot".
 */
class MonoBitmap(val width: Int, val height: Int) {

    init {
        require(width % 8 == 0) { "width must be a multiple of 8, was $width" }
    }

    val bytesPerRow: Int = width / 8
    val data: ByteArray = ByteArray(bytesPerRow * height)

    fun setBlack(x: Int, y: Int) {
        val i = y * bytesPerRow + (x shr 3)
        data[i] = (data[i].toInt() or (0x80 ushr (x and 7))).toByte()
    }

    fun isBlack(x: Int, y: Int): Boolean {
        val i = y * bytesPerRow + (x shr 3)
        return (data[i].toInt() and (0x80 ushr (x and 7))) != 0
    }

    /** Bytes for [rows] rows starting at [startRow] — one chunk of a raster command. */
    fun slice(startRow: Int, rows: Int): ByteArray =
        data.copyOfRange(startRow * bytesPerRow, (startRow + rows) * bytesPerRow)

    /** A rows-tall band of this image, used to keep single ESC/POS commands small. */
    fun band(startRow: Int, rows: Int): MonoBitmap {
        val out = MonoBitmap(width, rows)
        System.arraycopy(data, startRow * bytesPerRow, out.data, 0, rows * bytesPerRow)
        return out
    }

    /** Renders exactly what the printer will burn, for the on-screen preview. */
    fun toPreviewBitmap(): Bitmap {
        val px = IntArray(width * height)
        var p = 0
        for (y in 0 until height) {
            val rowOff = y * bytesPerRow
            for (x in 0 until width) {
                val bit = data[rowOff + (x shr 3)].toInt() and (0x80 ushr (x and 7))
                px[p++] = if (bit != 0) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    /** True if nothing at all would be printed. */
    fun isBlank(): Boolean = data.all { it.toInt() == 0 }
}
