package com.cold04.inkreadermgr

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uniffi.inkreaderlink_uniffi.SdkOperationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePage(onBack: () -> Unit) {
    val deviceState by DeviceSessions.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var connectingId by remember { mutableStateOf<String?>(null) }
    var operationFailure by remember { mutableStateOf<String?>(null) }
    var connectionFailure by remember { mutableStateOf<Pair<SavedDevice, Exception>?>(null) }
    var deleting by remember { mutableStateOf<SavedDevice?>(null) }
    var editing by remember { mutableStateOf<SavedDevice?>(null) }

    fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (cause: Exception) {
                operationFailure = connectionError(cause)
            } finally {
                busy = false
            }
        }
    }

    fun connectDevice(saved: SavedDevice) {
        if (connectingId != null) return
        connectingId = saved.id
        scope.launch {
            try {
                DeviceSessions.connect(saved.id)
            } catch (cause: Exception) {
                connectionFailure = saved to cause
            } finally {
                connectingId = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加设备")
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (busy) CircularProgressIndicator(Modifier.padding(20.dp))
            if (deviceState.saved.isEmpty()) {
                Text(
                    text = "尚未保存设备",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(deviceState.saved, key = { it.id }) { saved ->
                        val isActive = deviceState.active?.saved?.id == saved.id
                        ListItem(
                            headlineContent = { Text(saved.address) },
                            supportingContent = {
                                Column {
                                    Text("固件：${deviceLabel(saved.deviceType)}")
                                    if (isActive) Text("已连接")
                                    if (connectingId == saved.id) {
                                        Text("正在连接…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        onClick = {
                                            runOperation {
                                                if (isActive) DeviceSessions.disconnect()
                                                else connectDevice(saved)
                                            }
                                        },
                                        enabled = !busy && (connectingId == null || connectingId == saved.id),
                                    ) {
                                        Icon(
                                            if (isActive) Icons.Default.LinkOff else Icons.Default.Link,
                                            contentDescription = if (isActive) "断开设备" else "连接设备",
                                        )
                                    }
                                    IconButton(onClick = { editing = saved }, enabled = !busy) {
                                        Icon(Icons.Default.Edit, contentDescription = "编辑设备")
                                    }
                                    IconButton(onClick = { deleting = saved }, enabled = !busy) {
                                        Icon(Icons.Default.Delete, contentDescription = "移除设备")
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAdd) {
        DeviceEditorDialog(
            existing = null,
            onDismiss = { showAdd = false },
            onSave = { deviceType, address ->
                showAdd = false
                runOperation {
                    val saved = DeviceSessions.save(deviceType, address)
                    connectDevice(saved)
                }
            },
        )
    }

    editing?.let { saved ->
        DeviceEditorDialog(
            existing = saved,
            onDismiss = { editing = null },
            onSave = { deviceType, address ->
                val wasActive = deviceState.active?.saved?.id == saved.id
                editing = null
                runOperation {
                    DeviceSessions.update(saved.id, deviceType, address)
                    if (wasActive) connectDevice(saved)
                }
            },
        )
    }

    deleting?.let { saved ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移除设备？") },
            text = { Text(saved.address) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    runOperation { DeviceSessions.remove(saved.id) }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    connectionFailure?.let { (saved, cause) ->
        AlertDialog(
            onDismissRequest = { connectionFailure = null },
            title = { Text(connectionErrorTitle(cause)) },
            text = { Text("${connectionError(cause)}\n${saved.address}") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        connectionFailure = null
                        connectDevice(saved)
                    }) { Text("重试") }
                    TextButton(onClick = {
                        connectionFailure = null
                        editing = saved
                    }) { Text("修改地址") }
                }
            },
            dismissButton = { TextButton(onClick = { connectionFailure = null }) { Text("关闭") } },
        )
    }

    OperationErrorDialog(
        message = operationFailure,
        onDismiss = { operationFailure = null },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceEditorDialog(
    existing: SavedDevice?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var deviceType by rememberSaveable(existing?.id) { mutableStateOf(existing?.deviceType ?: "read-pico") }
    var address by rememberSaveable(existing?.id) { mutableStateOf(existing?.address ?: "") }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(ScanActivity.EXTRA_ADDRESS)?.let { address = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加设备" else "编辑设备") },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = deviceLabel(deviceType),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("固件类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        supportedDeviceTypes.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    deviceType = type
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("设备地址") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    scanLauncher.launch(Intent(context, ScanActivity::class.java))
                }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("扫描二维码")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(deviceType, address) }, enabled = address.isNotBlank()) {
                Text(if (existing == null) "保存并连接" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val supportedDeviceTypes = listOf("read-pico" to "Read Pico", "crosspoint" to "CrossPoint")

private fun deviceLabel(type: String): String = when (type) {
    "read-pico" -> "Read Pico"
    "crosspoint" -> "CrossPoint"
    else -> type
}

private fun connectionError(cause: Exception): String = when (cause) {
    is SdkOperationException.InvalidArgument -> "设备地址无效"
    is SdkOperationException.Unsupported -> "当前 SDK 不支持该设备"
    is SdkOperationException.Unreachable -> "无法连接设备，请检查地址和网络"
    is SdkOperationException.Timeout -> "连接超时，请确认设备处于传书模式"
    is SdkOperationException.RemoteFailure -> "设备响应异常，请重试"
    is IllegalArgumentException -> cause.message ?: "输入无效"
    else -> "连接失败，请重试"
}

private fun connectionErrorTitle(cause: Exception): String =
    if (cause is SdkOperationException.Timeout) "连接超时" else "连接失败"
