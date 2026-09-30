package com.cold04.picomanage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cold04.picomanage.ui.theme.PicoManageTheme

class DeviceInformationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeviceSessions.initialize(applicationContext)
        setContent { PicoManageTheme { DeviceInformationPage(onBack = ::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceInformationPage(onBack: () -> Unit) {
    val state by DeviceSessions.state.collectAsState()
    val profile = state.active?.profile

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (profile == null) {
            Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("设备未连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val identity = profile.identity
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                item { InformationRow("设备类型", identity.deviceType) }
                identity.deviceId?.takeIf(String::isNotBlank)?.let {
                    item { InformationRow("设备 ID", it) }
                }
                identity.firmwareVersion?.takeIf(String::isNotBlank)?.let {
                    item { InformationRow("固件版本", it) }
                }
                identity.protocolVersion?.let {
                    item { InformationRow("协议版本", it.toString()) }
                }
            }
        }
    }
}

@Composable
private fun InformationRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
    )
}
