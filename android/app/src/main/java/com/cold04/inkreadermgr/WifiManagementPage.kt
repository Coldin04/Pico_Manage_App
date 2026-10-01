package com.cold04.inkreadermgr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uniffi.inkreaderlink_uniffi.SdkOperationException
import uniffi.inkreaderlink_uniffi.SdkWifiCredential
import uniffi.inkreaderlink_uniffi.SdkWifiNetwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiManagementPage(onBack: () -> Unit) {
    val deviceState by DeviceSessions.state.collectAsState()
    val activeDevice = deviceState.active
    val capabilities = activeDevice?.profile?.capabilities?.toSet().orEmpty()
    val canManageWifi = activeDevice != null && "wifi.list" in capabilities
    val canSave = "wifi.save" in capabilities
    val canDelete = "wifi.delete" in capabilities
    val scope = rememberCoroutineScope()
    var networks by remember(activeDevice?.saved?.id) { mutableStateOf<List<SdkWifiNetwork>>(emptyList()) }
    var loading by remember(activeDevice?.saved?.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectRequired by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<WifiEditor?>(null) }
    var deleting by remember { mutableStateOf<SdkWifiNetwork?>(null) }

    fun refresh() {
        if (!canManageWifi || loading || busy) return
        loading = true
        error = null
        reconnectRequired = false
        scope.launch {
            try {
                networks = DeviceSessions.listWifiNetworks()
            } catch (cause: Exception) {
                error = wifiError(cause)
                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(activeDevice?.saved?.id, canManageWifi) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi 管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (canManageWifi && canSave) {
                        IconButton(onClick = { editor = WifiEditor() }, enabled = !busy) {
                            Icon(Icons.Default.Add, contentDescription = "添加 Wi-Fi")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            !canManageWifi -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (activeDevice == null) "设备未连接" else "设备不支持 Wi-Fi 管理",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            loading && networks.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                if (networks.isEmpty()) {
                    item {
                        Text(
                            "没有已保存的 Wi-Fi",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(networks, key = { it.index?.toString() ?: "read-pico-${it.ssid}" }) { network ->
                    ListItem(
                        headlineContent = { Text(network.ssid) },
                        supportingContent = {
                            when {
                                network.isLastConnected -> Text("最近连接")
                                network.hasPassword -> Text("已设置密码")
                            }
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (canSave) IconButton(
                                    onClick = {
                                        editor = WifiEditor(network.index, network.ssid, network.hasPassword)
                                    },
                                    enabled = !busy,
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑 ${network.ssid}")
                                }
                                if (canDelete) IconButton(onClick = { deleting = network }, enabled = !busy) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除 ${network.ssid}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    editor?.let { current ->
        WifiEditorDialog(
            initial = current,
            busy = busy,
            onDismiss = { editor = null },
            onSave = { ssid, password ->
                if (busy) return@WifiEditorDialog
                busy = true
                scope.launch {
                    try {
                        DeviceSessions.saveWifiNetwork(
                            SdkWifiCredential(
                                index = current.index,
                                ssid = ssid.trim(),
                                password = password.takeIf(String::isNotEmpty),
                            ),
                        )
                        editor = null
                        networks = DeviceSessions.listWifiNetworks()
                        error = null
                    } catch (cause: Exception) {
                        error = wifiError(cause)
                        reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }

    deleting?.let { network ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text("删除 Wi-Fi？") },
            text = { Text(network.ssid) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            try {
                                DeviceSessions.deleteWifiNetwork(network.index)
                                deleting = null
                                networks = DeviceSessions.listWifiNetworks()
                                error = null
                            } catch (cause: Exception) {
                                error = wifiError(cause)
                                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, enabled = !busy) { Text("取消") }
            },
        )
    }

    OperationErrorDialog(
        error,
        onDismiss = { error = null },
        title = "Wi-Fi 管理失败",
        reconnectToDevice = reconnectRequired,
    )
}

private data class WifiEditor(
    val index: UInt? = null,
    val ssid: String = "",
    val passwordRequired: Boolean = false,
)

@Composable
private fun WifiEditorDialog(
    initial: WifiEditor,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var ssid by remember(initial) { mutableStateOf(initial.ssid) }
    var password by remember(initial) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial.index == null && initial.ssid.isEmpty()) "添加 Wi-Fi" else "编辑 Wi-Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("网络名称") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(ssid, password) },
                enabled = !busy && ssid.isNotBlank() && (!initial.passwordRequired || password.isNotEmpty()),
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

private fun wifiError(cause: Exception): String = DeviceSessions.describeOperationFailure(cause, "Wi-Fi 操作")
