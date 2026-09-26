package com.mccal.folio

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
internal fun FolderPanel(
    folder: FolderEntry, apps: Map<String, AppEntry>, drag: HomeDragState, page: Int,
    homeDestinations: List<Int>, dockVacancies: List<Int>, onDismiss: () -> Unit,
    onRename: (String) -> Unit, onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onMoveOut: (String, DropTarget) -> Unit,
    color: Long? = null, onColor: (Long?) -> Unit = {},
    onAppsChange: (List<String>) -> Unit = {},
    editing: Boolean = false,
) {
    if (!rememberHomePopupVisible(onDismiss = onDismiss)) return
    var title by rememberSaveable(folder.id) { mutableStateOf(folder.title) }
    var showAppPicker by rememberSaveable(folder.id) { mutableStateOf(false) }
    // Zoom in from the folder's tile on Home and back into it on close, like iPhone folders.
    val appear = remember(folder.id) { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember(folder.id) { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) { closing = true; scope.launch {
            appear.animateTo(0f, FolioMotion.spring(FolioMotion.Firm)); onDismiss()
        } }
    }
    val tile = remember(folder.id) { IconBounds.of(folder.id) }
    var panelBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // Predictive back: the folder shrinks toward its icon as you swipe, and closes (or springs back) when you let go.
    PredictiveBack(enabled = !closing && !showAppPicker, onProgress = { p -> scope.launch { appear.snapTo(1f - .35f * p) } },
        onCancel = { scope.launch { appear.animateTo(1f, FolioMotion.spring(FolioMotion.Quick)) } }, onBack = close)
    DisposableEffect(folder.id, editing) {
        onDispose { if (editing && title.isNotBlank() && title != folder.title) onRename(title) }
    }
    DisposableEffect(drag, folder.id) {
        drag.activeSourceScope = folder.id
        onDispose {
            if (drag.activeSourceScope == folder.id) {
                drag.activeSourceScope = null
                drag.folderBounds = null
            }
            drag.folderMenuAppId = null
        }
    }
    LaunchedEffect(folder.id) { appear.animateTo(1f, MotionSpeed.spring(.78f, androidx.compose.animation.core.Spring.StiffnessMediumLow)) }
    val folderLook = LocalFolderLook.current
    var scrimOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()
        .onGloballyPositioned { scrimOrigin = it.positionInRoot() }
        // Draw outside Home's safe-area padding, before the content's alpha layer can clip the scrim.
        .drawBehind {
            val bounds = drag.rootBounds
            drawRect(Color.Black.copy(alpha = folderLook.backdropOpacity * appear.value.coerceIn(0f, 1f)),
                topLeft = if (bounds.isEmpty) androidx.compose.ui.geometry.Offset.Zero else bounds.topLeft - scrimOrigin,
                size = if (bounds.isEmpty) size else bounds.size)
        }
        .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Close folder",
            onClick = close,
        )
        .imePadding().testTag("folder-panel"),
        contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val panelPadding = FolioSpace.XL.dp
        val columnGap = FolioSpace.SMALL.dp
        val rowGap = FolioSpace.COMPACT.dp
        val availableWidth = (maxWidth - FolioSpace.LARGE.dp * 2).coerceAtMost(520.dp)
        val contentWidth = (availableWidth - panelPadding * 2).coerceAtLeast(1.dp)
        val maxColumns = ((contentWidth + columnGap) / (76.dp + columnGap)).toInt().coerceAtLeast(1)
        val columns = if (folderLook.columns > 0) folderLook.columns.coerceIn(1, maxColumns)
            else ((contentWidth + columnGap) / (84.dp + columnGap)).toInt().coerceIn(1, maxColumns)
        val cellWidth = 84.dp.coerceAtMost((contentWidth - columnGap * (columns - 1)) / columns)
        val panelWidth = cellWidth * columns + columnGap * (columns - 1) + panelPadding * 2
        val labelHeight = with(density) { MaterialTheme.typography.labelMedium.lineHeight.toDp() * 2 }
        val profileHeight = if (folder.appIds.any { apps[it]?.let { app -> app.isWork || !app.available } == true })
            with(density) { MaterialTheme.typography.labelSmall.lineHeight.toDp() } else 0.dp
        val rowHeight = 58.dp + FolioSpace.SNUG.dp + FolioSpace.COMPACT.dp * 2 + labelHeight + profileHeight
        val headerHeight = with(density) { 36.sp.toDp() } + 18.dp +
            if (editing) FolioTouch.MIN.dp + FolioSpace.COMFY.dp else 0.dp
        val availableHeight = maxHeight - headerHeight - panelPadding * 2 - FolioTouch.MIN.dp - FolioSpace.LARGE.dp * 2
        val maxRows = ((availableHeight + rowGap) / (rowHeight + rowGap)).toInt().coerceIn(1, 3)
        val rows = ((folder.appIds.size + columns - 1) / columns).coerceIn(1, maxRows)
        val gridHeight = rowHeight * rows + rowGap * (rows - 1)
        val perPage = rows * columns
        val pageCount = ((folder.appIds.size + perPage - 1) / perPage).coerceAtLeast(1)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(panelWidth)
            .onGloballyPositioned {
                panelBounds = it.boundsInWindow()
                drag.folderBounds = it.boundsInRoot()
            }
            .graphicsLayer {
                val p = appear.value
                if (tile != null && panelBounds.width > 0f) {
                    val start = (tile.width() / panelBounds.width).coerceIn(.08f, 1f)
                    val s = start + (1f - start) * p; scaleX = s; scaleY = s
                    translationX = (tile.exactCenterX() - panelBounds.center.x) * (1f - p)
                    translationY = (tile.exactCenterY() - panelBounds.center.y) * (1f - p)
                    alpha = (p * 1.8f).coerceIn(0f, 1f)
                } else { val s = .86f + .14f * p; scaleX = s; scaleY = s }
            }) {
        val titleModifier = Modifier.widthIn(max = 420.dp).padding(bottom = 18.dp).testTag("folder-name")
        val titleStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 30.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (editing) androidx.compose.foundation.text.BasicTextField(title, { title = it },
            titleModifier, singleLine = true, textStyle = titleStyle,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (title.isNotBlank()) onRename(title) }))
        else Text(folder.title, modifier = titleModifier, style = titleStyle)
        // Folder tint: none + a few iOS-like colors.
        if (editing) Row(Modifier.fillMaxWidth().padding(bottom = FolioSpace.COMFY.dp)
            .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
            (listOf<Long?>(null) + FolderSwatches).forEach { swatch ->
                val selected = swatch == color
                // The swatch still draws at 30 dp with a 10 dp gap; the tap is 40 x 48, made of the circle and the
                // gap around it. Eight 48 dp-wide targets would not fit a cover screen's folder panel (A11Y-1).
                Box(Modifier.width(40.dp).height(FolioTouch.MIN.dp)
                    .clickable(onClickLabel = if (swatch == null) "No folder color" else "Folder color") { onColor(swatch) },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(swatch?.let { Color(it) } ?: Color.White.copy(alpha = .18f))
                        .then(if (selected) Modifier.border(2.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape) else Modifier))
                }
            }
        }
        Surface(Modifier.fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .testTag("folder-panel-content"),
            color = when (folderLook.background) {
                FolderBackground.GLASS -> FolioGlass.card
                FolderBackground.SOLID -> FolioColors.SecondaryBackground
                FolderBackground.CLEAR -> Color.Transparent
            }, contentColor = Color.White, shape = RoundedCornerShape(38.dp),
            border = if (folderLook.background == FolderBackground.CLEAR) null else FolioGlass.edge) {
            Column(Modifier.padding(panelPadding)) {
                val reorderTarget = if (drag.reorderingFolder && drag.moved)
                    drag.destination(drag.pointer, setOf(page))?.appId else null
                val dragAppIds = remember(folder.id, drag.source) { folder.appIds }
                val previewAppIds = remember(folder.appIds, dragAppIds, drag.source?.appId, reorderTarget) {
                    val from = dragAppIds.indexOf(drag.source?.appId)
                    val to = dragAppIds.indexOf(reorderTarget)
                    if (from >= 0 && to >= 0) dragAppIds.toMutableList().apply { add(to, removeAt(from)) }
                    else folder.appIds
                }
                Box(Modifier.fillMaxWidth()) {
                    val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
                    val folderPager = rememberPagerState(pageCount = { pageCount })
                    val currentFolderPage = folderPager.currentPage
                    var pagerBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
                    LaunchedEffect(folder.id) { folderPager.scrollToPage(0) }
                    // Fixed cells on the visible page keep hover reordering stable while icons animate.
                    SideEffect {
                        if (drag.reorderingFolder && !pagerBounds.isEmpty) {
                            drag.folderDragRegions = dragAppIds.drop(currentFolderPage * perPage).take(perPage).mapIndexed { index, appId ->
                                val column = if (rtl) columns - 1 - index % columns else index % columns
                                with(density) {
                                    val left = pagerBounds.left + (cellWidth + columnGap).toPx() * column
                                    val top = pagerBounds.top + (rowHeight + rowGap).toPx() * (index / columns)
                                    DragRegion(DropTarget.Library(appId),
                                        androidx.compose.ui.geometry.Rect(left, top, left + cellWidth.toPx(), top + rowHeight.toPx()),
                                        appId, page, folderId = folder.id, scope = folder.id)
                                }
                            }
                        }
                    }
                    val physicalEdge = if (drag.reorderingFolder && drag.moved)
                        dragEdgeDirection(drag.pointer, pagerBounds, with(density) { 24.dp.toPx() }) else 0
                    val pageEdge = if (rtl) -physicalEdge else physicalEdge
                    LaunchedEffect(drag.reorderingFolder, drag.moved, pageEdge, pageCount) {
                        if (pageEdge != 0) while (drag.reorderingFolder && drag.moved) {
                            delay(500)
                            val next = (folderPager.currentPage + pageEdge).coerceIn(0, pageCount - 1)
                            if (next == folderPager.currentPage) break
                            folderPager.animateScrollToPage(next)
                        }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalPager(folderPager, Modifier.fillMaxWidth().height(gridHeight)
                            .onGloballyPositioned { pagerBounds = it.boundsInRoot() }.testTag("folder-pages"),
                            userScrollEnabled = !drag.active, verticalAlignment = Alignment.Top) { folderPage ->
                            LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), userScrollEnabled = false,
                                horizontalArrangement = Arrangement.spacedBy(columnGap), verticalArrangement = Arrangement.spacedBy(rowGap)) {
                                items(previewAppIds.drop(folderPage * perPage).take(perPage), key = { it }) { appId ->
                                    Box(Modifier.animateItem().height(rowHeight)) {
                                        apps[appId]?.let { app -> FolderChild(app, folder.id, drag, page, homeDestinations, dockVacancies,
                                            onLaunch = onLaunch, onMoveOut = onMoveOut,
                                            highlighted = drag.reorderingFolder && drag.moved && drag.source?.appId == appId) }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(FolioTouch.MIN.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pageCount > 1) Row(Modifier.weight(1f).height(32.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            repeat(pageCount) { index ->
                                val label = stringResource(R.string.page_1, index + 1)
                                Box(Modifier.size(32.dp).selectable(selected = folderPager.currentPage == index, enabled = !drag.active,
                                    role = Role.Tab, onClick = { scope.launch { folderPager.animateScrollToPage(index) } })
                                    .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(6.dp).background(Color.White.copy(alpha = if (folderPager.currentPage == index) 1f else .35f),
                                        androidx.compose.foundation.shape.CircleShape))
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showAppPicker = true }, modifier = Modifier.testTag("folder-add-apps")) {
                            Box(Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color.White.copy(alpha = .18f))
                                .border(1.5.dp, Color.White.copy(alpha = .8f), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Add, stringResource(R.string.folder_select_apps), Modifier.size(22.dp), tint = Color.White)
                            }
                        }
                        }
                    }
                }
            }
        }
        }
    }
    if (showAppPicker) FolderAppPicker(folder, apps.values.toList(),
        onDismiss = { showAppPicker = false },
        onConfirm = { selected -> onAppsChange(selected); showAppPicker = false })
}

