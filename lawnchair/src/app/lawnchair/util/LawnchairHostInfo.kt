package app.lawnchair.util

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import app.lawnchair.LawnchairApp
import com.android.launcher3.BuildConfig

object LawnchairHostInfo {
    @JvmStatic
    fun applicationId(context: Context): String = context.packageName

    @JvmStatic
    fun applicationId(): String {
        return runCatching { LawnchairApp.application.packageName }.getOrElse { BuildConfig.APPLICATION_ID }
    }

    @JvmStatic
    fun versionCode(context: Context): Int {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(packageInfo).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }.getOrElse { BuildConfig.VERSION_CODE }
    }

    @JvmStatic
    fun versionCode(): Int {
        return runCatching { versionCode(LawnchairApp.application) }.getOrElse { BuildConfig.VERSION_CODE }
    }

    @JvmStatic
    fun versionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { BuildConfig.VERSION_NAME }
    }

    @JvmStatic
    fun versionName(): String {
        return runCatching { versionName(LawnchairApp.application) }.getOrElse { BuildConfig.VERSION_NAME }
    }

    @JvmStatic
    fun commitHash(): String = BuildConfig.COMMIT_HASH

    @JvmStatic
    fun isNightly(context: Context): Boolean = applicationId(context).contains("nightly")

    @JvmStatic
    fun isNightly(): Boolean = applicationId().contains("nightly")
}
