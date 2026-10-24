@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.mccal.folio

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Incremented to focus the All apps search field (e.g. after a middle swipe-down on Home). */
internal val librarySearchFocusRequests = mutableIntStateOf(0)

@Composable
internal fun AppLibrary(
    state: LauncherState, query: String, onQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit, onPin: (String, Boolean) -> Unit, onActions: (AppEntry) -> Unit,
    modifier: Modifier = Modifier, editing: Boolean = false,
    drag: HomeDragState? = null, page: Int? = null,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onTurnOnWork: (Long) -> Unit = {},
    homeRequests: Int = 0,
    active: Boolean = true,
    onOpenCategory: (LibraryCategory, List<AppEntry>) -> Unit = { _, _ -> },
    bottomSpace: Dp = 0.dp,
    searchActive: Boolean = false,
    onSearchActiveChange: (Boolean) -> Unit = {},
    searchIndexWidth: Dp = 52.dp,
    browseEndSpace: Dp = 0.dp,
) {
    val searching = searchActive && !editing
    val glass = !editing
    val libraryForeground = LocalLibraryForeground.current
    val ink = if (glass) FolioGlass.ink else MaterialTheme.colorScheme.onSurface
    val pinned = remember(state.homeSlots, state.leadingSlots) {
        (state.homeSlots.asSequence() + state.leadingSlots.asSequence()).filterNotNull().toSet()
    }
    val hasWork = state.profiles.any { it.isWork } || state.apps.any { it.isWork }
    // With Work turned off in Settings the switch goes away and only personal apps are listed.
    val workSwitch = hasWork && state.libraryWork
    var showWork by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val closeSearch = {
        focus.clearFocus(); keyboard?.hide(); onQuery(""); onSearchActiveChange(false)
    }
    var wasSearching by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (wasSearching && !searching) { focus.clearFocus(); keyboard?.hide() }
        wasSearching = searching
    }
    androidx.activity.compose.BackHandler(enabled = active && searching && drag?.active != true) { closeSearch() }
    val focusRequest = librarySearchFocusRequests.intValue
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && !editing) {
            kotlinx.coroutines.delay(280) // let the page settle first
            runCatching { searchFocus.requestFocus(); keyboard?.show() }
        }
    }
    val listState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, searching, showWork) { searchListState.scrollToItem(0) }
    val selectedProfile = if (showWork) state.profiles.firstOrNull { it.isWork } else state.profiles.firstOrNull { it.isPersonal }
    val selectedSerial = selectedProfile?.userSerial ?: state.apps.firstOrNull { it.isWork == showWork }?.userSerial
        ?: context.getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(android.os.Process.myUserHandle())
    val hiddenApps = state.apps.filter { it.userSerial == selectedSerial && AppSecurity.isHidden(it, state.appSecurity) && !it.isShortcut }
        .distinctBy { it.packageName }.sortedWith(compareBy(java.text.Collator.getInstance()) { it.label })
    var hiddenProfile by remember { mutableStateOf<Long?>(null) }
    var hiddenFolderOpen by remember { mutableStateOf(false) }
    val hiddenTitle = stringResource(R.string.security_hidden)
    val authTitle = stringResource(R.string.security_auth_title)
    val currentActive by rememberUpdatedState(active && !editing)
    val currentProfile by rememberUpdatedState(selectedSerial)
    val currentShowWork by rememberUpdatedState(showWork)
    val currentHomeRequests by rememberUpdatedState(homeRequests)
    val closeHidden = { hiddenFolderOpen = false }
    val lockHidden = { hiddenFolderOpen = false; hiddenProfile = null; AppSecurity.closeFolder() }
    LaunchedEffect(active, editing, homeRequests, showWork, selectedSerial, searching) { lockHidden() }
    LaunchedEffect(hiddenApps.size) { if (hiddenApps.size <= 4) hiddenFolderOpen = false }
    DisposableEffect(Unit) { onDispose { if (hiddenProfile != null) AppSecurity.closeFolder() } }
    val openHidden: () -> Unit = {
        val requestedWork = showWork
        val serial = selectedSerial
        if (hiddenProfile == serial && AppSecurity.hasFolderAccess(serial)) hiddenFolderOpen = hiddenApps.size > 4
        else if (activity != null) AppSecurity.authenticate(activity, authTitle, onSuccess = {
            if (currentActive && currentShowWork == requestedWork && currentHomeRequests == homeRequests &&
                currentProfile == serial) {
                AppSecurity.openFolder(serial)
                hiddenProfile = serial
                hiddenFolderOpen = false
            }
        })
    }
    val downloads = Installs.active.collectAsStateWithLifecycle().value.values
        .filter { it.newApp && !AppSecurity.isHidden(it.packageName) }.distinctBy { it.packageName }
    // Hidden apps stay out of the App Library entirely, like iOS; they're listed (after unlocking) in Settings.
    val libraryApps = remember(state.apps, showWork, workSwitch, hasWork, state.hiddenApps, state.appSecurity, editing) {
        state.apps.filter { (if (workSwitch) it.isWork == showWork else !(hasWork && it.isWork)) &&
            (editing || it.id !in state.hiddenApps) && !AppSecurity.isHidden(it, state.appSecurity) &&
            (!it.isShortcut || !AppSecurity.isProtected(it, state.appSecurity)) }
    }
    // Keep the browsing content in place while the separate app-only search list covers it.
    val visibleApps = remember(libraryApps, query, editing) {
        val text = query.trim()
        when {
            text.isBlank() -> libraryApps
            editing -> libraryApps.filter { matchesAppQuery(it, text) }
            else -> rankApps(libraryApps, text)
        }
    }
    val browsing = state.libraryCategories && !editing
    val categorized by produceState(emptyMap<LibraryCategory, List<AppEntry>>(), libraryApps, browsing, state.appSecurity) {
        if (!browsing) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            val byId = libraryApps.associateBy { it.id }
            val suggestions = RecentApps.load(context).mapNotNull(byId::get).filterNot { AppSecurity.isProtected(it, state.appSecurity) }.take(8)
            val recentlyInstalled = libraryApps.filter { !it.isShortcut }
                .distinctBy { it.userSerial to it.packageName }
                .sortedByDescending { it.firstInstallTime }.take(30)
            val grouped = libraryApps.groupBy { LibraryCategory.of(pm, it.component.packageName) }
                .mapValues { (_, apps) -> apps.sortedWith(compareBy(java.text.Collator.getInstance()) { it.label }) }
            buildMap {
                if (suggestions.isNotEmpty()) put(LibraryCategory.SUGGESTIONS, suggestions)
                if (recentlyInstalled.isNotEmpty()) put(LibraryCategory.RECENTLY_INSTALLED, recentlyInstalled)
                grouped.entries.sortedWith(compareBy({ it.key == LibraryCategory.OTHER }, { -it.value.size })).forEach { put(it.key, it.value) }
            }
        }
    }
    // Security changes take effect immediately, before asynchronous category regrouping completes.
    val visibleIds = libraryApps.mapTo(mutableSetOf()) { it.id }
    val safeCategories = categorized.mapValues { (category, apps) ->
        apps.filter { it.id in visibleIds && (category != LibraryCategory.SUGGESTIONS || !AppSecurity.isProtected(it, state.appSecurity)) }
    }.filterValues { it.isNotEmpty() }
    // Reset retained pager content on entry, and after the initial category data replaces the A–Z rows.
    LaunchedEffect(active, homeRequests, browsing, categorized.isNotEmpty(), showWork, selectedProfile?.available, selectedProfile?.quiet) {
        listState.scrollToItem(index = 0, scrollOffset = 0)
    }
    val groups = remember(libraryApps, visibleApps, editing) {
        (if (editing) visibleApps else libraryApps).groupBy { Pinyin.heading(it.label) }
    }
    val searchGroups = remember(visibleApps) {
        val collator = java.text.Collator.getInstance()
        visibleApps.groupBy { librarySearchInitial(it.label) }
            .toSortedMap(compareBy<String>({ it == "#" }, { it }))
            .mapValues { (_, apps) -> apps.sortedWith(compareBy(collator) { it.label }) }
    }
    val workPaused = showWork && selectedProfile?.available == false
    val searchHeaders = remember(searchGroups, workPaused) {
        var position = if (workPaused) 1 else 0
        buildMap {
            searchGroups.forEach { (letter, apps) ->
                put(letter, position)
                position += apps.size + 1
            }
        }
    }
    // The keyboard replaces Home's bottom controls rather than adding another empty margin.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val blurRadius = with(density) { 32.dp.toPx() }
    val searchBlur = remember(blurRadius) { BlurEffect(blurRadius, blurRadius, TileMode.Clamp) }
    val imeHeight = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    // Search keeps its full-height backdrop and index. Only the app list avoids the keyboard.
    val libraryInsets = if (searching) Modifier else Modifier.imePadding()
        .padding(bottom = (bottomSpace - imeHeight).coerceAtLeast(0.dp))
    LibraryForegroundContent(modifier.then(libraryInsets), enabled = glass) { libraryModifier ->
    val panelShape = RoundedCornerShape(FolioRadius.PANEL.dp)
    Surface(libraryModifier
        .onGloballyPositioned { if (glass) libraryForeground?.panelBounds = it.boundsInRoot() },
        shape = panelShape,
        color = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
        contentColor = ink) {
        Box(Modifier.fillMaxSize()) {
        if (glass) Box(Modifier.matchParentSize()
            .graphicsLayer { renderEffect = if (searching) searchBlur else null }
            .materialBackground(panelShape, tint = FolioGlass.panel))
        Column(Modifier.padding(top = 18.dp)) {
            // iOS App Library has no title bar, just its search field; choosing Home apps keeps a title and count.
            if (editing) Row(Modifier.padding(horizontal = FolioSpace.LARGE.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.choose_home_apps_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(pluralStringResource(R.plurals.pinned, pinned.size, pinned.size), color = ink, fontSize = FolioType.GROUP_LABEL.sp)
            }
            if (workSwitch) Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpace.LARGE.dp).padding(top = FolioSpace.COMPACT.dp), horizontalArrangement = Arrangement.spacedBy(FolioSpace.SMALL.dp)) {
                IosChip(selected = !showWork, onClick = { showWork = false }, label = { Text(stringResource(R.string.personal)) })
                IosChip(selected = showWork, onClick = { showWork = true }, label = { Text(stringResource(R.string.work)) })
            }
            Row(Modifier.fillMaxWidth().zIndex(1f).padding(horizontal = FolioSpace.LARGE.dp)
                .padding(vertical = FolioSpace.MEDIUM.dp),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.COMPACT.dp), verticalAlignment = Alignment.CenterVertically) {
                IosSearchField(query, onQuery, if (editing) stringResource(R.string.search_apps) else stringResource(R.string.app_library),
                    Modifier.weight(1f).then(if (editing) Modifier else Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onSearchActiveChange(true); searchFocus.requestFocus(); keyboard?.show()
                    }),
                    fieldModifier = (if (editing) Modifier else Modifier.focusRequester(searchFocus)
                        .onFocusChanged { if (it.isFocused) onSearchActiveChange(true) })
                        .testTag(if (editing) "pin-search" else "library-search"),
                    ink = ink, material = glass, onSearch = { focus.clearFocus(); keyboard?.hide() },
                    showClearButton = editing, imeAction = androidx.compose.ui.text.input.ImeAction.Done)
                if (searching) Box(Modifier.size(52.dp).clip(CircleShape)
                    .materialBackground(CircleShape, tint = FolioGlass.panel)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = closeSearch)
                    .testTag("library-search-close"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.close), Modifier.size(24.dp), tint = ink)
                }
            }
            var libraryWidth by remember { mutableStateOf(360.dp) }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            LazyColumn(Modifier.fillMaxSize()
                .graphicsLayer { renderEffect = if (searching) searchBlur else null }
                .padding(start = FolioSpace.LARGE.dp,
                end = FolioSpace.LARGE.dp + if (searching) browseEndSpace else 0.dp)
                .onSizeChanged { libraryWidth = with(density) { it.width.toDp() } }.edgeFade(listState)
                .then(if (searching) Modifier.clearAndSetSemantics { } else Modifier)
                .testTag("all-apps-list"), state = listState,
                contentPadding = PaddingValues(bottom = 12.dp)) {
                if (workPaused) item("work-paused") {
                    Column(Modifier.fillMaxWidth().padding(vertical = FolioSpace.XL.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (selectedProfile?.quiet == true) stringResource(R.string.work_apps_are_paused) else stringResource(R.string.work_profile_is_unavailable))
                        if (selectedProfile?.quiet == true) FolioButton(stringResource(R.string.turn_on_work_apps), { onTurnOnWork(selectedProfile.userSerial) },
                            Modifier.padding(top = FolioSpace.COMPACT.dp), tag = "turn-on-work")
                    }
                }
                if (!editing && downloads.isNotEmpty()) item("downloading") { DownloadingApps(ink, downloads) }
                if (browsing && safeCategories.isNotEmpty()) {
                    // Tiles stay iPhone-sized: more columns on the wide inner screen instead of giant tiles.
                    val columns = libraryColumns(libraryWidth.value)
                    items(safeCategories.entries.toList().chunked(columns), key = { row -> "cat-" + row.first().key.name }) { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = FolioSpace.COMFY.dp), horizontalArrangement = Arrangement.spacedBy(FolioSpace.COMFY.dp)) {
                            row.forEach { (cat, apps) ->
                                CategoryCard(stringResource(cat.title), apps, Modifier.weight(1f), labelColor = ink,
                                    drag = if (searching) null else drag, page = page, category = cat,
                                    onLaunch = { onLaunchFrom(it, null) }, onActions = onActions) { onOpenCategory(cat, apps) }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else if (groups.isEmpty()) item { Text(if (state.loading) stringResource(R.string.loading_apps) else stringResource(R.string.no_apps_found), Modifier.padding(vertical = FolioSpace.XL.dp)) }
                if (!(browsing && safeCategories.isNotEmpty())) groups.forEach { (letter, entries) ->
                    stickyHeader(key = "heading-$letter") {
                        LibraryAppHeading(letter, glass, ink)
                    }
                    items(entries, key = { it.id }) { app ->
                        LibraryAppRow(app, ink, if (searching) null else drag, page, onLaunchFrom, onActions,
                            editing, app.id in pinned, onPin)
                    }
                }
                if (!editing) item("security-hidden") {
                    val columns = libraryColumns(libraryWidth.value)
                    Row(Modifier.fillMaxWidth().padding(bottom = FolioSpace.COMFY.dp),
                        horizontalArrangement = Arrangement.spacedBy(FolioSpace.COMFY.dp)) {
                        HiddenCategoryCard(hiddenTitle, Modifier.weight(1f), ink, openHidden,
                            apps = hiddenApps.takeIf { active && !searching && hiddenProfile == selectedSerial && AppSecurity.hasFolderAccess(selectedSerial) },
                            onLaunch = { if (AppSecurity.hasFolderAccess(selectedSerial)) onLaunchFrom(it, null) },
                            onActions = { if (AppSecurity.hasFolderAccess(selectedSerial)) onActions(it) })
                        repeat(columns - 1) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (searching) Box(Modifier.fillMaxSize()
                .background(FolioGlass.panel.copy(alpha = BackgroundMaterial.STRONG_BLUR.fillAlpha))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    focus.clearFocus(); keyboard?.hide()
                }.testTag("library-app-search")) {
                if (visibleApps.isEmpty()) Column(Modifier.fillMaxSize().imePadding().padding(FolioSpace.XL.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(52.dp), tint = ink.copy(alpha = .55f))
                    Text(if (state.loading) stringResource(R.string.loading_apps)
                        else if (query.isBlank()) stringResource(R.string.no_apps_found)
                        else stringResource(R.string.library_search_no_results, query.trim()),
                        Modifier.padding(top = FolioSpace.MEDIUM.dp), color = ink, fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (!state.loading && query.isNotBlank()) Text(stringResource(R.string.library_search_try_again),
                        Modifier.padding(top = FolioSpace.SMALL.dp), color = ink.copy(alpha = .62f), fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } else {
                    LazyColumn(Modifier.fillMaxSize().imePadding()
                        .padding(start = FolioSpace.LARGE.dp, end = searchIndexWidth).edgeFade(searchListState)
                        .testTag("library-search-results"), state = searchListState,
                        contentPadding = PaddingValues(horizontal = FolioSpace.SMALL.dp, vertical = FolioSpace.SMALL.dp)) {
                        if (workPaused) item("work-paused") {
                            Text(stringResource(R.string.work_apps_are_paused), Modifier.padding(vertical = FolioSpace.MEDIUM.dp))
                        }
                        searchGroups.forEach { (letter, entries) ->
                            stickyHeader(key = "search-heading-$letter") { LibraryAppHeading(letter, true, ink) }
                            items(entries, key = { it.id }) { app ->
                                LibraryAppRow(app, ink, drag, page, onLaunchFrom, onActions)
                            }
                        }
                    }
                    if (query.isNotBlank()) Box(Modifier.align(Alignment.TopEnd).width(searchIndexWidth).fillMaxHeight()) {
                        AppLibraryIndex(searchGroups.keys.toList(), onSelect = { letter ->
                            searchHeaders[letter]?.let { position -> scope.launch { searchListState.scrollToItem(position) } }
                        }, modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight().padding(top = FolioSpace.SMALL.dp), ink = ink)
                    }
                }
            }
            }
        }
        }
    }
    }
    hiddenProfile?.takeIf { hiddenFolderOpen && hiddenApps.size > 4 && active && !editing && it == selectedSerial && AppSecurity.hasFolderAccess(it) }?.let { serial ->
        CategoryFolder(hiddenTitle, hiddenApps, drag = drag, page = page,
            onDismiss = closeHidden,
            onLaunch = { if (AppSecurity.hasFolderAccess(serial)) onLaunchFrom(it, null) },
            onActions = { if (AppSecurity.hasFolderAccess(serial)) onActions(it) }, allowDrag = false)
    }
}

