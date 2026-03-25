package app.lawnchair.smartspace

import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import app.lawnchair.util.LawnchairHostInfo

class SmartspaceAppWidgetProvider : AppWidgetProvider() {

    companion object {
        @JvmField val componentName = ComponentName(LawnchairHostInfo.applicationId(), SmartspaceAppWidgetProvider::class.java.name)
    }
}
