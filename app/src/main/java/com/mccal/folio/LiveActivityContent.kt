package com.mccal.folio

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import kotlinx.coroutines.flow.first
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the compact pill currently shows: a live activity, or a brief system event on top of it. */
internal sealed interface IslandContent {
    data class Live(val activity: IslandActivity, val waveformBands: State<List<Float>>? = null) : IslandContent
    data class Event(val event: IslandEvent) : IslandContent
}

internal fun IslandContent.isProtectedContent(): Boolean = when (this) {
    is IslandContent.Live -> AppSecurity.isProtected(activity.packageName)
    is IslandContent.Event -> (event as? IslandEvent.Message)?.let { AppSecurity.isProtected(it.packageName) } == true
}

/** Brief events take the live activity's place, then restore it after their display time. */
@Composable
internal fun rememberRailContent(enabled: Boolean, eventsOff: Set<String>, holdEvent: Boolean): IslandContent? {
    val activity by IslandListenerService.activity.collectAsStateWithLifecycle()
    val eventPair by IslandEvents.latest.collectAsStateWithLifecycle()
    val securityRevision by AppSecurity.revision.collectAsStateWithLifecycle()
    val foreground by FolioForeground.visible.collectAsStateWithLifecycle()
    val held by rememberUpdatedState(holdEvent)
    var eventVisible by remember { mutableStateOf<IslandEvent?>(null) }
    DisposableEffect(enabled, foreground) {
        val receivesNotices = enabled && foreground
        if (receivesNotices) IslandEvents.noticeIslands++
        onDispose { if (receivesNotices) IslandEvents.noticeIslands-- }
    }
    LaunchedEffect(eventPair, enabled, foreground, eventsOff, securityRevision) {
        val prior = eventVisible
        if (!enabled || !foreground || eventPair == null || (prior != null && prior.kind in eventsOff) ||
            prior?.let { IslandContent.Event(it).isProtectedContent() } == true) eventVisible = null
        if (!enabled || !foreground || eventPair == null) return@LaunchedEffect
        if (held && eventVisible != null) snapshotFlow { held }.first { !it }
        eventVisible = null
        val (event, at) = eventPair ?: return@LaunchedEffect
        if (event.kind in eventsOff || IslandContent.Event(event).isProtectedContent()) return@LaunchedEffect
        val remaining = IslandEvents.showMs(event) - (System.currentTimeMillis() - at)
        if (remaining <= 0) return@LaunchedEffect
        eventVisible = event
        delay(remaining)
        snapshotFlow { held }.first { !it }
        eventVisible = null
    }
    val media = activity as? IslandActivity.Media
    val waveformBands = rememberAudioBands(media, enabled && foreground)
    if (!enabled || !foreground) return null
    return (eventVisible?.takeUnless { it.kind in eventsOff }?.let { IslandContent.Event(it) }
        ?: activity?.takeUnless { it is IslandActivity.Call && "CALL" in eventsOff }?.let { live ->
            IslandContent.Live(live, waveformBands.takeIf { live is IslandActivity.Media })
        })
        ?.takeUnless { it.isProtectedContent() }
}

internal fun openRailContent(context: android.content.Context, content: IslandContent, onOpen: () -> Unit = {}) {
    if (content.isProtectedContent()) return
    when (content) {
        is IslandContent.Live -> { onOpen(); IslandListenerService.open(context, content.activity) }
        is IslandContent.Event -> (content.event as? IslandEvent.Message)?.let {
            onOpen(); IslandListenerService.openKey(context, it.key, it.packageName)
        }
    }
}

