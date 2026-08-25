package app.lawnchair.nexuslauncher

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.lawnchair.ui.theme.LawnchairTheme

internal object MinusOneComposeHost {
    fun create(
        context: Context,
        content: @Composable (Modifier) -> Unit,
    ): ComposeView {
        return ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LawnchairTheme {
                    content(Modifier)
                }
            }
        }
    }
}
