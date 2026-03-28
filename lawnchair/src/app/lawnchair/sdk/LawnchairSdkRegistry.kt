package app.lawnchair.sdk

import app.lawnchair.sdk.api.LawnchairSdkEntry
import app.lawnchair.sdk.api.DesktopVisibleListener
import app.lawnchair.sdk.api.DesktopVisibleSource
import app.lawnchair.sdk.api.DesktopItemProvider
import app.lawnchair.sdk.api.MinusOneContentProvider
import app.lawnchair.sdk.api.MinusOneOverlayState
import app.lawnchair.sdk.api.MinusOneOverlayStateListener

internal object LawnchairSdkRegistry {
    private var entry: LawnchairSdkEntry = EmptyLawnchairSdkEntry

    val desktopItemProvider: DesktopItemProvider?
        get() = entry.desktopItemProvider

    val minusOneContentProvider: MinusOneContentProvider?
        get() = entry.minusOneContentProvider

    val openMinusOneOnLauncherLaunch: Boolean
        get() = hasMinusOneContentProvider && entry.openMinusOneOnLauncherLaunch

    val minusOneOverlayStateListener: MinusOneOverlayStateListener?
        get() = entry.minusOneOverlayStateListener

    val desktopVisibleListener: DesktopVisibleListener?
        get() = entry.desktopVisibleListener

    val hasMinusOneContentProvider: Boolean
        get() = minusOneContentProvider != null

    fun install(entry: LawnchairSdkEntry) {
        this.entry = entry
    }

    fun dispatchMinusOneOverlayState(state: MinusOneOverlayState, progress: Float) {
        minusOneOverlayStateListener?.onStateChanged(state, progress)
    }

    fun dispatchDesktopVisible(source: DesktopVisibleSource) {
        desktopVisibleListener?.onDesktopVisible(source)
    }
}

private object EmptyLawnchairSdkEntry : LawnchairSdkEntry
