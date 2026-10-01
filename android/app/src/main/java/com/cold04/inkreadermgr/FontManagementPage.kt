package com.cold04.inkreadermgr

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cold04.inkreadermgr.ui.theme.PicoManageTheme
import kotlinx.coroutines.launch
import uniffi.inkreaderlink_uniffi.SdkFontCatalog
import uniffi.inkreaderlink_uniffi.SdkFontFamily

class FontManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        enableEdgeToEdge()
        setContent { PicoManageTheme { FontManagementPage(onBack = ::finish) } }
    }
}

private data class FontSource(val uri: Uri, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontManagementPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val active by DeviceSessions.state.collectAsState()
    val activeDevice = active.active
    val capabilities = activeDevice?.profile?.capabilities?.toSet().orEmpty()
    val fontExtensions = activeDevice?.profile?.fileFormats?.fontUploadExtensions
        ?.map { it.trim().removePrefix(".").lowercase(java.util.Locale.ROOT) }
        ?.filter(String::isNotBlank)
        .orEmpty()
    val canManage = activeDevice != null && "fonts.list" in capabilities
    val canUpload = "fonts.upload" in capabilities && fontExtensions.isNotEmpty()
    val canDelete = "fonts.delete" in capabilities
    val scope = rememberCoroutineScope()
    var catalog by remember(activeDevice?.saved?.id) { mutableStateOf<SdkFontCatalog?>(null) }
    var loading by remember(activeDevice?.saved?.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectRequired by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<FontSource?>(null) }
    var familyName by remember { mutableStateOf("") }
    var requestedFamilyName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SdkFontFamily?>(null) }

    fun refresh() {
        if (!canManage || loading || busy) return
        loading = true
        error = null
        reconnectRequired = false
        scope.launch {
            try {
                catalog = DeviceSessions.listFonts()
            } catch (cause: Exception) {
                error = managerError(cause)
                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
            } finally {
                loading = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                    ?: "未命名字体"
                val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (extension !in fontExtensions) {
                    error = "请选择支持的字体文件：${fontExtensions.joinToString { ".$it" }}"
                } else {
                    source = FontSource(uri, name)
                    familyName = requestedFamilyName
                }
            } catch (cause: Exception) {
                error = managerError(cause)
                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
            }
        }
    }

    LaunchedEffect(activeDevice?.saved?.id, canManage) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("字体管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (canManage && canUpload) IconButton(onClick = {
                        requestedFamilyName = ""
                        filePicker.launch(arrayOf("*/*"))
                    }, enabled = !busy) {
                        Icon(Icons.Default.Add, contentDescription = "添加字体")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            !canManage -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (activeDevice == null) "设备未连接" else "设备不支持字体管理",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            loading && catalog == null -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                val families = catalog?.families.orEmpty()
                if (families.isEmpty() && error == null) item {
                    Text(
                        "没有已安装字体",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(families, key = { it.name }) { family ->
                    ListItem(
                        headlineContent = { Text(family.name) },
                        supportingContent = {
                            Column {
                                if (family.sizes.isNotEmpty()) Text("字号：${family.sizes.joinToString("、")} pt")
                                family.files.forEach { file -> Text("${file.name} · ${file.size} B") }
                            }
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (canUpload) IconButton(
                                    onClick = {
                                        requestedFamilyName = family.name
                                        filePicker.launch(arrayOf("*/*"))
                                    },
                                    enabled = !busy,
                                ) { Icon(Icons.Default.Add, contentDescription = "向 ${family.name} 添加字体文件") }
                                if (canDelete) IconButton(onClick = { deleting = family }, enabled = !busy) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除 ${family.name}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    source?.let { selected ->
        AlertDialog(
            onDismissRequest = { if (!busy) source = null },
            title = { Text("上传字体") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = familyName,
                        onValueChange = { familyName = it },
                        label = { Text("字体族") },
                        singleLine = true,
                        enabled = !busy,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && familyName.isNotBlank(),
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            try {
                                DeviceSessions.uploadFont(context, familyName.trim(), selected.uri, selected.name)
                                source = null
                                catalog = DeviceSessions.listFonts()
                                error = null
                            } catch (cause: Exception) {
                                error = managerError(cause)
                                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("上传") }
            },
            dismissButton = { TextButton(onClick = { source = null }, enabled = !busy) { Text("取消") } },
        )
    }

    deleting?.let { family ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text("删除字体族？") },
            text = { Text(family.name) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                DeviceSessions.deleteFontFamily(family.name)
                                deleting = null
                                catalog = DeviceSessions.listFonts()
                                error = null
                            } catch (cause: Exception) {
                                error = managerError(cause)
                                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text("取消") } },
        )
    }

    OperationErrorDialog(
        error,
        onDismiss = { error = null },
        title = "字体管理失败",
        reconnectToDevice = reconnectRequired,
    )
}

internal fun managerError(cause: Exception): String = DeviceSessions.describeOperationFailure(cause, "操作")
