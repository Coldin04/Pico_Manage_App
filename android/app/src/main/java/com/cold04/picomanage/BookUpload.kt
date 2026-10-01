package com.cold04.picomanage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.ClipData
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import uniffi.picobook_sdk.SdkDeviceProfile
import uniffi.picobook_sdk.SdkFileEntry
import uniffi.picobook_sdk.SdkFileLocation
import java.util.Locale

data class BookUploadFile(val uri: Uri, val name: String, val contentType: String?)

internal data class IncomingFilesEvent<T>(val id: Long, val value: T)

internal class IncomingFilesInbox<T> {
    private val nextId = AtomicLong(0)
    private val mutableState = MutableStateFlow<IncomingFilesEvent<T>?>(null)
    val state = mutableState.asStateFlow()

    fun publish(value: T) {
        mutableState.value = IncomingFilesEvent(nextId.incrementAndGet(), value)
    }

    fun consume(id: Long) {
        if (mutableState.value?.id == id) mutableState.value = null
    }
}

object IncomingShareFiles {
    fun receive(context: Context, intent: Intent?): List<BookUploadFile> {
        if (intent == null) return emptyList()
        val sharedUris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUri(Intent.EXTRA_STREAM)) +
                intent.clipData.uris()
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableUris(Intent.EXTRA_STREAM) + intent.clipData.uris()
            else -> emptyList()
        }.distinct()
        return sharedUris.map { uri ->
            BookUploadFile(
                uri,
                displayName(context, uri),
                runCatching { context.contentResolver.getType(uri) }.getOrNull(),
            )
        }
    }

    private fun ClipData?.uris(): List<Uri> = this?.let { clip ->
        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }.orEmpty()

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUri(key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java)
        else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUris(key: String): List<Uri> =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
}
data class BookUploadOutcome(
    val name: String,
    val success: Boolean,
    val message: String? = null,
    val reconnectRequired: Boolean = false,
)
data class BookUploadReview(
    val accepted: List<BookUploadFile>,
    val unsupportedNames: List<String>,
    val mayNotBeReadableNames: List<String>,
)
data class BookUploadProgress(
    val running: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val currentName: String? = null,
    val sentBytes: ULong? = null,
    val totalBytes: ULong? = null,
    val outcomes: List<BookUploadOutcome> = emptyList(),
    val skippedNames: List<String> = emptyList(),
    val batchError: String? = null,
    val reconnectRequired: Boolean = false,
)

@Composable
fun rememberBookFilePicker(onFilesPicked: (List<BookUploadFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onFilesPicked(uris.map { uri ->
            BookUploadFile(
                uri = uri,
                name = displayName(context, uri),
                contentType = context.contentResolver.getType(uri),
            )
        })
    }
    return { picker.launch(arrayOf("*/*")) }
}

internal fun displayName(context: Context, uri: Uri): String {
    val queriedName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
    return queriedName?.takeIf(String::isNotBlank)
        ?: Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').takeIf(String::isNotBlank)
        ?: "book"
}

fun reviewBookFiles(files: List<BookUploadFile>, profile: SdkDeviceProfile): BookUploadReview {
    val formats = profile.fileFormats
    val uploadExtensions = formats.uploadExtensions.map(::normalizeExtension).toSet()
    val readableExtensions = formats.readableExtensions.map(::normalizeExtension).toSet()
    val accepted = mutableListOf<BookUploadFile>()
    val unsupported = mutableListOf<String>()
    val mayNotBeReadable = mutableListOf<String>()
    files.forEach { file ->
        val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!formats.acceptsAnyUploadFormat && extension !in uploadExtensions) {
            unsupported += file.name
        } else {
            accepted += file
            if (formats.acceptsAnyUploadFormat && extension !in readableExtensions) mayNotBeReadable += file.name
        }
    }
    return BookUploadReview(accepted, unsupported, mayNotBeReadable)
}

private fun normalizeExtension(value: String) = value.trim().removePrefix(".").lowercase(Locale.ROOT)

object BookUploadQueue {
    private val mutableState = MutableStateFlow(BookUploadProgress())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = mutableState.asStateFlow()

