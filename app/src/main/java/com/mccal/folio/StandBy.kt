package com.mccal.folio

import android.app.Activity
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaInfo
import androidx.window.core.ExperimentalWindowApi
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sqrt

internal object StandByPose

/** Partly open and standing sideways in tent mode, with Home already on the cover display. */
@Composable
private fun rememberStandByPose(activity: Activity, enabled: Boolean, dualScreenRequested: Boolean): StandByPose? {
    val info by remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
        .collectAsStateWithLifecycle(initialValue = null)
    val folds = info?.displayFeatures?.filterIsInstance<FoldingFeature>().orEmpty()
    val configuration = LocalConfiguration.current
    // An embedded Home window may occupy only one side of the inner display and have no hinge feature.
    val displayBounds = activity.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
    val density = configuration.densityDpi / 160f
    val innerScreen = fitsRegularHomeLayout(displayBounds.width() / density, displayBounds.height() / density, configuration.classScale)
    val coverScreen = !innerScreen && folds.isEmpty()
    val rotation = activity.display?.rotation
    val quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    val naturalLandscape = if (rotation == null) null else when (configuration.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> quarterTurn
        Configuration.ORIENTATION_LANDSCAPE -> !quarterTurn
        else -> null
    }
    // A dual-screen session moves the primary Home window from the cover to the inner display.
    // Keep the cover's sensor coordinate basis during that move, but still require the tent pose.
    var coverNaturalLandscape by remember(activity) { mutableStateOf<Boolean?>(null) }
    SideEffect { if (coverScreen && !dualScreenRequested) coverNaturalLandscape = naturalLandscape }
    val postureNaturalLandscape = if (dualScreenRequested) coverNaturalLandscape else naturalLandscape
    val currentNaturalLandscape by rememberUpdatedState(postureNaturalLandscape)
    val screenEligible = coverScreen || dualScreenRequested
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sensors = remember(activity) { activity.getSystemService(SensorManager::class.java) }
    val hinge = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) }
    val gravity = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    var angle by remember(activity, enabled) { mutableStateOf<Float?>(null) }
    var sensorHalfOpen by remember(activity, enabled) { mutableStateOf(false) }
    var tentStanding by remember(activity, enabled) { mutableStateOf(false) }
    var gravityAt by remember(activity, enabled) { mutableLongStateOf(0L) }
    DisposableEffect(lifecycle, sensors, hinge, gravity, enabled, screenEligible) {
        var registered = false
        var filteredGravity: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!registered) return
                if (event.sensor.type == Sensor.TYPE_HINGE_ANGLE) {
                    val value = event.values.firstOrNull()?.takeIf { it.isFinite() && it in 0f..180f }
                    angle = value
                    // Preserve stepped hinge sensors and hysteresis near the entry boundaries.
                    sensorHalfOpen = value != null && if (sensorHalfOpen) value in 5f..165f else value in 10f..150f
                    return
                }
                val timestamp = event.timestamp / 1_000_000L
                if (event.values.size < 3 || event.values.take(3).any { !it.isFinite() } ||
                    SystemClock.elapsedRealtime() - timestamp !in 0L..GRAVITY_MAX_AGE_MS) {
                    tentStanding = false; gravityAt = 0L; filteredGravity = null
                    return
                }
                val values = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val filtered = filteredGravity ?: event.values.copyOf(3)
                    for (i in 0..2) filtered[i] = .8f * filtered[i] + .2f * event.values[i]
                    filteredGravity = filtered
                    filtered
                } else event.values
                val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
                if (magnitude !in 7f..12f) {
                    tentStanding = false; gravityAt = 0L
                    return
                }
                gravityAt = timestamp
                // Physical sideways posture still works when display rotation is locked.
                val standingAxis = abs(values[if (currentNaturalLandscape == true) 1 else 0])
                val otherAxis = abs(values[if (currentNaturalLandscape == true) 0 else 1])
                val staying = tentStanding
                tentStanding = standingAxis / magnitude >= (if (staying) .45f else .60f) &&
                    standingAxis >= otherAxis * (if (staying) .9f else 1.2f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun stop() {
            registered = false
            sensors?.unregisterListener(listener)
            angle = null
            sensorHalfOpen = false
            tentStanding = false
            gravityAt = 0L
            filteredGravity = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (enabled && screenEligible && !registered && hinge != null && gravity != null) {
                    registered = true
                    val ready = runCatching {
                        sensors?.registerListener(listener, hinge, SensorManager.SENSOR_DELAY_NORMAL) == true &&
                            sensors?.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_NORMAL) == true
                    }.getOrDefault(false)
                    if (!ready) stop()
                }
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(lifecycle, enabled, screenEligible) {
        if (enabled && screenEligible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(500)
                if (gravityAt == 0L || SystemClock.elapsedRealtime() - gravityAt > GRAVITY_MAX_AGE_MS) tentStanding = false
            }
        }
    }
    val ready = enabled && screenEligible && postureNaturalLandscape != null && sensorHalfOpen && tentStanding
    LaunchedEffect(enabled, coverScreen, postureNaturalLandscape, sensorHalfOpen, tentStanding, dualScreenRequested) {
        Diagnostics.event("StandBy posture: enabled=$enabled hinge=${angle ?: "unavailable"} cover=$coverScreen naturalLandscape=$postureNaturalLandscape tent=$tentStanding dual=$dualScreenRequested detected=$ready")
    }
    return if (ready) StandByPose else null
}