@Composable
internal fun ExpandedRailContent(content: IslandContent, onClose: () -> Unit, onOpen: () -> Unit = {}) {
    if (content.isProtectedContent()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    when (content) {
        is IslandContent.Live -> ExpandedCardContent(content.activity, contentPadding = PaddingValues(0.dp),
            showMediaProgress = true, mediaBands = content.waveformBands) {
            onClose(); openRailContent(context, content, onOpen)
        }
        is IslandContent.Event -> when (val event = content.event) {
            is IslandEvent.Message -> {
                var replying by remember(event.key) { mutableStateOf(false) }
                androidx.activity.compose.BackHandler(replying) { replying = false }
                MessageCardContent(event, replying, onReply = { replying = true },
                    onOpen = { onClose(); openRailContent(context, content, onOpen) },
                    onDone = { replying = false; onClose(); IslandEvents.dismiss() },
                    onDismiss = { onClose(); IslandEvents.dismiss() })
            }
            is IslandEvent.Notice -> NoticeCardContent(event) { onClose(); IslandEvents.dismiss() }
            else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LeadingGlyph(content, 44.dp)
                Text(describe(content, context.strings()), color = Color.White, fontSize = FolioType.BODY.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(stringResource(R.string.done), color = Color.White, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable { onClose(); IslandEvents.dismiss() }.wrapContentHeight(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** What TalkBack reads for the island. */
internal fun describe(content: IslandContent, strings: Strings): String = when (content) {
    is IslandContent.Event -> when (val e = content.event) {
        is IslandEvent.Charging -> e.level?.let { strings.plural(R.plurals.island_charging_percent, it, it) } ?: strings.get(R.string.charging)
        is IslandEvent.Silent -> strings.get(if (e.on) R.string.silent_mode_on else R.string.silent_mode_off)
        is IslandEvent.Focus -> strings.get(if (e.on) R.string.do_not_disturb_on_2 else R.string.do_not_disturb_off_2)
        is IslandEvent.Bluetooth -> e.name?.let { strings.get(R.string.island_connected_to, it) } ?: strings.get(R.string.connected)
        is IslandEvent.Notice -> e.text
        is IslandEvent.Message -> when {
            e.alert && e.text != null -> strings.get(R.string.island_alert_text, e.appLabel, e.sender, e.text)
            e.alert -> strings.get(R.string.island_alert, e.appLabel, e.sender)
            e.text != null -> strings.get(R.string.island_message_text, e.sender, e.text)
            else -> strings.get(R.string.island_message, e.sender)
        }
    }
    is IslandContent.Live -> strings.get(R.string.island_live_details, content.activity.title)
}

@Composable
internal fun LeadingGlyph(content: IslandContent, size: Dp, compact: Boolean = false) {
    when (content) {
        is IslandContent.Event -> when (val e = content.event) {
            is IslandEvent.Charging -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = IslandGreen, modifier = Modifier.size(size * .8f))
                if (!compact) Text(stringResource(R.string.charging), color = Color.White, fontSize = FolioType.FOOTNOTE.sp, fontWeight = FontWeight.SemiBold)
            }
            is IslandEvent.Silent -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (e.on) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsActive, null,
                    tint = if (e.on) Red else Color.White, modifier = Modifier.size(size * .75f))
                if (!compact) {
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.silent), color = Color.White, fontSize = FolioType.FOOTNOTE.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            is IslandEvent.Focus -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DarkMode, null, tint = Purple, modifier = Modifier.size(size * .75f))
                if (!compact) {
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.focus), color = Color.White, fontSize = FolioType.FOOTNOTE.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            is IslandEvent.Bluetooth -> Icon(if (e.speaker) Icons.Rounded.Speaker else Icons.Rounded.Headphones, null, tint = IslandBlue, modifier = Modifier.size(size * .8f))
            is IslandEvent.Message -> MessageAvatar(e, size)
            is IslandEvent.Notice -> e.appIcon?.let { Image(it.asImageBitmap(), null, Modifier.size(size).clip(RoundedCornerShape(size * .24f))) }
                ?: CircleGlyph(Icons.Rounded.Info, Color.White, size)
        }
        is IslandContent.Live -> when (val a = content.activity) {
            is IslandActivity.Call -> if (a.incoming) CallAvatar(a, size) else Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Call, null, tint = IslandGreen, modifier = Modifier.size(size * .7f))
                Spacer(Modifier.width(4.dp))
                Chronometer(remember(a.key) { a.since ?: System.currentTimeMillis() }, countDown = false, color = IslandGreen)
            }
            is IslandActivity.Timer -> CircleGlyph(Icons.Rounded.Timer, IslandOrange, size)
            is IslandActivity.Navigation -> CircleGlyph(Icons.Rounded.TurnRight, IslandBlue, size)
            else -> ((a as? IslandActivity.Media)?.art ?: a.icon)?.let { Image(it.asImageBitmap(), null, Modifier.size(size).clip(RoundedCornerShape(size * .28f)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
    }
}

@Composable
internal fun TrailingGlyph(content: IslandContent, size: Dp) {
    when (content) {
        is IslandContent.Event -> when (val e = content.event) {
            is IslandEvent.Charging -> Text("${e.level ?: ""}%", color = IslandGreen, fontSize = FolioType.FOOTNOTE.sp, fontWeight = FontWeight.SemiBold)
            is IslandEvent.Silent -> Text(if (e.on) "On" else "Off", color = if (e.on) Red else Color.White.copy(alpha = .7f), fontSize = FolioType.FOOTNOTE.sp, fontWeight = FontWeight.SemiBold)
            is IslandEvent.Focus -> Text(if (e.on) "On" else "Off", color = if (e.on) Purple else Color.White.copy(alpha = .7f), fontSize = FolioType.FOOTNOTE.sp, fontWeight = FontWeight.SemiBold)
            is IslandEvent.Bluetooth -> Text(e.name ?: stringResource(R.string.connected), color = Color.White, fontSize = FolioType.GROUP_LABEL.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The app's icon beside the sender's photo; without a photo the icon is already on the left.
            is IslandEvent.Message -> e.appIcon?.takeIf { e.avatar != null }?.let { Image(it.asImageBitmap(), null, Modifier.size(size * .8f).clip(RoundedCornerShape(size * .22f))) }
            is IslandEvent.Notice -> Unit
        }
        is IslandContent.Live -> when (val a = content.activity) {
            is IslandActivity.Media -> Bars(false, if (LocalTintOptions.current.media) rememberAccent(a.art)?.let { mixColor(it, Color.White, .25f) } ?: IslandGreen else IslandGreen,
                (content as? IslandContent.Live)?.waveformBands ?: rememberAudioBands(a, true))
            is IslandActivity.Progress -> Ring(a.fraction, size)
            is IslandActivity.Call -> Bars(playing = !a.incoming)
            is IslandActivity.Timer -> Chronometer(a.base, a.countDown, IslandOrange)
            is IslandActivity.Navigation -> Text(a.subtitle ?: a.title, color = Color.White, fontSize = FolioType.GROUP_LABEL.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Swipe up to put an island card away, like an iPhone banner: it follows the finger with some resistance and springs
 * back if let go early. The notification itself stays in Notification Center.
 */
private fun Modifier.swipeUpToHide(enabled: Boolean, onHide: () -> Unit): Modifier = composed {
    val pull = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val hideAt = with(LocalDensity.current) { 24.dp.toPx() }
    val latestOnHide by rememberUpdatedState(onHide)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var travel = 0f
        detectVerticalDragGestures(
            onDragStart = { travel = 0f },
            onDragEnd = { if (travel < -hideAt) latestOnHide() else scope.launch { pull.animateTo(0f, spring(dampingRatio = .7f)) } },
            onDragCancel = { scope.launch { pull.animateTo(0f) } },
        ) { change, dy ->
            change.consume(); travel += dy
            scope.launch { pull.snapTo((travel * .5f).coerceIn(-hideAt * 3f, 0f)) }
        }
    }.graphicsLayer { translationY = pull.value; alpha = 1f - (-pull.value / (hideAt * 6f)) }
}

/** Folio's own brief feedback: the app's icon (or an info symbol) and one or two lines. Tap or swipe up to hide. */
@Composable
private fun NoticeCardContent(notice: IslandEvent.Notice, onHide: () -> Unit) {
    Row(Modifier.fillMaxWidth().swipeUpToHide(true, onHide).clickable(onClick = onHide)
        .padding(horizontal = FolioSpace.LARGE.dp, vertical = FolioSpace.MEDIUM.dp), verticalAlignment = Alignment.CenterVertically) {
        notice.appIcon?.let { Image(it.asImageBitmap(), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))) }
            ?: Icon(Icons.Rounded.Info, null, tint = IslandBlue, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(notice.text, color = Color.White, fontSize = FolioType.SUBHEAD.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
    }
}

/** Sender photo (a notification's own picture for other apps), or the app icon when there isn't one. */
@Composable
private fun MessageAvatar(message: IslandEvent.Message, size: Dp) {
    val bitmap = message.avatar ?: message.appIcon
    // People are round, like iOS; other apps' pictures (album covers, photos, logos) keep a rounded square.
    val person = message.avatar != null && !message.alert
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(size).clip(if (person) CircleShape else RoundedCornerShape(size * .24f)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Box(Modifier.size(size).clip(CircleShape).background(FolioColors.MenuSurface), contentAlignment = Alignment.Center) {
        Text(message.sender.take(1).uppercase(), color = Color.White, fontSize = (size.value * .42f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MessageCardContent(message: IslandEvent.Message, replying: Boolean, onReply: () -> Unit, onOpen: () -> Unit, onDone: () -> Unit,
    onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().swipeUpToHide(!replying, onDismiss)
        .padding(start = FolioSpace.LARGE.dp, end = FolioSpace.LARGE.dp, top = FolioSpace.COMFY.dp, bottom = FolioSpace.COMFY.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.COMPACT.dp)) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
            Box {
                MessageAvatar(message, 44.dp)
                if (message.avatar != null) message.appIcon?.let {
                    Image(it.asImageBitmap(), null, Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(20.dp).clip(RoundedCornerShape(5.dp)))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message.sender, color = Color.White, fontSize = FolioType.SUBHEAD.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(message.appLabel, color = Color.White.copy(alpha = .5f), fontSize = FolioType.GROUP_LABEL.sp, maxLines = 1)
                }
                message.text?.let { Text(it, color = Color.White.copy(alpha = .85f), fontSize = 14.sp, maxLines = if (replying) 2 else 3,
                    overflow = TextOverflow.Ellipsis, lineHeight = 18.sp) }
            }
        }
        if (replying) QuickReplyField(message.sender.substringBefore(" · "), onSend = { IslandListenerService.reply(context, message.key, it) }, onDone = onDone)
        else if (message.canReply) Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.SMALL.dp)) {
            MessageActionPill(stringResource(R.string.reply), onReply)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ExpandedCardContent(activity: IslandActivity,
    contentPadding: PaddingValues = PaddingValues(start = 18.dp, end = 18.dp, top = FolioSpace.LARGE.dp, bottom = 18.dp),
    showMediaProgress: Boolean = false, mediaBands: State<List<Float>>? = null, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.COMPACT.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
            if (activity is IslandActivity.Call) CallAvatar(activity, 44.dp)
            else ((activity as? IslandActivity.Media)?.art ?: activity.icon)?.let { Image(it.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(activity.title, color = Color.White, fontSize = if (activity is IslandActivity.Media) 14.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = if (activity is IslandActivity.Media) 1 else 2,
                    overflow = if (activity is IslandActivity.Media) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (activity is IslandActivity.Media) Modifier.fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 900) else Modifier)
                val subtitle = when (activity) {
                    is IslandActivity.Media -> activity.subtitle
                    is IslandActivity.Progress -> activity.subtitle
                    is IslandActivity.Navigation -> activity.subtitle
                    is IslandActivity.Call -> if (activity.incoming) stringResource(R.string.incoming_call) else null
                    else -> null
                }
                subtitle?.let { Text(it, color = Color.White.copy(alpha = .6f), fontSize = FolioType.FOOTNOTE.sp,
                    maxLines = if (activity is IslandActivity.Media) 1 else 2,
                    overflow = if (activity is IslandActivity.Media) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (activity is IslandActivity.Media) Modifier.fillMaxWidth()
                        .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1400) else Modifier) }
            }
            when (activity) {
                is IslandActivity.Media -> Box(Modifier.align(Alignment.Top).padding(start = 12.dp, top = 6.dp, end = 6.dp)) {
                    Bars(false, if (LocalTintOptions.current.media)
                        rememberAccent(activity.art)?.let { mixColor(it, Color.White, .25f) } ?: IslandGreen else IslandGreen,
                        mediaBands ?: rememberAudioBands(activity, true))
                }
                is IslandActivity.Call -> if (!activity.incoming) Chronometer(remember(activity.key) { activity.since ?: System.currentTimeMillis() }, false, IslandGreen, 20.sp)
                is IslandActivity.Timer -> Chronometer(activity.base, activity.countDown, IslandOrange, 20.sp)
                else -> Unit
            }
        }
        if (showMediaProgress && activity is IslandActivity.Media) MediaPlaybackProgress(activity)
        when (activity) {
            is IslandActivity.Media -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val t = activity.controller.transportControls
                Icon(Icons.Rounded.FastRewind, stringResource(R.string.previous), tint = Color.White, modifier = Modifier.minimumInteractiveComponentSize().size(34.dp).clip(CircleShape).clickable { t.skipToPrevious() })
                Icon(if (activity.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(R.string.play_or_pause), tint = Color.White,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable { if (activity.playing) t.pause() else t.play() })
                Icon(Icons.Rounded.FastForward, stringResource(R.string.next), tint = Color.White, modifier = Modifier.minimumInteractiveComponentSize().size(34.dp).clip(CircleShape).clickable { t.skipToNext() })
            }
            is IslandActivity.Progress -> activity.fraction?.let { f ->
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .2f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(IslandGreen))
                }
            }
            is IslandActivity.Call -> CallButtons(activity)
            else -> Unit
        }
    }
}

/** Caller photo, or the calling app's icon in a circle. */
@Composable
private fun CallAvatar(call: IslandActivity.Call, size: Dp) {
    val bitmap = call.avatar ?: call.icon
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(size).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else CircleGlyph(Icons.Rounded.Call, IslandGreen, size)
}

/** iPhone call controls: Decline/Accept while ringing; Mute, End and Speaker during the call. */
@Composable
private fun CallButtons(call: IslandActivity.Call) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun act(kind: CallControls.Kind) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm); IslandListenerService.callAction(context, call.key, kind) }
    Row(Modifier.fillMaxWidth().padding(top = FolioSpace.TINY.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (call.incoming) {
            if (call.canDecline) CallButton(Icons.Rounded.CallEnd, stringResource(R.string.decline), Red) { act(CallControls.Kind.DECLINE) }
            if (call.canAnswer) CallButton(Icons.Rounded.Call, stringResource(R.string.accept), IslandGreen) { act(CallControls.Kind.ANSWER) }
        } else {
            if (call.canMute) CallButton(Icons.Rounded.MicOff, stringResource(R.string.mute), Color.White.copy(alpha = .22f)) { act(CallControls.Kind.MUTE) }
            if (call.canHangUp) CallButton(Icons.Rounded.CallEnd, "End", Red) { act(CallControls.Kind.HANG_UP) }
            if (call.canSpeaker) CallButton(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.speaker), Color.White.copy(alpha = .22f)) { act(CallControls.Kind.SPEAKER) }
            if (!call.canHangUp && !call.canMute && !call.canSpeaker) Text(stringResource(R.string.tap_to_return_to_the_call), color = Color.White.copy(alpha = .6f), fontSize = FolioType.FOOTNOTE.sp)
        }
    }
}

@Composable
private fun CallButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(color).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(label, color = Color.White.copy(alpha = .7f), fontSize = 11.sp, modifier = Modifier.padding(top = FolioSpace.TINY.dp))
    }
}

@Composable
internal fun CircleGlyph(icon: ImageVector, color: Color, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * .62f))
    }
}

