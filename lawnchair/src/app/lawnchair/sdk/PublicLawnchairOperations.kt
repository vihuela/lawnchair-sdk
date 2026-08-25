package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.sdk.api.DesktopRevealAnimation
import app.lawnchair.sdk.api.LauncherCommand
import app.lawnchair.sdk.api.LauncherCommandResult
import app.lawnchair.sdk.api.LauncherEvent
import app.lawnchair.sdk.api.LauncherFailureReason
import app.lawnchair.sdk.api.LauncherRejectReason
import app.lawnchair.sdk.api.LauncherStateSnapshot
import app.lawnchair.sdk.api.MinusOneAnimation
import app.lawnchair.sdk.internal.LawnchairOperationsDelegate
import app.lawnchair.sdk.internal.LawnchairSdkRuntimeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal object PublicLawnchairOperations : LawnchairOperationsDelegate {
    override val state: StateFlow<LauncherStateSnapshot> = LawnchairRuntimeSignals.state

    override val events: Flow<LauncherEvent> = LawnchairRuntimeSignals.events

    override suspend fun execute(command: LauncherCommand): LauncherCommandResult {
        val context = LawnchairApp.application
        return when (command) {
            LauncherCommand.LaunchDesktop -> runActivityCommand { launchDesktop(context) }

            is LauncherCommand.RevealDesktopItem -> executeDesktopItemReveal(
                context,
                command.itemId,
                command.animation,
            )

            is LauncherCommand.OpenDesktopItem -> executeDesktopItemOpen(
                context,
                command.itemId,
                command.animation,
            )

            is LauncherCommand.OpenMinusOne -> executeMinusOneOpen(context, command.animation)

            is LauncherCommand.CloseMinusOne -> executeMinusOneClose(command.animation)

            is LauncherCommand.ToggleMinusOne -> executeMinusOneToggle(context, command.animation)

            LauncherCommand.RefreshDesktopItems -> executeOnMain {
                SdkDesktopItemsSynchronizer.refreshAsync(context)
            }

            is LauncherCommand.RemoveDesktopItem -> executeOnMain {
                SdkDesktopItemsSynchronizer.removeItemAsync(
                    context,
                    command.itemId,
                    command.rememberRemoval,
                )
            }

            is LauncherCommand.RestoreDesktopItem -> executeOnMain {
                SdkDesktopItemsSynchronizer.restoreItemAsync(context, command.itemId)
            }

            LauncherCommand.RequestDefaultLauncher -> runActivityCommand {
                requestSetDefaultLauncher(context)
            }

            LauncherCommand.OpenHomeSettings -> runActivityCommand { openHomeSettings(context) }

            LauncherCommand.OpenLauncherAppInfo -> runActivityCommand {
                openLauncherAppInfo(context)
            }

            is LauncherCommand.SetApplicationLanguage -> executeOnMain {
                ApplicationLanguageAdapter.setLanguage(context, command.languageTag)
            }
        }
    }

    override fun isDefaultLauncher(context: Context): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homeActivity = context.packageManager.resolveActivity(homeIntent, 0)?.activityInfo
        return homeActivity?.packageName == context.packageName
    }

    private fun launchDesktop(context: Context) {
        context.startActivity(
            Intent(context, LawnchairLauncher::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private suspend fun executeDesktopItemReveal(
        context: Context,
        itemId: String,
        animation: DesktopRevealAnimation,
    ): LauncherCommandResult = executeDesktopItemRequest(
        context = context,
        itemId = itemId,
        animation = animation,
        openAfterReveal = false,
    )

    private suspend fun executeDesktopItemOpen(
        context: Context,
        itemId: String,
        animation: DesktopRevealAnimation,
    ): LauncherCommandResult = executeDesktopItemRequest(
        context = context,
        itemId = itemId,
        animation = animation,
        openAfterReveal = true,
    )

    private suspend fun executeDesktopItemRequest(
        context: Context,
        itemId: String,
        animation: DesktopRevealAnimation,
        openAfterReveal: Boolean,
    ): LauncherCommandResult {
        val stableId = itemId.trim()
        if (stableId.isEmpty()) {
            return LauncherCommandResult.Rejected(LauncherRejectReason.InvalidItemId)
        }
        return executeOnMain {
            runCatching {
                context.startActivity(
                    Intent(context, LawnchairLauncher::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(
                            if (openAfterReveal) {
                                LawnchairLauncher.EXTRA_OPEN_DESKTOP_ITEM_ID
                            } else {
                                LawnchairLauncher.EXTRA_REVEAL_DESKTOP_ITEM_ID
                            },
                            stableId,
                        )
                        .putExtra(LawnchairLauncher.EXTRA_DESKTOP_REVEAL_ANIMATION, animation.name),
                )
            }.fold(
                onSuccess = { LauncherCommandResult.Queued },
                onFailure = {
                    LauncherCommandResult.Failed(
                        LauncherFailureReason.ActivityLaunchFailed,
                        it.message,
                    )
                },
            )
        }
    }

    private fun requestSetDefaultLauncher(context: Context) {
        openHomeSettings(context)
    }

    private fun openHomeSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun openLauncherAppInfo(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private suspend fun executeMinusOneOpen(
        context: Context,
        animation: MinusOneAnimation,
    ): LauncherCommandResult {
        val launcher = LawnchairLauncher.instance
        if (launcher != null) {
            return executeOnMain {
                launcher.getMinusOneCoordinator().requestOpenCommand(animation)
            }
        }
        return executeOnMain {
            runCatching {
                context.startActivity(createOpenMinusOneIntent(context, animation))
            }.fold(
                onSuccess = { LauncherCommandResult.Queued },
                onFailure = {
                    LauncherCommandResult.Failed(
                        LauncherFailureReason.ActivityLaunchFailed,
                        it.message,
                    )
                },
            )
        }
    }

    private suspend fun executeMinusOneClose(
        animation: MinusOneAnimation,
    ): LauncherCommandResult {
        val launcher = LawnchairLauncher.instance
            ?: return LauncherCommandResult.Rejected(LauncherRejectReason.LauncherNotReady)
        return executeOnMain {
            launcher.getMinusOneCoordinator().requestCloseCommand(animation)
        }
    }

    private suspend fun executeMinusOneToggle(
        context: Context,
        animation: MinusOneAnimation,
    ): LauncherCommandResult {
        val launcher = LawnchairLauncher.instance
            ?: return executeMinusOneOpen(context, animation)
        return executeOnMain {
            launcher.getMinusOneCoordinator().toggleCommand(animation)
        }
    }

    private suspend fun executeOnMain(
        block: suspend () -> LauncherCommandResult,
    ): LauncherCommandResult {
        return runCatching { withContext(Dispatchers.Main.immediate) { block() } }
            .getOrElse {
                Log.e(TAG, "Launcher command failed", it)
                LauncherCommandResult.Failed(LauncherFailureReason.RuntimeUnavailable, it.message)
            }
    }

    private suspend fun runActivityCommand(block: () -> Unit): LauncherCommandResult {
        return executeOnMain {
            runCatching(block)
                .fold(
                    onSuccess = { LauncherCommandResult.Applied },
                    onFailure = {
                        LauncherCommandResult.Failed(
                            LauncherFailureReason.ActivityLaunchFailed,
                            it.message,
                        )
                    },
                )
        }
    }

    private fun createOpenMinusOneIntent(
        context: Context,
        animation: MinusOneAnimation,
    ): Intent {
        return Intent(context, LawnchairLauncher::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LawnchairLauncher.EXTRA_OPEN_MINUS_ONE, true)
            .putExtra(LawnchairLauncher.EXTRA_MINUS_ONE_ANIMATION, animation.name)
    }

    private const val TAG = "LawnchairOperations"
}

internal object LawnchairSdkApiInitializer {
    fun install() {
        LawnchairSdkRuntimeBridge.installOperationsDelegate(PublicLawnchairOperations)
    }
}
