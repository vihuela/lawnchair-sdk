package app.lawnchair.sdk

import app.lawnchair.sdk.api.LawnchairSdkEntry
import app.lawnchair.sdk.api.DesktopItemProvider
import app.lawnchair.sdk.api.MinusOneContentProvider

internal object LawnchairSdkRegistry {
    private var entry: LawnchairSdkEntry = EmptyLawnchairSdkEntry

    val desktopItemProvider: DesktopItemProvider?
        get() = entry.desktopItemProvider

    val minusOneContentProvider: MinusOneContentProvider?
        get() = entry.minusOneContentProvider

    val hasMinusOneContentProvider: Boolean
        get() = minusOneContentProvider != null

    fun install(entry: LawnchairSdkEntry) {
        this.entry = entry
    }
}

private object EmptyLawnchairSdkEntry : LawnchairSdkEntry
