package com.cold04.picomanage

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import uniffi.picobook_sdk.SdkDeviceClient
import uniffi.picobook_sdk.SdkDeviceProfile
import uniffi.picobook_sdk.SdkFileLocation
import uniffi.picobook_sdk.SdkFileEntry
import uniffi.picobook_sdk.SdkConflictPolicy
import uniffi.picobook_sdk.SdkUploadOptions
import uniffi.picobook_sdk.SdkUploadProgressObserver
import uniffi.picobook_sdk.SdkOperationException
import uniffi.picobook_sdk.SdkWifiCredential
import uniffi.picobook_sdk.SdkWifiNetwork
import uniffi.picobook_sdk.SdkFontCatalog
import uniffi.picobook_sdk.SdkOpdsCredential
import uniffi.picobook_sdk.SdkOpdsServer
import java.util.UUID

data class SavedDevice(val id: String, val deviceType: String, val address: String)
data class ActiveDevice(val saved: SavedDevice, val profile: SdkDeviceProfile)
data class DeviceState(val saved: List<SavedDevice> = emptyList(), val active: ActiveDevice? = null)

/** Saved addresses are persistent; the one live SDK client exists only in this process. */
object DeviceSessions {
    private const val PREFS_NAME = "devices"
    private const val SAVED_KEY = "saved"
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DeviceState())
    val state = mutableState.asStateFlow()
    private var appContext: Context? = null
    private var client: SdkDeviceClient? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val data = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(SAVED_KEY, "[]")
        val saved = try {
            val array = JSONArray(data)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                SavedDevice(item.getString("id"), item.getString("deviceType"), item.getString("address"))
            }
        } catch (_: Exception) {
            emptyList()
        }
        mutableState.value = DeviceState(saved = saved)
    }

    suspend fun save(deviceType: String, address: String): SavedDevice = withContext(Dispatchers.IO) {
        val target = address.trim()
        require(target.isNotEmpty()) { "请输入设备地址" }
        mutex.withLock {
            mutableState.value.saved.firstOrNull { it.deviceType == deviceType && it.address == target }?.let {
                return@withLock it
            }
            val saved = SavedDevice(UUID.randomUUID().toString(), deviceType, target)
            val updated = mutableState.value.saved + saved
            persist(updated)
            mutableState.value = mutableState.value.copy(saved = updated)
            saved
        }
    }

    suspend fun update(id: String, deviceType: String, address: String): SavedDevice = withContext(Dispatchers.IO) {
        val target = address.trim()
        require(target.isNotEmpty()) { "请输入设备地址" }
        mutex.withLock {
            val previous = mutableState.value.saved.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("设备不存在")
            if (previous.deviceType == deviceType && previous.address == target) return@withLock previous
            require(mutableState.value.saved.none {
                it.id != id && it.deviceType == deviceType && it.address == target
            }) { "该设备已保存" }

            val updatedDevice = previous.copy(deviceType = deviceType, address = target)
            val updated = mutableState.value.saved.map { if (it.id == id) updatedDevice else it }
            persist(updated)
            val wasActive = mutableState.value.active?.saved?.id == id
            if (wasActive) {
                client?.close()
                client = null
            }
            mutableState.value = mutableState.value.copy(
                saved = updated,
                active = mutableState.value.active?.takeUnless { wasActive },
            )
            updatedDevice
        }
    }

    suspend fun connect(id: String): ActiveDevice = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = mutableState.value.saved.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("设备不存在")
            mutableState.value.active?.takeIf { it.saved.id == id }?.let { return@withLock it }

            // One live client at a time, including while switching devices.
            client?.close()
            client = null
            mutableState.value = mutableState.value.copy(active = null)

            val next = SdkDeviceClient.connectAndVerify(saved.deviceType, saved.address, 5_000uL)
            try {
                val profile = next.profile()
                val active = ActiveDevice(saved, profile)
                client = next
                mutableState.value = mutableState.value.copy(active = active)
                active
            } catch (error: Exception) {
                next.close()
                throw error
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            client?.close()
            client = null
            mutableState.value = mutableState.value.copy(active = null)
        }
    }

    suspend fun listFiles(location: SdkFileLocation): List<SdkFileEntry> = withContext(Dispatchers.IO) {
        withDeviceClient("files.list") { it.listFiles(location) }
    }

    suspend fun deleteFile(path: String) = modify("files.delete") { it.delete(path) }

    suspend fun renameFile(path: String, newName: String) = modify("files.rename") { it.rename(path, newName) }

    suspend fun moveFile(path: String, destination: String) = modify("files.move") { it.moveFile(path, destination) }

    suspend fun createDirectory(parent: String, name: String) = modify("directories.create") {
        it.createDirectory(parent, name)
    }

    suspend fun downloadFile(path: String, destination: String) = modify("files.download") {
        it.download(path, destination)
    }

    suspend fun listWifiNetworks(): List<SdkWifiNetwork> = withContext(Dispatchers.IO) {
        withDeviceClient("wifi.list") { it.listWifiNetworks() }
    }

    suspend fun saveWifiNetwork(credential: SdkWifiCredential) = modify("wifi.save") {
        it.saveWifiNetwork(credential)
    }

    suspend fun deleteWifiNetwork(index: UInt?) = modify("wifi.delete") {
        it.deleteWifiNetwork(index)
    }

    suspend fun listFonts(): SdkFontCatalog = withContext(Dispatchers.IO) {
        withDeviceClient("fonts.list") { it.listFonts() }
    }

    suspend fun uploadFont(context: Context, family: String, uri: Uri, fileName: String) =
        withDeviceClient("fonts.upload") { sdkClient ->
                val profile = mutableState.value.active?.profile ?: throw IOException("设备未连接")
                val supportedExtensions = profile.fileFormats.fontUploadExtensions
                    .map { it.trim().removePrefix(".").lowercase(java.util.Locale.ROOT) }
                    .filter(String::isNotBlank)
                    .toSet()
                val extension = fileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (extension !in supportedExtensions) {
                    val accepted = supportedExtensions.joinToString { ".$it" }
                    throw IOException(
                        if (accepted.isEmpty()) "设备未声明支持的字体文件类型"
                        else "设备支持的字体文件类型：$accepted",
                    )
                }
                val temporary = File.createTempFile("pico-font-", ".$extension", context.cacheDir)
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("无法读取字体文件")
                    input.buffered().use { source ->
                        temporary.outputStream().buffered().use { destination ->
                            source.copyTo(destination, 64 * 1024)
                        }
                    }
                    sdkClient.uploadFont(
                        family,
                        temporary.absolutePath,
                        fileName,
                    )
                } finally {
                    temporary.delete()
                }
        }

    suspend fun deleteFontFamily(family: String) = modify("fonts.delete") {
        it.deleteFontFamily(family)
    }

    suspend fun listOpdsServers(): List<SdkOpdsServer> = withContext(Dispatchers.IO) {
        withDeviceClient("opds.list") { it.listOpdsServers() }
    }

    suspend fun saveOpdsServer(credential: SdkOpdsCredential) = modify("opds.save") {
        it.saveOpdsServer(credential)
    }

    suspend fun deleteOpdsServer(index: UInt) = modify("opds.delete") {
        it.deleteOpdsServer(index)
    }

    suspend fun uploadBatch(
        context: Context,
        files: List<BookUploadFile>,
        location: SdkFileLocation,
        onProgress: (Int, BookUploadFile, ULong?, ULong?) -> Unit,
    ): List<BookUploadOutcome> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val active = mutableState.value.active ?: throw IllegalStateException("设备未连接")
            val profile = active.profile
            check(profile.capabilities.contains("files.upload")) { "设备不支持上传文件" }
            val sdkClient = checkNotNull(client) { "设备未连接" }
            val supportsWebsocket = profile.capabilities.contains("upload.websocket")
            val canChooseDirectory = profile.capabilities.contains("upload.target-directory") &&
                profile.constraints.canChooseUploadDirectory
            val uploadLocation = if (canChooseDirectory) location else SdkFileLocation.Root
            Log.i("PicoUpload", "Starting batch: count=${files.size}, location=$uploadLocation, websocket=$supportsWebsocket")
            val acceptedExtensions = profile.fileFormats.uploadExtensions.map {
                it.trim().removePrefix(".").lowercase(java.util.Locale.ROOT)
            }.toSet()
            var connectionFailure = false

            files.mapIndexed { index, file ->
                if (client !== sdkClient) {
                    connectionFailure = true
                    return@mapIndexed BookUploadOutcome(file.name, false, "设备连接已断开", true)
                }
                if (connectionFailure) {
                    return@mapIndexed BookUploadOutcome(file.name, false, "设备连接异常，未继续上传", true)
                }
                onProgress(index, file, null, null)
                val extension = file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (!profile.fileFormats.acceptsAnyUploadFormat && extension !in acceptedExtensions) {
                    return@mapIndexed BookUploadOutcome(file.name, false, "设备不支持此文件格式")
                }
                var temporary: File? = null
                var stage = "创建临时缓存"
                try {
                    val cacheFile = File.createTempFile("pico-upload-", ".tmp", context.cacheDir)
                    temporary = cacheFile
                    stage = "读取所选文件"
                    val input = context.contentResolver.openInputStream(file.uri)
                        ?: throw IOException("无法读取所选文件")
                    input.buffered().use { source ->
                        cacheFile.outputStream().buffered().use { destination -> source.copyTo(destination, 64 * 1024) }
                    }
                    stage = "SDK 上传"
                    Log.i("PicoUpload", "Calling SDK upload: name=${file.name}, bytes=${cacheFile.length()}, location=$uploadLocation")
                    val observer = if (supportsWebsocket) object : SdkUploadProgressObserver {
                        override fun onProgress(sentBytes: ULong, totalBytes: ULong) {
                            onProgress(index, file, sentBytes, totalBytes)
                        }
                    } else null
                    val upload: suspend (SdkConflictPolicy) -> Unit = { conflictPolicy ->
                        sdkClient.upload(
                            cacheFile.absolutePath,
                            file.name,
                            uploadLocation,
                            SdkUploadOptions(
                                conflictPolicy = conflictPolicy,
                                contentType = file.contentType,
                                preferWebsocket = supportsWebsocket,
                            ),
                            observer,
                        )
                    }
                    try {
                        upload(SdkConflictPolicy.OVERWRITE_WHEN_SUPPORTED)
                    } catch (cause: SdkOperationException.Unsupported) {
                        upload(SdkConflictPolicy.REPLACE_WITH_BACKUP)
                    }
                    Log.i("PicoUpload", "SDK upload succeeded: name=${file.name}")
                    BookUploadOutcome(file.name, true)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    val reconnectRequired = isConnectionFailure(cause)
                    connectionFailure = connectionFailure || reconnectRequired
                    val reason = describeUploadFailure(cause)
                    Log.e("PicoUpload", "Upload failed at stage=$stage: name=${file.name}, reason=$reason", cause)
                    val userMessage = if (stage == "SDK 上传") reason else "${stage}失败：$reason"
                    BookUploadOutcome(file.name, false, userMessage, reconnectRequired)
                } finally {
                    temporary?.delete()
                }
            }
        }
    }

    private suspend fun modify(capability: String, operation: suspend (SdkDeviceClient) -> Unit) = withContext(Dispatchers.IO) {
        withDeviceClient(capability, operation)
    }

    private suspend fun <T> withDeviceClient(
        capability: String,
        operation: suspend (SdkDeviceClient) -> T,
    ): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkCapability(capability)
            val current = checkNotNull(client) { "设备未连接" }
            try {
                operation(current)
            } catch (cause: Exception) {
                throw cause
            }
        }
    }

    fun isConnectionFailure(cause: Throwable): Boolean = generateSequence(cause) { it.cause }
        .filterIsInstance<SdkOperationException>()
        .any { it is SdkOperationException.Unreachable || it is SdkOperationException.Timeout }

    fun describeOperationFailure(cause: Throwable, operation: String): String {
        val sdkError = generateSequence(cause) { it.cause }
            .filterIsInstance<SdkOperationException>()
            .firstOrNull()
        return when (sdkError) {
            is SdkOperationException.Unreachable -> "暂时无法访问设备，请检查网络后重试"
            is SdkOperationException.Timeout -> "设备响应超时，请稍后重试"
            is SdkOperationException.InvalidArgument -> "输入无效：${sdkError.detail}"
            is SdkOperationException.Unsupported -> "设备不支持此操作：${sdkError.detail}"
            is SdkOperationException.RemoteFailure -> "设备操作失败：${sdkError.detail}"
            is SdkOperationException.Conflict -> "设备内容已变化，请刷新后重试：${sdkError.detail}"
            is SdkOperationException.InsufficientStorage -> "设备存储空间不足"
            is SdkOperationException.CommittedWithWarning -> "操作已提交，但设备报告警告：${sdkError.detail}"
            is SdkOperationException.CommittedButCleanupFailed -> "操作已完成，但设备清理失败：${sdkError.detail}"
            is SdkOperationException.RecoveryFailed -> "操作失败且设备恢复未完成：${sdkError.detail}"
            null -> cause.message?.takeIf(String::isNotBlank)
                ?: cause.cause?.message?.takeIf(String::isNotBlank)
                ?: "${operation}失败"
        }
    }

    fun describeUploadFailure(cause: Throwable): String {
        val sdkError = generateSequence(cause) { it.cause }.filterIsInstance<SdkOperationException>().firstOrNull()
        return when (sdkError) {
            is SdkOperationException.InvalidArgument -> "上传参数无效：${sdkError.detail}"
            is SdkOperationException.Unsupported -> "设备不支持此上传方式：${sdkError.detail}"
            is SdkOperationException.Unreachable -> "设备无法访问，请检查设备连接和网络"
            is SdkOperationException.Timeout -> "上传超时，请检查设备连接后重试"
            is SdkOperationException.Conflict -> "设备中已存在同名文件：${sdkError.detail}"
            is SdkOperationException.InsufficientStorage -> "设备存储空间不足"
            is SdkOperationException.RemoteFailure -> "设备拒绝了上传：${sdkError.detail}"
            is SdkOperationException.CommittedWithWarning -> "文件已上传，但设备报告警告：${sdkError.detail}"
            is SdkOperationException.CommittedButCleanupFailed -> "文件已上传，但设备清理临时数据失败：${sdkError.detail}"
            is SdkOperationException.RecoveryFailed -> "上传失败且设备恢复未完成：${sdkError.detail}"
            null -> cause.message?.takeIf(String::isNotBlank)
                ?: cause.cause?.message?.takeIf(String::isNotBlank)
                ?: "${cause::class.simpleName ?: "未知错误"}（详情见 Logcat）"
        }
    }

    private fun checkCapability(capability: String) {
        check(mutableState.value.active?.profile?.capabilities?.contains(capability) == true) {
            "设备不支持此操作"
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = mutableState.value.saved.filterNot { it.id == id }
            persist(updated)
            if (mutableState.value.active?.saved?.id == id) {
                client?.close()
                client = null
            }
            mutableState.value = mutableState.value.copy(
                saved = updated,
                active = mutableState.value.active?.takeUnless { it.saved.id == id },
            )
        }
    }

    private fun persist(savedDevices: List<SavedDevice>) {
        val array = JSONArray()
        savedDevices.forEach { saved ->
            array.put(JSONObject().put("id", saved.id).put("deviceType", saved.deviceType).put("address", saved.address))
        }
        val stored = checkNotNull(appContext).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(SAVED_KEY, array.toString()).commit()
        if (!stored) throw IOException("无法保存设备列表")
    }
}
