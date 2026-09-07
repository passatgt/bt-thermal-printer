package me.visztpeter.thermalprint.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.launch
import me.visztpeter.thermalprint.Settings
import me.visztpeter.thermalprint.pdf.MonoBitmap

/** Handles "Open with" / share-sheet PDFs and images: preview, then print. */
class PrintPdfActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent?.data
        }
        setContent {
            ThermalTheme {
                PrintScreen(uri = uri, onClose = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrintScreen(uri: Uri?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(context) }
    val snackbar = remember { SnackbarHostState() }

    var pages by remember { mutableStateOf<List<MonoBitmap>>(emptyList()) }
    var rendering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var printing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var autoCrop by remember { mutableStateOf(settings.autoCrop) }

    LaunchedEffect(uri, autoCrop) {
        if (uri == null) {
            error = "No document was passed in"
            rendering = false
            return@LaunchedEffect
        }
        rendering = true
        error = null
        runCatching { PrintOps.rasterize(context, uri, settings) }
            .onSuccess {
                pages = it
                if (it.isEmpty()) error = "Nothing to print — every page looked blank"
            }
            .onFailure { error = it.message ?: "Could not read that document" }
        rendering = false
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Print") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                settings.printerName ?: "No printer selected — open Thermal Print first",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Trim margins", style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = autoCrop,
                    enabled = !printing,
                    onCheckedChange = { autoCrop = it; settings.autoCrop = it },
                )
            }

            when {
                rendering -> CircularProgressIndicator()
                error != null -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    Text(
                        "${pages.size} page${if (pages.size == 1) "" else "s"} · " +
                            "${pages.first().width} × ${pages.sumOf { it.height }} dots",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    pages.forEach { page ->
                        Image(
                            bitmap = remember(page) { page.toPreviewBitmap().asImageBitmap() },
                            contentDescription = "Print preview",
                            contentScale = ContentScale.FillWidth,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier
                                .width(260.dp)
                                .background(Color.White),
                        )
                    }
                }
            }

            if (printing) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = pages.isNotEmpty() && !printing && settings.hasPrinter,
                    onClick = {
                        scope.launch {
                            printing = true
                            progress = 0f
                            runCatching {
                                PrintOps.send(context, pages, settings) { progress = it }
                            }.onSuccess {
                                snackbar.showSnackbar("Sent to the printer")
                                onClose()
                            }.onFailure {
                                snackbar.showSnackbar(it.message ?: "Printing failed")
                            }
                            printing = false
                        }
                    },
                ) { Text("Print") }

                OutlinedButton(enabled = !printing, onClick = onClose) { Text("Cancel") }
            }
        }
    }
}