/**
 * Cover-screen StandBy while partly open and standing sideways: clock, date, next alarm,
 * battery and now playing. Dim red at night. Tap anywhere or leave that pose to exit.
 */
@Composable
@OptIn(ExperimentalWindowApi::class)
internal fun StandByOverlay(activity: ComponentActivity, enabled: Boolean, blocked: Boolean, status: DeviceStatus) {
    val lighting = remember(activity) { StandByLighting(activity) }
    val pose = rememberStandByPose(activity, enabled, lighting.requested)
    var active by remember { mutableStateOf(false) }
    var dismissedForPose by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(pose, enabled, blocked, foreground) {
        if (pose == null) { active = false; dismissedForPose = false; return@LaunchedEffect }
        if (!enabled || blocked || !foreground) { active = false; return@LaunchedEffect }
        if (dismissedForPose) return@LaunchedEffect
        delay(ENTER_DELAY_MS) // only after the phone has been set down, not while folding through
        active = true
    }
    LaunchedEffect(pose, enabled, blocked, foreground, active, dismissedForPose) {
        Diagnostics.event("StandBy: pose=$pose enabled=$enabled blocked=$blocked foreground=$foreground active=$active dismissed=$dismissedForPose")
    }
    val view = LocalView.current
    val visible = active && pose != null && enabled && !blocked && foreground
    DisposableEffect(visible) { view.keepScreenOn = visible; onDispose { view.keepScreenOn = false } }
    val dismiss = { active = false; dismissedForPose = true }
    BackHandler(visible, onBack = dismiss)

    val areas by lighting.controller.windowAreaInfos.collectAsStateWithLifecycle(initialValue = emptyList())
    val rearArea = areas.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
    val capability = rearArea?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status
    val composition = rememberCompositionContext()
    val currentStatus by rememberUpdatedState(status)
    val currentDismiss by rememberUpdatedState(dismiss)
    var attemptedLighting by remember { mutableStateOf(false) }
    LaunchedEffect(visible, capability, lighting.awaitingStart) {
        if (!visible) {
            attemptedLighting = false
            lighting.stop()
        } else if (!attemptedLighting && !lighting.awaitingStart && rearArea != null &&
            capability == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) {
            attemptedLighting = true
            lighting.start(rearArea, composition) { StandByScreen(currentStatus, currentDismiss) }
        } else if (!lighting.requested) {
            Diagnostics.event("StandBy ambient light unavailable: capability=${capability ?: "unsupported"}")
        }
    }
    DisposableEffect(lighting) { onDispose { lighting.stop() } }

    val configuration = LocalConfiguration.current
    val bounds = activity.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    val density = configuration.densityDpi / 160f
    val innerScreen = fitsRegularHomeLayout(bounds.width() / density, bounds.height() / density, configuration.classScale)
    val innerLight = visible && innerScreen && lighting.presenting
    DisposableEffect(activity, innerLight) {
        val window = activity.window
        val brightness = window.attributes.screenBrightness
        val insets = window.decorView.rootWindowInsets
        val statusBarVisible = insets?.isVisible(android.view.WindowInsets.Type.statusBars()) == true
        val navigationBarVisible = insets?.isVisible(android.view.WindowInsets.Type.navigationBars()) == true
        if (innerLight) {
            window.attributes = window.attributes.apply { screenBrightness = .18f }
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (innerLight) {
                window.attributes = window.attributes.apply { screenBrightness = brightness }
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (statusBarVisible) controller.show(WindowInsetsCompat.Type.statusBars())
                if (navigationBarVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    if (pose != null) AnimatedVisibility(visible, enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
        if (innerScreen) {
            // The inner panel is only a light source; the clock and app content stay on the cover.
            Box(Modifier.fillMaxSize().background(if (innerLight) Color(0xFFFFD5A0) else Color.Black)
                .clickable(remember { MutableInteractionSource() }, null, onClick = dismiss))
        } else StandByScreen(status, dismiss)
    }
}

@Composable
private fun StandByScreen(status: DeviceStatus, onDismiss: () -> Unit) {
    val tick by rememberMinuteTick()
    val now = displayNow(tick)
    val night = now.hour >= 22 || now.hour < 6
    val ink = if (night) Color(0xFFB3261E) else Color.White
    val soft = ink.copy(alpha = if (night) .75f else .6f)
    Box(Modifier.fillMaxSize().background(Color.Black)
        .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
        val clock: @Composable (Modifier) -> Unit = { m -> BigClock(now, ink, soft, m) }
        val info: @Composable (Modifier) -> Unit = { m -> StandByInfo(status, ink, soft, night, m) }
        // The cover screen's aspect ratio determines the two-panel layout.
        val configuration = LocalConfiguration.current
        val stacked = configuration.screenHeightDp >= configuration.screenWidthDp
        if (stacked) Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            clock(Modifier.weight(1f).fillMaxWidth()); info(Modifier.weight(1f).fillMaxWidth())
        } else Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            clock(Modifier.weight(1f).fillMaxHeight()); info(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun BigClock(now: LocalDateTime, ink: Color, soft: Color, modifier: Modifier) {
    val context = LocalContext.current
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(now.format(DateTimeFormatter.ofPattern(pattern)), color = ink, fontSize = 120.sp, fontWeight = FontWeight.Thin,
            lineHeight = 124.sp, style = TextStyle(fontFeatureSettings = "tnum"))
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), color = soft, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StandByInfo(status: DeviceStatus, ink: Color, soft: Color, night: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val tick by rememberMinuteTick()
    val alarm = remember(tick) { UpNext.nextAlarm(context) }
    val media = IslandListenerService.activity.collectAsState().value as? IslandActivity.Media
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            status.battery?.let { level ->
                InfoChip(if (status.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, "$level%", ink, soft)
            }
            alarm?.let {
                val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
                InfoChip(Icons.Rounded.Alarm, t.format(DateTimeFormatter.ofPattern(pattern)), ink, soft)
            }
        }
        // Up Next while nothing is playing: the next event, in the calendar's color (not tinted red at night).
        val next by produceState<UpNextEvent?>(null, tick) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UpNext.events(context, limit = 1).firstOrNull() } }
        if (media == null) next?.let { e ->
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(if (night) Color(0xFF1A0605) else FolioColors.SecondaryBackground)
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(if (night) soft else e.color?.let { Color(it) } ?: LocalAccent.current.fill))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val begin = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.begin), ZoneId.systemDefault())
                    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
                    Text(if (e.allDay) stringResource(R.string.all_day) else begin.format(DateTimeFormatter.ofPattern(pattern)), color = soft, fontSize = 14.sp)
                }
            }
        }
        if (media != null) Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(if (night) Color(0xFF1A0605) else FolioColors.SecondaryBackground).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            media.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(media.title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                media.subtitle?.let { Text(it, color = soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            val t = media.controller.transportControls
            Icon(Icons.Rounded.SkipPrevious, "Previous", tint = ink, modifier = Modifier.minimumInteractiveComponentSize().size(36.dp).clip(CircleShape).clickable { t.skipToPrevious() })
            Icon(if (media.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", tint = ink,
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable { if (media.playing) t.pause() else t.play() })
            Icon(Icons.Rounded.SkipNext, "Next", tint = ink, modifier = Modifier.minimumInteractiveComponentSize().size(36.dp).clip(CircleShape).clickable { t.skipToNext() })
        }
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, ink: Color, soft: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = soft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

private const val ENTER_DELAY_MS = 2_500L
private const val GRAVITY_MAX_AGE_MS = 2_000L