    fun start(
        context: Context,
        files: List<BookUploadFile>,
        location: SdkFileLocation,
        skippedNames: List<String> = emptyList(),
    ) {
        if (files.isEmpty() || mutableState.value.running) return
        mutableState.value = BookUploadProgress(
            running = true,
            totalCount = files.size,
            skippedNames = skippedNames,
        )
        scope.launch {
            val outcomes = try {
                DeviceSessions.uploadBatch(context.applicationContext, files, location) { index, file, sent, total ->
                    mutableState.update {
                        it.copy(currentIndex = index + 1, currentName = file.name, sentBytes = sent, totalBytes = total)
                    }
                }
            } catch (cause: Exception) {
                val message = DeviceSessions.describeUploadFailure(cause)
                Log.e("PicoUpload", "Upload batch failed before completing: $message", cause)
                mutableState.update {
                    it.copy(
                        running = false,
                        batchError = message,
                        reconnectRequired = DeviceSessions.isConnectionFailure(cause),
                        sentBytes = null,
                        totalBytes = null,
                    )
                }
                return@launch
            }
            mutableState.update { it.copy(running = false, outcomes = outcomes, sentBytes = null, totalBytes = null) }
            if (outcomes.all { it.success } && skippedNames.isEmpty()) {
                try {
                    withContext(Dispatchers.Main.immediate) {
                        Toast.makeText(
                            context.applicationContext,
                            uploadResultMessage(outcomes, skippedNames),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } catch (cause: Exception) {
                    Log.w("PicoUpload", "Upload succeeded but completion notification failed", cause)
                }
            }
        }
    }
}

private fun uploadResultMessage(outcomes: List<BookUploadOutcome>, skippedNames: List<String>): String {
    val failures = outcomes.filterNot { it.success }
    val successCount = outcomes.size - failures.size
    val failureCount = failures.size + skippedNames.size
    if (failureCount == 0) return "上传完成：${successCount} 个文件"
    val firstFailure = failures.firstOrNull()
    val detail = firstFailure?.let { "${it.name}：${it.message ?: "上传失败"}" }
        ?: skippedNames.firstOrNull()?.let { "$it：设备不支持此文件格式" }
    return buildString {
        append("上传结束：成功 $successCount，失败 $failureCount")
        if (detail != null) append("；$detail")
    }
}

@Composable
@androidx.compose.material3.ExperimentalMaterial3Api
fun UploadDirectoryPicker(
    onCancel: () -> Unit,
    onChoose: (String?) -> Unit,
) {
    var pathStack by remember { mutableStateOf(emptyList<String>()) }
    val currentDirectory = pathStack.lastOrNull()
    var entries by remember { mutableStateOf(emptyList<SdkFileEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectRequired by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    fun navigateUp() { pathStack = pathStack.dropLast(1) }
    BackHandler { if (pathStack.isEmpty()) onCancel() else navigateUp() }
    LaunchedEffect(currentDirectory) {
        loading = true
        error = null
        entries = emptyList()
        try {
            entries = DeviceSessions.listFiles(currentDirectory?.let(SdkFileLocation::Directory) ?: SdkFileLocation.Root)
        } catch (cause: Exception) {
            error = DeviceSessions.describeOperationFailure(cause, "读取目录")
            reconnectRequired = DeviceSessions.isConnectionFailure(cause)
        } finally { loading = false }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(currentDirectory?.substringAfterLast('/')?.ifEmpty { "选择上传目录" } ?: "选择上传目录") },
                    navigationIcon = {
                        IconButton(onClick = { if (pathStack.isEmpty()) onCancel() else navigateUp() }) {
                            Icon(
                                if (pathStack.isEmpty()) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (pathStack.isEmpty()) "取消" else "返回上级目录",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { onChoose(currentDirectory) }, enabled = !loading) {
                            Icon(Icons.Default.Check, contentDescription = "选择此目录")
                        }
                    },
                )
            },
        ) { insets ->
            DeviceFileBrowser(
                entries = entries,
                currentDirectory = currentDirectory,
                loading = loading,
                error = error,
                mode = FileBrowserMode.SelectDirectory,
                showCurrentDirectoryHeader = false,
                showSelectDirectoryAction = false,
                onOpenDirectory = { pathStack = pathStack + it.path },
                onNavigateUp = ::navigateUp,
                modifier = Modifier.padding(insets),
            )
        }
        OperationErrorDialog(
            error,
            onDismiss = { error = null },
            title = "目录读取失败",
            reconnectToDevice = reconnectRequired,
        )
    }
}

@Composable
fun BookUploadReviewDialog(
    review: BookUploadReview,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    val hasWarnings = review.unsupportedNames.isNotEmpty() || review.mayNotBeReadableNames.isNotEmpty()
    if (!hasWarnings) return
    val title = if (review.accepted.isEmpty()) "文件格式不支持" else "确认上传"
    val message = buildList {
        if (review.unsupportedNames.isNotEmpty()) add("不支持上传：${review.unsupportedNames.joinToString("、")}")
        if (review.mayNotBeReadableNames.isNotEmpty()) add("设备可能无法阅读：${review.mayNotBeReadableNames.joinToString("、")}")
    }.joinToString("\n")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (review.accepted.isNotEmpty()) TextButton(onClick = onContinue) { Text("继续") }
            else TextButton(onClick = onCancel) { Text("关闭") }
        },
        dismissButton = if (review.accepted.isNotEmpty()) ({ TextButton(onClick = onCancel) { Text("取消") } }) else null,
    )
}

