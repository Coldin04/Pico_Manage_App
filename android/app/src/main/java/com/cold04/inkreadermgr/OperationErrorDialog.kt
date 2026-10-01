package com.cold04.inkreadermgr

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.content.Intent

@Composable
fun OperationErrorDialog(
    message: String?,
    onDismiss: () -> Unit,
    title: String = "操作失败",
    reconnectToDevice: Boolean = false,
) {
    if (message == null) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message.ifBlank { "操作失败，请重试" }) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = if (reconnectToDevice) {
            {
                TextButton(onClick = {
                    onDismiss()
                    context.startActivity(Intent(context, DeviceActivity::class.java))
                }) { Text("设备连接") }
            }
        } else null,
    )
}
