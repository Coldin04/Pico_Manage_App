package com.cold04.picomanage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.cold04.picomanage.ui.theme.PicoManageTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        PendingIncomingFiles.receive(this, intent)
        enableEdgeToEdge()
        setContent {
            PicoManageTheme {
                val devices by DeviceSessions.state.collectAsState()
                MainShell(
                    activeDevice = devices.active,
                    onDevicesClick = { startActivity(Intent(this, DeviceActivity::class.java)) },
                    onWifiClick = { startActivity(Intent(this, WifiManagementActivity::class.java)) },
                    onFontsClick = { startActivity(Intent(this, FontManagementActivity::class.java)) },
                    onOpdsClick = { startActivity(Intent(this, OpdsManagementActivity::class.java)) },
                    onFirmwareClick = { file ->
                        startActivity(Intent(this, FirmwareUpdateActivity::class.java).apply {
                            if (file != null) {
                                data = file.uri
                                putExtra(FirmwareUpdateActivity.EXTRA_FILE_NAME, file.name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        })
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PendingIncomingFiles.receive(this, intent)
    }
}

class DeviceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        enableEdgeToEdge()
        setContent { PicoManageTheme { DevicePage(onBack = ::finish) } }
    }
}

private enum class MainDestination(val label: String) {
    SEND("推书"),
    FILES("文件管理"),
    FEATURES("功能"),
}

private enum class SendFileCategory(val label: String) {
    BOOK("图书"),
    FONT("字体"),
    FIRMWARE("固件"),
}

private fun BookUploadFile.isBinFirmwareCandidate(): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) == "bin"

@Composable
private fun MainShell(
    activeDevice: ActiveDevice?,
    onDevicesClick: () -> Unit,
    onWifiClick: () -> Unit,
    onFontsClick: () -> Unit,
    onOpdsClick: () -> Unit,
    onFirmwareClick: (BookUploadFile?) -> Unit,
) {
    val destinations = MainDestination.entries
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val coroutineScope = rememberCoroutineScope()
    val incomingFiles by PendingIncomingFiles.files.collectAsState()
    val connectedDeviceCount = if (activeDevice == null) 0 else 1

    androidx.compose.runtime.LaunchedEffect(incomingFiles) {
        if (incomingFiles.isNotEmpty()) pagerState.animateScrollToPage(destinations.indexOf(MainDestination.SEND))
    }

    fun returnToSend() {
        coroutineScope.launch { pagerState.animateScrollToPage(destinations.indexOf(MainDestination.SEND)) }
    }

    val currentDestination = destinations.getOrElse(pagerState.currentPage) { MainDestination.SEND }
    BackHandler(enabled = currentDestination == MainDestination.FEATURES) {
        returnToSend()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            key = { destinations[it] },
        ) { page ->
            val destination = destinations[page]
            MainPage(
                destination = destination,
                connectedDeviceCount = connectedDeviceCount,
                onDevicesClick = onDevicesClick,
            ) {
                when (destination) {
                    MainDestination.SEND -> SendPage(activeDevice, onDevicesClick, onFirmwareClick)
                    MainDestination.FILES -> FileManagementPage(
                        connectedDeviceCount = connectedDeviceCount,
                        onDevicesClick = onDevicesClick,
                        isVisible = pagerState.currentPage == page,
                        onBackFromRoot = ::returnToSend,
                    )
                    MainDestination.FEATURES -> FeatureListPage(
                        activeDevice = activeDevice,
                        onWifiClick = onWifiClick,
                        onFontsClick = onFontsClick,
                        onOpdsClick = onOpdsClick,
                        onFirmwareClick = { onFirmwareClick(null) },
                    )
                }
            }
        }
        MainNavigation(
            destinations = destinations,
            selectedDestination = currentDestination,
            onDestinationClick = { destination ->
                coroutineScope.launch { pagerState.animateScrollToPage(destinations.indexOf(destination)) }
            },
        )
    }
}