@Composable
fun BookUploadStatus(modifier: Modifier = Modifier) {
    val progress by BookUploadQueue.state.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    var showFailureSnackbar by remember { mutableStateOf(false) }
    val failedOutcomes = progress.outcomes.filterNot { it.success }
    val hasFailure = !progress.running && (progress.batchError != null || failedOutcomes.isNotEmpty() || progress.skippedNames.isNotEmpty())
    LaunchedEffect(progress.running, progress.batchError, progress.outcomes, progress.skippedNames) {
        if (hasFailure) {
            showFailureSnackbar = true
            delay(4_000)
            showFailureSnackbar = false
        } else {
            showFailureSnackbar = false
        }
    }
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val snackbarContainer = if (isDarkTheme) Color.Black else Color(0xFF313033)
    if (progress.running) {
        Snackbar(
            modifier = modifier,
            containerColor = snackbarContainer,
            contentColor = Color(0xFFF4EFF4),
            actionContentColor = MaterialTheme.colorScheme.primary,
        ) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                Text("上传 ${progress.currentIndex.coerceAtLeast(1)}/${progress.totalCount} ${progress.currentName.orEmpty()}")
                val total = progress.totalBytes
                val sent = progress.sentBytes
                if (total != null && total > 0uL && sent != null) {
                    LinearProgressIndicator(
                        progress = { (sent.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    } else if (hasFailure && showFailureSnackbar) {
        val failureCount = failedOutcomes.size + progress.skippedNames.size
        val successes = progress.outcomes.count { it.success }
        val summary = progress.batchError?.let { "上传失败：$it" }
            ?: "上传结束：成功 $successes，失败 $failureCount"
        Snackbar(
            modifier = modifier,
            containerColor = snackbarContainer,
            contentColor = Color(0xFFF4EFF4),
            actionContentColor = MaterialTheme.colorScheme.primary,
            action = { TextButton(onClick = { showDetails = true }) { Text("详情") } },
            actionOnNewLine = true,
        ) {
            Text(
                summary,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showDetails && hasFailure) {
        val details = buildList {
            progress.batchError?.let { add("上传失败：$it") }
            failedOutcomes.forEach { add("${it.name}：${it.message ?: "上传失败"}") }
            progress.skippedNames.forEach { add("$it：设备不支持此文件格式") }
        }.joinToString("\n")
        OperationErrorDialog(
            message = details.ifBlank { "上传失败，请重试" },
            onDismiss = { showDetails = false },
            title = if (progress.reconnectRequired || failedOutcomes.any { it.reconnectRequired }) "设备连接异常" else "上传详情",
            reconnectToDevice = progress.reconnectRequired || failedOutcomes.any { it.reconnectRequired },
        )
    }
}