@Composable
internal fun Chronometer(base: Long, countDown: Boolean, color: Color, fontSize: androidx.compose.ui.unit.TextUnit = 13.sp) {
    val now by rememberSecondTick()
    val seconds = ((if (countDown) base - now else now - base) / 1000).coerceAtLeast(0)
    val text = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    Text(text, color = color, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
}

@Composable
internal fun rememberBarsPhase(playing: Boolean, key: Any? = Unit): State<Float> {
    // Before the first playback, keep the original four short idle lines.
    val phase = remember(key) { Animatable(-1f) }
    val animate = playing && !LocalReduceMotion.current
    LaunchedEffect(phase, animate) {
        // Cancelling on pause keeps the last frame; resume completes the remaining cycle.
        if (animate && phase.value < 0f) phase.snapTo(0f)
        while (animate) {
            phase.animateTo(1f, tween(((1f - phase.value) * 900).roundToInt().coerceAtLeast(1), easing = LinearEasing))
            phase.snapTo(0f)
        }
    }
    return phase.asState()
}

@Composable
internal fun Bars(playing: Boolean, color: Color = IslandGreen, bands: State<List<Float>>? = null) {
    val currentPhase = rememberBarsPhase(playing && bands == null)
    Canvas(Modifier.size(width = 20.dp, height = 14.dp)) {
        val w = size.width / 7
        val position = currentPhase.value
        for (i in 0 until 4) {
            val wave = bands?.value?.getOrNull(i)?.coerceIn(0f, 1f) ?: if (position < 0f) .15f
                else (kotlin.math.sin(position * 2 * Math.PI + i * 1.3).toFloat() + 1f) / 2f
            // Rounded stroke caps also need room inside the canvas at the tallest frame.
            val h = (size.height - w).coerceAtLeast(0f) * (.3f + .7f * wave)
            drawLine(color, Offset(w * (1 + i * 1.7f), size.height / 2 + h / 2), Offset(w * (1 + i * 1.7f), size.height / 2 - h / 2),
                strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun Ring(fraction: Float?, size: Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.width * .14f
        val arc = Size(this.size.width - stroke, this.size.height - stroke)
        val o = Offset(stroke / 2, stroke / 2)
        drawArc(Color.White.copy(alpha = .2f), 0f, 360f, false, o, arc, style = Stroke(stroke))
        drawArc(IslandGreen, -90f, 360f * (fraction ?: .25f), false, o, arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

internal val IslandGreen = FolioColors.Green
internal val IslandOrange = FolioColors.Orange
private val Red = FolioColors.Red
private val Purple = FolioColors.Indigo
internal val IslandBlue = FolioColors.Blue