@Composable
private fun FolderChild(
    app: AppEntry, folderId: String, drag: HomeDragState, page: Int,
    homeDestinations: List<Int>, dockVacancies: List<Int>,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onMoveOut: (String, DropTarget) -> Unit,
    highlighted: Boolean,
) {
    val menu = drag.folderMenuAppId == app.id && !drag.moved
    val menuLabel = stringResource(R.string.move_1_s, app.label)
    Surface(Modifier.fillMaxWidth().testTag("folder-child-${app.id}")
        .graphicsLayer { alpha = if (drag.moved && drag.source?.appId == app.id) .25f else 1f },
        color = if (highlighted) Color.White.copy(alpha = .18f) else Color.Transparent, contentColor = Color.White,
        shape = RoundedCornerShape(18.dp)) {
        Box {
            Column(Modifier.fillMaxWidth().dropRegion(drag, DropTarget.Library(app.id), app.id, page,
                folderId = folderId, scope = folderId).clickable(enabled = app.available) { onLaunch(app, null) }
                .semantics { onLongClick(label = menuLabel) { drag.folderMenuAppId = app.id; true } }
                .padding(horizontal = FolioSpace.SNUG.dp, vertical = FolioSpace.COMPACT.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(app, null, Modifier.size(58.dp), shape = RoundedCornerShape(FolioRadius.CARD.dp))
                Text(app.label, Modifier.padding(top = FolioSpace.SNUG.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium)
                if (app.isWork || !app.available) Text(if (app.available) app.profileLabel else "${app.profileLabel} unavailable",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            // Keep the held pointer in Home's drag handler so moving can still take the app out of its folder.
            FolioMenuPopup(menu, onDismiss = { drag.folderMenuAppId = null }, tag = "folder-options-${app.id}",
                focusable = !drag.active) {
                homeDestinations.distinctBy(::homeCellPage).forEachIndexed { index, destination ->
                    val destinationPage = homeCellPage(destination)
                    val label = if (destinationPage == -1) stringResource(R.string.move_to_the_unfolded_only_page)
                        else stringResource(R.string.move_to_page_1_d, destinationPage + 1)
                    if (index > 0) MenuDivider()
                    MenuRow(label, tag = "folder-move-${app.id}-page-$destinationPage") {
                        drag.folderMenuAppId = null; onMoveOut(app.id, DropTarget.Home(destination))
                    }
                }
                dockVacancies.firstOrNull()?.let { dock ->
                    MenuDivider()
                    MenuRow(stringResource(R.string.move_to_dock), tag = "folder-move-${app.id}-dock") {
                        drag.folderMenuAppId = null; onMoveOut(app.id, DropTarget.Dock(dock))
                    }
                }
                MenuDivider()
                MenuRow(stringResource(R.string.remove_shortcut), destructive = true, tag = "folder-remove-${app.id}") {
                    drag.folderMenuAppId = null; onMoveOut(app.id, DropTarget.Remove)
                }
            }
        }
    }
}

@Composable
private fun FolderAppPicker(folder: FolderEntry, apps: List<AppEntry>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    var selected by rememberSaveable(folder.id) { mutableStateOf(ArrayList(folder.appIds)) }
    var query by rememberSaveable { mutableStateOf("") }
    val appsById = remember(apps) { apps.associateBy { it.id } }
    val selectedApps = remember(selected, appsById) { selected.mapNotNull(appsById::get) }
    val shown = remember(apps, query) {
        apps.filter { it.label.contains(query.trim(), ignoreCase = true) }.sortedBy { it.label.lowercase() }
    }
    AlertDialog(onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = FolioSpace.LARGE.dp).imePadding(),
        width = 520.dp, textMaxHeight = 620.dp,
        title = { Text(stringResource(R.string.folder_select_apps)) },
        text = {
            Column(Modifier.heightIn(max = 620.dp)) {
                IosSearchField(query, { query = it }, stringResource(R.string.search_apps), Modifier.padding(bottom = FolioSpace.SMALL.dp))
                Text(stringResource(R.string.folder_selected_order), style = MaterialTheme.typography.labelMedium)
                if (selectedApps.isEmpty()) Text(stringResource(R.string.folder_no_selected_apps),
                    Modifier.padding(vertical = FolioSpace.SMALL.dp), style = MaterialTheme.typography.bodySmall)
                else LazyRow(Modifier.fillMaxWidth().padding(vertical = FolioSpace.TINY.dp).testTag("folder-selection-order"),
                    horizontalArrangement = Arrangement.spacedBy(FolioSpace.TINY.dp)) {
                    itemsIndexed(selectedApps, key = { _, app -> app.id }) { index, app ->
                        Column(Modifier.width(48.dp).clip(RoundedCornerShape(FolioRadius.CONTROL.dp))
                            .toggleable(value = true, role = Role.Checkbox, onValueChange = {
                                selected = ArrayList(selected - app.id)
                            }), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                            AppIcon(app, null, Modifier.size(28.dp), shape = RoundedCornerShape(FolioRadius.CONTROL.dp), badge = false)
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(bottom = FolioSpace.SMALL.dp))
                LazyColumn(Modifier.weight(1f, fill = false).testTag("folder-app-picker")) {
                    items(shown, key = { it.id }) { app ->
                        val checked = app.id in selected
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("folder-select-${app.id}")
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { value ->
                                selected = ArrayList(if (value) selected + app.id else selected - app.id)
                            }).padding(vertical = FolioSpace.SNUG.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app, null, Modifier.size(40.dp), shape = RoundedCornerShape(FolioRadius.CONTROL.dp), badge = false)
                            Column(Modifier.weight(1f).padding(horizontal = FolioSpace.SMALL.dp)) {
                                Text(app.label)
                                if (app.isWork) Text(app.profileLabel, style = MaterialTheme.typography.labelSmall)
                            }
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.toList()) }, modifier = Modifier.testTag("folder-app-picker-confirm")) {
            Text(stringResource(R.string.folder_confirm_apps))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private val FolderSwatches = listOf(0xFFFF6B63, 0xFFFFA94D, 0xFFFFD84D, 0xFF63D98B, 0xFF4DB8FF, 0xFF8E7CFF, 0xFFFF7EB9)
