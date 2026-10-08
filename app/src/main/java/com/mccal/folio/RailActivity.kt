package com.mccal.folio

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.basicMarquee
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

/** Empty Home cells and the surrounding wallpaper share the same dismiss action. */
internal val LocalHomeBackgroundTap = staticCompositionLocalOf<() -> Unit> { {} }

private fun IslandContent.railTransitionKey(): Any = when (this) {
    is IslandContent.Event -> event.javaClass to when (val brief = event) {
        is IslandEvent.Message -> brief.key
        is IslandEvent.Notice -> brief.text
        is IslandEvent.Bluetooth -> brief.name
        is IslandEvent.Silent -> brief.on
        is IslandEvent.Focus -> brief.on
        is IslandEvent.Charging -> null
    }
    is IslandContent.Live -> activity.javaClass to when (val live = activity) {
        is IslandActivity.Media -> live.token
        is IslandActivity.Call -> live.key
        is IslandActivity.Navigation -> live.key
        is IslandActivity.Timer -> live.key
        is IslandActivity.Progress -> live.key
    }
}

/**
 * Live activities and brief system events share the rail above the dock, and leave again when they end.
 * A tap opens the app; a long press
 * expands the activity along the rail or over Home, depending on the selected style.
 *
 * Drawn like Apple's island: pure black with no outline, concentric corners (inner radius = outer radius minus
 * the inset), and spring motion for arriving, leaving and expanding.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun RailLiveActivity(content: IslandContent?, width: androidx.compose.ui.unit.Dp,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    overlayExpansion: Boolean = false, onOpen: () -> Unit = {}) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(expanded) { onExpandedChange(false) }
    DisposableEffect(Unit) { onDispose { onExpandedChange(false) } }
    // Keep showing the last activity while the rail shrinks away.
    var shown by remember { mutableStateOf(content) }
    if (content != null) shown = content
    if (shown?.isProtectedContent() == true) shown = null
    val activity = (content as? IslandContent.Live)?.activity
    val media = (shown as? IslandContent.Live)?.activity as? IslandActivity.Media
    val sharedBands = (shown as? IslandContent.Live)?.waveformBands
    val fallbackBands = rememberAudioBands(media, content != null && sharedBands == null)
    val waveformBands = sharedBands ?: fallbackBands
    val eventKey = (content as? IslandContent.Event)?.event
    LaunchedEffect(activity?.packageName, activity?.javaClass, eventKey, overlayExpansion) {
        onExpandedChange(false)
    }
    val expandedInRail = expanded && !overlayExpansion
    val reduceMotion = LocalReduceMotion.current
    val bouncy = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntSize>(dampingRatio = .72f, stiffness = 420f)
    val viewConfiguration = androidx.compose.ui.platform.LocalViewConfiguration.current
    val railViewConfiguration = remember(viewConfiguration) {
        object : androidx.compose.ui.platform.ViewConfiguration by viewConfiguration {
            override val longPressTimeoutMillis: Long = 500L
        }
    }
    CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides railViewConfiguration) {
    AnimatedVisibility(content != null, modifier = modifier,
        enter = if (reduceMotion) fadeIn() else expandVertically(bouncy, expandFrom = Alignment.Top, clip = false) +
            scaleIn(androidx.compose.animation.core.spring(dampingRatio = .52f, stiffness = 520f), initialScale = .7f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 0f)) +
            fadeIn(androidx.compose.animation.core.tween(120)),
        exit = if (reduceMotion) fadeOut() else shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
        val shownContent = shown ?: return@AnimatedVisibility
        val current = (shownContent as? IslandContent.Live)?.activity
        val description = describe(shownContent, context.strings())
        val inset = 10.dp
        val outer = if (expandedInRail) 44.dp else width / 2
        val glyph = (width - inset * 2).coerceIn(28.dp, 48.dp)
        val compactHeight = railCompactHeight(width)
        val pressed = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val isPressed by pressed.collectIsPressedAsState()
        val scale by androidx.compose.animation.core.animateFloatAsState(if (isPressed) .94f else 1f,
            androidx.compose.animation.core.spring(dampingRatio = .6f, stiffness = 700f), label = "rail island press")
        val open = {
            if (current != null || (shownContent as? IslandContent.Event)?.event is IslandEvent.Message) {
                onExpandedChange(false); openRailContent(context, shownContent, onOpen)
            } else onExpandedChange(!expanded)
        }
        Column(Modifier.width(if (expandedInRail && current !is IslandActivity.Media) 300.dp else width).graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(8.dp, SquircleCornerShape(outer), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(SquircleCornerShape(outer)).background(Color.Black)
            // Grows and shrinks along the rail with the same spring as the island.
            .animateContentSize(if (reduceMotion) androidx.compose.animation.core.snap() else bouncy)
            .then(if (expandedInRail) Modifier else Modifier.height(compactHeight))
            .combinedClickable(interactionSource = pressed, indication = null,
                onClickLabel = description, onClick = open,
                onLongClickLabel = "Expand",
                onLongClick = { onExpandedChange(true) })
            .padding(inset).testTag("rail-live-activity")
            .semantics { contentDescription = description },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AnimatedContent(shownContent, Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
                contentKey = { it.railTransitionKey() }, label = "rail activity content",
                transitionSpec = {
                    if (reduceMotion) fadeIn() togetherWith fadeOut()
                    else (fadeIn(androidx.compose.animation.core.tween(320)) + scaleIn(
                        androidx.compose.animation.core.spring(dampingRatio = .55f, stiffness = 180f), initialScale = .78f)) togetherWith
                        (fadeOut(androidx.compose.animation.core.tween(220)) + scaleOut(
                            androidx.compose.animation.core.tween(280), targetScale = .92f))
                }) { displayed ->
                if (displayed.isProtectedContent()) return@AnimatedContent
                val current = (displayed as? IslandContent.Live)?.activity
                val mediaBands = (displayed as? IslandContent.Live)?.waveformBands ?: waveformBands
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FolioSpace.SMALL.dp)) {
                    if (expandedInRail && current !is IslandActivity.Media) ExpandedRailContent(displayed,
                        onClose = { onExpandedChange(false) }, onOpen = onOpen)
                    else when (current) {
                        is IslandActivity.Media -> {
                            val accent = if (LocalTintOptions.current.media)
                                rememberAccent(current.art)?.let { mixColor(it, Color.White, .25f) } ?: IslandGreen else IslandGreen
                            if (expandedInRail) Box(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), contentAlignment = Alignment.TopEnd) {
                                Bars(false, accent, mediaBands)
                            }
                            // The capsule handles both taps and long presses, including those on the artwork.
                            (current.art ?: current.icon)?.let {
                                androidx.compose.foundation.Image(it.asImageBitmap(), current.title,
                                    Modifier.size(glyph).clip(RoundedCornerShape((outer - inset).coerceAtMost(glyph * .3f))),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            }
                            if (!expandedInRail) Box(Modifier.padding(bottom = FolioSpace.HAIR.dp).graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }) { Bars(false, accent, mediaBands) }
                            else RailNowPlaying(current, accent, glyph)
                        }
                        // The island's row layout (icon + timer) is too wide for the rail: stack it.
                        is IslandActivity.Call -> {
                            if (current.incoming) LeadingGlyph(IslandContent.Live(current), glyph)
                            else CircleGlyph(Icons.Rounded.Call, IslandGreen, glyph)
                            if (!current.incoming) Chronometer(remember(current.key) { current.since ?: System.currentTimeMillis() }, false, IslandGreen, 12.sp)
                        }
                        is IslandActivity.Navigation -> {
                            LeadingGlyph(IslandContent.Live(current), glyph)
                            Text(current.subtitle ?: current.title, color = Color.White, fontSize = 10.sp, maxLines = 2, lineHeight = 12.sp,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                        is IslandActivity.Timer -> {
                            LeadingGlyph(IslandContent.Live(current), glyph)
                            Chronometer(current.base, current.countDown, IslandOrange, 12.sp)
                        }
                        is IslandActivity.Progress -> {
                            LeadingGlyph(IslandContent.Live(current), glyph)
                            Ring(current.fraction, 20.dp)
                        }
                        null -> {
                            LeadingGlyph(displayed, glyph, compact = true)
                            when (val event = (displayed as? IslandContent.Event)?.event) {
                                is IslandEvent.Message -> Text(event.sender, color = Color.White, fontSize = 10.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                is IslandEvent.Notice -> Text(event.text, color = Color.White, fontSize = 10.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                else -> TrailingGlyph(displayed, glyph * .6f)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Measured separately from the rail so expansion never changes the dock's position. */
