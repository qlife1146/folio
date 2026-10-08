package com.mccal.folio

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

internal data class DragRegion(val target: DropTarget, val bounds: Rect, val appId: String?, val page: Int?,
    val widgetId: Int? = null, val folderId: String? = null, val scope: String? = null, val iconSizePx: Float? = null,
    val contentBounds: Rect = bounds) {
    val movable get() = appId != null || (widgetId != null && widgetId != EMPTY_WIDGET)
    fun containsIcon(point: Offset): Boolean {
        val size = iconSizePx?.coerceAtMost(minOf(bounds.width, bounds.height)) ?: return false
        val left = bounds.center.x - size / 2f
        return Rect(left, bounds.top, left + size, bounds.top + size).contains(point)
    }
}

internal const val SPOTLIGHT_DRAG_SCOPE = "spotlight"

internal data class HomeDragInputConfig(
    val enabled: Boolean, val page: Int, val eligiblePages: Set<Int>, val immediate: Boolean,
    val sourceOverride: DragRegion?, val sourceScope: String?,
    val onStart: () -> Unit, val onFinish: (Boolean) -> Unit, val onMoveStart: () -> Unit,
)

/** The window owns the gesture; Home supplies its latest placement callbacks. */
@Stable
internal class HomeDragHost {
    val drag = HomeDragState()
    var input: HomeDragInputConfig? = null
    var onAppMenu: (AppEntry) -> Unit = {}
    var spotlightMenuOpen by mutableStateOf(false)
    var spotlightMenuContent: (@Composable () -> Unit)? = null
}

internal data class HomeHoverIntent(val target: DropTarget, val direction: HomePushDirection?,
    val folder: DropTarget.Home?)

@Stable
internal class HomeDragState {
    val regions = mutableStateMapOf<DropTarget, DragRegion>()
    private val regionOwners = mutableMapOf<DropTarget, Any>()
    var source by mutableStateOf<DragRegion?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var origin by mutableStateOf(Offset.Zero)
    var rootBounds by mutableStateOf(Rect.Zero)
    val rootOrigin get() = rootBounds.topLeft
    var originPage = 0
    var moved by mutableStateOf(false)
    var folderPreviewTarget by mutableStateOf<DropTarget.Home?>(null)
    var activeSourceScope by mutableStateOf<String?>(null)
    var folderMenuAppId by mutableStateOf<String?>(null)
    var folderBounds by mutableStateOf<Rect?>(null)
    var folderExited by mutableStateOf(false)
    var folderDragRegions by mutableStateOf<List<DragRegion>>(emptyList())
    val reorderingFolder get() = source?.folderId != null && !folderExited
    val active get() = source != null
    fun hit(point: Offset, pages: Set<Int>, scope: String? = activeSourceScope) = regions.values
        .filter { (it.page == null || it.page in pages) && it.bounds.contains(point) &&
            (source != null || it.target !is DropTarget.Folder) && it.scope == scope }
        .maxByOrNull(::dragRegionPriority)
    fun destination(point: Offset, pages: Set<Int>): DragRegion? {
        if (reorderingFolder) {
            // Gaps and unused space still belong to the folder, never to the Home cells behind it.
            // Preview icons move between cells; keep targeting the original cells to avoid oscillating order.
            val children = folderDragRegions
            return children.firstOrNull { it.bounds.contains(point) }
                ?: children.minByOrNull { (it.bounds.center - point).getDistance() }
        }
        regions[DropTarget.Remove]?.takeIf { source?.target !is DropTarget.Library && it.bounds.contains(point) }?.let { return it }
        // Resolve against saved Home cells, not icons moving in the insertion preview. The icon area
        // accepts apps into a folder; the surrounding gap inserts and shifts neighboring shortcuts.
        val draggedApp = source?.appId
        if (draggedApp != null && !isReservedFolderId(draggedApp)) {
            regions.values.firstOrNull {
                it.target is DropTarget.Home && (it.page == null || it.page in pages) && it.bounds.contains(point)
            }?.let { cell ->
                val folderId = cell.appId?.takeIf(::isFolderId)
                return if (folderId != null && folderId != source?.folderId && cell.containsIcon(point))
                    cell.copy(target = DropTarget.Folder(folderId), folderId = folderId)
                else cell
            }
        }
        return regions.values.filter {
            (it.page == null || it.page in pages) && it.bounds.contains(point) && when (it.target) {
                // A moving widget is rendered at its preview footprint and registers the
                // same Widget target again. Never let that preview shadow the Home cells
                // beneath it. Widgets always move through the cell grid.
                is DropTarget.Widget -> false
                is DropTarget.Home -> source?.appId != null || source?.target is DropTarget.Widget
                is DropTarget.Dock -> source?.appId != null
                // A dragged folder is drawn under the finger with its own drop target, and folders don't nest,
                // so a folder only ever lands on Home cells (and apps never on the folder they're leaving).
                is DropTarget.Folder -> source?.appId?.let { !isReservedFolderId(it) } == true &&
                    source?.target !is DropTarget.Folder && source?.folderId != it.folderId
                DropTarget.Remove -> source?.target !is DropTarget.Library
                is DropTarget.Library -> false
            }
        }.maxByOrNull(::dragRegionPriority)
    }
    fun clear() { source = null; moved = false; folderPreviewTarget = null; folderExited = false; folderDragRegions = emptyList() }