@Composable
private fun MainPage(
    destination: MainDestination,
    connectedDeviceCount: Int,
    onDevicesClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            if (destination != MainDestination.FILES) {
                MainTopBar(destination, connectedDeviceCount, onDevicesClick)
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    destination: MainDestination,
    connectedDeviceCount: Int,
    onDevicesClick: () -> Unit,
) {
    when (destination) {
        MainDestination.SEND -> TopAppBar(
            title = { Text("推书") },
            actions = { DeviceConnectionControl(connectedDeviceCount = connectedDeviceCount, onClick = onDevicesClick) },
        )
        MainDestination.FILES -> TopAppBar(
            title = { Text("文件管理") },
            actions = { DeviceConnectionControl(connectedDeviceCount = connectedDeviceCount, onClick = onDevicesClick) },
        )
        MainDestination.FEATURES -> TopAppBar(
            title = { Text("功能") },
            actions = { DeviceConnectionControl(connectedDeviceCount = connectedDeviceCount, onClick = onDevicesClick) },
        )
    }
}

@Composable
internal fun DeviceConnectionControl(connectedDeviceCount: Int, onClick: () -> Unit) {
    val description = if (connectedDeviceCount > 0) "$connectedDeviceCount 台设备已连接" else "未连接设备"
    IconButton(onClick = onClick) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_mobile_2),
                contentDescription = description,
                modifier = Modifier.size(24.dp),
            )
            Badge(
                modifier = Modifier.align(Alignment.BottomEnd).size(8.dp),
                containerColor = if (connectedDeviceCount > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MainNavigation(
    destinations: List<MainDestination>,
    selectedDestination: MainDestination,
    onDestinationClick: (MainDestination) -> Unit,
) {
    NavigationBar {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = selectedDestination == destination,
                onClick = { onDestinationClick(destination) },
                icon = {
                    when (destination) {
                        MainDestination.SEND -> Icon(Icons.AutoMirrored.Filled.Send, null)
                        MainDestination.FILES -> Icon(Icons.Default.Folder, null)
                        MainDestination.FEATURES -> Icon(Icons.Default.MoreHoriz, null)
                    }
                },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SendPage(
    activeDevice: ActiveDevice?,
    onDevicesClick: () -> Unit,
    onFirmwareClick: (BookUploadFile?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val uploadProgress by BookUploadQueue.state.collectAsState()
    var selectedFiles by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<BookUploadFile>>(emptyList())
    }
    val incomingFiles by PendingIncomingFiles.files.collectAsState()
    var fontFamilyName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var showFontUploadDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var operationError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var reconnectRequired by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var category by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(SendFileCategory.BOOK)
    }
    var selectedDirectory by androidx.compose.runtime.remember(activeDevice?.saved?.id) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var pendingReview by androidx.compose.runtime.remember(activeDevice?.saved?.id) {
        androidx.compose.runtime.mutableStateOf<BookUploadReview?>(null)
    }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedDirectory = result.data?.getStringExtra(UploadDirectoryActivity.EXTRA_DIRECTORY)
        }
    }
    val canChooseDirectory = activeDevice?.profile?.let { profile ->
        profile.capabilities.contains("upload.target-directory") &&
            profile.constraints.canChooseUploadDirectory && profile.constraints.canListDirectories &&
            profile.capabilities.contains("files.list")
    } == true
    val fontExtensions = activeDevice?.profile?.fileFormats?.fontUploadExtensions
        ?.map { it.trim().removePrefix(".").lowercase(Locale.ROOT) }
        ?.filter(String::isNotBlank)
        .orEmpty()
    val canUploadFonts = activeDevice?.profile?.let { "fonts.upload" in it.capabilities } == true && fontExtensions.isNotEmpty()

    androidx.compose.runtime.LaunchedEffect(incomingFiles, activeDevice?.saved?.id) {
        if (incomingFiles.isNotEmpty()) {
            selectedFiles = incomingFiles
            val extension = incomingFiles.singleOrNull()?.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            category = when {
                incomingFiles.size == 1 && incomingFiles.single().isBinFirmwareCandidate() -> SendFileCategory.FIRMWARE
                incomingFiles.size == 1 && canUploadFonts && extension in fontExtensions -> SendFileCategory.FONT
                else -> SendFileCategory.BOOK
            }
            PendingIncomingFiles.clear()
        }
    }

    fun startUpload(review: BookUploadReview) {
        if (review.accepted.isEmpty()) return
        pendingReview = null
        val location = if (canChooseDirectory) {
            selectedDirectory?.let(uniffi.picobook_sdk.SdkFileLocation::Directory)
                ?: uniffi.picobook_sdk.SdkFileLocation.Root
        } else {
            uniffi.picobook_sdk.SdkFileLocation.Root
        }
        BookUploadQueue.start(context, review.accepted, location, review.unsupportedNames)
    }

    val chooseBooks = rememberBookFilePicker { files ->
        if (files.isNotEmpty()) {
            selectedFiles = files
            val extension = files.singleOrNull()?.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            category = when {
                files.size == 1 && files.single().isBinFirmwareCandidate() -> SendFileCategory.FIRMWARE
                files.size == 1 && canUploadFonts && extension in fontExtensions -> SendFileCategory.FONT
                else -> SendFileCategory.BOOK
            }
        }
    }
    val firmwareCandidate = selectedFiles.size == 1 && selectedFiles.single().isBinFirmwareCandidate()
    val fontCandidate = selectedFiles.size == 1 && canUploadFonts &&
        selectedFiles.single().name.substringAfterLast('.', "").lowercase(Locale.ROOT) in fontExtensions
    val unreadableCount = selectedFiles.count { file ->
        val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        activeDevice?.profile?.fileFormats?.readableExtensions?.none {
            it.trim().removePrefix(".").lowercase(Locale.ROOT) == extension
        } == true
    }
    val bookReview = activeDevice?.profile?.let { reviewBookFiles(selectedFiles, it) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (category != SendFileCategory.FIRMWARE) {
                SendSelectionContent(
                    icon = Icons.Default.Smartphone,
                    title = "设备",
                    value = activeDevice?.saved?.address ?: "连接设备",
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDevicesClick).padding(16.dp),
                )
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
            }
                SendSelectionContent(
                    icon = Icons.Default.Description,
                    title = "选择发送的文件",
                    value = when (selectedFiles.size) {
                        0 -> null
                        1 -> selectedFiles.single().name
                        else -> "${selectedFiles.size} 个文件"
                    },
                    modifier = Modifier.fillMaxWidth().clickable(
                        enabled = !uploadProgress.running,
                        onClick = chooseBooks,
                    ).padding(16.dp),
                )
                HorizontalDivider()
                if (selectedFiles.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    val categories = buildList {
                        add(SendFileCategory.BOOK)
                        if (canUploadFonts) add(SendFileCategory.FONT)
                        add(SendFileCategory.FIRMWARE)
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        categories.forEachIndexed { index, choice ->
                            SegmentedButton(
                                selected = category == choice,
                                onClick = { category = choice },
                                enabled = when (choice) {
                                    SendFileCategory.BOOK -> true
                                    SendFileCategory.FONT -> true
                                    SendFileCategory.FIRMWARE -> true
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, categories.size),
                                label = { Text(choice.label) },
                            )
                        }
                    }
                    if (category == SendFileCategory.BOOK) {
                        val warning = when {
                            bookReview?.unsupportedNames?.isNotEmpty() == true -> "设备不支持上传所选文件格式"
                            unreadableCount == 1 -> "此文件不在设备可阅读格式中"
                            unreadableCount > 1 -> "$unreadableCount 个文件不在设备可阅读格式中"
                            else -> null
                        }
                        if (warning != null) Text(
                            warning,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (category == SendFileCategory.FONT && !fontCandidate) {
                        Text(
                            "请选择设备支持的字体文件",
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (category == SendFileCategory.BOOK && canChooseDirectory) {
                    Spacer(Modifier.height(20.dp))
                    SendSelectionContent(
                        icon = Icons.Default.Folder,
                        title = "接收目录",
                        value = selectedDirectory?.substringAfterLast('/')?.ifEmpty { "根目录" } ?: "默认目录",
                        modifier = Modifier.fillMaxWidth().clickable(
                            enabled = !uploadProgress.running,
                            onClick = { directoryLauncher.launch(Intent(context, UploadDirectoryActivity::class.java)) },
                        ).padding(16.dp),
                    )
                    HorizontalDivider()
                }
                Button(
                    onClick = {
                        when (category) {
                            SendFileCategory.BOOK -> {
                                val review = bookReview ?: return@Button
                                if (review.unsupportedNames.isNotEmpty() || review.mayNotBeReadableNames.isNotEmpty()) {
                                    pendingReview = review
                                } else {
                                    startUpload(review)
                                }
                            }
                            SendFileCategory.FONT -> {
                                fontFamilyName = ""
                                showFontUploadDialog = true
                            }
                            SendFileCategory.FIRMWARE -> selectedFiles.singleOrNull()?.let { onFirmwareClick(it) }
                        }
                    },
                    enabled = selectedFiles.isNotEmpty() && !uploadProgress.running && when (category) {
                        SendFileCategory.BOOK -> activeDevice?.profile?.capabilities?.contains("files.upload") == true
                        SendFileCategory.FONT -> fontCandidate
                        SendFileCategory.FIRMWARE -> selectedFiles.size == 1
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                ) {
                    Icon(
                        if (category == SendFileCategory.FIRMWARE) Icons.Default.SystemUpdateAlt
                        else Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(when (category) {
                        SendFileCategory.FIRMWARE -> "前往刷机"
                        SendFileCategory.FONT -> "上传字体"
                        SendFileCategory.BOOK -> "发送"
                    })
                }
        }
        if (category != SendFileCategory.FIRMWARE || uploadProgress.running) {
            BookUploadStatus(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }

    pendingReview?.let { review ->
        BookUploadReviewDialog(
            review = review,
            onCancel = { pendingReview = null },
            onContinue = { startUpload(review) },
        )
    }
    if (showFontUploadDialog) {
        AlertDialog(
            onDismissRequest = { if (!uploadProgress.running) showFontUploadDialog = false },
            title = { Text("上传字体") },
            text = {
                OutlinedTextField(
                    value = fontFamilyName,
                    onValueChange = { fontFamilyName = it },
                    label = { Text("字体族") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = fontCandidate && fontFamilyName.isNotBlank() && !uploadProgress.running,
                    onClick = {
                        val selected = selectedFiles.singleOrNull() ?: return@TextButton
                        showFontUploadDialog = false
                        scope.launch {
                            try {
                                DeviceSessions.uploadFont(context, fontFamilyName.trim(), selected.uri, selected.name)
                                android.widget.Toast.makeText(context, "字体上传完成", android.widget.Toast.LENGTH_LONG).show()
                            } catch (cause: Exception) {
                                operationError = managerError(cause)
                                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                            }
                        }
                    },
                ) { Text("上传") }
            },
            dismissButton = { TextButton(onClick = { showFontUploadDialog = false }) { Text("取消") } },
        )
    }
    OperationErrorDialog(
        operationError,
        onDismiss = { operationError = null },
        title = "字体上传失败",
        reconnectToDevice = reconnectRequired,
    )
}

@Composable
private fun SendSelectionContent(
    icon: ImageVector,
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Text(
                    value,
                    modifier = Modifier.widthIn(max = 280.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class FeatureEntry(
    val title: String,
    val description: String,
    val requiredCapabilities: Set<String> = emptySet(),
    val availableWithoutDevice: Boolean = false,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
)

@Composable
private fun FeatureListPage(
    activeDevice: ActiveDevice?,
    onWifiClick: () -> Unit,
    onFontsClick: () -> Unit,
    onOpdsClick: () -> Unit,
    onFirmwareClick: () -> Unit,
) {
    val entries = listOf(
        FeatureEntry(
            title = "Wi-Fi 管理",
            description = "管理设备已保存的 Wi-Fi 网络",
            requiredCapabilities = setOf("wifi.list"),
            icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
            onClick = onWifiClick,
        ),
        FeatureEntry(
            title = "字体管理",
            description = "管理设备字体族",
            requiredCapabilities = setOf("fonts.list"),
            icon = { Icon(Icons.Default.FontDownload, contentDescription = null) },
            onClick = onFontsClick,
        ),
        FeatureEntry(
            title = "OPDS 管理",
            description = "管理 OPDS 书库",
            requiredCapabilities = setOf("opds.list"),
            icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
            onClick = onOpdsClick,
        ),
        FeatureEntry(
            title = "设备刷机",
            description = "刷写设备固件",
            availableWithoutDevice = true,
            icon = { Icon(Icons.Default.SystemUpdateAlt, contentDescription = null) },
            onClick = onFirmwareClick,
        ),
    )
    val declaredCapabilities = activeDevice?.profile?.capabilities?.toSet().orEmpty()
    val visibleEntries = entries.filter { entry ->
        entry.availableWithoutDevice ||
            (activeDevice != null && declaredCapabilities.containsAll(entry.requiredCapabilities))
    }

    LazyColumn {
        items(visibleEntries) { entry ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text(entry.title) },
                supportingContent = { Text(entry.description) },
                leadingContent = entry.icon,
                modifier = Modifier.fillMaxWidth().clickable(onClick = entry.onClick),
            )
        }
    }
}

class WifiManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeviceSessions.initialize(applicationContext)
        setContent { PicoManageTheme { WifiManagementPage(onBack = ::finish) } }
    }
}

class FirmwareUpdateActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FILE_NAME = "firmware_file_name"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PicoManageTheme {
                androidx.compose.material3.Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("设备刷机") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                        )
                    },
                ) { innerPadding ->
                    Box(
                        Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("暂不支持刷机", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PicoManagerPreview() {
    PicoManageTheme {
        MainShell(
            activeDevice = null,
            onDevicesClick = {},
            onWifiClick = {},
            onFontsClick = {},
            onOpdsClick = {},
            onFirmwareClick = { _ -> },
        )
    }
}
