package com.mccal.folio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * One widget being resized on Home: which slot the handles are on, how many cells it covers while the drag is
 * going on, what the widget itself will allow, and the pitches that turn a finger's travel into whole cells.
 *
 * These were eight separate pieces of state in [LauncherScreen], set together and cleared together. A resize never
 * outlived the activity — [slot] was only ever `remember`ed — so gathering the rest behind it changes nothing about
 * when a half-finished resize disappears.
 */
@Stable
internal class WidgetResize {
    /** The placement slot under the handles, or null when no widget is being resized. */
    var slot by mutableStateOf<Int?>(null)
        private set

    /** What the widget itself allows; null for a widget that hasn't said. */
    var constraints by mutableStateOf<WidgetSpanConstraints?>(null)
        private set

    /** Cells the widget covers as the drag goes on, before anything is committed. */
    var width by mutableIntStateOf(1)
    var height by mutableIntStateOf(1)

    /** A cell's width and a row's height in pixels, plus the two pitches the top rows and the app rows use. */
    var pitchX by mutableFloatStateOf(1f)
    var pitchY by mutableFloatStateOf(1f)
    var topPitch by mutableFloatStateOf(1f)
    var appPitch by mutableFloatStateOf(1f)

    val active: Boolean get() = slot != null

    fun start(slot: Int, width: Int, height: Int, constraints: WidgetSpanConstraints?) {
        this.slot = slot
        this.width = width
        this.height = height
        this.constraints = constraints
    }

    fun stop() {
        slot = null
    }
}

@Composable
internal fun rememberWidgetResize(): WidgetResize = remember { WidgetResize() }

@Composable
internal fun WidgetResizeHandle(valid: Boolean, dragging: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).materialBackground(CircleShape, tint = if (valid) FolioGlass.panel else FolioColors.Red)
        .background(Color.Black.copy(alpha = if (dragging) .22f else 0f), CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.OpenInFull, stringResource(R.string.drag_to_resize_widget),
            tint = FolioGlass.ink, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun WidgetEditActions(
    enabled: Boolean, onApply: () -> Unit, onCancel: () -> Unit, applyLabel: String,
    modifier: Modifier = Modifier, applyModifier: Modifier = Modifier, cancelModifier: Modifier = Modifier,
) {
    val colors = ButtonDefaults.textButtonColors(contentColor = FolioGlass.ink,
        disabledContentColor = FolioGlass.ink.copy(alpha = .38f))
    Row(modifier.materialBackground(RoundedCornerShape(FolioRadius.GROUPED_CARD.dp), tint = FolioGlass.panel),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = enabled, onClick = onApply, modifier = applyModifier, colors = colors) { Text(applyLabel) }
        TextButton(onClick = onCancel, modifier = cancelModifier, colors = colors) { Text(stringResource(R.string.cancel)) }
    }
}

/** Shared on-canvas resize controls for Home and Today widgets. */
@Composable
internal fun WidgetResizeFrame(
    id: Int, valid: Boolean, modifier: Modifier = Modifier, message: String? = null,
    onDragStart: () -> Unit, onDrag: (Offset) -> Unit, onCancel: () -> Unit, onApply: () -> Unit,
) {
    var dragging by remember(id) { mutableStateOf(false) }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    Box(modifier.testTag("widget-resize-preview-$id")) {
        Box(Modifier.matchParentSize()
            .border(3.dp, if (valid) Color.White else Color(0xFFFF6B6B), RoundedCornerShape(FolioRadius.PANEL.dp)))
        WidgetResizeHandle(valid, dragging, Modifier.align(Alignment.BottomEnd).zIndex(1f).offset(12.dp, 12.dp)
            .testTag("widget-resize-handle-$id")
            .pointerInput(id) {
                detectDragGestures(onDragStart = { dragging = true; start() },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                    onDrag = { change, amount -> change.consume(); move(amount) })
            })
        WidgetEditActions(valid, onApply, onCancel, stringResource(R.string.apply),
            Modifier.align(Alignment.TopCenter).padding(top = FolioSpace.SMALL.dp))
        if (message != null) Text(message, color = Color.White, modifier = Modifier.align(Alignment.Center)
            .clip(RoundedCornerShape(FolioRadius.CARD.dp)).background(Color.Black.copy(alpha = .65f))
            .padding(horizontal = FolioSpace.COMFY.dp, vertical = FolioSpace.COMPACT.dp))
    }
}
