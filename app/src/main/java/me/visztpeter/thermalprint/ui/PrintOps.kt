package me.visztpeter.thermalprint.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.visztpeter.thermalprint.Settings
import me.visztpeter.thermalprint.bt.ThermalPrinter
import me.visztpeter.thermalprint.pdf.DocumentRasterizer
import me.visztpeter.thermalprint.pdf.MonoBitmap

object PrintOps {

    /** Runtime permissions the app actually needs on this OS version. */
    val bluetoothPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    suspend fun rasterize(context: Context, uri: Uri, settings: Settings): List<MonoBitmap> =
        withContext(Dispatchers.IO) {
            DocumentRasterizer.rasterize(context, uri, settings)
        }

    suspend fun send(
        context: Context,
        pages: List<MonoBitmap>,
        settings: Settings,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        ThermalPrinter.print(context, pages, settings, onProgress)
    }

    suspend fun testPage(settings: Settings): MonoBitmap = withContext(Dispatchers.Default) {
        DocumentRasterizer.testPage(settings)
    }

    fun openPrintSettings(context: Context) {
        val intent = Intent(AndroidSettings.ACTION_PRINT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
    }

    fun openBluetoothSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
