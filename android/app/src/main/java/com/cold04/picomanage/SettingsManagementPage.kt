package com.cold04.picomanage

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cold04.picomanage.ui.theme.PicoManageTheme
import kotlinx.coroutines.launch
import uniffi.picobook_sdk.SdkSettingChange
import uniffi.picobook_sdk.SdkSettingDescriptor
import uniffi.picobook_sdk.SdkSettingKind
import uniffi.picobook_sdk.SdkSettingValue
import uniffi.picobook_sdk.SdkSettingsSnapshot

class SettingsManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeviceSessions.initialize(applicationContext)
        setContent { PicoManageTheme { SettingsManagementPage(onBack = ::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsManagementPage(onBack: () -> Unit) {
    val deviceState by DeviceSessions.state.collectAsState()
    val activeDevice = deviceState.active
    val capabilities = activeDevice?.profile?.capabilities?.toSet().orEmpty()
    val canList = activeDevice != null && "settings.list" in capabilities
    val canUpdate = "settings.update" in capabilities
    val scope = rememberCoroutineScope()
    var snapshot by remember(activeDevice?.saved?.id) { mutableStateOf<SdkSettingsSnapshot?>(null) }
    var loading by remember(activeDevice?.saved?.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectRequired by remember { mutableStateOf(false) }
    val values = remember(activeDevice?.saved?.id) { mutableStateMapOf<String, SdkSettingValue>() }
    val numberInputs = remember(activeDevice?.saved?.id) { mutableStateMapOf<String, String>() }

    fun loadSettings() {
        if (!canList || loading || busy) return
        loading = true
        scope.launch {
            try {
                snapshot = DeviceSessions.listSettings().also {
                    values.clear()
                    numberInputs.clear()
                }
                error = null
            } catch (cause: Exception) {
                error = DeviceSessions.describeOperationFailure(cause, "读取设置")
                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(activeDevice?.saved?.id, canList) { loadSettings() }

    val settings = snapshot?.settings.orEmpty()
    val hasChanges = settings.any { setting -> values[setting.key]?.let { it != setting.value } == true } ||
        settings.any { setting ->
            val input = numberInputs[setting.key] ?: return@any false
            val value = (setting.value as? SdkSettingValue.Number)?.value ?: return@any false
            input.toLongOrNull()?.let { it != value } ?: true
        }
    val numbersValid = settings.all { setting ->
        val kind = setting.kind as? SdkSettingKind.Number ?: return@all true
        val raw = numberInputs[setting.key] ?: return@all true
        val number = raw.toLongOrNull() ?: return@all false
        number in kind.min..kind.max && (kind.step <= 1L || (number - kind.min) % kind.step == 0L)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (canUpdate) TextButton(
                        onClick = {
                            val current = snapshot ?: return@TextButton
                            val changes = settings.mapNotNull { setting ->
                                val updated = values[setting.key] ?: numberInputs[setting.key]
                                    ?.toLongOrNull()?.let { SdkSettingValue.Number(it) }
                                updated?.takeIf { it != setting.value }?.let { SdkSettingChange(setting.key, it) }
                            }
                            if (changes.isEmpty() || busy) return@TextButton
                            busy = true
                            scope.launch {
                                try {
                                    snapshot = DeviceSessions.applySettings(current, changes).also {
                                        values.clear()
                                        numberInputs.clear()
                                    }
                                    error = null
                                } catch (cause: Exception) {
                                    error = DeviceSessions.describeOperationFailure(cause, "保存设置")
                                    reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = hasChanges && numbersValid && !busy,
                    ) { Text("保存") }
                },
            )
        },
    ) { innerPadding ->
        when {
            !canList -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (activeDevice == null) "设备未连接" else "设备不支持设置读取",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            loading && snapshot == null -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> {
                val categories = settings.groupBy { it.category.ifBlank { "其他" } }
                LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                    if (settings.isEmpty() && error == null) item {
                        Text(
                            "没有可显示的设置",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    categories.forEach { (category, entries) ->
                        item(key = "category:$category") {
                            Text(
                                category,
                                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(entries, key = { it.key }) { setting ->
                            SettingRow(
                                setting = setting,
                                editable = canUpdate && !busy,
                                value = values[setting.key] ?: setting.value,
                                numberInput = numberInputs[setting.key]
                                    ?: (setting.value as? SdkSettingValue.Number)?.value?.toString().orEmpty(),
                                numberError = setting.numberInputError(numberInputs[setting.key]),
                                onValueChange = { values[setting.key] = it },
                                onNumberChange = { numberInputs[setting.key] = it },
                            )
                        }
                    }
                }
            }
        }
    }

    OperationErrorDialog(
        error,
        onDismiss = { error = null },
        title = "设置操作失败",
        reconnectToDevice = reconnectRequired,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingRow(
    setting: SdkSettingDescriptor,
    editable: Boolean,
    value: SdkSettingValue,
    numberInput: String,
    numberError: String?,
    onValueChange: (SdkSettingValue) -> Unit,
    onNumberChange: (String) -> Unit,
) {
    when (val kind = setting.kind) {
        SdkSettingKind.Toggle -> {
            val enabled = (value as? SdkSettingValue.Toggle)?.value ?: false
            ListItem(
                headlineContent = { Text(setting.name) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onValueChange(SdkSettingValue.Toggle(it)) },
                        enabled = editable,
                    )
                },
            )
        }
        is SdkSettingKind.Choice -> {
            val selected = (value as? SdkSettingValue.Choice)?.index?.toInt() ?: -1
            var expanded by remember(setting.key) { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = kind.options.getOrNull(selected) ?: "-",
                    onValueChange = {},
                    enabled = editable,
                    readOnly = true,
                    singleLine = true,
                    label = { Text(setting.name) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    kind.options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValueChange(SdkSettingValue.Choice(index.toUInt()))
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        is SdkSettingKind.Number -> {
            OutlinedTextField(
                value = numberInput,
                onValueChange = onNumberChange,
                enabled = editable,
                label = { Text(setting.name) },
                supportingText = {
                    Column {
                        Text("范围 ${kind.min} 到 ${kind.max}，步长 ${kind.step}")
                        if (numberError != null) {
                            Text(numberError, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                isError = numberError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (kind.min < 0L) KeyboardType.Ascii else KeyboardType.Number,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SdkSettingKind.Text -> {
            val text = (value as? SdkSettingValue.Text)?.value.orEmpty()
            OutlinedTextField(
                value = text,
                onValueChange = { onValueChange(SdkSettingValue.Text(it)) },
                enabled = editable,
                label = { Text(setting.name) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
            )
        }
    }
}

private fun SdkSettingDescriptor.numberInputError(input: String?): String? {
    val kind = kind as? SdkSettingKind.Number ?: return null
    if (input == null) return null
    val number = input.toLongOrNull()
        ?: return "请输入 ${kind.min} 到 ${kind.max} 范围内的整数"
    if (number !in kind.min..kind.max) return "数值需在 ${kind.min} 到 ${kind.max} 之间"
    if (kind.step > 1L && (number - kind.min) % kind.step != 0L) {
        return "请输入符合步长 ${kind.step} 的整数"
    }
    return null
}
