package me.visztpeter.thermalprint

import android.content.Context

enum class RenderMode { SHARP, DITHER }

/**
 * All persisted app state. Deliberately tiny: the only thing that really has to be
 * remembered is which printer to talk to, everything else has a sane default.
 */
class Settings(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("thermalprint", Context.MODE_PRIVATE)

    var printerMac: String?
        get() = sp.getString(K_MAC, null)
        set(v) = sp.edit().putString(K_MAC, v).apply()

    var printerName: String?
        get() = sp.getString(K_NAME, null)
        set(v) = sp.edit().putString(K_NAME, v).apply()

    /** Printable width in dots. 384 = 58mm paper (48mm print area), 576 = 80mm paper. */
    var dotWidth: Int
        get() = sp.getInt(K_WIDTH, 384)
        set(v) = sp.edit().putInt(K_WIDTH, v).apply()

    /** Trim the white border off the page and blow the content up to full paper width. */
    var autoCrop: Boolean
        get() = sp.getBoolean(K_CROP, true)
        set(v) = sp.edit().putBoolean(K_CROP, v).apply()

    var mode: RenderMode
        get() = runCatching { RenderMode.valueOf(sp.getString(K_MODE, null) ?: "") }
            .getOrDefault(RenderMode.SHARP)
        set(v) = sp.edit().putString(K_MODE, v.name).apply()

    /** -60..+60, shifts the black/white threshold. Higher = darker print. */
    var darkness: Int
        get() = sp.getInt(K_DARK, 25)
        set(v) = sp.edit().putInt(K_DARK, v.coerceIn(-60, 60)).apply()

    /** Blank lines fed after a job so the last line clears the tear-off edge. */
    var feedLines: Int
        get() = sp.getInt(K_FEED, 4)
        set(v) = sp.edit().putInt(K_FEED, v.coerceIn(0, 12)).apply()

    val threshold: Int get() = (128 + darkness).coerceIn(16, 240)

    val hasPrinter: Boolean get() = !printerMac.isNullOrBlank()

    companion object {
        private const val K_MAC = "printer_mac"
        private const val K_NAME = "printer_name"
        private const val K_WIDTH = "dot_width"
        private const val K_CROP = "auto_crop"
        private const val K_MODE = "render_mode"
        private const val K_DARK = "darkness"
        private const val K_FEED = "feed_lines"
    }
}
