package com.mccal.folio

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/** A Home request dismisses every registered window, including alerts with no back-dismiss action. */
internal class HomeDismissal {
    private val callbacks = linkedMapOf<() -> Unit, Int>()
    private var popupLevels by mutableStateOf<List<Int>>(emptyList())

    val hasOpenPopup: Boolean get() = popupLevels.isNotEmpty()
    val openPopupCount: Int get() = popupLevels.size
    fun hasPopupAbove(level: Int): Boolean = popupLevels.any { it > level }

    fun register(callback: () -> Unit, level: Int = 0) {
        callbacks[callback] = level
        popupLevels = callbacks.values.toList()
    }
    fun unregister(callback: () -> Unit) {
        callbacks -= callback
        popupLevels = callbacks.values.toList()
    }
    fun dismissAll() {
        val opened = callbacks.keys.toList()
        callbacks.clear()
        popupLevels = emptyList()
        opened.asReversed().forEach { it() }
    }
}

internal val LocalHomePopupLevel = staticCompositionLocalOf { 0 }

@Composable
internal fun rememberHomePopupVisible(open: Boolean = true, onDismiss: () -> Unit): Boolean {
    val dismissals = (LocalActivity.current as? MainActivity)?.homeDismissal
    val level = LocalHomePopupLevel.current
    var dismissed by remember(open) { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val visible = open && !dismissed
    DisposableEffect(dismissals, visible, level) {
        val callback = { dismissed = true; dismiss() }
        if (visible) dismissals?.register(callback, level)
        onDispose { dismissals?.unregister(callback) }
    }
    return visible
}

@Composable
internal fun PopupBackdropContent(content: @Composable () -> Unit) {
    PopupBackdropScope { backdrop -> Box(backdrop) { content() } }
}

/** Separate windows apply the modifier to their own surface instead of Home's layout. */
@Composable
internal fun PopupBackdropScope(content: @Composable (Modifier) -> Unit) {
    val level = LocalHomePopupLevel.current
    val dismissals = (LocalActivity.current as? MainActivity)?.homeDismissal
    val backdrop = Modifier.popupBackdropBlur(dismissals?.hasPopupAbove(level) == true)
    CompositionLocalProvider(LocalHomePopupLevel provides level + 1) {
        content(backdrop)
    }
}

@Composable
internal fun HomeDismissibleDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit) {
    if (rememberHomePopupVisible(onDismiss = onDismissRequest)) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            PopupBackdropContent {
                ApplyDialogStatusBar()
                content()
            }
        }
    }
}
