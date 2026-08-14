package dev.scoutr.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import dev.scoutr.app.ui.components.ScoutrMark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

import dev.scoutr.app.data.PairingPayloadParser
import dev.scoutr.app.ui.imeOrNavigationBarsPadding
import dev.scoutr.app.state.ConnectViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.state.Loadable

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    viewModel: ConnectViewModel = rememberConnectViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var host by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        if (!contents.isNullOrEmpty()) {
            val payload = PairingPayloadParser.parse(contents)
            if (payload != null) {
                host = payload.host
                token = payload.token
                scanError = null
                viewModel.connect(payload.host, payload.token)
            } else {
                scanError = "That QR doesn't look like a Scoutr pairing code"
            }
        }
    }

    // React to the handshake result exactly once.
    if (state is Loadable.Ready) {
        onConnected()
        viewModel.reset()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imeOrNavigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // The wordmark is ink, not accent: green means live and AI-owned and
        // nothing else, so a static brand label must not wear it (§9d, §8c).
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoutrMark(size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Scoutr",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your terminal agents, at a glance.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Bridge address") },
            placeholder = { Text("https://artemis.tail…ts.net:8737") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("connect_host"),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Pairing token") },
            placeholder = { Text("scoutr_…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("connect_token"),
        )

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            shape = MaterialTheme.shapes.small,
            onClick = {
                scanError = null
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Scan the QR from `scoutr-bridge pair`")
                    },
                )
            },
            modifier = Modifier.fillMaxWidth().testTag("connect_scan"),
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan QR code")
        }
        if (scanError != null) {
            Text(
                text = scanError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))

        when (val s = state) {
            is Loadable.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(12.dp))
                    Text("Checking connection…")
                }
            }

            is Loadable.Failed -> {
                Text(
                    text = "Could not reach the bridge: ${s.reason}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = { viewModel.connect(host, token) },
                    modifier = Modifier.fillMaxWidth().testTag("connect_button"),
                ) {
                    Text("Try again")
                }
            }

            else -> {
                Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = { viewModel.connect(host, token) },
                    enabled = host.isNotBlank() && token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("connect_button"),
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun rememberConnectViewModel(): ConnectViewModel {
    return viewModel(
        factory = viewModelFactory<ConnectViewModel> { app ->
            ConnectViewModel(app.container.bridge, app.container.connectionStore)
        },
    )
}

