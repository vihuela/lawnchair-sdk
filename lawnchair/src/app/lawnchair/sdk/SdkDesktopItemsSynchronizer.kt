package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import android.util.Log
import app.lawnchair.sdk.api.DesktopItemsSnapshot
import app.lawnchair.sdk.api.DesktopRevealAnimation
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.runtime.DesktopItemRevealController
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

    suspend fun revealItemAsync(
        context: Context,
        itemId: String,
        animation: DesktopRevealAnimation,
    ): LauncherCommandResult {
        return holder(context).revealController.reveal(itemId, animation)
    }

    suspend fun openItemAsync(
        context: Context,
        itemId: String,
        animation: DesktopRevealAnimation,
    ): LauncherCommandResult {
        return holder(context).revealController.open(itemId, animation)
    }

    fun onLauncherStarted(
        context: Context,
        pendingItemId: String?,
        animation: DesktopRevealAnimation,
        openAfterReveal: Boolean,
    ) {
        LawnchairSdkScope.launch {
            logAutomaticResult("desktop item refresh", refreshAsync(context))
            if (pendingItemId != null) {
                logAutomaticResult(
                    if (openAfterReveal) "desktop item open" else "desktop item reveal",
                    navigateItemAsync(context, pendingItemId, animation, openAfterReveal),
                )
            }
        }
    }

    fun requestRefresh(context: Context) {
        LawnchairSdkScope.launch {
            logAutomaticResult("desktop item refresh", refreshAsync(context))
        }
    }

    fun requestNavigation(
        context: Context,
        itemId: String,
        animation: DesktopRevealAnimation,
        openAfterReveal: Boolean,
    ) {
        LawnchairSdkScope.launch {
            logAutomaticResult(
                if (openAfterReveal) "desktop item open" else "desktop item reveal",
                navigateItemAsync(context, itemId, animation, openAfterReveal),
            )
        }
    }

    private suspend fun navigateItemAsync(
        context: Context,
        itemId: String,
        animation: DesktopRevealAnimation,
        openAfterReveal: Boolean,
    ): LauncherCommandResult {
        return if (openAfterReveal) {
            openItemAsync(context, itemId, animation)
        } else {
            revealItemAsync(context, itemId, animation)
        }
    }

    fun onUserRemovalCommitted(context: Context, item: ItemInfo?) {
        val stableId = getStableId(item?.intent) ?: return
        controller(context).onUserRemovalCommitted(stableId)
    }

    private fun controller(context: Context): DesktopItemsController {
        return holder(context).controller
    }

    private fun holder(context: Context): ControllerHolder {
        val appContext = context.applicationContext
        holder?.takeIf { it.context === appContext }?.let { return it }
        return synchronized(this) {
            holder?.takeIf { it.context === appContext } ?: run {
                val workspace = LauncherWorkspaceAdapter(appContext)
                ControllerHolder(
                    context = appContext,
                    workspace = workspace,
                    controller = DesktopItemsController(
                        source = RegistryDesktopItemsSource,
                        removalStore = SharedPreferencesDesktopRemovalStore(appContext),
                        workspace = workspace,
                    ),
                    revealController = DesktopItemRevealController(workspace),
                ).also { holder = it }
            }
        }
    }

    private fun logAutomaticResult(operation: String, result: LauncherCommandResult) {
        when (result) {
            is LauncherCommandResult.Failed -> Log.e(TAG, "Automatic $operation failed: $result")
            is LauncherCommandResult.Rejected -> Log.w(TAG, "Automatic $operation rejected: $result")
            else -> Log.d(TAG, "Automatic $operation completed: $result")
        }
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
        val revealController: DesktopItemRevealController,
    )

    private object RegistryDesktopItemsSource : DesktopItemsSourcePort {
        override suspend fun loadSnapshot(): DesktopItemsSnapshot {
            return LawnchairSdkRegistry.getDesktopItemsSnapshot()
        }
    }

    private const val TAG = "SdkDesktopItems"
}
