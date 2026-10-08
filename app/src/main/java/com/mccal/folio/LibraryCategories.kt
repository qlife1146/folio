package com.mccal.folio

import androidx.compose.foundation.combinedClickable
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App Library categories, from the category apps declare plus simple package hints. */
internal enum class LibraryCategory(@androidx.annotation.StringRes val title: Int) {
    SUGGESTIONS(R.string.suggestions), RECENTLY_INSTALLED(R.string.recently_installed_apps),
    SOCIAL(R.string.social), PRODUCTIVITY(R.string.productivity_finance), CREATIVITY(R.string.photo_video),
    ENTERTAINMENT(R.string.entertainment), GAMES(R.string.games), INFO(R.string.information_reading), TRAVEL(R.string.travel_maps),
    SHOPPING(R.string.shopping_food), UTILITIES(R.string.utilities), OTHER(R.string.other);

    companion object {
        fun of(pm: PackageManager, packageName: String): LibraryCategory {
            val p0 = packageName.lowercase()
            // Browsers and system tools often declare odd categories; our hints win for them.
            if (hint(p0, "chrome", "firefox", "browser", "opera", "brave", "edge", "duckduckgo", "settings", "myfiles", "files",
                    "clock", "calculator", "contacts", "dialer", "vending", "authenticator", "callfilter", "call.filter", "vpn")) return UTILITIES
            val declared = runCatching { pm.getApplicationInfo(packageName, 0).category }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
            when (declared) {
                ApplicationInfo.CATEGORY_SOCIAL -> return SOCIAL
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return PRODUCTIVITY
                ApplicationInfo.CATEGORY_IMAGE, ApplicationInfo.CATEGORY_VIDEO -> return if (hint(packageName, "youtube", "netflix", "tv", "hulu", "disney", "twitch")) ENTERTAINMENT else CREATIVITY
                ApplicationInfo.CATEGORY_AUDIO -> return ENTERTAINMENT
                ApplicationInfo.CATEGORY_GAME -> return GAMES
                ApplicationInfo.CATEGORY_NEWS -> return INFO
                ApplicationInfo.CATEGORY_MAPS -> return TRAVEL
                ApplicationInfo.CATEGORY_ACCESSIBILITY -> return UTILITIES
            }
            val p = packageName.lowercase()
            return when {
                hint(p, "discord", "twitter", "instagram", "facebook", "whatsapp", "telegram", "snapchat", "reddit", "linkedin", "tiktok", "threads", "messenger", "signal", "teams", "slack") -> SOCIAL
                hint(p, "mail", "gmail", "calendar", "office", "docs", "sheets", "notes", "drive", "bank", "pay", "wallet", "finance", "cash", "venmo", "chatgpt", "claude", "perplexity", "bard", "calendly") -> PRODUCTIVITY
                hint(p, "spotify", "music", "youtube", "netflix", "hulu", "disney", "twitch", "podcast", "audible", "tv") -> ENTERTAINMENT
                hint(p, "camera", "gallery", "photo", "lightroom", "snapseed", "video", "capcut") -> CREATIVITY
                hint(p, "game", "games", "roblox", "minecraft", "chess") -> GAMES
                hint(p, "news", "weather", "kindle", "books", "health", "fitness", "wiki") -> INFO
                hint(p, "maps", "uber", "lyft", "airline", "travel", "transit", "waze", "airbnb") -> TRAVEL
                hint(p, "shop", "amazon", "store", "ebay", "doordash", "ubereats", "grubhub", "food", "starbucks", "target", "walmart") -> SHOPPING
                hint(p, "settings", "files", "myfiles", "clock", "calculator", "contacts", "dialer", "phone", "messaging", "vending", "chrome", "browser", "firefox", "vpn", "authenticator", "security") -> UTILITIES
                else -> OTHER
            }
        }

        private fun hint(p: String, vararg words: String) = words.any { p.contains(it) }
    }
}

/** Register each visible copy separately: Suggestions can repeat an app from another category. */
@Composable
internal fun Modifier.libraryAppInteraction(
    app: AppEntry, drag: HomeDragState?, page: Int?, instance: String, scope: String? = null,
    onLaunch: (AppEntry) -> Unit, onActions: (AppEntry) -> Unit,
): Modifier {
    val bounds = remember(app.id) { android.graphics.Rect() }
    val interaction = remember(app.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val optionsLabel = androidx.compose.ui.res.stringResource(R.string.app_options)
    val actions = { IconBounds.update(app.id, bounds); onActions(app) }
    val region = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id, instance), app.id, page, scope = scope)
        else Modifier
    return then(region).onGloballyPositioned {
        val position = it.positionOnScreen()
        bounds.set(position.x.toInt(), position.y.toInt(),
            (position.x + it.size.width).toInt(), (position.y + it.size.height).toInt())
    }.pointerInput(app.id) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            IconBounds.update(app.id, bounds)
        }
    }.graphicsLayer {
        alpha = if (pressed) .55f else 1f
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }.then(if (drag == null) Modifier.combinedClickable(interactionSource = interaction, indication = null,
        onClick = { onLaunch(app) }, onLongClick = actions)
        else Modifier.clickable(interactionSource = interaction, indication = null) { onLaunch(app) }
            .semantics { onLongClick(optionsLabel) { actions(); true } })
}

