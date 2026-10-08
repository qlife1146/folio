package com.mccal.folio

import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Last on-screen bounds of each app icon, so a long-press menu can lift the icon in place. */
internal object IconBounds {
    private val bounds = HashMap<String, Rect>()
    fun update(id: String, rect: Rect) { if (!rect.isEmpty) bounds[id] = Rect(rect) }
    fun of(id: String): Rect? = bounds[id]
}

internal data class QuickAction(val label: String, val icon: Bitmap?, val info: ShortcutInfo)

/** The app's own shortcuts (Folio can read them as the default Home app). Call off the main thread. */
internal fun loadQuickActions(context: android.content.Context, app: AppEntry, limit: Int = 4): List<QuickAction> = runCatching {
    if (AppSecurity.isProtected(app)) return@runCatching emptyList()
    val apps = context.getSystemService(LauncherApps::class.java)
    if (!apps.hasShortcutHostPermission()) return@runCatching emptyList()
    val query = LauncherApps.ShortcutQuery().setPackage(app.component.packageName).setActivity(app.component)
        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
    apps.getShortcuts(query, app.user).orEmpty().filter { it.isEnabled }.sortedBy { it.rank }.take(limit).map { info ->
        QuickAction((info.shortLabel ?: info.longLabel ?: "").toString(),
            runCatching { apps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)?.toBitmap(96, 96) }.getOrNull(), info)
    }
}.getOrDefault(emptyList())

/**
 * iPhone-style long-press menu: the icon lifts where it is, Home blurs behind, and a compact menu
 * appears next to it with the app's own quick actions first, then Folio's actions.
 */
