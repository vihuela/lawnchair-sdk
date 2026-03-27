package app.lawnchair.sdk

import app.lawnchair.sdk.api.LawnchairSdkEntry
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

    val hasMinusOneContentProvider: Boolean
        get() = minusOneContentProvider != null

    fun install(entry: LawnchairSdkEntry) {
        this.entry = entry
    }

    fun dispatchMinusOneOverlayState(state: MinusOneOverlayState, progress: Float) {
        minusOneOverlayStateListener?.onStateChanged(state, progress)
    }
}

private object EmptyLawnchairSdkEntry : LawnchairSdkEntry
