@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mccal.folio

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.material3.rememberModalBottomSheetState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Today View, the page left of Home: search, the date, app suggestions and Home-sized widget spans.
 */
@Composable
internal fun TodayView(state: LauncherState, widgets: WidgetController, modifier: Modifier = Modifier,
    onSearch: () -> Unit, onLaunch: (AppEntry) -> Unit, onAddWidget: (WidgetGridSizing) -> Unit,
    onReplaceWidget: (Int, WidgetGridSizing) -> Unit,
    onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit, onResize: (Int, WidgetSpan) -> Unit,
    active: Boolean = true, homeRequests: Int = 0, topPadding: Dp = FolioSpace.MEDIUM.dp,
    searchTopInRoot: Float? = null,
    onWidgetGesture: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val edit = remember { HomeEditMode() }
    var selectedWidget by remember { mutableStateOf<Int?>(null) }
    val resize = rememberWidgetResize()
    var dragging by remember { mutableStateOf<TodayWidgetDrag?>(null) }
    var widgetHeld by remember { mutableStateOf(false) }
    val reportGesture by rememberUpdatedState(onWidgetGesture)
    SideEffect { reportGesture(widgetHeld || resize.active) }
    DisposableEffect(Unit) { onDispose { reportGesture(false) } }
    LaunchedEffect(active, homeRequests) {
        edit.stop(); selectedWidget = null; resize.stop(); dragging = null; widgetHeld = false
        reportGesture(false)
    }
    LaunchedEffect(edit.active) { if (!edit.active) { resize.stop(); dragging = null } }
    androidx.activity.compose.BackHandler(edit.active) { edit.stop() }
    androidx.activity.compose.BackHandler(resize.active) { resize.stop() }
    val tick by rememberMinuteTick()
    val today = remember(tick) { LocalDate.now() }
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMd"), locale)
    }
    val apps = remember(state.apps, state.hiddenApps, state.appSecurity) {
        state.apps.filter { it.id !in state.hiddenApps && !AppSecurity.isProtected(it, state.appSecurity) }
    }
    val suggestions by produceState(emptyList<AppEntry>(), apps) {
        value = withContext(Dispatchers.IO) {
            Suggestions.forNow(context, apps)
        }
    }

    var topInRoot by remember { mutableStateOf<Float?>(null) }
    ProvideJiggle(edit) {
        BoxWithConstraints(modifier.testTag("today-view").onGloballyPositioned { topInRoot = it.positionInRoot().y }) {
            val wide = maxWidth > TODAY_TWO_COLUMN_MIN_WIDTH_DP.dp
            val columnsWidth = minOf(maxWidth - 32.dp, 390.dp)
            val gap = 14.dp
            val cell = (columnsWidth - gap * (GRID_COLUMNS - 1)) / GRID_COLUMNS
            val grid = remember(cell, gap) { WidgetGridSizing(GRID_COLUMNS, GRID_ROWS,
                (cell + gap).value, (cell + gap).value, horizontalGapDp = gap.value, verticalGapDp = gap.value) }
            val resolvedWidgets = remember(state.todayWidgets, widgets, grid) {
                state.todayWidgets.map { widget ->
                    // Old layouts stored only Small/Medium/Large. Recover the provider's actual footprint.
                    val span = widget.span ?: if (widget.id >= 0) runCatching {
                        widgets.manager.getAppWidgetInfo(widget.id)?.let { widgets.sizing(it, grid)?.preferred }
                    }.getOrNull() else null
                    widget.copy(span = span)
                }
            }
            val density = LocalDensity.current
            val alignedTopPadding = searchTopInRoot?.let { clockTop ->
                topInRoot?.let { viewTop -> with(density) { (clockTop - viewTop).toDp().coerceAtLeast(0.dp) } }
            } ?: topPadding
            val pitch = with(density) { (cell + gap).toPx() }
            var gridOrigin by remember { mutableStateOf(Offset.Zero) }
            val savedPlacements = remember(resolvedWidgets) { todayPlacements(resolvedWidgets) }
            val moving = dragging
            val hoverId = moving?.let { drag ->
                val point = drag.pointerStart + drag.offset - gridOrigin
                savedPlacements.firstOrNull { it.widget.id != drag.id &&
                    Rect(it.column * pitch, it.row * pitch,
                        (it.column + it.span.width) * pitch, (it.row + it.span.height) * pitch).contains(point) }?.widget?.id
            }
            LaunchedEffect(moving, hoverId) {
                if (moving != null && hoverId != null) { delay(500); moving.targetId = hoverId }
            }
            val orderedWidgets = moving?.let { drag -> drag.targetId?.let { target ->
                val from = resolvedWidgets.indexOfFirst { it.id == drag.id }
                val to = resolvedWidgets.indexOfFirst { it.id == target }
                if (from >= 0 && to >= 0) TodayWidgets.move(resolvedWidgets, drag.id, to - from) else resolvedWidgets
            } } ?: resolvedWidgets
            val placements = todayPlacements(orderedWidgets.map {
                if (it.id == resize.slot) it.copy(span = WidgetSpan(resize.width, resize.height)) else it
            })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = !widgetHeld && moving == null && !resize.active).padding(horizontal = FolioSpace.LARGE.dp).padding(top = alignedTopPadding, bottom = 96.dp), // clear of Home's page dots
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.width(columnsWidth), verticalArrangement = Arrangement.spacedBy(gap)) {
                    // Search capsule
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).materialBackground(RoundedCornerShape(18.dp), tint = FolioGlass.panel)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = stringResource(R.string.search), onClick = onSearch)
                        .heightIn(min = 52.dp).padding(start = FolioSpace.COMFY.dp, end = FolioSpace.TINY.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, tint = FolioGlass.ink.copy(alpha = .75f), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.search), color = FolioGlass.ink.copy(alpha = .55f), fontSize = 18.sp)
                    }
                    Column(Modifier.padding(start = FolioSpace.TINY.dp, top = FolioSpace.SNUG.dp)) {
                        Text(today.format(DateTimeFormatter.ofPattern("EEEE")).uppercase(), color = FolioColors.Red, fontSize = FolioType.FOOTNOTE.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp)
                        Text(today.format(dateFormatter), color = LocalHomeInk.current.primary, fontSize = if (wide) 40.sp else 34.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    val visibleSuggestions = suggestions.filterNot { AppSecurity.isProtected(it, state.appSecurity) }
                    if (visibleSuggestions.isNotEmpty()) TodaySuggestions(visibleSuggestions, 4, onLaunch, editing = edit.active)

                    val gridRows = placements.maxOfOrNull { it.row + it.span.height } ?: 0
                    if (gridRows > 0) Box(Modifier.fillMaxWidth().height((cell + gap) * gridRows - gap)
                        .onGloballyPositioned { gridOrigin = it.positionInRoot() }) {
                        placements.forEach { placement ->
                            val widget = placement.widget
                            key(widget.id) {
                                val movingTile = moving?.takeIf { it.id == widget.id }
                                TodayWidgetTile(widget, widgets, (cell + gap) * placement.span.width - gap,
                                    (cell + gap) * placement.span.height - gap, edit,
                                    inputEnabled = active && !resize.active,
                                    onGestureOwnership = { held ->
                                        widgetHeld = held
                                        reportGesture(held || resize.active)
                                    },
                                    onRemove = { onRemove(widget.id) },
                                    onEdit = { selectedWidget = widget.id },
                                    onDragStart = { point ->
                                        edit.start()
                                        dragging = TodayWidgetDrag(widget.id, Offset(placement.column * pitch, placement.row * pitch), point)
                                    },
                                    onDrag = { point -> dragging?.let { it.offset = point - it.pointerStart } },
                                    onDragEnd = { cancelled ->
                                        val current = dragging
                                        if (!cancelled && current != null) {
                                            val from = state.todayWidgets.indexOfFirst { it.id == current.id }
                                            val to = state.todayWidgets.indexOfFirst { it.id == current.targetId }
                                            if (from >= 0 && to >= 0 && from != to) onMove(current.id, to - from)
                                        }
                                        dragging = null
                                    },
                                    modifier = Modifier.offset(x = (cell + gap) * placement.column, y = (cell + gap) * placement.row)
                                        .zIndex(if (movingTile != null) 1f else 0f)
                                        .graphicsLayer {
                                            translationX = if (movingTile != null) movingTile.origin.x + movingTile.offset.x - placement.column * pitch else 0f
                                            translationY = if (movingTile != null) movingTile.origin.y + movingTile.offset.y - placement.row * pitch else 0f
                                        }.then(if (movingTile != null) Modifier.border(3.dp, Color.White,
                                            RoundedCornerShape(FolioRadius.PANEL.dp)) else Modifier))
                            }
                        }
                        placements.firstOrNull { it.widget.id == resize.slot }?.let { placement ->
                            val limits = resize.constraints
                            val minW = limits?.minimum?.width ?: 2
                            val minH = limits?.minimum?.height ?: 2
                            val maxW = minOf(GRID_COLUMNS, limits?.maximum?.width ?: GRID_COLUMNS)
                            val maxH = minOf(GRID_ROWS, limits?.maximum?.height ?: GRID_ROWS)
                            val valid = minW <= maxW && minH <= maxH && (placement.widget.id < 0 || limits?.minimumFitsGrid == true) &&
                                resize.width in minW..maxW && resize.height in minH..maxH
                            var delta by remember(placement.widget.id) { mutableStateOf(Offset.Zero) }
                            var start by remember(placement.widget.id) { mutableStateOf(WidgetSpan(resize.width, resize.height)) }
                            WidgetResizeFrame(placement.widget.id, valid,
                                Modifier.zIndex(2f).offset(x = (cell + gap) * placement.column, y = (cell + gap) * placement.row)
                                    .size((cell + gap) * resize.width - gap, (cell + gap) * resize.height - gap),
                                onDragStart = { delta = Offset.Zero; start = WidgetSpan(resize.width, resize.height) },
                                onDrag = { amount ->
                                    delta += amount
                                    if (minW <= maxW && limits?.canResizeHorizontally != false)
                                        resize.width = (start.width + (delta.x / pitch).roundToInt()).coerceIn(minW, maxW)
                                    if (minH <= maxH && limits?.canResizeVertically != false)
                                        resize.height = (start.height + (delta.y / pitch).roundToInt()).coerceIn(minH, maxH)
                                }, onCancel = resize::stop,
                                onApply = { onResize(placement.widget.id, WidgetSpan(resize.width, resize.height)); resize.stop() })
                        }
                    }
                    if (state.todayWidgets.isEmpty()) Text(stringResource(R.string.add_widgets_for_the_things_you_check_mos), color = LocalHomeInk.current.secondary,
                        fontSize = FolioType.SUBHEAD.sp, modifier = Modifier.padding(FolioSpace.TINY.dp))

                    // Edit / Add / Done, like the bottom of iOS's Today View
                    Row(Modifier.fillMaxWidth().padding(top = FolioSpace.SNUG.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (edit.active) {
                            JigglePill(stringResource(R.string.add_widget_2), Icons.Rounded.Add, description = stringResource(R.string.add_widget)) { onAddWidget(grid) }
                            Spacer(Modifier.width(12.dp))
                            JigglePill(stringResource(R.string.done), emphasized = true) { edit.stop() }
                        } else JigglePill(stringResource(R.string.edit)) { edit.start() }
                    }
                }
            }
            savedPlacements.firstOrNull { it.widget.id == selectedWidget }?.let { placement ->
                val id = placement.widget.id
                val info = widgets.rememberInfo(id)
                val constraints = remember(info, grid) { info?.let { widgets.sizing(it, grid) } }
                ModalBottomSheet(onDismissRequest = { selectedWidget = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    // Today repacks an unbounded scrolling grid, so sizing is independent of its current row.
                    WidgetActions(WidgetPlacement(id, id, 0, 0, 0, placement.span.width, placement.span.height), constraints,
                        canConfigure = widgets.canReconfigure(id),
                        onConfigure = { selectedWidget = null; widgets.reconfigure(id) },
                        isValid = { w, h -> w in 1..GRID_COLUMNS && h in 1..GRID_ROWS },
                        onResize = { w, h -> onResize(id, WidgetSpan(w, h)) },
                        onStartResize = { w, h -> edit.start(); resize.start(id, w, h, constraints); selectedWidget = null },
                        onMoveToPage = { false }, homePages = 1, onReplace = {
                            selectedWidget = null; resize.stop(); dragging = null; widgetHeld = false
                            onReplaceWidget(id, grid)
                        },
                        onRemove = { onRemove(id); selectedWidget = null }, onClose = { selectedWidget = null })
                }
            }
        }
    }
}