@Composable
internal fun AppContextMenu(
    app: AppEntry, onHome: Boolean, fromHome: Boolean, hidden: Boolean,
    /** The Focus locking Home editing, if any: editing rows are replaced by a note. */
    lockedBy: String? = null,
    onDismiss: () -> Unit, onAddOrRemove: () -> Unit,
    onWidgets: (() -> Unit)?, onToggleHidden: () -> Unit, onInfo: () -> Unit, onRename: () -> Unit,
    onUninstall: (() -> Unit)? = null,
    /** Choose the apps tucked behind this icon (Icon Stacks); null where stacks don't apply. */
    onStack: (() -> Unit)? = null,
    /** Captured source position in the window, independent of other copies of the app. */
    iconAnchor: androidx.compose.ui.geometry.Rect? = null,
    onIconBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    secured: Boolean = false, secureHidden: Boolean = false, onSecurity: () -> Unit = {},
    showHomeAction: Boolean = true,
) {
    if (!rememberHomePopupVisible(onDismiss = onDismiss)) return
    CompositionLocalProvider(LocalMenuInk provides FolioGlass.ink) {
    PopupBackdropContent {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val density = LocalDensity.current
    val appear = remember { Animatable(0f) }
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear.animateTo(1f, MotionSpeed.spring(.72f, Spring.StiffnessMediumLow)) }
    DisposableEffect(Unit) {
        LauncherContextMenusOpen.intValue++
        LauncherSheetsOpen.intValue++
        onDispose { LauncherSheetsOpen.intValue--; LauncherContextMenusOpen.intValue-- }
    }

    val protected = secured || AppSecurity.isProtected(app)
    val unread = appBadgeCount(app, LocalBadgeCounts.current, LocalProfileBadgeCounts.current)
    val actions by produceState(emptyList<QuickAction>(), app.id, protected) {
        value = if (protected || app.isShortcut) emptyList() else withContext(Dispatchers.IO) { loadQuickActions(context, app) }
    }

    // Stay in Home's window: opening a Dialog would cancel the finger that invoked this menu.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(3f).onGloballyPositioned {
        origin = it.positionOnScreen(); rootOrigin = it.boundsInRoot().topLeft
    }
        .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = LocalBackgroundMaterial.current.scrimAlpha))
        .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val icon = IconBounds.of(app.id) ?: Rect((screenW / 2 - 80).toInt(), (screenH / 3).toInt(), (screenW / 2 + 80).toInt(), (screenH / 3 + 160).toInt())
        val iconLeft = iconAnchor?.let { it.left - rootOrigin.x } ?: (icon.left - origin.x)
        val iconTop = iconAnchor?.let { it.top - rootOrigin.y } ?: (icon.top - origin.y)
        val iconSize = iconAnchor?.width ?: icon.width().toFloat()

        // Lifted icon, exactly where it was.
        AppIcon(app, null, Modifier.offset { IntOffset(iconLeft.roundToInt(), iconTop.roundToInt()) }
            .size(with(density) { iconSize.toDp() })
            .graphicsLayer { val s = 1f + .1f * appear.value; scaleX = s; scaleY = s }
            .onGloballyPositioned { onIconBounds(it.boundsInRoot()) }
            , shape = RoundedCornerShape(with(density) { (iconSize * .24f).toDp() }))

        // Menu below the icon, or above when there's no room; aligned to the icon, kept on screen.
        val menuW = with(density) { 260.dp.toPx() }
        val gap = with(density) { 14.dp.toPx() }
        val safeTop = with(density) { 56.dp.toPx() }      // clear of the camera and island
        val safeBottom = with(density) { 32.dp.toPx() }
        val spaceBelow = screenH - (iconTop + iconSize * 1.1f + gap) - safeBottom
        val spaceAbove = iconTop - gap - safeTop
        // App Info and More stay visible, alongside placement actions or the Focus notice and Clear Badge.
        val folioRows = 3 + (if (lockedBy != null || (showHomeAction && !secureHidden && (fromHome || !onHome))) 1 else 0) +
            (if (unread > 0 && LocalIconLook.current.badges != BadgeStyle.OFF && lockedBy == null) 1 else 0)
        val estimatedH = with(density) { (49.dp * ((if (protected) 0 else actions.size) + folioRows) + 8.dp).toPx() }
        // Prefer below (like iOS) when it fits; otherwise whichever side has more room, scrolling if needed.
        val below = estimatedH <= spaceBelow || spaceBelow >= spaceAbove
        val maxMenuH = with(density) { (if (below) spaceBelow else spaceAbove).coerceAtLeast(120f).toDp() }
        // Keep Folio's own rows visible without scrolling: drop app quick actions that don't fit.
        val rowPx = with(density) { 49.dp.toPx() }
        // Opening More swaps the app's quick actions for Folio's extra rows, so the menu doesn't need to scroll.
        val shownActions = if (more || protected) emptyList() else actions.take((((if (below) spaceBelow else spaceAbove) - with(density) { 8.dp.toPx() }) / rowPx - folioRows).toInt().coerceAtLeast(0))
        val menuLeft = (iconLeft + iconSize / 2 - menuW / 2).coerceIn(gap, screenW - menuW - gap)
        val origX = ((iconLeft + iconSize / 2 - menuLeft) / menuW).coerceIn(0f, 1f)
        // Position the menu from its measured height so it stays clear of the lifted icon.
        var menuH by remember { mutableIntStateOf(0) }
        val lift = iconSize * .05f
        Column(Modifier.offset {
                IntOffset(menuLeft.roundToInt(),
                    if (below) (iconTop + iconSize + lift + gap).roundToInt() else (iconTop - lift - gap - menuH).roundToInt())
            }
            .onSizeChanged { menuH = it.height }
            .width(260.dp)
            .heightIn(max = maxMenuH)
            .graphicsLayer {
                val s = .7f + .3f * appear.value; scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(origX, if (below) 0f else 1f)
            }
            .clip(RoundedCornerShape(18.dp)).materialBackground(RoundedCornerShape(18.dp), tint = FolioGlass.panel)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .fadingVerticalScroll()) {
            shownActions.forEachIndexed { i, action ->
                MenuRow(action.label, bitmap = action.icon) {
                    onDismiss()
                    AppSecurity.run(context, app.packageName, app.user) {
                        runCatching { context.getSystemService(LauncherApps::class.java).startShortcut(action.info, null, null) }
                    }
                }
                if (i == shownActions.lastIndex) Box(Modifier.fillMaxWidth().height(8.dp).background(FolioGlass.ink.copy(alpha = .06f)))
                else MenuDivider()
            }
            if (lockedBy != null) {
                Text(stringResource(R.string.home_editing_is_off_while_1_is_on, lockedBy), color = FolioGlass.secondaryInk, fontSize = FolioType.FOOTNOTE.sp,
                    modifier = Modifier.padding(horizontal = FolioSpace.LARGE.dp, vertical = FolioSpace.MEDIUM.dp))
                MenuDivider()
            } else {
            if (unread > 0 && LocalIconLook.current.badges != BadgeStyle.OFF) {
                val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
                MenuRow(stringResource(R.string.clear_badge), Icons.Rounded.NotificationsOff) {
                    activity?.let { IslandListenerService.clearBadge(app.packageName, app.user) }; onDismiss()
                }
                MenuDivider()
            }
            if (showHomeAction && !secureHidden && (fromHome || !onHome)) {
                // A shortcut exists only as this icon, so removing it deletes it (like iOS's "Delete Bookmark").
                MenuRow(when { app.isShortcut -> stringResource(R.string.delete_shortcut); onHome -> stringResource(R.string.remove_from_home); else -> stringResource(R.string.add_to_home) },
                    if (onHome || app.isShortcut) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.AddCircleOutline,
                    destructive = onHome || app.isShortcut) { onAddOrRemove() }
                MenuDivider()
            }
            }
            MenuRow(if (app.isShortcut) stringResource(R.string.info_for_app) else stringResource(R.string.app_info), Icons.Rounded.Info) { onInfo() }
            MenuDivider()
            MenuRow(stringResource(if (protected) R.string.security_not_required else R.string.security_required),
                if (protected) Icons.Rounded.LockOpen else Icons.Rounded.Lock, onClick = onSecurity)
            MenuDivider()
            // iOS keeps context menus short: the less common actions sit behind "More".
            if (!more) MenuRow(stringResource(R.string.more), Icons.Rounded.MoreHoriz) { more = true }
            else {
                onWidgets?.let { MenuRow(stringResource(R.string.widgets), Icons.Rounded.Widgets) { it() }; MenuDivider() }
                onStack?.let { MenuRow(stringResource(R.string.stack_apps), Icons.Rounded.Layers) { it() }; MenuDivider() }
                MenuRow(stringResource(R.string.rename), Icons.Rounded.DriveFileRenameOutline) { onRename() }
                MenuDivider()
                if (!protected) MenuRow(if (hidden) stringResource(R.string.show_in_app_library) else stringResource(R.string.hide_from_app_library), if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff) { onToggleHidden() }
                onUninstall?.let {
                    MenuDivider()
                    MenuRow(stringResource(R.string.uninstall_app), Icons.Rounded.DeleteOutline, destructive = true) { it() }
                }
            }
        }
    }
    }
    }
}

