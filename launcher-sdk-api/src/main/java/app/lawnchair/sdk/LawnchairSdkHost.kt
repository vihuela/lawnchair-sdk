package app.lawnchair.sdk

import android.app.Application

object LawnchairSdkHost {
    private const val RUNTIME_CLASS_NAME = "app.lawnchair.LawnchairHostRuntime"
    private const val INITIALIZE_METHOD_NAME = "initialize"

    @JvmStatic
    fun initialize(application: Application) {
        runCatching {
            val runtimeClass = Class.forName(RUNTIME_CLASS_NAME)
            val initializeMethod = runtimeClass.getMethod(INITIALIZE_METHOD_NAME, Application::class.java)
            initializeMethod.invoke(null, application)
        }.getOrElse { error ->
            throw IllegalStateException("Failed to initialize Lawnchair runtime from host application", error)
        }
    }
}
