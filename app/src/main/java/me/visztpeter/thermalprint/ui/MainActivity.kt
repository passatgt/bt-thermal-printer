package me.visztpeter.thermalprint.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.visztpeter.thermalprint.RenderMode
import me.visztpeter.thermalprint.Settings
import me.visztpeter.thermalprint.bt.BtScanner
import me.visztpeter.thermalprint.bt.DeviceEntry
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ThermalTheme { MainScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(context) }
    val scanner = remember { BtScanner(context) }

    var printerName by remember { mutableStateOf(settings.printerName) }
    var printerMac by remember { mutableStateOf(settings.printerMac) }
    var dotWidth by remember { mutableIntStateOf(settings.dotWidth) }
    var autoCrop by remember { mutableStateOf(settings.autoCrop) }
    var mode by remember { mutableStateOf(settings.mode) }
    var darkness by remember { mutableIntStateOf(settings.darkness) }
    var feedLines by remember { mutableIntStateOf(settings.feedLines) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showPicker by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scanner.refreshBonded() }

    LaunchedEffect(Unit) { permissionLauncher.launch(PrintOps.bluetoothPermissions) }

    DisposableEffect(Unit) {
        scanner.start()
        onDispose { scanner.stop() }
    }

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            context.startActivity(
                Intent(context, PrintPdfActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Thermal Print") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            SectionCard("Printer") {
                Text(
                    text = printerName ?: "No printer selected",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = printerMac ?: "Pick your printer to get started",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (printerMac != null) FontFamily.Monospace else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        permissionLauncher.launch(PrintOps.bluetoothPermissions)
                        scanner.refreshBonded()
                        showPicker = true
                    }) { Text(if (printerMac == null) "Pick printer" else "Change") }

                    OutlinedButton(
                        enabled = printerMac != null && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                progress = 0f
                                runCatching {
                                    val page = PrintOps.testPage(settings)
                                    PrintOps.send(context, listOf(page), settings) { progress = it }
                                }.onFailure {
                                    snackbar.showSnackbar(it.message ?: "Test print failed")
                                }.onSuccess {
                                    snackbar.showSnackbar("Test page sent")
                                }
                                busy = false
                            }
                        },
                    ) { Text("Test print") }
                }
                if (busy) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionCard("Print a document") {
                Text(
                    "Pick a PDF or image, check the preview, then send it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = {
                    pickDocument.launch(arrayOf("application/pdf", "image/*"))
                }) { Text("Choose a file") }
            }

            SectionCard("Print from other apps") {
                Text(
                    "Thermal Print registers as a system printer, so it shows up in any " +
                        "app's Print dialog once you switch it on in Android's print settings. " +
                        "It also accepts PDFs and images from the share sheet and from " +
                        "\"Open with\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Printing a photo from another app? Pick the paper size that matches " +
                        "its shape, and set Orientation to match — the Android print " +
                        "dialog crops photos to fill the page.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { PrintOps.openPrintSettings(context) }) {
                    Text("Open print settings")
                }
            }

            SectionCard("Output") {
                Text("Paper width", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(384 to "58 mm", 576 to "80 mm").forEach { (dots, label) ->
                        FilterChip(
                            selected = dotWidth == dots,
                            onClick = { dotWidth = dots; settings.dotWidth = dots },
                            label = { Text("$label · $dots dots") },
                        )
                    }
                }

                Text("Image style", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        RenderMode.AUTO to "Auto",
                        RenderMode.SHARP to "Sharp",
                        RenderMode.DITHER to "Dithered",
                    ).forEach { (m, label) ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m; settings.mode = m },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    when (mode) {
                        RenderMode.AUTO ->
                            "Picks per page: sharp for text, dithered for photos."
                        RenderMode.SHARP -> "Plain threshold. Keeps text strokes solid."
                        RenderMode.DITHER -> "Floyd-Steinberg. Fakes greys for photos."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Trim margins", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Cut the white border off and scale the content to the full " +
                                "printable width.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = autoCrop,
                        onCheckedChange = { autoCrop = it; settings.autoCrop = it },
                    )
                }

                Text("Darkness: $darkness", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = darkness.toFloat(),
                    onValueChange = { darkness = it.roundToInt() },
                    onValueChangeFinished = { settings.darkness = darkness },
                    valueRange = -60f..60f,
                    steps = 23,
                )

                Text("Feed after print: $feedLines lines",
                    style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = feedLines.toFloat(),
                    onValueChange = { feedLines = it.roundToInt() },
                    onValueChangeFinished = { settings.feedLines = feedLines },
                    valueRange = 0f..12f,
                    steps = 11,
                )
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { scanner.stopScan(); showPicker = false }) {
            DevicePicker(
                scanner = scanner,
                selectedMac = printerMac,
                onSelect = { entry ->
                    settings.printerMac = entry.address
                    settings.printerName = entry.name
                    printerMac = entry.address
                    printerName = entry.name
                    if (!entry.bonded) {
                        scanner.pair(entry.address)
                        scope.launch {
                            snackbar.showSnackbar("Pairing with ${entry.name}…")
                        }
                    }
                    scanner.stopScan()
                    showPicker = false
                },
            )
        }
    }
}

@Composable
private fun DevicePicker(
    scanner: BtScanner,
    selectedMac: String?,
    onSelect: (DeviceEntry) -> Unit,
) {
    val context = LocalContext.current
    val devices by scanner.devices.collectAsState()
    val scanning by scanner.scanning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Choose your printer", style = MaterialTheme.typography.titleLarge)
        Text(
            "Paired devices are listed first. If the printer isn't here, turn it on and " +
                "scan — pairing usually asks for 0000 or 1234.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = !scanning,
                onClick = { scanner.startScan() },
            ) { Text(if (scanning) "Scanning…" else "Scan") }
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                TextButton(onClick = { scanner.stopScan() }) { Text("Stop") }
            }
            TextButton(onClick = { PrintOps.openBluetoothSettings(context) }) {
                Text("Bluetooth settings")
            }
        }

        if (devices.isEmpty()) {
            Text("No devices yet. Tap Scan with the printer switched on.")
        }

        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
            items(devices, key = { it.address }) { entry ->
                ListItem(
                    modifier = Modifier.clickable { onSelect(entry) },
                    headlineContent = { Text(entry.name) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(entry.address)
                                if (entry.bonded) append("  ·  paired")
                                if (entry.likelyPrinter) append("  ·  looks like a printer")
                                if (entry.address == selectedMac) append("  ·  selected")
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