/** iOS App Library tile: three big icons and a mini cluster that opens the whole category. */
@Composable
internal fun CategoryCard(title: String, apps: List<AppEntry>, modifier: Modifier, labelColor: Color = FolioGlass.ink,
    drag: HomeDragState?, page: Int?, category: LibraryCategory,
    onLaunch: (AppEntry) -> Unit, onActions: (AppEntry) -> Unit, onOpen: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(FolioRadius.PANEL.dp))
            .materialBackground(RoundedCornerShape(FolioRadius.PANEL.dp), tint = FolioGlass.panel).padding(FolioSpace.MEDIUM.dp)) {
            val gap = 10.dp
            val cell = (maxWidth - gap) / 2
            val big = if (apps.size > 4) apps.take(3) else apps.take(4)
            val rest = if (apps.size > 4) apps.drop(3) else emptyList()
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0 until 2) {
                        val index = row * 2 + col
                        when {
                            index < big.size -> {
                                val app = big[index]
                                AppIcon(app, app.label, Modifier.size(cell)
                                    .libraryAppInteraction(app, drag, page, category.name, onLaunch = onLaunch, onActions = onActions),
                                    shape = RoundedCornerShape(cell * .24f))
                            }
                            index == 3 && rest.isNotEmpty() -> Box(Modifier.size(cell).clip(RoundedCornerShape(cell * .24f))
                                .clickable(onClick = onOpen).semantics { contentDescription = "Show all ${apps.size} $title apps" }) {
                                val mini = (cell - 4.dp) / 2
                                Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.TINY.dp)) {
                                    rest.take(4).chunked(2).forEach { pair ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.TINY.dp)) {
                                            pair.forEach { AppIcon(it, null, Modifier.size(mini).clip(RoundedCornerShape(mini * .24f))) }
                                        }
                                    }
                                }
                            }
                            else -> Spacer(Modifier.size(cell))
                        }
                    }
                }
            }
        }
        Text(title, color = labelColor, fontSize = FolioType.GROUP_LABEL.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = FolioSpace.SNUG.dp).clickable(onClick = onOpen))
    }
}

/** Locked placeholders never disclose contents; the current library session allows a badge-free preview. */
@Composable
internal fun HiddenCategoryCard(title: String, modifier: Modifier, labelColor: Color, onOpen: () -> Unit,
    apps: List<AppEntry>? = null, onLaunch: (AppEntry) -> Unit, onActions: (AppEntry) -> Unit) {
    val canOpen = apps == null || apps.size > 4
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(FolioRadius.PANEL.dp))
            .materialBackground(RoundedCornerShape(FolioRadius.PANEL.dp), tint = FolioGlass.panel)
            .clickable(enabled = canOpen, onClick = onOpen).testTag("security-hidden-folder").padding(FolioSpace.MEDIUM.dp)) {
            val gap = 10.dp
            val cell = (maxWidth - gap) / 2
            val skeleton = Color(0xFF3C3C43).copy(alpha = .82f)
            val big = if (apps.orEmpty().size > 4) apps.orEmpty().take(3) else apps.orEmpty().take(4)
            val rest = if (apps.orEmpty().size > 4) apps.orEmpty().drop(3).take(4) else emptyList()
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(2) { col ->
                            val index = row * 2 + col
                            if (apps != null && index < big.size) {
                                val app = big[index]
                                AppIcon(app, app.label, Modifier.size(cell).libraryAppInteraction(app, null, null, "hidden-preview",
                                    onLaunch = onLaunch, onActions = onActions), shape = RoundedCornerShape(cell * .24f), badge = false)
                            }
                            else if (index == 3 && (apps == null || rest.isNotEmpty())) Column(Modifier.size(cell), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val mini = (cell - 4.dp) / 2
                                repeat(2) { miniRow ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        repeat(2) { miniCol ->
                                            val app = rest.getOrNull(miniRow * 2 + miniCol)
                                            if (apps == null) Box(Modifier.size(mini).background(skeleton, RoundedCornerShape(mini * .24f)))
                                            else if (app != null) AppIcon(app, null, Modifier.size(mini),
                                                shape = RoundedCornerShape(mini * .24f), badge = false)
                                            else Spacer(Modifier.size(mini))
                                        }
                                    }
                                }
                            } else if (apps == null) Box(Modifier.size(cell).background(skeleton, RoundedCornerShape(cell * .24f)))
                            else Spacer(Modifier.size(cell))
                        }
                    }
                }
            }
        }
        Text(title, color = labelColor, fontSize = FolioType.GROUP_LABEL.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = FolioSpace.SNUG.dp).clickable(enabled = canOpen, onClick = onOpen))
    }
}