    /** The approached edge determines which way the cell's occupant yields. */
    fun pushDirection(point: Offset, pages: Set<Int>): HomePushDirection? {
        val cell = destination(point, pages)?.takeIf { it.target is DropTarget.Home } ?: return null
        val bounds = cell.bounds
        if (bounds.width <= 0f || bounds.height <= 0f) return null
        val x = (point.x - bounds.center.x) / bounds.width
        val y = (point.y - bounds.center.y) / bounds.height
        // At the exact center use the approach vector, where neither edge is nearer.
        val delta = if (kotlin.math.abs(x) + kotlin.math.abs(y) < .05f)
            Offset((point.x - origin.x) / bounds.width, (point.y - origin.y) / bounds.height)
        else Offset(-x, -y)
        return if (kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)) {
            if (delta.x < 0f) HomePushDirection.LEFT else HomePushDirection.RIGHT
        } else if (delta.y < 0f) HomePushDirection.UP else HomePushDirection.DOWN
    }

    /** Overlapping an icon makes a folder; the space between icons previews a reorder. */
    fun folderCreationTarget(point: Offset, pages: Set<Int>, layout: HomeLayout): DropTarget.Home? {
        val from = source ?: return null
        if (from.target !is DropTarget.Home || from.appId == null || isReservedFolderId(from.appId)) return null
        val region = destination(point, pages) ?: return null
        val target = region.target as? DropTarget.Home ?: return null
        val other = layout.slotAt(target.index) ?: return null
        if (other == from.appId || isReservedFolderId(other)) return null
        val iconSize = region.iconSizePx?.coerceAtMost(minOf(region.bounds.width, region.bounds.height)) ?: return null
        // The icon touches the cell's top: leave that edge for downward insertion instead of folder creation.
        return target.takeIf { region.containsIcon(point) && point.y >= region.bounds.top + iconSize * .25f }
    }

    fun register(owner: Any, region: DragRegion) {
        regionOwners[region.target] = owner
        regions[region.target] = region
    }

    fun update(owner: Any, target: DropTarget, appId: String?, page: Int?, widgetId: Int?, folderId: String?, scope: String?, iconSizePx: Float? = null) {
        regions[target]?.takeIf { regionOwners[target] === owner }?.let {
            if (it.appId != appId || it.widgetId != widgetId || it.page != page || it.folderId != folderId || it.scope != scope || it.iconSizePx != iconSizePx) {
                regions[target] = it.copy(appId = appId, widgetId = widgetId, page = page, folderId = folderId, scope = scope, iconSizePx = iconSizePx)
            }
        }
    }

    fun unregister(owner: Any, target: DropTarget) {
        if (regionOwners[target] === owner) {
            regionOwners.remove(target)
            regions.remove(target)
        }
    }
}

