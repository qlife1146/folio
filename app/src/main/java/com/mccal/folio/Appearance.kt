package com.mccal.folio

import android.content.Context
import androidx.compose.runtime.*
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

enum class AppearanceMode { LIGHT, DARK, SYSTEM, SUNRISE_SUNSET }
data class AppearanceState(val mode: AppearanceMode = AppearanceMode.SYSTEM, val accent: AccentChoice = AccentChoice.FOLIO_TEAL, val place: String = "",
    val latitude: Double? = null, val longitude: Double? = null, val locationTime: Long = 0,
    val deviceLocation: Boolean = false, val dark: Boolean = false, val fallback: String? = null,
    val locationStatus: String? = null)

class AppearanceStore(private val context: Context) {
    private fun currentSystemDark() = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    var state by mutableStateOf(load(currentSystemDark())); private set
    init { DuoAppearanceRuntime.dark = state.dark; DuoAppearanceRuntime.accent = state.accent }
    // Old preferences and imported appearance settings cannot override the device theme.
    private fun load(systemDark: Boolean) = AppearanceState(dark = systemDark)
    fun setMode(mode: AppearanceMode, systemDark: Boolean) { save(state.copy(mode = mode), systemDark) }
    fun setAccent(accent: AccentChoice, systemDark: Boolean) { save(state.copy(accent = accent), systemDark) }
    fun setManual(place: String, latitude: Double, longitude: Double, systemDark: Boolean) {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        save(state.copy(place = place.trim(), latitude = latitude, longitude = longitude,
            locationTime = System.currentTimeMillis(), deviceLocation = false), systemDark)
    }
    fun setDeviceLocation(latitude: Double, longitude: Double, systemDark: Boolean) = save(state.copy(
        place = "Approximate device location", latitude = latitude, longitude = longitude,
        locationTime = System.currentTimeMillis(), deviceLocation = true, locationStatus = null), systemDark)
    fun locationStatus(message: String?) { state = state.copy(locationStatus = message) }
    fun clearLocation(systemDark: Boolean) = save(state.copy(place = "", latitude = null, longitude = null,
        locationTime = 0, deviceLocation = false), systemDark)
    fun refresh(systemDark: Boolean) {
        state = load(systemDark)
        DuoAppearanceRuntime.dark = state.dark
        DuoAppearanceRuntime.accent = state.accent
    }
    fun reloadFromPreferences(systemDark: Boolean = currentSystemDark()) {
        val transientStatus = state.locationStatus
        state = load(systemDark).copy(locationStatus = transientStatus)
        DuoAppearanceRuntime.dark = state.dark
        DuoAppearanceRuntime.accent = state.accent
    }
    private fun save(value: AppearanceState, systemDark: Boolean) {
        refresh(systemDark)
    }
}

object DuoAppearanceRuntime {
    @Volatile var dark: Boolean = false
    /** Mirrors the saved accent, so a service or an overlay outside the theme can draw with it too. */
    @Volatile var accent: AccentChoice = AccentChoice.FOLIO_TEAL
}

data class DuoPalette(val ink: androidx.compose.ui.graphics.Color, val glass: androidx.compose.ui.graphics.Color,
    val backgroundTop: androidx.compose.ui.graphics.Color, val backgroundBottom: androidx.compose.ui.graphics.Color, val dark: Boolean)
val LightDuoPalette = DuoPalette(androidx.compose.ui.graphics.Color(0xFF243A46), androidx.compose.ui.graphics.Color(0xFFE8EFF2),
    androidx.compose.ui.graphics.Color(0xFF41687E), androidx.compose.ui.graphics.Color(0xFFD8CEB6), false)
val DarkDuoPalette = DuoPalette(androidx.compose.ui.graphics.Color(0xFFEAF3F6), androidx.compose.ui.graphics.Color(0xFF263A43),
    androidx.compose.ui.graphics.Color(0xFF132832), androidx.compose.ui.graphics.Color(0xFF463F35), true)
val LocalDuoPalette = staticCompositionLocalOf { LightDuoPalette }

@Composable
fun rememberSavedAppearance(): AppearanceState {
    val context = LocalContext.current
    val systemDark = androidx.compose.ui.platform.LocalConfiguration.current.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember(context) { AppearanceStore(context.applicationContext) }
    LaunchedEffect(store, systemDark) { store.refresh(systemDark) }
    DisposableEffect(context, store, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) { store.reloadFromPreferences() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        }
        var registered = false
        fun register() { if (!registered) {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true; store.reloadFromPreferences()
        } }
        fun unregister() { if (registered) { runCatching { context.unregisterReceiver(receiver) }; registered = false } }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> register()
            Lifecycle.Event.ON_STOP -> unregister()
            else -> Unit
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); unregister() }
    }
    return store.state
}
