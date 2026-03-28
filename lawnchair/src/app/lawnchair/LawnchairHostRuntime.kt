package app.lawnchair

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.util.Log
import app.lawnchair.backup.LawnchairBackup
import app.lawnchair.flowerpot.Flowerpot
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.sdk.LawnchairSdkApiInitializer
import app.lawnchair.sdk.LawnchairSdkLoader
import app.lawnchair.util.restartLauncher
import com.android.launcher3.BuildConfig
import com.android.launcher3.InvariantDeviceProfile
import com.android.quickstep.RecentsActivity
import com.android.systemui.shared.system.QuickStepContract
import java.io.File

object LawnchairHostRuntime {
    private const val TAG = "LawnchairHostRuntime"
    private val initLock = Any()

    @Volatile
    private var application: Application? = null

    @Volatile
    var isRecentsEnabled: Boolean = false
        private set

    @Volatile
    private var lifecycleCallbacksRegistered = false

    private val activityHandler = object : ActivityLifecycleCallbacks {
        private val activities = HashSet<Activity>()
        private var foregroundActivity: Activity? = null

        fun finishAll() {
            HashSet(activities).forEach { it.finish() }
        }

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            foregroundActivity = activity
        }

        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityDestroyed(activity: Activity) {
            if (activity == foregroundActivity) foregroundActivity = null
            activities.remove(activity)
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activities.add(activity)
        }
    }

    @JvmStatic
    fun initialize(application: Application) {
        val computedRecentsEnabled = synchronized(initLock) {
            if (this.application === application) {
                return
            }
            val enabled = (Build.VERSION.SDK_INT in BuildConfig.QUICKSTEP_MIN_SDK..BuildConfig.QUICKSTEP_MAX_SDK) &&
                checkRecentsComponent(application)
            this.application = application
            isRecentsEnabled = enabled
            lifecycleCallbacksRegistered = false
            enabled
        }

        LawnchairSdkApiInitializer.install()
        LawnchairSdkLoader.load(application)
        QuickStepContract.sRecentsDisabled = !computedRecentsEnabled
        Flowerpot.Manager.getInstance(application)
    }

    fun requireApplication(): Application {
        return application ?: error(
            "Lawnchair runtime is not initialized. " +
                "Use app.lawnchair.LawnchairApp as your application, " +
                "or call app.lawnchair.sdk.LawnchairSdkHost.initialize(application) from Application.onCreate().",
        )
    }

    fun onLauncherAppStateCreated() {
        val application = requireApplication()
        synchronized(initLock) {
            if (lifecycleCallbacksRegistered) return
            application.registerActivityLifecycleCallbacks(activityHandler)
            lifecycleCallbacksRegistered = true
        }
    }

    fun restart(recreateLauncher: Boolean) {
        if (recreateLauncher) {
            activityHandler.finishAll()
        } else {
            restartLauncher(requireApplication())
        }
    }

    fun renameRestoredDb(dbName: String) {
        val application = requireApplication()
        val restoredDbFile = application.getDatabasePath(LawnchairBackup.RESTORED_DB_FILE_NAME)
        if (!restoredDbFile.exists()) return
        val dbFile = application.getDatabasePath(dbName)
        restoredDbFile.renameTo(dbFile)
    }

    fun migrateDbName(dbName: String) {
        val application = requireApplication()
        val dbFile = application.getDatabasePath(dbName)
        if (dbFile.exists()) return
        val prefs = PreferenceManager.INSTANCE.get(application)
        val dbJournalFile = getJournalFile(dbFile)
        val oldDbSlot = prefs.sp.getString("pref_currentDbSlot", "a")
        val oldDbName = if (oldDbSlot == "a") "launcher.db" else "launcher.db_b"
        val oldDbFile = application.getDatabasePath(oldDbName)
        val oldDbJournalFile = getJournalFile(oldDbFile)
        if (oldDbFile.exists()) {
            oldDbFile.copyTo(dbFile)
            oldDbJournalFile.copyTo(dbJournalFile)
            oldDbFile.delete()
            oldDbJournalFile.delete()
        }
    }

    fun cleanUpDatabases() {
        val application = requireApplication()
        val idp = InvariantDeviceProfile.INSTANCE.get(application)
        val dbName = idp.dbFile
        val dbFile = application.getDatabasePath(dbName)
        dbFile.parentFile?.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("launcher") && !name.startsWith(dbName)) {
                file.delete()
            }
        }
    }

    private fun getJournalFile(file: File): File = File(file.parentFile, "${file.name}-journal")

    @SuppressLint("DiscouragedApi")
    private fun checkRecentsComponent(application: Application): Boolean {
        val resId = application.resources.getIdentifier("config_recentsComponentName", "string", "android")
        if (resId == 0) {
            Log.d(TAG, "config_recentsComponentName not found, disabling recents")
            return false
        }

        val recentsComponent = ComponentName.unflattenFromString(application.resources.getString(resId))
        if (recentsComponent == null) {
            Log.d(TAG, "config_recentsComponentName is empty, disabling recents")
            return false
        }

        val isRecentsComponent = recentsComponent.packageName == application.packageName &&
            recentsComponent.className == RecentsActivity::class.java.name
        if (!isRecentsComponent) {
            Log.d(TAG, "config_recentsComponentName ($recentsComponent) is not Lawnchair, disabling recents")
            return false
        }

        return true
    }
}
