package app.lawnchair.sdk.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface MinusOneContentProvider {
    @Composable
    fun Content(modifier: Modifier = Modifier)
}
