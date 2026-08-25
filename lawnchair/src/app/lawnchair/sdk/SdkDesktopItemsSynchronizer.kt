package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import android.util.Log
import app.lawnchair.sdk.api.DesktopItemsSnapshot
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.runtime.DesktopItemsController
import app.lawnchair.sdk.runtime.DesktopItemsSourcePort
import com.android.launcher3.model.data.ItemInfo
import kotlinx.coroutines.launch

/**
 * Launcher3 adapter facade.
 *
 * Business rules live in [DesktopItemsController]. Android storage and LauncherModel details are
 * supplied through ports, so a platform migration only needs a new workspace adapter.
 */
internal object SdkDesktopItemsSynchronizer {
    @Volatile
    private var holder: ControllerHolder? = null

    suspend fun refreshAsync(context: Context): LauncherCommandResult {
        return controller(context).refresh()
    }

    suspend fun removeItemAsync(
        context: Context,
        itemId: String,
        rememberRemoval: Boolean,
    ): LauncherCommandResult {
        return controller(context).remove(itemId, rememberRemoval)
    }

    suspend fun restoreItemAsync(context: Context, itemId: String): LauncherCommandResult {
        return controller(context).restore(itemId)
    }

    fun onLauncherStarted(context: Context) {
        requestRefresh(context)
    }

    fun requestRefresh(context: Context) {
        LawnchairSdkScope.launch {
            when (val result = refreshAsync(context)) {
                is LauncherCommandResult.Failed -> {
                    Log.e(TAG, "Automatic desktop item refresh failed: $result")
                }

                is LauncherCommandResult.Rejected -> {
                    Log.w(TAG, "Automatic desktop item refresh rejected: $result")
                }

                else -> Log.d(TAG, "Automatic desktop item refresh completed: $result")
            }
        }
    }

    fun onUserRemovalCommitted(context: Context, item: ItemInfo?) {
        val stableId = getStableId(item?.intent) ?: return
        controller(context).onUserRemovalCommitted(stableId)
    }

    private fun controller(context: Context): DesktopItemsController {
        val appContext = context.applicationContext
        holder?.takeIf { it.context === appContext }?.let { return it.controller }
        return synchronized(this) {
            holder?.takeIf { it.context === appContext }?.controller ?: run {
                val workspace = LauncherWorkspaceAdapter(appContext)
                DesktopItemsController(
                    source = RegistryDesktopItemsSource,
                    removalStore = SharedPreferencesDesktopRemovalStore(appContext),
                    workspace = workspace,
                ).also { holder = ControllerHolder(appContext, workspace, it) }
            }
        }
    }

    private fun workspace(context: Context): LauncherWorkspaceAdapter {
        controller(context)
        return checkNotNull(holder).workspace
    }

    private fun getStableId(intent: Intent?): String? {
        return intent?.getStringExtra(SdkDesktopItemActionDispatcher.EXTRA_DESKTOP_ITEM_ID)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private data class ControllerHolder(
        val context: Context,
        val workspace: LauncherWorkspaceAdapter,
        val controller: DesktopItemsController,
    )

    private object RegistryDesktopItemsSource : DesktopItemsSourcePort {
        override suspend fun loadSnapshot(): DesktopItemsSnapshot {
            return LawnchairSdkRegistry.getDesktopItemsSnapshot()
        }
    }

    private const val TAG = "SdkDesktopItems"
}
