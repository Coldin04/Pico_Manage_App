package com.cold04.picomanage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cold04.picomanage.ui.theme.PicoManageTheme
import kotlinx.coroutines.launch
import uniffi.picobook_sdk.SdkOpdsCredential
import uniffi.picobook_sdk.SdkOpdsServer

class OpdsEditorActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_INDEX = "opds_index"
        private const val EXTRA_NAME = "opds_name"
        private const val EXTRA_URL = "opds_url"
        private const val EXTRA_USERNAME = "opds_username"

        fun intent(context: Context, server: SdkOpdsServer? = null): Intent =
            Intent(context, OpdsEditorActivity::class.java).apply {
                if (server != null) {
                    putExtra(EXTRA_INDEX, server.index.toString())
                    putExtra(EXTRA_NAME, server.name)
                    putExtra(EXTRA_URL, server.url)
                    putExtra(EXTRA_USERNAME, server.username)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        val initial = OpdsEditorData(
            index = intent.getStringExtra(EXTRA_INDEX)?.toUIntOrNull(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            url = intent.getStringExtra(EXTRA_URL).orEmpty(),
            username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
        )
        enableEdgeToEdge()
        setContent {
            PicoManageTheme {
                OpdsEditorPage(
                    initial = initial,
                    onBack = ::finish,
                    onSaved = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                )
            }
        }
    }
}

private data class OpdsEditorData(
    val index: UInt?,
    val name: String,
    val url: String,
    val username: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpdsEditorPage(initial: OpdsEditorData, onBack: () -> Unit, onSaved: () -> Unit) {
    val deviceState by DeviceSessions.state.collectAsState()
    val canSave = deviceState.active?.profile?.capabilities?.contains("opds.save") == true
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var url by rememberSaveable { mutableStateOf(initial.url) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectRequired by remember { mutableStateOf(false) }
    val editing = initial.index != null

    BackHandler(enabled = busy) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "编辑 OPDS 书库" else "添加 OPDS 书库") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave && !busy && name.isNotBlank() && url.isNotBlank(),
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            scope.launch {
                                try {
                                    DeviceSessions.saveOpdsServer(
                                        SdkOpdsCredential(
                                            index = initial.index,
                                            name = name.trim(),
                                            url = url.trim(),
                                            username = username.trim(),
                                            password = password.takeIf(String::isNotEmpty),
                                        ),
                                    )
                                    onSaved()
                                } catch (cause: Exception) {
                                    error = managerError(cause)
                                    reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text("保存") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!canSave) {
                Text(
                    if (deviceState.active == null) "设备未连接" else "设备不支持 OPDS 管理",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                enabled = canSave && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                enabled = canSave && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名（可选）") },
                singleLine = true,
                enabled = canSave && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码（可选）") },
                placeholder = {
                    if (editing) Text("未修改")
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = canSave && !busy) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        )
                    }
                },
                singleLine = true,
                enabled = canSave && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    OperationErrorDialog(
        error,
        onDismiss = { error = null },
        title = "保存失败",
        reconnectToDevice = reconnectRequired,
    )
}
