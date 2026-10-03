package com.cold04.inkreadermgr

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal object AppUpdateInstaller {
    private const val CACHE_PREFS = "app_update_cache"
    private const val KEY_VERSION = "version"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_WAITING_FOR_SOURCE_PERMISSION = "waiting_for_source_permission"
    private const val KEY_INSTALL_RESULT = "install_result"
    private const val INSTALL_STATUS_ACTION = "com.cold04.inkreadermgr.APP_INSTALL_STATUS"
    private const val MAX_APK_BYTES = 512L * 1024L * 1024L

    data class CachedUpdate(val versionName: String, val apkFile: File)

    fun hasCachedUpdateRecord(context: Context): Boolean =
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).contains(KEY_VERSION)

    suspend fun cachedUpdate(context: Context): CachedUpdate? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, null) ?: return@withContext null
        if (isVersionInstalled(context, version)) {
            clearCacheIfInstalled(context)
            return@withContext null
        }
        val apk = apkFile(context, version)
        val expectedSha256 = prefs.getString(KEY_SHA256, null)
        if (apk.isFile && apk.length() > 0L &&
            expectedSha256?.matches(Regex("[0-9a-fA-F]{64}")) == true &&
            runCatching { sha256(apk) }.getOrNull()?.equals(expectedSha256, ignoreCase = true) == true
        ) {
            return@withContext CachedUpdate(version, apk)
        }
        apk.delete()
        prefs.edit().remove(KEY_VERSION).remove(KEY_SHA256).apply()
        null
    }

    suspend fun download(
        context: Context,
        release: AppRelease,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        if (!release.apkSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw IOException("APK 缺少有效的 SHA-256 校验值")
        }
        val directory = File(context.filesDir, "update-cache")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建下载缓存目录")
        val destination = apkFile(context, release.versionName)
        val temporary = File(directory, "${destination.name}.part")
        val connection = URL(release.apkDownloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "PicoManager-Android")
        try {
            val response = connection.responseCode
            if (response !in 200..299) throw IOException("下载失败（HTTP $response）")
            val declaredLength = connection.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
            if (declaredLength != null && declaredLength > MAX_APK_BYTES) {
                throw IOException("APK 大小超出允许范围")
            }
            var received = 0L
            var lastProgressAt = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_APK_BYTES) throw IOException("APK 大小超出允许范围")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 200L) {
                            onProgress?.invoke(received, declaredLength)
                            lastProgressAt = now
                        }
                    }
                }
            }
            if (received == 0L || (declaredLength != null && received != declaredLength)) {
                throw IOException("下载文件不完整")
            }
            if (!digest.hexDigest().equals(release.apkSha256, ignoreCase = true)) {
                throw IOException("APK SHA-256 校验失败")
            }
            onProgress?.invoke(received, declaredLength)
            if (destination.exists() && !destination.delete()) throw IOException("无法替换旧 APK 缓存")
            if (!temporary.renameTo(destination)) throw IOException("无法保存 APK 缓存")
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_VERSION, release.versionName)
                .putString(KEY_SHA256, release.apkSha256)
                .putBoolean(KEY_WAITING_FOR_SOURCE_PERMISSION, false)
                .apply()
            directory.listFiles()?.filter { it != destination }?.forEach(File::delete)
            destination
        } catch (cause: Exception) {
            temporary.delete()
            throw cause
        } finally {
            connection.disconnect()
        }
    }

    fun beginWaitingForSourcePermission(context: Context) {
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WAITING_FOR_SOURCE_PERMISSION, true)
            .apply()
    }

    fun consumeSourcePermissionReturn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WAITING_FOR_SOURCE_PERMISSION, false)) return false
        prefs.edit().putBoolean(KEY_WAITING_FOR_SOURCE_PERMISSION, false).apply()
        return true
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun submitInstall(context: Context, apk: File) {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val targetVersion = prefs.getString(KEY_VERSION, null) ?: throw IOException("找不到 APK 对应的版本")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).buffered().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val statusIntent = Intent(context, AppInstallStatusReceiver::class.java)
                    .setAction(INSTALL_STATUS_ACTION)
                val statusPendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(statusPendingIntent.intentSender)
            }
        } catch (cause: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw cause
        }
    }

    fun recordInstallResult(context: Context, result: String) {
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INSTALL_RESULT, result)
            .apply()
    }

    fun takeInstallResult(context: Context): String? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val result = prefs.getString(KEY_INSTALL_RESULT, null)
        prefs.edit().remove(KEY_INSTALL_RESULT).apply()
        return result
    }

    fun clearCacheIfInstalled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val targetVersion = prefs.getString(KEY_VERSION, null) ?: return false
        if (!isVersionInstalled(context, targetVersion)) return false

        val directory = File(context.filesDir, "update-cache")
        val remainingFiles = directory.listFiles().orEmpty()
        if (remainingFiles.any { it.exists() && !it.delete() }) return false
        if (directory.exists() && !directory.delete()) return false
        prefs.edit().clear().apply()
        return true
    }

    private fun isVersionInstalled(context: Context, targetVersion: String): Boolean {
        val currentVersion = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull() ?: return false
        return AppUpdateChecker.compareVersions(currentVersion.orEmpty(), targetVersion) >= 0
    }

    private fun apkFile(context: Context, versionName: String) =
        File(File(context.filesDir, "update-cache"), "PicoManager-$versionName.apk")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.hexDigest()
    }

    private fun MessageDigest.hexDigest(): String = buildString(64) {
        val digits = "0123456789abcdef"
        for (byte in digest()) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
