package com.mccal.folio

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.compose.ui.window.DialogProperties

/** A Home request dismisses every registered window, including alerts with no back-dismiss action. */
internal class HomeDismissal {
    private val callbacks = linkedSetOf<() -> Unit>()

    fun register(callback: () -> Unit) { callbacks += callback }
    fun unregister(callback: () -> Unit) { callbacks -= callback }
    fun dismissAll() {
        val opened = callbacks.toList()
        callbacks.clear()
        opened.asReversed().forEach { it() }
    }
}

@Composable
internal fun rememberHomePopupVisible(open: Boolean = true, onDismiss: () -> Unit): Boolean {
    val dismissals = (LocalActivity.current as? MainActivity)?.homeDismissal
    var dismissed by remember(open) { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(dismissals, open) {
        val callback = { dismissed = true; dismiss() }
        if (open) dismissals?.register(callback)
        onDispose { dismissals?.unregister(callback) }
    }
    return open && !dismissed
}

@Composable
internal fun HomeDismissibleDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit) {
    if (rememberHomePopupVisible(onDismiss = onDismissRequest)) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
    }
}
