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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
) {
    val appOptionsLabel = stringResource(R.string.app_options)
    val glass = !editing
    val palette = LocalDuoPalette.current
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
    val focusRequest = librarySearchFocusRequests.intValue
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && !editing) {
            kotlinx.coroutines.delay(280) // let the page settle first
            runCatching { searchFocus.requestFocus(); keyboard?.show() }
        }
    }
    val listState = rememberLazyListState()
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
    LaunchedEffect(active, editing, homeRequests, showWork, selectedSerial) { lockHidden() }
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
    val visibleApps = remember(state.apps, query, showWork, workSwitch, hasWork, state.hiddenApps, state.appSecurity, editing) {
        val text = query.trim()
        // A renamed app answers to both names here, the same as in Spotlight.
        state.apps.filter { (if (workSwitch) it.isWork == showWork else !(hasWork && it.isWork)) &&
            matchesAppQuery(it, text) &&
            (editing || it.id !in state.hiddenApps) && !AppSecurity.isHidden(it, state.appSecurity) &&
            (!it.isShortcut || !AppSecurity.isProtected(it, state.appSecurity)) }
    }
    // iOS-style App Library: category tiles while browsing; the A–Z list for search, hidden and editing.
    val browsing = state.libraryCategories && !editing && query.isBlank()
    val categorized by produceState(emptyMap<LibraryCategory, List<AppEntry>>(), visibleApps, browsing, state.appSecurity) {
        if (!browsing) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            val byId = visibleApps.associateBy { it.id }
            val suggestions = RecentApps.load(context).mapNotNull(byId::get).filterNot { AppSecurity.isProtected(it, state.appSecurity) }.take(8)
            val recentlyInstalled = visibleApps.filter { !it.isShortcut }
                .distinctBy { it.userSerial to it.packageName }
                .sortedByDescending { it.firstInstallTime }.take(30)
            val grouped = visibleApps.groupBy { LibraryCategory.of(pm, it.component.packageName) }
                .mapValues { (_, apps) -> apps.sortedWith(compareBy(java.text.Collator.getInstance()) { it.label }) }
            buildMap {
                if (suggestions.isNotEmpty()) put(LibraryCategory.SUGGESTIONS, suggestions)
                if (recentlyInstalled.isNotEmpty()) put(LibraryCategory.RECENTLY_INSTALLED, recentlyInstalled)
                grouped.entries.sortedWith(compareBy({ it.key == LibraryCategory.OTHER }, { -it.value.size })).forEach { put(it.key, it.value) }
            }
        }
    }
    // Security changes take effect immediately, before asynchronous category regrouping completes.
    val visibleIds = visibleApps.mapTo(mutableSetOf()) { it.id }
    val safeCategories = categorized.mapValues { (category, apps) ->
        apps.filter { it.id in visibleIds && (category != LibraryCategory.SUGGESTIONS || !AppSecurity.isProtected(it, state.appSecurity)) }
    }.filterValues { it.isNotEmpty() }
    // Reset retained pager content on entry, and after the initial category data replaces the A–Z rows.
    LaunchedEffect(active, homeRequests, browsing, categorized.isNotEmpty(), showWork, selectedProfile?.available, selectedProfile?.quiet) {
        listState.scrollToItem(index = 0, scrollOffset = 0)
    }
    val groups = remember(visibleApps) {
        visibleApps.groupBy {
            Pinyin.heading(it.label)
        }
    }
    // The keyboard replaces Home's bottom controls rather than adding another empty margin.
    val imeBottom = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LibraryForegroundContent(modifier.imePadding().padding(bottom = if (imeBottom > 0) 0.dp else bottomSpace), enabled = glass) { libraryModifier ->
    val panelShape = RoundedCornerShape(FolioRadius.PANEL.dp)
    Surface(libraryModifier.then(if (glass) Modifier.materialBackground(panelShape, tint = FolioGlass.panel) else Modifier)
        .onGloballyPositioned { if (glass) libraryForeground?.panelBounds = it.boundsInRoot() },
        shape = panelShape,
        color = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
        contentColor = ink) {
        Column(Modifier.padding(horizontal = FolioSpace.LARGE.dp).padding(top = 18.dp)) {
            // iOS App Library has no title bar, just its search field; choosing Home apps keeps a title and count.
            if (editing) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.choose_home_apps_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(pluralStringResource(R.plurals.pinned, pinned.size, pinned.size), color = ink, fontSize = FolioType.GROUP_LABEL.sp)
            }
            if (workSwitch) Row(Modifier.fillMaxWidth().padding(top = FolioSpace.COMPACT.dp), horizontalArrangement = Arrangement.spacedBy(FolioSpace.SMALL.dp)) {
                IosChip(selected = !showWork, onClick = { showWork = false }, label = { Text(stringResource(R.string.personal)) })
                IosChip(selected = showWork, onClick = { showWork = true }, label = { Text(stringResource(R.string.work)) })
            }
            IosSearchField(query, onQuery, if (editing) stringResource(R.string.search_apps) else stringResource(R.string.app_library), Modifier.padding(vertical = FolioSpace.MEDIUM.dp),
                fieldModifier = (if (editing) Modifier else Modifier.focusRequester(searchFocus)).testTag(if (editing) "pin-search" else "library-search"),
                ink = ink, material = glass, onSearch = {
                    if (!editing && query.isNotBlank()) openWebSearch(context,
                        runCatching { WebSearchTarget.valueOf(state.searchEngine) }.getOrDefault(WebSearchTarget.GOOGLE), query)
                })
            var libraryWidth by remember { mutableStateOf(360.dp) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            LazyColumn(Modifier.weight(1f).edgeFade(listState).onSizeChanged { libraryWidth = with(density) { it.width.toDp() } }.testTag("all-apps-list"), state = listState,
                contentPadding = PaddingValues(bottom = 12.dp)) {
                if (showWork && selectedProfile?.available == false) item("work-paused") {
                    Column(Modifier.fillMaxWidth().padding(vertical = FolioSpace.XL.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (selectedProfile.quiet) stringResource(R.string.work_apps_are_paused) else stringResource(R.string.work_profile_is_unavailable))
                        if (selectedProfile.quiet) FolioButton(stringResource(R.string.turn_on_work_apps), { onTurnOnWork(selectedProfile.userSerial) },
                            Modifier.padding(top = FolioSpace.COMPACT.dp), tag = "turn-on-work")
                    }
                }
                if (!editing && query.isBlank() && downloads.isNotEmpty()) item("downloading") { DownloadingApps(ink, downloads) }
                if (!editing && query.isNotBlank()) item("web-search") {
                    WebSearchRow(query) { openWebSearch(context, it, query) }
                }
                if (browsing && safeCategories.isNotEmpty()) {
                    // Tiles stay iPhone-sized: more columns on the wide inner screen instead of giant tiles.
                    val columns = libraryColumns(libraryWidth.value)
                    items(safeCategories.entries.toList().chunked(columns), key = { row -> "cat-" + row.first().key.name }) { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = FolioSpace.COMFY.dp), horizontalArrangement = Arrangement.spacedBy(FolioSpace.COMFY.dp)) {
                            row.forEach { (cat, apps) ->
                                CategoryCard(stringResource(cat.title), apps, Modifier.weight(1f), labelColor = ink,
                                    drag = drag, page = page, category = cat,
                                    onLaunch = { onLaunchFrom(it, null) }, onActions = onActions) { onOpenCategory(cat, apps) }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else if (groups.isEmpty()) item { Text(if (state.loading) stringResource(R.string.loading_apps) else stringResource(R.string.no_apps_found), Modifier.padding(vertical = FolioSpace.XL.dp)) }
                if (!(browsing && safeCategories.isNotEmpty())) groups.forEach { (letter, entries) ->
                    stickyHeader(key = "heading-$letter") {
                        Row(Modifier.fillMaxWidth().padding(top = FolioSpace.SMALL.dp, bottom = FolioSpace.SNUG.dp), verticalAlignment = Alignment.CenterVertically) {
                            // An opaque small chip prevents text from showing through the sticky letter.
                            Box(Modifier.size(width = 32.dp, height = 28.dp).background(
                                if (glass) (if (palette.dark) Color(0xFF314852) else Color(0xFFB7CBD3))
                                else MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(FolioRadius.CONTROL.dp)), contentAlignment = Alignment.Center) {
                                Text(letter, color = ink, fontWeight = FontWeight.SemiBold, fontSize = FolioType.GROUP_LABEL.sp)
                            }
                            if (glass) HorizontalDivider(Modifier.weight(1f).padding(start = FolioSpace.COMPACT.dp), color = ink.copy(alpha = .24f))
                        }
                    }
                    items(entries, key = { it.id }) { app ->
                        val isPinned = app.id in pinned
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
                                // iOS selection: filled blue check when on Home, empty ring when not.
                                Icon(if (isPinned) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    if (isPinned) stringResource(R.string.remove_from_home_3, app.label) else stringResource(R.string.pin_to_home, app.label),
                                    tint = if (isPinned) LocalAccent.current.ink else ink.copy(alpha = .35f),
                                    modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                if (!editing && query.isBlank()) item("security-hidden") {
                    val columns = libraryColumns(libraryWidth.value)
                    Row(Modifier.fillMaxWidth().padding(bottom = FolioSpace.COMFY.dp),
                        horizontalArrangement = Arrangement.spacedBy(FolioSpace.COMFY.dp)) {
                        HiddenCategoryCard(hiddenTitle, Modifier.weight(1f), ink, openHidden,
                            apps = hiddenApps.takeIf { active && hiddenProfile == selectedSerial && AppSecurity.hasFolderAccess(selectedSerial) },
                            onLaunch = { if (AppSecurity.hasFolderAccess(selectedSerial)) onLaunchFrom(it, null) },
                            onActions = { if (AppSecurity.hasFolderAccess(selectedSerial)) onActions(it) })
                        repeat(columns - 1) { Spacer(Modifier.weight(1f)) }
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

@Composable
private fun WebSearchRow(query: String, onSearch: (WebSearchTarget) -> Unit) {
    val ink = LocalContentColor.current
    Column(Modifier.fillMaxWidth().padding(bottom = FolioSpace.SMALL.dp)) {
        Text(stringResource(R.string.search_1_with, query.trim()), fontSize = FolioType.GROUP_LABEL.sp, color = LocalContentColor.current.copy(alpha = .75f),
            modifier = Modifier.padding(bottom = FolioSpace.SNUG.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.SMALL.dp)) {
            WebSearchTarget.entries.forEach { target ->
                AssistChip(onClick = { onSearch(target) }, label = { Text(target.label) },
                    leadingIcon = { Icon(if (target.label.startsWith("Ask")) Icons.Rounded.AutoAwesome else Icons.Rounded.Public,
                        null, Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("web-search-${target.name.lowercase()}"),
                    colors = AssistChipDefaults.assistChipColors(labelColor = ink, leadingIconContentColor = ink))
            }
        }
    }
}