internal data class TodayPlacement(val widget: TodayWidget, val column: Int, val row: Int, val span: WidgetSpan)

/** Pack individual cells in reading order, allowing short widgets beside taller ones. */
internal fun todayPlacements(list: List<TodayWidget>): List<TodayPlacement> {
    val occupied = mutableSetOf<Int>()
    var cursor = 0
    return list.map { widget ->
        val requested = widget.span ?: WidgetSpan(widget.size.columns * 2, widget.size.rows * 2)
        val span = WidgetSpan(requested.width.coerceIn(1, GRID_COLUMNS), requested.height.coerceIn(1, GRID_ROWS))
        fun cells(index: Int) = (0 until span.height).flatMap { y ->
            (0 until span.width).map { x -> index + y * GRID_COLUMNS + x }
        }
        while (cursor % GRID_COLUMNS + span.width > GRID_COLUMNS || cells(cursor).any { it in occupied }) cursor++
        val index = cursor++
        occupied += cells(index)
        TodayPlacement(widget, index % GRID_COLUMNS, index / GRID_COLUMNS, span)
    }
}

@Composable
private fun TodaySuggestions(apps: List<AppEntry>, columns: Int, onLaunch: (AppEntry) -> Unit, editing: Boolean = false) {
    SpotlightSectionCard(stringResource(R.string.suggestions)) {
        Row(Modifier.fillMaxWidth()) {
            apps.take(columns).forEach { app ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(FolioRadius.CARD.dp))
                    .then(if (editing) Modifier else Modifier.clickable { onLaunch(app) }).padding(vertical = FolioSpace.SNUG.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (editing) Box(Modifier.size(52.dp).background(FolioGlass.ink.copy(alpha = .12f), RoundedCornerShape(13.dp)))
                    else AppIcon(app, app.label, Modifier.size(52.dp), shape = RoundedCornerShape(13.dp), badge = false)
                    Box(Modifier.fillMaxWidth().padding(top = FolioSpace.TINY.dp, start = FolioSpace.HAIR.dp, end = FolioSpace.HAIR.dp),
                        contentAlignment = Alignment.Center) {
                        Text(if (editing) " " else app.label, color = FolioGlass.ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (editing) Box(Modifier.width(32.dp).height(6.dp)
                            .background(FolioGlass.ink.copy(alpha = .12f), RoundedCornerShape(3.dp)))
                    }
                }
            }
            repeat((columns - apps.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun TodayWidgetTile(widget: TodayWidget, widgets: WidgetController, width: Dp, height: Dp, edit: HomeEditMode,
    inputEnabled: Boolean, onGestureOwnership: (Boolean) -> Unit, onRemove: () -> Unit, onEdit: () -> Unit,
    onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: (Boolean) -> Unit,
    modifier: Modifier = Modifier) {
    val openEditor by rememberUpdatedState(onEdit)
    val beginDrag by rememberUpdatedState(onDragStart)
    val moveDrag by rememberUpdatedState(onDrag)
    val finishDrag by rememberUpdatedState(onDragEnd)
    val enabled by rememberUpdatedState(inputEnabled)
    val ownGesture by rememberUpdatedState(onGestureOwnership)
    var tileCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val editLabel = stringResource(R.string.edit_widget)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(widget.id, width, height) {
        if (widget.id >= 0) runCatching {
            widgets.manager.updateAppWidgetOptions(widget.id, exactWidgetSizeOptions(width.value, height.value))
        }
    }
    Box(modifier.size(width, height).jiggle("today-${widget.id}", .5f)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(FolioRadius.PANEL.dp))
            .onGloballyPositioned { tileCoordinates = it }
            .semantics { onLongClick(editLabel) { if (enabled) { openEditor(); true } else false } }
            .pointerInput(widget.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!enabled) return@awaitEachGesture
                    val startPoint = tileCoordinates?.localToRoot(down.position) ?: return@awaitEachGesture
                    var moved = false
                    var ownsGesture = false
                    try {
                        if (edit.active) {
                            // In edit mode the next movement belongs to this widget, including before slop.
                            ownGesture(true); ownsGesture = true
                            moved = awaitSlopOrLongPress(down) ?: return@awaitEachGesture
                        } else {
                            awaitWidgetLongPressOrCancellation(down) ?: return@awaitEachGesture
                            ownGesture(true); ownsGesture = true
                        }
                        currentEvent.changes.forEach { it.consume() }
                        haptic.perform(FolioHaptic.PickedUp)
                        if (moved) beginDrag(startPoint)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || event.changes.any { it.id != down.id && it.pressed }) {
                                if (moved) finishDrag(true)
                                break
                            }
                            val point = tileCoordinates?.takeIf { it.isAttached }?.localToRoot(change.position) ?: startPoint
                            if (!moved && (point - startPoint).getDistance() > viewConfiguration.touchSlop) {
                                moved = true; beginDrag(startPoint)
                            }
                            if (moved) moveDrag(point)
                            event.changes.forEach { it.consume() }
                            if (!change.pressed) {
                                if (moved) finishDrag(false) else openEditor()
                                break
                            }
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        if (moved) finishDrag(true)
                        throw cancel
                    } finally {
                        if (ownsGesture) ownGesture(false)
                    }
                }
            }) {
            Box(Modifier.fillMaxSize().then(if (edit.active) Modifier.clearAndSetSemantics { } else Modifier)) {
            if (widget.id < 0) BuiltinWidgetCard(widget.id, -1) { if (!edit.active) openEditor() }
            else {
                val info = widgets.rememberInfo(widget.id)
                if (info == null) Box(Modifier.fillMaxSize().background(Glass.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.widget_unavailable), color = Color.White.copy(alpha = .8f), fontSize = FolioType.FOOTNOTE.sp)
                } else if (AppSecurity.isProtected(info.provider.packageName, info.profile)) {
                    Box(Modifier.fillMaxSize().background(Glass.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.security_widget_locked), color = Color.White.copy(alpha = .8f), fontSize = FolioType.FOOTNOTE.sp)
                    }
                } else key(widget.id, widgets.providerRevision) {
                    AndroidView(factory = { widgets.host.createView(it, widget.id, info) },
                        update = { it.importantForAccessibility = if (edit.active) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO }, modifier = Modifier.fillMaxSize())
                }
            }
            }
            // A sibling above AndroidView intercepts touches before any RemoteViews action can run.
            if (edit.active) Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClickLabel = editLabel, onClick = { if (enabled) openEditor() }))
        }
        if (edit.active && inputEnabled) {
            JiggleRemoveButton("Remove widget", onRemove = onRemove)
        }
    }
}


private class TodayWidgetDrag(val id: Int, val origin: Offset, val pointerStart: Offset) {
    var offset by mutableStateOf(Offset.Zero)
    var targetId by mutableStateOf<Int?>(null)
}
