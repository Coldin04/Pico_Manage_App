package com.cold04.inkreadermgr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class AppInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmationIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdateInstaller.clearCacheIfInstalled(context)
                AppUpdateInstaller.recordInstallResult(context, "success")
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                AppUpdateInstaller.recordInstallResult(context, "not_completed")
                if (!AppUpdateInstaller.canInstallPackages(context)) {
                    AppUpdateInstaller.beginWaitingForSourcePermission(context)
                    runCatching {
                        context.startActivity(
                            AppUpdateInstaller.unknownSourcesSettingsIntent(context)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            else -> AppUpdateInstaller.recordInstallResult(context, "not_completed")
        }
    }
}
