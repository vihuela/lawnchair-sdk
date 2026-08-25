package app.lawnchair.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal object LawnchairSdkScope : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Main.immediate
}
