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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.cold04.picomanage.ui.theme.PicoManageTheme
import kotlinx.coroutines.launch
import uniffi.picobook_sdk.SdkDeviceInfoField

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
    val activeDevice = state.active
    val canReadDeviceInfo = activeDevice?.profile?.capabilities?.contains("device.info") == true
    val scope = rememberCoroutineScope()
    var fields by remember(activeDevice?.saved?.id) { mutableStateOf<List<SdkDeviceInfoField>>(emptyList()) }
    var loading by remember(activeDevice?.saved?.id) { mutableStateOf(false) }
    var failure by remember(activeDevice?.saved?.id) { mutableStateOf<String?>(null) }

    fun refresh() {
        if (!canReadDeviceInfo || loading) return
        loading = true
        failure = null
        scope.launch {
            try {
                fields = DeviceSessions.deviceInfo()
            } catch (cause: Exception) {
                failure = DeviceSessions.describeOperationFailure(cause, "获取设备信息")
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(activeDevice?.saved?.id, canReadDeviceInfo) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (canReadDeviceInfo) {
                        IconButton(onClick = ::refresh, enabled = !loading) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新设备信息")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            activeDevice == null -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("设备未连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !canReadDeviceInfo -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("设备不支持读取设备信息", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            loading && fields.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                if (fields.isEmpty()) {
                    item {
                        Text(
                            "设备没有返回信息",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(fields.size, key = { fields[it].key }) { index ->
                    val field = fields[index]
                    InformationRow(deviceInfoLabel(field.key), formatDeviceInfoValue(field))
                }
            }
        }

        failure?.let { message ->
            AlertDialog(
                onDismissRequest = { failure = null },
                title = { Text("无法获取设备信息") },
                text = { Text(message) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = ::refresh) { Text("重试") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { failure = null }) { Text("关闭") }
                },
            )
        }
    }
}

private fun deviceInfoLabel(key: String): String = when (key) {
    "storage_is_flash" -> "存储介质"
    "storage_free_bytes" -> "可用存储空间"
    "storage_file_limit" -> "文件数量上限"
    "storage_root" -> "存储目录"
    "network_mode" -> "网络模式"
    "wifi_configured" -> "Wi-Fi"
    "wifi_ssid" -> "Wi-Fi 名称"
    "firmware_version" -> "固件版本"
    "ip_address" -> "IP 地址"
    "wifi_rssi" -> "Wi-Fi 信号"
    "free_heap" -> "可用内存"
    "uptime" -> "运行时间"
    "device_model" -> "设备型号"
    else -> key
}

private fun formatDeviceInfoValue(field: SdkDeviceInfoField): String = when (field.key) {
    "storage_is_flash" -> if (field.value == "true") "闪存" else "非闪存"
    "storage_free_bytes", "free_heap" -> field.value.toULongOrNull()?.let(::formatBytes) ?: field.value
    "uptime" -> field.value.toULongOrNull()?.let(::formatDuration) ?: field.value
    "wifi_configured" -> if (field.value == "true") "已配置" else "未配置"
    "network_mode" -> when (field.value.lowercase()) {
        "ap" -> "热点"
        "sta", "station" -> "Wi-Fi"
        else -> field.value
    }
    "wifi_rssi" -> field.value.toLongOrNull()?.let { "$it dBm" } ?: field.value
    else -> field.value
}

private fun formatBytes(bytes: ULong): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes ${units[unit]}" else "%.1f %s".format(value, units[unit])
}

private fun formatDuration(seconds: ULong): String {
    val days = seconds / 86_400u
    val hours = seconds % 86_400u / 3_600u
    val minutes = seconds % 3_600u / 60u
    val remainingSeconds = seconds % 60u
    return buildList {
        if (days > 0u) add("${days}天")
        if (hours > 0u || isNotEmpty()) add("${hours}小时")
        if (minutes > 0u || isNotEmpty()) add("${minutes}分")
        add("${remainingSeconds}秒")
    }.joinToString("")
}

@Composable
private fun InformationRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
    )
}
