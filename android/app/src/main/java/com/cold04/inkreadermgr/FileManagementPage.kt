package com.cold04.inkreadermgr

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import uniffi.inkreaderlink_uniffi.SdkFileEntry
import uniffi.inkreaderlink_uniffi.SdkFileKind
import uniffi.inkreaderlink_uniffi.SdkFileLocation
import uniffi.inkreaderlink_uniffi.SdkFileDownload

enum class FileBrowserMode { Browse, SelectDirectory }

private data class DirectoryListing(val directory: String?, val entries: List<SdkFileEntry>)

/** Shared directory/file presentation for management and future upload destination selection. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DeviceFileBrowser(
    entries: List<SdkFileEntry>,
    currentDirectory: String?,
    loading: Boolean,
    error: String?,
    mode: FileBrowserMode = FileBrowserMode.Browse,
    capabilities: Set<String> = emptySet(),
    actionsEnabled: Boolean = true,
    showCurrentDirectoryHeader: Boolean = true,
    selectedEntryPaths: Set<String> = emptySet(),
    highlightedEntryPaths: Set<String> = emptySet(),
    onEntryLongPress: ((SdkFileEntry) -> Unit)? = null,
    onToggleEntrySelection: ((SdkFileEntry) -> Unit)? = null,
    onOpenDirectory: (SdkFileEntry) -> Unit,
    onNavigateUp: () -> Unit,
    onRename: ((SdkFileEntry) -> Unit)? = null,
    onDelete: ((SdkFileEntry) -> Unit)? = null,
    onMove: ((SdkFileEntry) -> Unit)? = null,
    onDownload: ((SdkFileEntry) -> Unit)? = null,
    onSelectDirectory: ((String?) -> Unit)? = null,
    canSelectDirectory: (String?) -> Boolean = { true },
    showSelectDirectoryAction: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showLoadingSkeleton by remember { mutableStateOf(false) }
    LaunchedEffect(loading, entries.isEmpty()) {
        if (loading && entries.isEmpty()) {
            showLoadingSkeleton = false
            delay(300)
            showLoadingSkeleton = true
        } else {
            showLoadingSkeleton = false
        }
    }
    Column(modifier.fillMaxSize()) {
        if (currentDirectory != null && showCurrentDirectoryHeader) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级目录")
                }
                Text(currentDirectory.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
            }
        }
        when {
            loading && entries.isEmpty() -> if (showLoadingSkeleton) FileListSkeleton() else Spacer(Modifier.fillMaxSize())
            entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("此目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.path }) { entry ->
                    val directory = entry.kind == SdkFileKind.DIRECTORY
                    val canSelectEntry = onEntryLongPress != null && when {
                        capabilities.contains("files.move") || capabilities.contains("files.rename") ||
                            capabilities.contains("files.delete") -> true
                        !directory && capabilities.contains("files.download") -> true
                        else -> false
                    }
                    ListItem(
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            enabled = actionsEnabled,
                            onClick = {
                                when {
                                    selectedEntryPaths.isNotEmpty() && canSelectEntry -> onToggleEntrySelection?.invoke(entry)
                                    selectedEntryPaths.isNotEmpty() -> Unit
                                    directory -> onOpenDirectory(entry)
                                    else -> Unit
                                }
                            },
                            onLongClick = { if (canSelectEntry) onEntryLongPress?.invoke(entry) },
                        ),
                        colors = ListItemDefaults.colors(
                            containerColor = if (entry.path in selectedEntryPaths || entry.path in highlightedEntryPaths) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else MaterialTheme.colorScheme.surface,
                        ),
                        leadingContent = {
                            Icon(
                                if (directory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (mode == FileBrowserMode.SelectDirectory && !directory) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        headlineContent = {
                            Text(
                                entry.name,
                                color = if (mode == FileBrowserMode.SelectDirectory && !directory) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                } else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = {
                            if (!directory) Text(
                                formatFileSize(entry.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (mode == FileBrowserMode.SelectDirectory) 0.45f else 1f,
                                ),
                            )
                        },
                        trailingContent = if (directory) {
                            {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else null,
                    )
                }
            }
        }
        if (mode == FileBrowserMode.SelectDirectory && showSelectDirectoryAction) {
            TextButton(
                onClick = { onSelectDirectory?.invoke(currentDirectory) },
                enabled = !loading && canSelectDirectory(currentDirectory),
                modifier = Modifier.align(Alignment.End).padding(12.dp),
            ) { Text(if (canSelectDirectory(currentDirectory)) "选择此目录" else "不能选择此目录") }
        }
        if (loading && entries.isNotEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp).size(22.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun FileListSkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        repeat(6) {
            Row(
                Modifier.fillMaxWidth().height(68.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.fillMaxWidth(0.62f).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
                    Spacer(Modifier.fillMaxWidth(0.36f).height(11.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FileManagementPage(
    connectedDeviceCount: Int,
    onDevicesClick: () -> Unit,
    isVisible: Boolean,
    onBackFromRoot: () -> Unit,
) {
    val deviceState by DeviceSessions.state.collectAsState()
    val active = deviceState.active
    val capabilities = active?.profile?.capabilities?.toSet().orEmpty()
    val scope = rememberCoroutineScope()
    var directoryStack by remember(active?.saved?.id) { mutableStateOf(emptyList<String>()) }
    val directory = directoryStack.lastOrNull()
    var navigatingForward by remember(active?.saved?.id) { mutableStateOf(true) }
    fun navigateUp() {
        if (directoryStack.isNotEmpty()) {
            navigatingForward = false
            directoryStack = directoryStack.dropLast(1)
        }
    }
    var listing by remember(active?.saved?.id) { mutableStateOf(DirectoryListing(null, emptyList())) }
    var loading by remember { mutableStateOf(false) }
    var operationBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectToDevice by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingFileAction?>(null) }
    var dialogValue by remember { mutableStateOf("") }
    var selectedEntries by remember(active?.saved?.id) { mutableStateOf(emptyList<SdkFileEntry>()) }
    var selectionMenuExpanded by remember { mutableStateOf(false) }
    var fileActionsExpanded by remember(active?.saved?.id) { mutableStateOf(false) }
    var movingEntries by remember(active?.saved?.id) { mutableStateOf(emptyList<SdkFileEntry>()) }
    val movingEntry = movingEntries.singleOrNull()
    var downloadEntry by remember { mutableStateOf<SdkFileEntry?>(null) }
    var downloadEntries by remember { mutableStateOf(emptyList<SdkFileEntry>()) }
    var pendingUploadReview by remember { mutableStateOf<BookUploadReview?>(null) }
    val context = LocalContext.current
    val uploadProgress by BookUploadQueue.state.collectAsState()
    val selectedEntry = selectedEntries.singleOrNull()

    fun clearSelection() {
        selectedEntries = emptyList()
        selectionMenuExpanded = false
    }

    fun toggleEntrySelection(entry: SdkFileEntry) {
        selectionMenuExpanded = false
        selectedEntries = if (selectedEntries.any { it.path == entry.path }) {
            selectedEntries.filterNot { it.path == entry.path }
        } else {
            selectedEntries + entry
        }
    }

    fun startUpload(review: BookUploadReview) {
        val profile = active?.profile ?: return
        val canChooseDirectory = profile.capabilities.contains("upload.target-directory") &&
            profile.constraints.canChooseUploadDirectory
        val location = if (canChooseDirectory) {
            directory?.let(SdkFileLocation::Directory) ?: SdkFileLocation.Root
        } else SdkFileLocation.Root
        pendingUploadReview = null
        if (review.accepted.isNotEmpty()) {
            BookUploadQueue.start(context, review.accepted, location, review.unsupportedNames)
        }
    }

    val pickBooks = rememberBookFilePicker { files ->
        val profile = active?.profile ?: return@rememberBookFilePicker
        if (files.isEmpty()) return@rememberBookFilePicker
        val review = reviewBookFiles(files, profile)
        if (review.unsupportedNames.isNotEmpty() || review.mayNotBeReadableNames.isNotEmpty()) {
            pendingUploadReview = review
        } else startUpload(review)
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val selected = downloadEntry
        downloadEntry = null
        if (uri != null && selected != null) {
            scope.launch {
                loading = true
                error = null
                val temporaryFile = File(context.cacheDir, "device-download-${System.nanoTime()}")
                try {
                    DeviceSessions.downloadFile(selected.path, temporaryFile.absolutePath)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        temporaryFile.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
                    } ?: throw IllegalStateException("无法写入所选位置")
                } catch (cause: Exception) {
                    error = DeviceSessions.describeOperationFailure(cause, "下载")
                    reconnectToDevice = DeviceSessions.isConnectionFailure(cause)
                } finally {
                    temporaryFile.delete()
                    loading = false
                }
            }
        }
    }
    val downloadDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        val files = downloadEntries
        downloadEntries = emptyList()
        if (treeUri != null && files.isNotEmpty()) {
            scope.launch {
                operationBusy = true
                loading = true
                error = null
                val stagedFiles = mutableListOf<File>()
                try {
                    files.forEach { stagedFiles += File.createTempFile("device-download-", ".tmp", context.cacheDir) }
                    DeviceSessions.downloadFiles(files.zip(stagedFiles).map { (entry, staged) ->
                        SdkFileDownload(entry.path, staged.absolutePath)
                    })
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri),
                    )
                    files.zip(stagedFiles).forEach { (entry, staged) ->
                        val documentUri = DocumentsContract.createDocument(
                            context.contentResolver,
                            parentUri,
                            "application/octet-stream",
                            entry.name,
                        ) ?: throw IllegalStateException("无法在所选目录创建文件：${entry.name}")
                        context.contentResolver.openOutputStream(documentUri)?.use { output ->
                            staged.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
                        } ?: throw IllegalStateException("无法写入文件：${entry.name}")
                    }
                    clearSelection()
                } catch (cause: Exception) {
                    error = DeviceSessions.describeOperationFailure(cause, "批量下载")
                    reconnectToDevice = DeviceSessions.isConnectionFailure(cause)
                } finally {
                    stagedFiles.forEach(File::delete)
                    loading = false
                    operationBusy = false
                }
            }
        }
    }

    fun refresh() {
        if (!isVisible || active == null || !active.profile.capabilities.contains("files.list")) return
        val requestedDirectory = directory
        scope.launch {
            loading = true
            error = null
            reconnectToDevice = false
            try {
                val updatedEntries = DeviceSessions.listFiles(
                    requestedDirectory?.let(SdkFileLocation::Directory) ?: SdkFileLocation.Root,
                )
                listing = DirectoryListing(requestedDirectory, updatedEntries)
            } catch (cause: Exception) {
                if (listing.directory != requestedDirectory) {
                    listing = DirectoryListing(requestedDirectory, emptyList())
                }
                error = DeviceSessions.describeOperationFailure(cause, "读取文件列表")
                reconnectToDevice = DeviceSessions.isConnectionFailure(cause)
            } finally {
                loading = false
            }
        }
    }
    fun pasteMove() {
        val sources = movingEntries.filter { candidate ->
            movingEntries.none { ancestor ->
                ancestor.path != candidate.path && ancestor.kind == SdkFileKind.DIRECTORY &&
                    isStrictDescendant(ancestor.path, candidate.path)
            }
        }
        if (sources.isEmpty()) return
        val destination = directory
        if (sources.any { isSameOrDescendant(it.path, destination) }) {
            error = "不能移动到自身或其子目录"
            return
        }
        scope.launch {
            operationBusy = true
            loading = true
            error = null
            try {
                DeviceSessions.moveFiles(sources.map { it.path }, destination ?: "/")
                movingEntries = emptyList()
                refresh()
            } catch (cause: Exception) {
                error = DeviceSessions.describeOperationFailure(cause, "移动")
                reconnectToDevice = DeviceSessions.isConnectionFailure(cause)
            } finally {
                loading = false
                operationBusy = false
            }
        }
    }
    LaunchedEffect(active?.saved?.id, directory, isVisible) {
        if (isVisible) refresh()
    }
    LaunchedEffect(uploadProgress.running, uploadProgress.totalCount, uploadProgress.outcomes.size) {
        if (!uploadProgress.running && uploadProgress.totalCount > 0 && uploadProgress.outcomes.isNotEmpty() &&
            uploadProgress.outcomes.all { it.success }
        ) refresh()
    }

    BackHandler(enabled = isVisible && fileActionsExpanded) {
        fileActionsExpanded = false
    }
    BackHandler(enabled = isVisible) {
        when {
            selectedEntries.isNotEmpty() -> clearSelection()
            movingEntries.isNotEmpty() -> movingEntries = emptyList()
            directoryStack.isNotEmpty() -> navigateUp()
            else -> onBackFromRoot()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedEntries.isNotEmpty()) "${selectedEntries.size}个项目"
                        else directory?.substringAfterLast('/')?.ifEmpty { "文件管理" } ?: "文件管理",
                    )
                },
                navigationIcon = {
                    if (selectedEntries.isNotEmpty() || directory != null) {
                        IconButton(onClick = {
                            if (selectedEntries.isNotEmpty()) clearSelection() else navigateUp()
                        }) {
                            Icon(
                                if (selectedEntries.isNotEmpty()) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (selectedEntries.isNotEmpty()) "取消选择" else "返回上级目录",
                            )
                        }
                    }
                },
                actions = {
                    if (selectedEntries.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { selectionMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多文件操作")
                            }
                            DropdownMenu(
                                expanded = selectionMenuExpanded,
                                onDismissRequest = { selectionMenuExpanded = false },
                            ) {
                                if (selectedEntries.all { it.kind != SdkFileKind.DIRECTORY } && capabilities.contains("files.download")) {
                                    DropdownMenuItem(text = { Text("下载") }, onClick = {
                                        selectionMenuExpanded = false
                                        if (selectedEntries.size == 1) {
                                            val entry = selectedEntries.single()
                                            downloadEntry = entry
                                            downloadPicker.launch(entry.name)
                                            clearSelection()
                                        } else {
                                            downloadEntries = selectedEntries
                                            downloadDirectoryPicker.launch(null)
                                            clearSelection()
                                        }
                                    })
                                }
                                if (capabilities.contains("files.move")) {
                                    DropdownMenuItem(text = { Text("移动") }, onClick = {
                                        selectionMenuExpanded = false
                                        movingEntries = selectedEntries
                                        clearSelection()
                                    })
                                }
                                if (selectedEntry != null && capabilities.contains("files.rename")) {
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                        selectionMenuExpanded = false
                                        dialogValue = selectedEntry.name
                                        pendingAction = PendingFileAction.Rename(selectedEntry)
                                        clearSelection()
                                    })
                                }
                                if (capabilities.contains("files.delete")) {
                                    DropdownMenuItem(text = { Text("删除") }, onClick = {
                                        selectionMenuExpanded = false
                                        pendingAction = PendingFileAction.Delete(selectedEntries)
                                    })
                                }
                            }
                        }
                    } else if (movingEntries.isNotEmpty()) {
                        IconButton(onClick = { movingEntries = emptyList() }) {
                            Icon(Icons.Default.Close, contentDescription = "取消移动")
                        }
                        val canPaste = movingEntries.isNotEmpty() && movingEntries.none { isSameOrDescendant(it.path, directory) }
                        IconButton(onClick = ::pasteMove, enabled = canPaste && !operationBusy) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "粘贴到此目录")
                        }
                    } else {
                        if (directory == null) DeviceConnectionControl(connectedDeviceCount, onDevicesClick)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                when {
                    active == null -> Text(
                        "设备未连接",
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !capabilities.contains("files.list") -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.fillMaxWidth().height(200.dp)) { FileListSkeleton() }
                        Text("设备不支持文件管理", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        AnimatedContent(
                            targetState = listing,
                            transitionSpec = {
                                val direction = if (navigatingForward) 1 else -1
                                (slideInHorizontally { direction * it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -direction * it } + fadeOut())
                            },
                            contentKey = { it.directory },
                            label = "file-directory-transition",
                        ) { page ->
                            DeviceFileBrowser(
                                entries = page.entries.filterNot { candidate ->
                                    candidate.kind == SdkFileKind.DIRECTORY && movingEntries.any { source ->
                                        source.kind == SdkFileKind.DIRECTORY && isStrictDescendant(source.path, candidate.path)
                                    }
                                },
                                currentDirectory = page.directory,
                                loading = loading,
                                error = error,
                                capabilities = capabilities,
                                actionsEnabled = !operationBusy && !uploadProgress.running,
                                showCurrentDirectoryHeader = false,
                                selectedEntryPaths = selectedEntries.mapTo(linkedSetOf()) { it.path },
                                highlightedEntryPaths = movingEntries.mapTo(linkedSetOf()) { it.path },
                                onEntryLongPress = if (movingEntries.isEmpty()) {
                                    { entry ->
                                        fileActionsExpanded = false
                                        selectionMenuExpanded = false
                                        toggleEntrySelection(entry)
                                    }
                                } else null,
                                onToggleEntrySelection = if (movingEntries.isEmpty()) {
                                    ::toggleEntrySelection
                                } else null,
                                onOpenDirectory = {
                                    if (movingEntries.isEmpty() || movingEntries.none { source -> isSameOrDescendant(source.path, it.path) }) {
                                        fileActionsExpanded = false
                                        navigatingForward = true
                                        clearSelection()
                                        directoryStack = directoryStack + it.path
                                    }
                                },
                                onNavigateUp = ::navigateUp,
                                onRename = { entry ->
                                    dialogValue = entry.name
                                    pendingAction = PendingFileAction.Rename(entry)
                                },
                                onDelete = { entry -> pendingAction = PendingFileAction.Delete(listOf(entry)) },
                                onMove = { movingEntries = listOf(it); clearSelection() },
                                onDownload = { entry ->
                                    downloadEntry = entry
                                    downloadPicker.launch(entry.name)
                                },
                            )
                        }
                    }
                }
                if (active != null && selectedEntries.isEmpty() && movingEntries.isEmpty() &&
                    !operationBusy && !uploadProgress.running &&
                    (capabilities.contains("files.upload") || capabilities.contains("directories.create"))
                ) {
                    SpeedDialMenu(
                        expanded = fileActionsExpanded,
                        onExpandedChange = { fileActionsExpanded = it },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                        actions = buildList {
                            if (capabilities.contains("directories.create")) {
                                add(
                                    SpeedDialAction(
                                        label = "新建文件夹",
                                        icon = Icons.Default.CreateNewFolder,
                                        onClick = {
                                            fileActionsExpanded = false
                                            dialogValue = ""
                                            pendingAction = PendingFileAction.CreateDirectory
                                        },
                                    ),
                                )
                            }
                            if (capabilities.contains("files.upload")) {
                                add(
                                    SpeedDialAction(
                                        label = "上传文件",
                                        icon = Icons.Default.UploadFile,
                                        onClick = {
                                            fileActionsExpanded = false
                                            pickBooks()
                                        },
                                    ),
                                )
                            }
                        },
                        buttonContentDescription = "打开文件操作",
                    )
                }
                if (uploadProgress.running) {
                    BookUploadStatus(
                        Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }

    pendingUploadReview?.let { review ->
        BookUploadReviewDialog(
            review = review,
            onCancel = { pendingUploadReview = null },
            onContinue = { startUpload(review) },
        )
    }

    pendingAction?.let { action ->
        val delete = action is PendingFileAction.Delete
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(when (action) {
                PendingFileAction.CreateDirectory -> "新建目录"
                is PendingFileAction.Rename -> "重命名"
                is PendingFileAction.Delete -> if (action.entries.size == 1) "删除项目？" else "删除 ${action.entries.size} 个项目？"
            }) },
            text = {
                if (delete) Text((action as PendingFileAction.Delete).entries.singleOrNull()?.name ?: "删除后无法恢复")
                else OutlinedTextField(value = dialogValue, onValueChange = { dialogValue = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    scope.launch {
                        operationBusy = true
                        loading = true
                        error = null
                        try {
                            when (action) {
                                PendingFileAction.CreateDirectory -> DeviceSessions.createDirectory(directory ?: "/", dialogValue.trim())
                                is PendingFileAction.Rename -> DeviceSessions.renameFile(action.entry.path, dialogValue.trim())
                                is PendingFileAction.Delete -> DeviceSessions.deleteFiles(action.entries.map { it.path })
                            }
                            if (action is PendingFileAction.Delete) clearSelection()
                            refresh()
                        } catch (cause: Exception) {
                            error = DeviceSessions.describeOperationFailure(cause, "文件操作")
                            reconnectToDevice = DeviceSessions.isConnectionFailure(cause)
                        } finally { loading = false; operationBusy = false }
                    }
                }, enabled = (delete || dialogValue.isNotBlank()) && !uploadProgress.running) { Text(if (delete) "删除" else "确定") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("取消") } },
        )
    }

    OperationErrorDialog(
        message = error,
        onDismiss = { error = null },
        title = "文件管理失败",
        reconnectToDevice = reconnectToDevice,
    )
}

private sealed interface PendingFileAction {
    data object CreateDirectory : PendingFileAction
    data class Rename(val entry: SdkFileEntry) : PendingFileAction
    data class Delete(val entries: List<SdkFileEntry>) : PendingFileAction
}

private fun isSameOrDescendant(sourcePath: String, destinationPath: String?): Boolean {
    if (destinationPath == null) return false
    val source = sourcePath.trimEnd('/').ifEmpty { "/" }
    val destination = destinationPath.trimEnd('/').ifEmpty { "/" }
    return destination == source || (source == "/" && destination.startsWith('/')) || destination.startsWith("$source/")
}

private fun isStrictDescendant(sourcePath: String, destinationPath: String): Boolean {
    val source = sourcePath.trimEnd('/').ifEmpty { "/" }
    val destination = destinationPath.trimEnd('/').ifEmpty { "/" }
    return destination != source && isSameOrDescendant(source, destination)
}

private fun formatFileSize(size: ULong): String = when {
    size < 1024uL -> "$size B"
    size < 1024uL * 1024uL -> "${(size.toDouble() / 1024.0).toInt()} KB"
    else -> "${(size.toDouble() / (1024.0 * 1024.0)).toInt()} MB"
}
