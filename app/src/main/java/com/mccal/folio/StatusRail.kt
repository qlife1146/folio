package com.mccal.folio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

/** Side-rail status settings. Icons always use the integrated ring; legacy glyph choices are ignored. */
data class StatusStyle(
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showBatteryPercent: Boolean = true,
    val colorfulBattery: Boolean = true,
    /** Frost strength shared by the status, dock and island capsules (0 = clear, 1 = solid). */
    val railGlass: Float = .26f,
    /** A bell with a slash while the ringer is on silent or vibrate, like iPhone's status bar. */
    val showSilent: Boolean = true,
    /** The frosted capsule behind the status (the dock keeps its own). */
    val background: Boolean = true,
    /** Space between the status items (time, date, icons) in dp: [STANDARD_SPACING], or [COMPACT_SPACING] packed tight. */
    val spacing: Float = STANDARD_SPACING,
) {
    /** At or near Compact, text lines pack tight too, as the old Compact choice did. */
    val tight get() = spacing < (COMPACT_SPACING + STANDARD_SPACING) / 2f
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("showTime", showTime).put("showDate", showDate)
        .put("showBatteryPercent", showBatteryPercent).put("colorfulBattery", colorfulBattery)
        .put("railGlass", railGlass.toDouble()).put("showSilent", showSilent)
        .put("background", background).put("spacing", spacing.toDouble())

    companion object {
        const val STANDARD_SPACING = 4f
        const val COMPACT_SPACING = 0f
        fun fromJson(j: org.json.JSONObject?): StatusStyle = if (j == null) StatusStyle() else StatusStyle(
            showTime = j.optBoolean("showTime", true), showDate = j.optBoolean("showDate", true),
            showBatteryPercent = j.optBoolean("showBatteryPercent", true),
            colorfulBattery = j.optBoolean("colorfulBattery", true),
            railGlass = j.optDouble("railGlass", .26).toFloat().coerceIn(0f, 1f),
            showSilent = j.optBoolean("showSilent", true),
            background = j.optBoolean("background", true),
            // Before the slider, spacing was Standard or Compact.
            spacing = if (j.has("spacing")) j.optDouble("spacing", STANDARD_SPACING.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 16f) ?: STANDARD_SPACING
                else if (j.optBoolean("compactSpacing", false)) COMPACT_SPACING else STANDARD_SPACING)
    }
}

/**
 * The Gauge's ring, as angles: the break at the top is opened just wide enough for the reading plus a margin either
 * side, so 100 has the same air around it as 9 does, and closes altogether when the percentage is off. The break at
 * the bottom is fixed, for the dots.
 */
internal fun gaugeRing(digits: Int, showsReading: Boolean, bottomGap: Float = 120f, readingScale: Float = 1f): GaugeRing {
    val topGap = if (!showsReading) 0f else {
        val needed = gaugeReadingWidth(digits) * readingScale + 2f * GAUGE_READING_PAD
        val half = Math.toDegrees(kotlin.math.asin((needed / (2f * GAUGE_RADIUS)).coerceIn(0f, 1f).toDouble())).toFloat()
        (half * 2f).coerceIn(60f * readingScale, 130f)
    }
    val side = (360f - topGap - bottomGap) / 2f
    val leftStart = 90f + bottomGap / 2f
    return GaugeRing(topGap, side, leftStart, leftStart + side + topGap)
}

/** Where the Gauge's two arcs begin and how long they are, in degrees (0 is 3 o'clock, growing clockwise). */
internal data class GaugeRing(val topGap: Float, val side: Float, val leftStart: Float, val rightStart: Float)

/**
 * How tall the Gauge's reading is, as a fraction of the glyph's width. A full charge is three digits and takes a
 * smaller size so it still fits across the ring; 1 and 10 keep the larger one, since they have room.
 */
internal fun gaugeReadingSize(digits: Int): Float = if (digits > 2) .245f else .28f

/** Roughly how wide a reading of this many digits comes out, in the same fractions, for keeping it inside the mark. */
internal fun gaugeReadingWidth(digits: Int): Float = digits * gaugeReadingSize(digits) * .62f

/** Everything below is a fraction of the glyph's width: the mark is taller than it is wide, like the one it copies. */
private const val GAUGE_RADIUS = .36f
/** Air either side of the reading inside the break. */
private const val GAUGE_READING_PAD = .055f

/** Shared capsule look for the side rail (status, dock, island). */