/** A category opened full size: a simple icon grid with labels. */
/** An App Library category opened like an iOS folder: big title and a rounded glass card of every app, over a dimmed background. */
@Composable
internal fun CategoryFolder(title: String, apps: List<AppEntry>, drag: HomeDragState?, page: Int?,
    onDismiss: () -> Unit, onLaunch: (AppEntry) -> Unit, onActions: (AppEntry) -> Unit, allowDrag: Boolean = true) {
    if (!rememberHomePopupVisible(onDismiss = onDismiss)) return
    val folderApps = apps.filter { (!AppSecurity.isHidden(it) || (!allowDrag && AppSecurity.hasFolderAccess(it.userSerial))) &&
        (!it.isShortcut || !AppSecurity.isProtected(it)) }
    PopupBackdropContent {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val sourceScope = if (allowDrag) "library-category" else "library-hidden"
    val scrimAlpha = LocalBackgroundMaterial.current.scrimAlpha
    DisposableEffect(drag, sourceScope) {
        drag?.activeSourceScope = sourceScope
        onDispose { drag?.let { if (it.activeSourceScope == sourceScope) it.activeSourceScope = null } }
    }
    // Share Home's pointer handler so dismissing this panel does not cancel an in-flight drag.
    Box(Modifier.fillMaxSize()) {
        val appear = rememberEntrance(stiffness = 600f, dampingRatio = .82f)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }.background(Color.Black.copy(alpha = scrimAlpha)).clickable(androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onDismiss)
            .testTag("category-folder-scrim"))
        FoldAvoidingBox(Modifier.padding(FolioSpace.XXL.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val width = minOf(maxWidth, 560.dp)
                val availableHeight = maxHeight
                // ~84dp per app: three or four columns on the cover, up to six unfolded.
                val columns = evenColumnsOnHinge(((width - 40.dp) / 84.dp).toInt().coerceIn(3, 6), 3)
                Column(Modifier.width(width).graphicsLayer {
                    alpha = appear.value; scaleX = .9f + .1f * appear.value; scaleY = scaleX
                }) {
                    Text(title, color = FolioGlass.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(bottom = FolioSpace.MEDIUM.dp)
                            .materialBackground(RoundedCornerShape(FolioRadius.GROUPED_CARD.dp), tint = FolioGlass.panel)
                            .pointerInput(Unit) { detectTapGestures() }
                            .padding(horizontal = 20.dp, vertical = FolioSpace.SMALL.dp))
                    if (folderApps.isEmpty()) Text(androidx.compose.ui.res.stringResource(
                        if (allowDrag) R.string.no_apps_found else R.string.security_hidden_empty),
                        color = FolioGlass.ink,
                        modifier = Modifier.fillMaxWidth().materialBackground(RoundedCornerShape(FolioRadius.PANEL.dp), tint = FolioGlass.panel)
                            .padding(20.dp).pointerInput(Unit) { detectTapGestures() })
                    // Lazy, so a category with dozens of apps only builds the rows on screen as it opens.
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    if (folderApps.isNotEmpty()) androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        androidx.compose.foundation.lazy.grid.GridCells.Fixed(columns),
                        Modifier.fillMaxWidth().heightIn(max = availableHeight - 64.dp - FolioSpace.SMALL.dp * 2).clip(RoundedCornerShape(FolioRadius.PANEL.dp))
                            .materialBackground(RoundedCornerShape(FolioRadius.PANEL.dp), tint = FolioGlass.panel)
                            .pointerInput(Unit) { detectTapGestures() }
                            .edgeFade(gridState).testTag("category-folder"),
                        state = gridState, contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(FolioSpace.COMFY.dp)) {
                        items(folderApps.size, key = { folderApps[it].id }) { index ->
                            val app = folderApps[index]
                            Column(Modifier.clip(RoundedCornerShape(FolioRadius.CARD.dp))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onLaunch(app) }
                                .padding(vertical = FolioSpace.TINY.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                AppIcon(app, app.label, Modifier.size(54.dp)
                                    .libraryAppInteraction(app, drag.takeIf { allowDrag }, page, sourceScope, scope = sourceScope,
                                        onLaunch = onLaunch, onActions = onActions), shape = RoundedCornerShape(13.dp))
                                Text(app.label, color = FolioGlass.ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = FolioSpace.TINY.dp, start = FolioSpace.HAIR.dp, end = FolioSpace.HAIR.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
