package dev.cockpit.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.cockpit.app.CockpitApp
import dev.cockpit.app.data.PairingPayloadParser
import dev.cockpit.app.state.ConnectState
import dev.cockpit.app.state.ConnectViewModel

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
                scanError = "That QR doesn't look like a Cockpit pairing code"
            }
        }
    }

    // React to the handshake result exactly once.
    if (state is ConnectState.Connected) {
        onConnected()
        viewModel.reset()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Cockpit",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
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
            placeholder = { Text("cockpit_…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("connect_token"),
        )

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                scanError = null
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Scan the QR from `cockpit-bridge pair`")
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
            is ConnectState.Testing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking connection…")
                }
            }

            is ConnectState.Failed -> {
                Text(
                    text = "Could not reach the bridge: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.connect(host, token) },
                    modifier = Modifier.fillMaxWidth().testTag("connect_button"),
                ) {
                    Text("Try again")
                }
            }

            else -> {
                Button(
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
    val app = LocalContext.current.applicationContext as CockpitApp
    return viewModel(
        factory = ConnectViewModel.factory(app.container.bridge, app.container.connectionStore),
    )
}