@Composable
internal fun RailActivityOverlay(content: IslandContent?, expanded: Boolean,
    anchor: androidx.compose.ui.geometry.Rect, leftHanded: Boolean,
    gridTop: androidx.compose.ui.unit.Dp, gridOuterInset: androidx.compose.ui.unit.Dp,
    onCollapse: () -> Unit, onBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    var shown by remember { mutableStateOf(content) }
    if (content != null) shown = content
    if (shown?.isProtectedContent() == true) shown = null
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    DisposableEffect(Unit) { onDispose { onBounds(androidx.compose.ui.geometry.Rect.Zero) } }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        // Cover the grid and status rail, ending at the collapsed island's bottom edge.
        val left = (if (leftHanded) with(density) { (anchor.left - origin.x).toDp() }
            else gridOuterInset).coerceIn(0.dp, maxWidth)
        val right = (if (leftHanded) maxWidth - gridOuterInset
            else with(density) { (anchor.right - origin.x).toDp() }).coerceIn(left, maxWidth)
        val top = gridTop.coerceIn(0.dp, maxHeight)
        val bottom = with(density) { (anchor.bottom - origin.y).toDp() }.coerceIn(top, maxHeight)
        val cardWidth = (right - left).coerceAtLeast(1.dp)
        val cardHeight = (bottom - top).coerceAtLeast(1.dp)
        val growthOrigin = if (leftHanded) androidx.compose.ui.AbsoluteAlignment.BottomLeft
            else androidx.compose.ui.AbsoluteAlignment.BottomRight
        val collapsedSize: (androidx.compose.ui.unit.IntSize) -> androidx.compose.ui.unit.IntSize = { full ->
            androidx.compose.ui.unit.IntSize(anchor.width.toInt().coerceIn(0, full.width),
                anchor.height.toInt().coerceIn(0, full.height))
        }
        // Keep the animated layout's bottom rail corner stationary as its measured size changes.
        Box(Modifier.absoluteOffset(left, top).size(cardWidth, cardHeight), contentAlignment = growthOrigin) {
        AnimatedVisibility(expanded && content != null && anchor != androidx.compose.ui.geometry.Rect.Zero,
            modifier = Modifier
                .onGloballyPositioned { onBounds(it.boundsInRoot()) },
            enter = if (reduceMotion) fadeIn() else androidx.compose.animation.expandIn(
                expandFrom = growthOrigin, initialSize = collapsedSize) + fadeIn(),
            exit = if (reduceMotion) fadeOut() else androidx.compose.animation.shrinkOut(
                shrinkTowards = growthOrigin, targetSize = collapsedSize) + fadeOut()) {
            val current = shown ?: return@AnimatedVisibility
            Box(Modifier.size(cardWidth, cardHeight)
                .shadow(12.dp, SquircleCornerShape(44.dp))
                .clip(SquircleCornerShape(44.dp)).background(Color.Black)
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { }
                .padding(20.dp)
                .verticalScroll(rememberScrollState()).testTag("rail-activity-overlay"),
                contentAlignment = Alignment.Center) {
                ExpandedRailContent(current, onClose = onCollapse)
            }
        }
        }
    }
}