@Composable
fun StatusRail(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    locationInUse: Boolean = false,
    island: (@Composable () -> Unit)? = null,
    style: StatusStyle = StatusStyle(),
    /** The Focus that's on: its icon sits above the time, as iPhone shows it beside the clock. */
    focus: FocusMode? = null,
    onClockTop: (Float) -> Unit = {},
    showStatus: Boolean = true,
    onActivityHeight: (Float) -> Unit = {},
) {
    if (!showStatus) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { RailActivitySlot(island, onActivityHeight) }
        return
    }
    // Text sits on the frosted capsule, not straight on the wallpaper, so pick its color from how light the capsule
    // looks: the wallpaper's main color seen through the glass. An explicit Light or Dark "Text on Home" still wins.
    val tone = LocalWallpaperTone.current
    val glassColor = Glass
    val homeInk = LocalHomeInk.current.let { base ->
        if (!base.automatic) base else {
            val wallpaperLum = tone.primary?.let { Color(it).luminance() } ?: if (tone.prefersDarkText) .75f else .25f
            // Without the capsule the text sits right on the wallpaper.
            val capsuleLum = if (!style.background) wallpaperLum
                else wallpaperLum + (glassColor.luminance() - wallpaperLum) * style.railGlass.coerceIn(0f, 1f) * 1.6f
            HomeInk(dark = capsuleLum.coerceIn(0f, 1f) > .5f, automatic = true)
        }
    }
    val ink = homeInk.primary
    // Over light wallpapers (dark text) the frosted capsule is light too, so dim parts and colors need more weight to read.
    val onLight = homeInk.dark
    fun faint(alpha: Float) = if (onLight) (alpha * 1.6f).coerceAtMost(.6f) else alpha
    val charging = if (onLight) BatteryChargingOnLight else BatteryCharging
    val low = if (onLight) BatteryLowOnLight else BatteryLow
    // Ticker, not a loop of its own: one clock for the whole app, stopped while nothing is watching (DYN-14).
    val now = displayNow(rememberMinuteTick().value)
    val format = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    val timeFormatter = remember(format) { DateTimeFormatter.ofPattern(format) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d") }
    val description = listOfNotNull(
        if (locationInUse) "Location in use" else null,
        focus?.let { "${it.name} on" },
        if (status.silent && style.showSilent) "Silent mode" else null,
        now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, $format")),
        status.battery?.let { "Battery $it percent${if (status.charging) ", charging" else ""}" } ?: "Battery unavailable",
        if (status.wifiConnected) "Wi-Fi connected${status.wifiLevel?.let { ", signal $it of 4" } ?: ""}" else "Wi-Fi disconnected",
        if (status.airplane) "Airplane mode" else status.cellularLevel?.let { "Cellular signal $it of 4" } ?: "Cellular signal unavailable",
    ).joinToString(". ")
    val fontScale = LocalDensity.current.fontScale
    val wifiVisual = wifiSignalVisual(status.wifiConnected, status.wifiLevel)
    val batteryColor = when {
        !style.colorfulBattery -> ink
        status.charging -> charging
        (status.battery ?: 100) <= 20 -> low
        else -> ink
    }
    val capsule = RoundedCornerShape(30.dp)
    BoxWithConstraints(modifier.testTag("status-rail").semantics(mergeDescendants = true) { contentDescription = description }) {
        val availableWidth = (maxWidth - 16.dp).coerceAtLeast(28.dp)
        val visualSize = (maxWidth - 4.dp).coerceAtLeast(28.dp)
        val timeSize = minOf(17f, availableWidth.value / (2.5f * fontScale)).sp
        val detailSize = minOf(11f, availableWidth.value / (3.3f * fontScale)).sp
        val clockStyle = androidx.compose.ui.text.TextStyle(
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                androidx.compose.ui.text.style.LineHeightStyle.Trim.Both))
        val indicators: @Composable () -> Unit = {
            if (!compact && locationInUse) Icon(Icons.Rounded.LocationOn, null, tint = ink,
                modifier = Modifier.size(18.dp))
            focus?.let { Icon(it.icon(), "${it.name} on", tint = androidx.compose.ui.graphics.Color(it.color).let { c ->
                if (LocalHomeInk.current.dark) c else androidx.compose.ui.graphics.lerp(c, androidx.compose.ui.graphics.Color.White, .35f) },
                modifier = Modifier.size(if (compact) 14.dp else 16.dp).testTag("status-focus")) }
            if (status.silent && style.showSilent) Icon(Icons.Rounded.NotificationsOff, null,
                tint = if (style.colorfulBattery) (if (onLight) SilentOnLight else Silent) else ink,
                modifier = Modifier.size(if (compact) 14.dp else 16.dp).testTag("status-silent"))
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Same frosted capsule as the dock so status and dock read as one side rail.
            Column(Modifier.fillMaxWidth()
                .then(if (style.background) Modifier.materialBackground(capsule, tint = Glass) else Modifier)
                // Without glass, the clock itself starts at the grid's top.
                .padding(vertical = if (!style.background) 0.dp else if (compact) FolioSpace.SMALL.dp
                    else (8f + style.spacing).coerceAtMost(12f).dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((style.spacing * .5f).dp)) {
                if (style.background) indicators()
                if (style.showTime) Text(now.format(timeFormatter), color = ink, fontSize = timeSize, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.onGloballyPositioned { onClockTop(it.positionInRoot().y) },
                    lineHeight = timeSize, style = clockStyle,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                if (!compact && style.showDate) Text(now.format(dateFormatter), color = ink.copy(alpha = if (onLight) .85f else .7f), fontSize = detailSize,
                    lineHeight = detailSize, style = clockStyle,
                    fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                if (!style.background) indicators()
                // With the percentage on, the ring breaks at the top to hold it; with it off, the ring closes
                // over the top and the mark is shorter. The break at the bottom is always there for the dots.
                val showsReading = style.showBatteryPercent
                // Reserve room for the active SIMs and optional battery reading at any rail size.
                val simLevels = status.cellularSignals.take(2).map { it.level }.ifEmpty { listOf(status.cellularLevel) }
                val showsDots = !status.airplane
                val dualSim = simLevels.size > 1
                val reading = status.battery?.toString() ?: "\u2014"
                val readingScale = .75f
                val readingSize = gaugeReadingSize(reading.length) * readingScale
                val headroom = if (!showsReading) .06f else readingSize * .97f
                val ringCenter = headroom + GAUGE_RADIUS
                val ring = gaugeRing(reading.length, showsReading, readingScale = readingScale)
                val dotsY = ringCenter + .30f
                val markHeight = ringCenter + (if (dualSim) .46f else .41f)
                Box(Modifier.width(visualSize).height(visualSize * markHeight),
                    contentAlignment = Alignment.TopCenter) {
                    // One ring combines battery charge, the connection and cellular strength.
                    val still = LocalReduceMotion.current
                    val level by animateFloatAsState(((status.battery ?: 0).coerceIn(0, 100)) / 100f,
                        if (still) snap() else tween(FolioMotion.GAUGE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        label = "gauge level")
                    val arcColor by animateColorAsState(batteryColor,
                        if (still) snap() else tween(FolioMotion.GAUGE_MS), label = "gauge color")
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val center = Offset(w / 2, w * ringCenter)
                        val radius = w * GAUGE_RADIUS
                        val corner = Offset(center.x - radius, center.y - radius)
                        val box = Size(radius * 2, radius * 2)
                        val stroke = Stroke(width = w * .085f, cap = StrokeCap.Round)
                        val track = ink.copy(alpha = faint(.22f))
                        if (ring.topGap > 0f) {
                            drawArc(track, ring.leftStart, ring.side, false, corner, box, style = stroke)
                            drawArc(track, ring.rightStart, ring.side, false, corner, box, style = stroke)
                        } else drawArc(track, ring.leftStart, ring.side * 2f, false, corner, box, style = stroke)
                        if (status.battery != null) {
                            // Anchor remaining charge at the lower left, so depletion travels from right to left.
                            val filled = level.coerceIn(0f, 1f) * ring.side * 2f
                            val start = ring.leftStart
                            if (ring.topGap == 0f) {
                                if (filled > 0f) drawArc(arcColor, start, filled, false, corner, box, style = stroke)
                            } else {
                                // Skip only the reading gap while keeping both arcs on the same continuous progression.
                                val left = minOf(filled, ring.side)
                                val right = (filled - ring.side).coerceAtLeast(0f)
                                if (left > 0f) drawArc(arcColor, start, left, false, corner, box, style = stroke)
                                if (right > 0f) drawArc(arcColor, ring.rightStart, right, false, corner, box, style = stroke)
                            }
                        }
                        if (wifiVisual is WifiSignalVisual.Connected) {
                            // The visible fan spans .24w–.60w, including its stroke and bottom dot.
                            // Align that midpoint before scaling so either percentage mode stays centered.
                            scale(.80f, center) {
                                translate(0f, center.y - w * .42f) { drawWifiFan(w, wifiVisual, ink = ink, onLight = onLight) }
                            }
                        }
                        // Cellular strength as a row of dots under the ring, where the lower break opens.
                        if (showsDots) simLevels.forEachIndexed { row, strength ->
                            repeat(4) { i ->
                                val dotRadius = if (dualSim) .027f else .045f
                                val dotPitch = if (dualSim) .095f else .15f
                                val dotX = (i - 1.5f) * w * dotPitch
                                // A single network completes the battery's circle along the same radius.
                                val dotY = if (dualSim) w * (dotsY + row * .085f)
                                    else center.y + kotlin.math.sqrt(radius * radius - dotX * dotX)
                                drawCircle(ink.copy(alpha = if (strength != null && i < strength.coerceIn(0, 4)) 1f else faint(.28f)),
                                    w * dotRadius, Offset(center.x + dotX, dotY))
                            }
                        }
                    }
                    if (!status.wifiConnected && !status.airplane)
                        Box(Modifier.fillMaxWidth().padding(top = visualSize * (ringCenter - .16f)).height(visualSize * .32f),
                            contentAlignment = Alignment.Center) {
                            Text(status.cellularNetwork ?: "—", color = ink, fontWeight = FontWeight.Bold,
                                fontSize = (visualSize.value * .24f / fontScale).sp,
                                lineHeight = (visualSize.value * .24f / fontScale).sp,
                                style = clockStyle, maxLines = 1, softWrap = false)
                        }
                    if (showsReading) Text(reading, color = if (status.charging && style.colorfulBattery) charging else ink,
                        fontSize = (visualSize.value * readingSize / fontScale).sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                        // It rests on the ring's top break, like the mark it copies.
                        modifier = Modifier.padding(top = visualSize *
                            ((ringCenter - GAUGE_RADIUS) - readingSize * .97f).coerceAtLeast(0f)))
                    if (status.airplane && !status.wifiConnected)
                        Icon(Icons.Rounded.AirplanemodeActive, null, tint = ink,
                            modifier = Modifier.padding(top = visualSize * (ringCenter - .15f)).size(visualSize * .3f))
                }
            }
            Spacer(Modifier.height(8.dp)); RailActivitySlot(island, onActivityHeight)
        }
    }
}

/** Reserve the compact activity space even when there is nothing to show, keeping the dock below it. */
@Composable
private fun RailActivitySlot(content: (@Composable () -> Unit)?, onHeight: (Float) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = railCompactHeight(maxWidth))
            .onSizeChanged { onHeight(with(density) { it.height.toDp().value }) }) { content?.invoke() }
    }
}