/** Page turning follows the window edges, including the space beside the fixed dock. */
internal fun dragEdgeDirection(point: Offset, window: Rect, edgeWidth: Float): Int = when {
    window.isEmpty || !window.contains(point) -> 0
    point.x < window.left + edgeWidth -> -1
    point.x > window.right - edgeWidth -> 1
    else -> 0
}

@Composable
internal fun Modifier.dropRegion(drag: HomeDragState, target: DropTarget, appId: String? = null, page: Int? = null,
    widgetId: Int? = null, folderId: String? = null, scope: String? = null, iconSizePx: Float? = null): Modifier {
    val owner = remember { Any() }
    DisposableEffect(drag, target, owner) { onDispose { drag.unregister(owner, target) } }
    SideEffect { drag.update(owner, target, appId, page, widgetId, folderId, scope, iconSizePx) }
    return onGloballyPositioned { coordinates ->
        // Hit testing uses visible bounds; the drag preview must retain the full widget size even when scrolled.
        val contentBounds = Rect(coordinates.localToRoot(Offset.Zero),
            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        drag.register(owner, DragRegion(target, coordinates.boundsInRoot(), appId, page, widgetId, folderId, scope, iconSizePx, contentBounds))
    }
}

internal fun dragRegionPriority(region: DragRegion): Int = when (region.target) {
    is DropTarget.Folder -> 3
    is DropTarget.Widget -> 2
    is DropTarget.Library -> if (region.scope != null) 2 else 0
    else -> 1
}

/** Keeps the grabbed point over the pointer while resolving the widget's top-left cell. */
internal fun adjustedWidgetDropIndex(
    rawIndex: Int,
    placement: WidgetPlacement,
    sourceBounds: Rect,
    grabPoint: Offset,
): Int {
    val columnOffset = (((grabPoint.x - sourceBounds.left) / sourceBounds.width.coerceAtLeast(1f)) * placement.spanX)
        .toInt().coerceIn(0, placement.spanX - 1)
    val rowOffset = (((grabPoint.y - sourceBounds.top) / sourceBounds.height.coerceAtLeast(1f)) * placement.spanY)
        .toInt().coerceIn(0, placement.spanY - 1)
    val page = homeCellPage(rawIndex)
    val rawLocal = homeCellLocal(rawIndex)
    val column = (rawLocal % GRID_COLUMNS - columnOffset).coerceIn(0, GRID_COLUMNS - placement.spanX)
    val row = (rawLocal / GRID_COLUMNS - rowOffset)
        .coerceIn(0, GRID_ROWS - placement.spanY.coerceAtMost(GRID_ROWS))
    return homeCellIndex(page, row * GRID_COLUMNS + column)
}

/**
 * A native collection widget consumes its own MOVE events even while the finger has only
 * jittered. Compose's stock long-press helper treats that consumption as cancellation, so a
 * Calendar-style widget could scroll but could no longer be picked up. The root observes the
 * Initial pass and arbitrates intent from geometry instead: jitter keeps waiting, while a real
 * move, release/cancel, or second pointer yields the stream to the provider.
 */
internal suspend fun AwaitPointerEventScope.awaitWidgetLongPressOrCancellation(
    down: PointerInputChange,
): PointerInputChange? {
    var current = down
    var canceled = false
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (!canceled) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            canceled = change == null || !change.pressed ||
                event.changes.any { it.id != down.id && it.pressed } ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            if (!canceled) current = change!!
        }
    }
    return current.takeUnless { canceled }
}

/** The root owns the pointer so paging cannot dispose the active gesture. */
@Composable
internal fun Modifier.homeDragInput(
    drag: HomeDragState, enabled: Boolean, page: Int, eligiblePages: Set<Int> = setOf(page),
    onStart: () -> Unit, onFinish: (Boolean) -> Unit,
    /** Jiggle mode: moving a finger past touch slop picks the item up without a long-press. */
    immediate: Boolean = false,
    onMoveStart: () -> Unit = {},
    /** While a menu is open, only its lifted icon can initiate another drag. */
    sourceOverride: DragRegion? = null,
): Modifier {
    val configuration by rememberUpdatedState(HomeDragInputConfig(enabled, page, eligiblePages, immediate,
        sourceOverride, null, onStart, onFinish, onMoveStart))
    return homeDragInput(drag) { configuration }
}

