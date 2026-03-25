package app.lawnchair.sdk

import android.content.Context
import android.content.Intent
import app.lawnchair.sdk.api.DesktopItemClickAction
import app.lawnchair.sdk.api.DesktopItemSpec

object SdkDesktopItemActionDispatcher {
    private const val ACTION_DESKTOP_ITEM_PROVIDER = "app.lawnchair.sdk.action.DESKTOP_ITEM_PROVIDER"

    const val EXTRA_DESKTOP_ITEM_ID = "app.lawnchair.sdk.extra.DESKTOP_ITEM_ID"
    const val EXTRA_DESKTOP_ITEM_ICON_RES_ID = "app.lawnchair.sdk.extra.DESKTOP_ITEM_ICON_RES_ID"

    private const val EXTRA_DESKTOP_ITEM_ACTION_KIND = "app.lawnchair.sdk.extra.DESKTOP_ITEM_ACTION_KIND"
    private const val EXTRA_DESKTOP_ITEM_ACTION_ID = "app.lawnchair.sdk.extra.DESKTOP_ITEM_ACTION_ID"
    private const val ACTION_KIND_PROVIDER = "provider"

    fun createPersistedIntent(
        context: Context,
        spec: DesktopItemSpec,
        stableId: String,
    ): Intent {
        val baseIntent = when (val action = spec.clickAction) {
            is DesktopItemClickAction.LaunchIntent -> Intent(action.intent)
            is DesktopItemClickAction.ProviderAction ->
                Intent(ACTION_DESKTOP_ITEM_PROVIDER).apply {
                    `package` = context.packageName
                    putExtra(EXTRA_DESKTOP_ITEM_ACTION_KIND, ACTION_KIND_PROVIDER)
                    putExtra(EXTRA_DESKTOP_ITEM_ACTION_ID, action.actionId)
                }
        }
        return baseIntent.apply {
            putExtra(EXTRA_DESKTOP_ITEM_ID, stableId)
            putExtra(EXTRA_DESKTOP_ITEM_ICON_RES_ID, spec.iconResId)
        }
    }

    @JvmStatic
    fun dispatchIfNeeded(
        context: Context,
        intent: Intent?,
    ): Boolean {
        if (intent == null) return false
        val actionKind = intent.getStringExtra(EXTRA_DESKTOP_ITEM_ACTION_KIND)
            ?: when (intent.action) {
                ACTION_DESKTOP_ITEM_PROVIDER -> ACTION_KIND_PROVIDER
                else -> return false
            }
        return when (actionKind) {
            ACTION_KIND_PROVIDER -> dispatchProviderAction(context, intent)
            else -> false
        }
    }

    private fun dispatchProviderAction(
        context: Context,
        intent: Intent,
    ): Boolean {
        val itemId = intent.getStringExtra(EXTRA_DESKTOP_ITEM_ID).orEmpty()
        val actionId = intent.getStringExtra(EXTRA_DESKTOP_ITEM_ACTION_ID).orEmpty()
        if (itemId.isBlank() || actionId.isBlank()) return false
        return LawnchairSdkRegistry.desktopItemProvider?.onDesktopItemAction(
            context = context,
            itemId = itemId,
            actionId = actionId,
        ) == true
    }
}
