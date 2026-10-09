package com.hermes.client.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LocalNetworkPermissionScreen(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Local network access required", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Allow local network access in the Nearby devices permission dialog so Hermes " +
                "can connect to your gateway. Android otherwise silently blocks LAN connections.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("Allow local network access") }
        TextButton(onClick = onSettings) { Text("Open app settings") }
    }
}
