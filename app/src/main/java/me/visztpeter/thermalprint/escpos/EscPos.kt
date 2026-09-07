package me.visztpeter.thermalprint.escpos

import me.visztpeter.thermalprint.pdf.MonoBitmap

/** ESC/POS byte sequences. Only the handful a picture-only printer needs. */
object EscPos {

    /** ESC @ — reset to defaults. */
    val INIT = byteArrayOf(0x1B, 0x40)

    /**
     * ESC 3 0 — line spacing 0. Without this many printers insert their default 30-dot
     * feed between consecutive raster commands, which shows up as white stripes across
     * a tall image.
     */
    val LINE_SPACING_ZERO = byteArrayOf(0x1B, 0x33, 0x00)

    /** ESC 2 — back to the default line spacing. */
    val LINE_SPACING_DEFAULT = byteArrayOf(0x1B, 0x32)

    val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)

    /** ESC d n — feed n blank lines. */
    fun feed(lines: Int): ByteArray = byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte())

    /**
     * GS v 0 — raster bit image, split into bands.
     *
     * These printers have a few KB of buffer, so one command per band keeps each
     * transfer small enough that a stalled printer can't drop the tail of the image.
     */
    fun rasterBands(image: MonoBitmap, bandRows: Int = 64): List<ByteArray> {
        val out = ArrayList<ByteArray>((image.height + bandRows - 1) / bandRows)
        val xl = (image.bytesPerRow and 0xFF).toByte()
        val xh = ((image.bytesPerRow shr 8) and 0xFF).toByte()

        var row = 0
        while (row < image.height) {
            val rows = minOf(bandRows, image.height - row)
            val payload = image.slice(row, rows)
            val cmd = ByteArray(8 + payload.size)
            cmd[0] = 0x1D
            cmd[1] = 0x76
            cmd[2] = 0x30
            cmd[3] = 0x00 // mode 0: normal
            cmd[4] = xl
            cmd[5] = xh
            cmd[6] = (rows and 0xFF).toByte()
            cmd[7] = ((rows shr 8) and 0xFF).toByte()
            payload.copyInto(cmd, 8)
            out += cmd
            row += rows
        }
        return out
    }
}