@Composable
internal fun Modifier.homeDragInput(drag: HomeDragState, input: () -> HomeDragInputConfig?): Modifier {
    val currentInput by rememberUpdatedState(input)
    return onGloballyPositioned { drag.rootBounds = it.boundsInRoot() }.pointerInput(drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val configuration = currentInput()?.takeIf { it.enabled } ?: return@awaitEachGesture
            val point = down.position + drag.rootOrigin
            val menuSource = configuration.sourceOverride
            val region = (menuSource?.takeIf { it.bounds.contains(point) }
                ?: if (menuSource == null) drag.hit(point, configuration.eligiblePages, configuration.sourceScope ?: drag.activeSourceScope) else null)
                ?.takeIf { it.movable } ?: return@awaitEachGesture
            val app = region.appId?.let { !isReservedFolderId(it) } == true && region.folderId == null
            var movedAlready = false
            if (configuration.immediate) {
                val result = awaitSlopOrLongPress(down,
                    if (app) APP_MENU_HOLD_MILLIS else viewConfiguration.longPressTimeoutMillis) ?: return@awaitEachGesture
                movedAlready = result
            } else if (app) {
                awaitAppMenuHoldOrCancellation(down) ?: return@awaitEachGesture
            } else if (region.target is DropTarget.Widget && (region.widgetId ?: EMPTY_WIDGET) >= 0) {
                awaitWidgetLongPressOrCancellation(down) ?: return@awaitEachGesture
            } else {
                awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            }
            // Claim the pointer before the menu changes hit testing; retain it until release.
            currentEvent.changes.forEach { it.consume() }
            drag.source = region
            drag.folderExited = false
            drag.folderDragRegions = if (region.folderId != null) drag.regions.values.filter {
                it.scope == region.folderId && it.target is DropTarget.Library
            } else emptyList()
            drag.pointer = point
            drag.origin = point
            drag.originPage = currentInput()?.page ?: configuration.page
            drag.moved = movedAlready
            currentInput()?.onStart?.invoke()
            if (movedAlready) currentInput()?.onMoveStart?.invoke()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || event.changes.any { it.id != down.id && it.pressed }) {
                        event.changes.forEach { it.consume() }
                        currentInput()?.onFinish?.invoke(true)
                        break
                    }
                    drag.pointer = change.position + drag.rootOrigin
                    if (!drag.moved && (drag.pointer - drag.origin).getDistance() > viewConfiguration.touchSlop) {
                        drag.moved = true
                        currentInput()?.onMoveStart?.invoke()
                    }
                    if (drag.moved && drag.reorderingFolder && drag.folderBounds?.contains(drag.pointer) == false) {
                        drag.folderExited = true
                    }
                    change.consume()
                    if (!change.pressed) {
                        currentInput()?.onFinish?.invoke(false)
                        break
                    }
                    if (!drag.active) break
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                drag.clear()
                throw cancel
            }
        }
    }
}

/** true once the finger moves past slop, false after a long-press without moving, null on release or cancel. */
internal suspend fun AwaitPointerEventScope.awaitSlopOrLongPress(
    down: PointerInputChange, timeoutMillis: Long = viewConfiguration.longPressTimeoutMillis,
): Boolean? {
    var result: Boolean? = false
    val finished = withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed || change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) {
                result = null; break
            }
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                change.consume(); result = true; break
            }
        }
        Unit
    }
    return if (finished == null) false else result
}

private const val APP_MENU_HOLD_MILLIS = 500L

/** The same half-second hold for Home, Dock, App Library and Spotlight. */
private suspend fun AwaitPointerEventScope.awaitAppMenuHoldOrCancellation(down: PointerInputChange): PointerInputChange? {
    var current = down
    var canceled = false
    withTimeoutOrNull(APP_MENU_HOLD_MILLIS) {
        while (!canceled) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            canceled = change == null || !change.pressed || change.isConsumed ||
                event.changes.any { it.id != down.id && it.pressed } ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            if (!canceled) current = change!!
        }
    }
    return current.takeUnless { canceled }
}
