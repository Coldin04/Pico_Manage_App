package com.cold04.picomanage

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cold04.picomanage.ui.theme.PicoManageTheme
import kotlinx.coroutines.launch
import uniffi.picobook_sdk.SdkOpdsServer

class OpdsManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        enableEdgeToEdge()
        setContent { PicoManageTheme { OpdsManagementPage(onBack = ::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpdsManagementPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val deviceState by DeviceSessions.state.collectAsState()
    val activeDevice = deviceState.active
    val capabilities = activeDevice?.profile?.capabilities?.toSet().orEmpty()
    val canManage = activeDevice != null && "opds.list" in capabilities
    val canSave = "opds.save" in capabilities
    val canDelete = "opds.delete" in capabilities
    val scope = rememberCoroutineScope()
    var servers by remember(activeDevice?.saved?.id) { mutableStateOf<List<SdkOpdsServer>>(emptyList()) }
    var loading by remember(activeDevice?.saved?.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectRequired by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SdkOpdsServer?>(null) }

    fun refresh() {
        if (!canManage || loading || busy) return
        loading = true
        error = null
        reconnectRequired = false
        scope.launch {
            try {
                servers = DeviceSessions.listOpdsServers()
            } catch (cause: Exception) {
                error = managerError(cause)
                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
            } finally {
                loading = false
            }
        }
    }

    val editorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) refresh()
    }

    LaunchedEffect(activeDevice?.saved?.id, canManage) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OPDS 管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (canManage && canSave) IconButton(
                        onClick = { editorLauncher.launch(OpdsEditorActivity.intent(context)) },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加 OPDS")
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
                    if (activeDevice == null) "设备未连接" else "设备不支持 OPDS 管理",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            loading && servers.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                if (servers.isEmpty()) item {
                    Text(
                        "没有已保存的 OPDS 书库",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(servers, key = { it.index.toLong() }) { server ->
                    ListItem(
                        headlineContent = { Text(server.name) },
                        supportingContent = {
                            Column {
                                Text(server.url)
                                if (server.username.isNotBlank()) Text(server.username)
                                if (server.hasPassword) Text("已设置密码")
                            }
                        },
                        trailingContent = {
                            androidx.compose.foundation.layout.Row {
                                if (canSave) IconButton(
                                    onClick = { editorLauncher.launch(OpdsEditorActivity.intent(context, server)) },
                                    enabled = !busy,
                                ) { Icon(Icons.Default.Edit, contentDescription = "编辑 ${server.name}") }
                                if (canDelete) IconButton(onClick = { deleting = server }, enabled = !busy) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除 ${server.name}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    deleting?.let { server ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text("删除 OPDS 书库？") },
            text = { Text(server.name) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                DeviceSessions.deleteOpdsServer(server.index)
                                deleting = null
                                servers = DeviceSessions.listOpdsServers()
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
        title = "OPDS 管理失败",
        reconnectToDevice = reconnectRequired,
    )
}