private fun librarySearchInitial(label: String): String {
    val name = label.trimStart()
    val first = name.firstOrNull() ?: return "#"
    return if (first in '\uAC00'..'\uD7A3') "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"[(first.code - 0xAC00) / 588].toString()
        else Pinyin.heading(name)
}

@Composable
private fun LibraryAppHeading(letter: String, glass: Boolean, ink: Color) {
    Row(Modifier.fillMaxWidth().padding(top = FolioSpace.SMALL.dp, bottom = FolioSpace.SNUG.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 32.dp, height = 28.dp).background(
            if (glass) FolioGlass.panel.copy(alpha = 1f) else MaterialTheme.colorScheme.surfaceContainer,
            RoundedCornerShape(FolioRadius.CONTROL.dp)), contentAlignment = Alignment.Center) {
            Text(letter, color = ink, fontWeight = FontWeight.SemiBold, fontSize = FolioType.GROUP_LABEL.sp)
        }
        if (glass) HorizontalDivider(Modifier.weight(1f).padding(start = FolioSpace.COMPACT.dp), color = ink.copy(alpha = .24f))
    }
}

@Composable
private fun LibraryAppRow(app: AppEntry, ink: Color, drag: HomeDragState?, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    editing: Boolean = false, isPinned: Boolean = false, onPin: (String, Boolean) -> Unit = { _, _ -> }) {
    val appOptionsLabel = stringResource(R.string.app_options)
    val launchBounds = remember { android.graphics.Rect() }
    val interaction = remember(app.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragModifier = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id), app.id, page) else Modifier
    val click = { if (editing) onPin(app.id, !isPinned) else onLaunchFrom(app, launchBounds) }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).then(dragModifier).clip(RoundedCornerShape(FolioRadius.CARD.dp)).testTag("library-app-${app.id}")
        .then(if (drag == null) Modifier.combinedClickable(interactionSource = interaction, indication = null, onClick = click, onLongClick = { onActions(app) })
            else Modifier.clickable(interactionSource = interaction, indication = null, onClick = click).semantics { onLongClick(appOptionsLabel) { onActions(app); true } })
        .padding(vertical = FolioSpace.SNUG.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app, null, Modifier.size(40.dp)
            .graphicsLayer {
                alpha = if (pressed) .55f else 1f
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
            .onGloballyPositioned { launchBounds.set(it.boundsInWindow().toAndroidBounds()) }.clip(RoundedCornerShape(FolioRadius.CONTROL.dp)))
        Row(Modifier.weight(1f).padding(start = FolioSpace.MEDIUM.dp), verticalAlignment = Alignment.CenterVertically) {
            NewAppDot(app.packageName, 7.dp)
            Text(app.label, maxLines = 2, fontSize = 14.sp)
        }
        if (editing) IconButton(onClick = { onPin(app.id, !isPinned) }, Modifier.testTag("pin-${app.id}")) {
            Icon(if (isPinned) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                if (isPinned) stringResource(R.string.remove_from_home_3, app.label) else stringResource(R.string.pin_to_home, app.label),
                tint = if (isPinned) LocalAccent.current.ink else ink.copy(alpha = .35f), modifier = Modifier.size(24.dp))
        }
    }
}
