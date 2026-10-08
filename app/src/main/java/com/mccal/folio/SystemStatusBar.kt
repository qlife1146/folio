package com.mccal.folio

import android.content.Context
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject

internal val LocalSystemStatusBarVisible = compositionLocalOf { false }

internal fun Context.savedSystemStatusBarVisible(): Boolean = runCatching {
    val state = JSONObject(getSharedPreferences("launcher", 0).getString("state", "{}") ?: "{}")
    state.optBoolean("showSystemStatusBar", !state.optBoolean("verticalStatus", true))
}.getOrDefault(false)

internal fun Window.setSystemStatusBarVisible(visible: Boolean) {
    WindowCompat.getInsetsController(this, decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
    }
}

@Composable
internal fun ApplyDialogStatusBar() {
    val visible = LocalSystemStatusBarVisible.current
    val view = LocalView.current
    LaunchedEffect(view, visible) {
        (view.parent as? DialogWindowProvider)?.window?.setSystemStatusBarVisible(visible)
    }
}