@Composable
internal fun railCompactHeight(width: Dp): Dp = 20.dp + (width - 20.dp).coerceIn(28.dp, 48.dp) +
    FolioSpace.SMALL.dp + with(LocalDensity.current) { 28.sp.toDp() }.coerceAtLeast(20.dp)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWifiFan(w: Float, wifiVisual: WifiSignalVisual.Connected, ink: Color, onLight: Boolean) {
    val cx = size.width / 2
    val fanY = w * .56f
    for (i in 1..3) {
        val r = w * (.07f + i * .075f)
        drawArc(ink.copy(alpha = signalAlpha(wifiVisual.elements[i], onLight)), 225f, 90f, false,
            Offset(cx - r, fanY - r), Size(r * 2, r * 2), style = Stroke(w * .05f, cap = StrokeCap.Round))
    }
    drawCircle(ink.copy(alpha = signalAlpha(wifiVisual.elements[0], onLight)), w * .04f, Offset(cx, fanY))
}

private val BatteryCharging = Color(0xFF6EE39A)
/** iOS shows Silent mode's bell in red. */
private val Silent = FolioColors.Red
private val SilentOnLight = Color(0xFFD70015)
private val BatteryLow = Color(0xFFFFB35C)
/** iOS's darker system green and orange, which keep their contrast on light backgrounds. */
private val BatteryChargingOnLight = Color(0xFF248A3D)
private val BatteryLowOnLight = Color(0xFFC93400)

private fun signalAlpha(emphasis: SignalElementEmphasis, onLight: Boolean = false): Float = when (emphasis) {
    SignalElementEmphasis.DIM -> if (onLight) .45f else .3f
    SignalElementEmphasis.NEUTRAL -> if (onLight) .75f else .62f
    SignalElementEmphasis.LIT -> 1f
}