private val LocalMenuInk = staticCompositionLocalOf { Color.White }

@Composable
internal fun MenuRow(label: String, icon: ImageVector? = null, bitmap: Bitmap? = null, destructive: Boolean = false,
    tag: String? = null, onClick: () -> Unit) {
    val ink = LocalMenuInk.current
    val tint = if (destructive) (if (ink == Color.White) FolioColors.RedOnDark else FolioColors.Red) else ink
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = FolioSpace.LARGE.dp, vertical = FolioSpace.MEDIUM.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), null, Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)))
            icon != null -> Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun MenuDivider() = HorizontalDivider(color = LocalMenuInk.current.copy(alpha = .1f), thickness = .5.dp)

@Composable
internal fun AppSecurityAlert(app: AppEntry, onDismiss: () -> Unit, onSelect: (AppSecurityMode) -> Unit) {
    if (!rememberHomePopupVisible(onDismiss = onDismiss)) return
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.security_request_title, app.label)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.security_request_message))
            Text(stringResource(R.string.security_shared_search_note), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Column(Modifier.fillMaxWidth()) {
            TextButton(onClick = { onSelect(AppSecurityMode.LOCKED) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.security_required))
            }
            HorizontalDivider(color = FolioGlass.ink.copy(alpha = .16f), thickness = .5.dp)
            TextButton(onClick = { onSelect(AppSecurityMode.HIDDEN) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.security_hide_and_require))
            }
            HorizontalDivider(color = FolioGlass.ink.copy(alpha = .16f), thickness = .5.dp)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel), fontWeight = FontWeight.Normal)
            }
        } })
}

/** Rename an app: the typed name replaces the label everywhere, and an empty field puts Android's name back. */
@Composable
internal fun RenameAppAlert(app: AppEntry, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(app.id) { mutableStateOf(if (app.label == app.systemLabel) "" else app.label) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(app.id) { runCatching { focus.requestFocus() } }
    val ink = FolioGlass.ink
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_app)) },
        text = {
            Column {
                Text(stringResource(R.string.leave_it_empty_to_use_s_again, app.systemLabel), fontSize = FolioType.FOOTNOTE.sp)
                androidx.compose.foundation.text.BasicTextField(name, { name = it.takeAppName() },
                    Modifier.padding(top = FolioSpace.MEDIUM.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(ink.copy(alpha = .08f)).padding(horizontal = FolioSpace.COMPACT.dp, vertical = FolioSpace.COMPACT.dp)
                        .focusRequester(focus).testTag("app-name"),
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = ink, fontSize = FolioType.BODY.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(ink),
                    decorationBox = { field ->
                        Box {
                            // The app's own name as a hint, so an empty field doesn't look like a blank row.
                            if (name.isEmpty()) Text(app.systemLabel, color = ink.copy(alpha = .4f), fontSize = FolioType.BODY.sp)
                            field()
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onRename(name) }))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onRename(name) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
// The buttons stay Material's TextButton: Folio's AlertDialog restyles them itself, the way its other alerts do.
}
