package app.vezuna.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The space the system navigation bar / home indicator takes at the bottom of the page area. The shell provides
 * it and lets pages draw under that edge, so scrolling content flows under the indicator instead of stopping at a
 * hard line above it. It is zero when the bottom tab bar is showing (the bar already clears the edge) and while
 * the keyboard is up.
 */
internal val LocalContentBottomInset = compositionLocalOf { 0.dp }

/** Keeps the end of a scrolling page, or a fixed bottom bar, clear of the navigation bar. Put it inside the scroll. */
@Composable
fun Modifier.clearOfNavigationBar(): Modifier = padding(bottom = LocalContentBottomInset.current)

/** For lazy lists, whose end padding is a value. */
@Composable
fun contentBottomInset(): Dp = LocalContentBottomInset.current