/** The expanded rail's Now Playing: scrolling title and artist, playback position, and controls stacked down the rail. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun RailNowPlaying(media: IslandActivity.Media, accent: Color, width: androidx.compose.ui.unit.Dp) {
    val controller = runCatching { media.controller }.getOrNull()
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpace.TINY.dp)) {
        // Too narrow for a full title: it scrolls, like a marquee on the island.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpace.HAIR.dp)) {
            Text(media.title, color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 900))
            media.subtitle?.let { Text(it, color = Color.White.copy(alpha = .6f), fontSize = 10.sp, maxLines = 1,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1400)) }
        }
        MediaPlaybackProgress(media, accent, showTimes = false)
        val controls = controller?.transportControls
        RailControl(Icons.Rounded.FastRewind, "Previous", 22.dp) { controls?.skipToPrevious() }
        RailControl(if (media.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (media.playing) "Pause" else "Play", 32.dp) {
            if (media.playing) controls?.pause() else controls?.play()
        }
        RailControl(Icons.Rounded.FastForward, "Next", 22.dp) { controls?.skipToNext() }
    }
}

/** Playback timing comes from the media session; streams without a duration keep an unfilled track. */
@Composable
internal fun MediaPlaybackProgress(media: IslandActivity.Media, accent: Color = IslandGreen, showTimes: Boolean = true) {
    val controller = runCatching { media.controller }.getOrNull()
    var now by remember(controller) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(controller, media.playing) {
        now = android.os.SystemClock.elapsedRealtime()
        while (media.playing) {
            kotlinx.coroutines.delay(500)
            now = android.os.SystemClock.elapsedRealtime()
        }
    }
    val playback = controller?.playbackState
    val duration = controller?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
    val position = playback?.takeIf { it.position >= 0 }?.let { p ->
        val elapsed = if (p.state == android.media.session.PlaybackState.STATE_PLAYING && p.lastPositionUpdateTime > 0)
            ((now - p.lastPositionUpdateTime).coerceAtLeast(0) * p.playbackSpeed).toLong() else 0L
        (p.position + elapsed).coerceIn(0L, duration ?: Long.MAX_VALUE)
    }
    if (!showTimes && (duration == null || position == null)) return
    val fraction = if (duration != null && position != null) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val elapsedLabel = position?.let(::formatClock) ?: "—"
    val durationLabel = duration?.let(::formatClock) ?: "—"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(if (showTimes) 6.dp else 4.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = .22f))
            .semantics { contentDescription = "$elapsedLabel / $durationLabel" }) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(accent))
        }
        if (showTimes) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(elapsedLabel, color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
            Text(durationLabel, color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun RailControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(width = 48.dp, height = 40.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size))
    }
}

private fun formatClock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
