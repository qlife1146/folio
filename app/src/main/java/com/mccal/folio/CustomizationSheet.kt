package com.mccal.folio

import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Where Settings was when it closed, so opening it again picks up there, like iPhone Settings. The page itself is
 * kept by Home; this holds the rest. In memory only, so a restart opens at the top.
 */
/** A page to open Settings on, set by something outside Settings (the setup reminder on Home). */
internal object SettingsLink { var page: CustomizationPage? = null }

internal object SettingsMemory {
    var focusId = ""
    val bodyScroll = mutableMapOf<String, Int>()
    var sidebarScroll = 0
}

internal enum class CustomizationPage { OVERVIEW, SETUP, WALLPAPER, HOME, STATUS, GESTURES, FOLD, BACKUP, HELP, SIDE_KEY, LOCK, CREDITS, ADVANCED, NOTIFICATIONS, SEARCH, TODAY, ISLAND, PERMISSIONS, FOCUS, FOCUS_MODE, ISLAND_APPS;

    /** The page Back returns to: the nav bar button and the system Back gesture both use it. */
    val parent: CustomizationPage get() = when (this) {
        FOCUS_MODE -> FOCUS
        ISLAND_APPS -> ISLAND
        else -> OVERVIEW
    }

    /** The top-level page this one lives under, highlighted in the split view's sidebar. */
    val root: CustomizationPage get() = if (parent == OVERVIEW) this else parent.root
}

@Composable
internal fun CustomizationSheet(state: LauncherState, initiallyWide: Boolean, model: LauncherModel,
    isDefaultHome: Boolean, page: CustomizationPage, onPage: (CustomizationPage) -> Unit,
    onMakeDefault: () -> Unit, onClose: () -> Unit, onEditPins: () -> Unit, onWidget: (Int) -> Unit,
    onAddWidget: (Int) -> Unit, onRemoveWidget: (Int) -> Unit, onWallpaperPreview: () -> Unit,
    onExportLayout: () -> Unit, onImportLayout: () -> Unit, onSaveLayoutToFolder: (String) -> Unit = {},
    appearance: AppearanceState, onAppearanceMode: (AppearanceMode) -> Unit, onAppearanceAccent: (AccentChoice) -> Unit = {},
    onAppearanceManual: (String, Double, Double) -> Unit, onAppearanceDeviceLocation: () -> Unit,
    onAppearanceClear: () -> Unit, backgrounds: LauncherBackgroundController, homePage: Int = 0,
    onShadeSetup: () -> Unit = {},
    onShowWelcome: () -> Unit = {},
) {
    if (page == CustomizationPage.SIDE_KEY || page == CustomizationPage.LOCK || page == CustomizationPage.FOLD) {
        LaunchedEffect(page) { onPage(CustomizationPage.OVERVIEW) }
        return
    }
    var wide by rememberSaveable { mutableStateOf(initiallyWide) }
    var focusId by rememberSaveable { mutableStateOf(SettingsMemory.focusId) }
    SideEffect { SettingsMemory.focusId = focusId }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    var namingBackup by remember { mutableStateOf(false) }
    val updateContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var updateAttempt by remember { mutableIntStateOf(0) }
    var updateResult by remember { mutableStateOf<AppUpdateResult?>(null) }
    LaunchedEffect(updateAttempt) {
        updateResult = null
        updateResult = checkAppUpdate(updateContext)
    }
    val title = when (page) {
        CustomizationPage.OVERVIEW -> stringResource(R.string.folio)
        CustomizationPage.SETUP -> stringResource(R.string.setup_checklist)
        CustomizationPage.WALLPAPER -> stringResource(R.string.wallpaper_appearance)
        CustomizationPage.HOME -> stringResource(R.string.home_screen_dock)
        CustomizationPage.STATUS -> stringResource(R.string.icons_side_bar)
        CustomizationPage.GESTURES -> stringResource(R.string.gestures_actions)
        CustomizationPage.FOLD, CustomizationPage.SIDE_KEY, CustomizationPage.LOCK -> stringResource(R.string.folio)
        CustomizationPage.BACKUP -> stringResource(R.string.backup)
        CustomizationPage.HELP -> stringResource(R.string.help)
        CustomizationPage.CREDITS -> stringResource(R.string.credits)
        CustomizationPage.FOCUS -> stringResource(R.string.focus)
        CustomizationPage.FOCUS_MODE -> state.focusModes.firstOrNull { it.id == focusId }?.name ?: stringResource(R.string.focus)
        CustomizationPage.ADVANCED -> stringResource(R.string.advanced)
        CustomizationPage.NOTIFICATIONS -> stringResource(R.string.notifications_control_center)
        CustomizationPage.SEARCH -> stringResource(R.string.search_app_library)
        CustomizationPage.TODAY -> stringResource(R.string.today_view)
        CustomizationPage.ISLAND -> stringResource(R.string.live_activities)
        CustomizationPage.ISLAND_APPS -> stringResource(R.string.other_notifications)
        CustomizationPage.PERMISSIONS -> stringResource(R.string.privacy_permissions)
    }
    // Each destination owns its scroll position, including lists kept beside a nested page.
    val pageScrollStates = remember { mutableMapOf<String, ScrollState>() }
    val scrollForPage: (CustomizationPage) -> ScrollState = { destination ->
        val identity = when (destination) {
            CustomizationPage.FOCUS_MODE -> "${destination.name}:$focusId"
            CustomizationPage.OVERVIEW -> if (settingsQuery.isBlank()) destination.name else "${destination.name}:search"
            else -> destination.name
        }
        pageScrollStates.getOrPut(identity) { ScrollState(SettingsMemory.bodyScroll[identity] ?: 0) }
    }
    val bodyScroll = scrollForPage(page)
    val sidebarScroll = rememberScrollState(SettingsMemory.sidebarScroll)
    DisposableEffect(Unit) {
        onDispose {
            pageScrollStates.forEach { (destination, scroll) -> SettingsMemory.bodyScroll[destination] = scroll.value }
            SettingsMemory.sidebarScroll = sidebarScroll.value
        }
    }
    val setupSteps = rememberSetupSteps(isDefaultHome, onMakeDefault, onShadeSetup, state.messagesApp, model::setMessagesApp, state.systemWallpaper, model::setSystemWallpaper)
    val setupLeft = setupSteps.count { it.required && !it.done }
    val onBack = { onPage(page.parent) }
    val sheetContext = androidx.compose.ui.platform.LocalContext.current
    var waveformPermissionGranted by remember {
        mutableStateOf(sheetContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val waveformPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> waveformPermissionGranted = granted }
    val nestedBackLabel = when (page.parent) { CustomizationPage.FOCUS -> stringResource(R.string.focus); CustomizationPage.ISLAND -> stringResource(R.string.live_activities); else -> null }

    // The settings list. On the phone it's the first page; in the split view it's the sidebar, with the open page highlighted.
    val overviewRows: @Composable ColumnScope.(selected: CustomizationPage?, sidebar: Boolean) -> Unit = { selected, sidebar ->
                    SheetGroup {
                        TweakRow(Icons.Rounded.Wallpaper, FolioColors.Value.CyanLight, stringResource(R.string.wallpaper_appearance), "customization-wallpaper",
                            if (backgrounds.previewPending) stringResource(R.string.photo_ready) else null, selected = selected == CustomizationPage.WALLPAPER, chevron = !sidebar) { onPage(CustomizationPage.WALLPAPER) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.GridView, FolioColors.Value.Blue, stringResource(R.string.home_screen_dock), "customization-home", selected = selected == CustomizationPage.HOME, chevron = !sidebar) { onPage(CustomizationPage.HOME) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.Today, FolioColors.Value.Orange, stringResource(R.string.today_view), "customization-today", selected = selected == CustomizationPage.TODAY, chevron = !sidebar) { onPage(CustomizationPage.TODAY) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.Apps, FolioColors.Value.Indigo, stringResource(R.string.icons_side_bar), "customization-status", selected = selected == CustomizationPage.STATUS, chevron = !sidebar) { onPage(CustomizationPage.STATUS) }
                    }
                    SheetGroup {
                        TweakRow(Icons.Rounded.Circle, FolioColors.Value.SecondaryBackground, stringResource(R.string.live_activities), "customization-island", selected = selected == CustomizationPage.ISLAND, chevron = !sidebar) { onPage(CustomizationPage.ISLAND) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.Notifications, FolioColors.Value.RedLight, stringResource(R.string.notifications_control_center), "customization-notifications", selected = selected == CustomizationPage.NOTIFICATIONS, chevron = !sidebar) { onPage(CustomizationPage.NOTIFICATIONS) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.DarkMode, FolioColors.Value.Indigo, stringResource(R.string.focus), "customization-focus",
                            state.focusModes.firstOrNull { it.id == state.activeFocus }?.name, selected = selected == CustomizationPage.FOCUS, chevron = !sidebar) { onPage(CustomizationPage.FOCUS) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.Search, FolioColors.Value.Gray, stringResource(R.string.search_app_library), "customization-search", selected = selected == CustomizationPage.SEARCH, chevron = !sidebar) { onPage(CustomizationPage.SEARCH) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.Gesture, FolioColors.Value.Teal, stringResource(R.string.gestures_actions), "customization-gestures", selected = selected == CustomizationPage.GESTURES, chevron = !sidebar) { onPage(CustomizationPage.GESTURES) }
                    }
                    SheetGroup {
                        TweakRow(Icons.Rounded.Save, FolioColors.Value.Gray, stringResource(R.string.backup), "customization-backup", selected = selected == CustomizationPage.BACKUP, chevron = !sidebar) { onPage(CustomizationPage.BACKUP) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.PanTool, FolioColors.Value.Blue, stringResource(R.string.privacy_permissions), "customization-permissions", selected = selected == CustomizationPage.PERMISSIONS, chevron = !sidebar) { onPage(CustomizationPage.PERMISSIONS) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.Settings, FolioColors.Value.Gray, stringResource(R.string.advanced), "customization-advanced", selected = selected == CustomizationPage.ADVANCED, chevron = !sidebar) { onPage(CustomizationPage.ADVANCED) }
                    }
                    SheetGroup {
                        TweakRow(Icons.Rounded.HelpOutline, FolioColors.Value.Blue, stringResource(R.string.help), "customization-help", selected = selected == CustomizationPage.HELP, chevron = !sidebar) { onPage(CustomizationPage.HELP) }
                        MenuDivider()
                        TweakRow(Icons.Rounded.Favorite, FolioColors.Value.Red, stringResource(R.string.credits), "customization-credits", selected = selected == CustomizationPage.CREDITS, chevron = !sidebar) { onPage(CustomizationPage.CREDITS) }
                    }
    }
    // Home-app actions and the setup reminder: above the list on the phone, on Folio's own page in the split view.
    val overviewActions: @Composable ColumnScope.() -> Unit = {
                    if (!isDefaultHome || state.canUndoEdit) SheetGroup {
                        if (!isDefaultHome) IosActionRow(stringResource(R.string.set_as_home_app), "default-home-settings", onClick = onMakeDefault)
                        if (!isDefaultHome && state.canUndoEdit) MenuDivider()
                        if (state.canUndoEdit) IosActionRow(stringResource(R.string.undo_last_layout_change), onClick = { model.undoEdit(); onClose() })
                    }
                    if (setupLeft > 0) {
                        val required = setupSteps.count { it.required }
                        CustomizationDestination(Icons.Rounded.Checklist, stringResource(R.string.finish_setting_up_folio),
                            pluralStringResource(R.plurals.setup_steps_left_full, setupLeft, setupLeft), "customization-setup",
                            leading = { SetupRing(required - setupLeft, required, 40.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .15f)) }) {
                            onPage(CustomizationPage.PERMISSIONS)
                        }
                    }
                    AppUpdateSettings(updateResult) { updateResult = null; updateAttempt++ }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Roomy landscape windows show the sidebar beside the page; portrait uses the same navigation as the cover.
    val fullWidth = maxWidth
    // The keyboard covers Settings; it doesn't make the window smaller (ADP-1, ADP-18). Measured out here, so that
    // tapping the search field can't turn the unfolded screen into a phone-sized one for as long as the keyboard is
    // up, take the sidebar away with it, and take the field you just tapped with the sidebar (#117).
    val keyboardDp = keyboardDpOverSheet()
    // With only one pane to spare, Settings shows the list and a page pushed over it with Back.
    val split = settingsSplits(maxWidth.value, maxHeight.value, androidx.compose.ui.platform.LocalConfiguration.current.classScale,
        keyboardDp, LocalSettingsMaxColumns.current)
    // Takes the page to draw rather than reading the open one, so the split view can show a list and the thing you
    // picked from it side by side. Inside, `page` means "the page this column is drawing".
    val pageContent: @Composable ColumnScope.(CustomizationPage) -> Unit = { page ->
            when (page) {
                CustomizationPage.OVERVIEW -> if (split) {
                    TweakBanner()
                    overviewActions()
                    MiniHomePreview(backgrounds.previewBitmap, state, 260.dp)
                } else {
                    // Like iOS Settings: the header gets out of the way while searching.
                    if (settingsQuery.isBlank()) TweakBanner()
                    SettingsSearchField(settingsQuery) { settingsQuery = it }
                    if (settingsQuery.isNotBlank()) SettingsSearchResults(settingsQuery, onOpen = { settingsQuery = ""; onPage(it) })
                    else {
                        overviewActions()
                        MiniHomePreview(backgrounds.previewBitmap, state, 176.dp)
                        overviewRows(null, false)
                        }
                }
                // The old Setup Checklist lives on in Privacy & Permissions (one list of everything Folio can use).
                CustomizationPage.SETUP -> PermissionsPage(isDefaultHome, onMakeDefault, onShadeSetup)
                CustomizationPage.WALLPAPER -> {
                    val wallpaperContext = androidx.compose.ui.platform.LocalContext.current
                    AppIconCard(onChanged = { model.refresh() })
                    SettingsCard(stringResource(R.string.background)) {
                        IosSegmented(listOf(true to stringResource(R.string.android_wallpaper), false to stringResource(R.string.folio_background)),
                            state.systemWallpaper, { system -> model.setSystemWallpaper(system); wallpaperContext.asActivity()?.applyWallpaperWindow(system) },
                            Modifier.padding(vertical = FolioSpace.SNUG.dp), tag = "background-choice")
                        if (state.systemWallpaper) CardAction(stringResource(R.string.change_android_wallpaper), onClick = {
                            runCatching { wallpaperContext.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SET_WALLPAPER), wallpaperContext.getString(R.string.change_wallpaper))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, modifier = Modifier.testTag("background-change-system"))
                        CardNote(if (state.systemWallpaper) stringResource(R.string.uses_the_same_wallpaper_as_your_phone_s)
                            else stringResource(R.string.folio_s_dunes_or_a_photo_you_choose_only))
                    }
                    GlassCardSettings(state, model)
                    SettingsCard(stringResource(R.string.text_on_home)) {
                        IosMenuRow(stringResource(R.string.text_color), listOf("AUTO" to stringResource(R.string.automatic), "LIGHT" to stringResource(R.string.light), "DARK" to stringResource(R.string.dark)), state.homeInk, model::setHomeInk, tag = "home-ink")
                        SettingsSwitch(stringResource(R.string.dark_appearance_dims_wallpaper), state.dimWallpaperDark, model::setDimWallpaperDark, "dim-wallpaper-switch")
                        if (state.systemWallpaper) SettingsSwitch(stringResource(R.string.wallpaper_moves_with_pages), state.wallpaperMotion, model::setWallpaperMotion, "wallpaper-motion-switch")
                        CardNote(stringResource(R.string.labels_status_page_dots_and_widget_text))
                    }
                    if (!state.systemWallpaper) {
                    MiniHomePreview(backgrounds.previewBitmap, state, 228.dp)
                    SheetGroupLabel(stringResource(R.string.launcher_background))
                    SheetGroup {
                        IosActionRow(if (backgrounds.previewPending) stringResource(R.string.choose_a_different_photo) else stringResource(R.string.choose_a_photo), "background-choose",
                            enabled = !backgrounds.loading, onClick = backgrounds::choosePhoto)
                        if (backgrounds.previewPending) {
                            MenuDivider()
                            IosActionRow(stringResource(R.string.apply), "background-preview-apply", enabled = backgrounds.previewBitmap != null, onClick = backgrounds::applyPreview)
                            MenuDivider()
                            IosActionRow(stringResource(R.string.cancel), "background-preview-cancel", onClick = backgrounds::cancelPreview)
                        }
                        if (backgrounds.photoSelected && !backgrounds.previewPending) {
                            MenuDivider()
                            IosActionRow(stringResource(R.string.reset_to_default_dunes), "background-reset", destructive = true, onClick = backgrounds::reset)
                        }
                        MenuDivider()
                        IosActionRow(stringResource(R.string.preview_as_phone_wallpaper), "wallpaper-preview", onClick = onWallpaperPreview)
                    }
                    if (backgrounds.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("background-loading"))
                    CardNote(stringResource(R.string.changes_the_image_behind_folio_s_home_sc) + stringResource(R.string.opens_android_s_preview_to_use_folio_s_b), Modifier.padding(horizontal = FolioSpace.TINY.dp))
                    (backgrounds.errorMessage ?: backgrounds.successMessage)?.let { message ->
                        TextButton(onClick = backgrounds::clearMessage, Modifier.fillMaxWidth().testTag("background-message")) { Text(message) }
                    }
                    }
                }
                CustomizationPage.HOME -> {
                    HomeLayoutSettings(state, wide, { wide = it }, model)
                    SettingsCard(stringResource(R.string.folders)) {
                        IosMenuRow(stringResource(R.string.columns), listOf(0 to stringResource(R.string.automatic), 3 to "3", 4 to "4"), state.folderColumns, model::setFolderColumns, tag = "folder-columns")
                    }
                    SettingsCard(stringResource(R.string.app_icons)) {
                        SettingsSwitch(stringResource(R.string.app_panels), state.appPanels, model::setAppPanels, "app-panels-switch")
                        SettingsSwitch(stringResource(R.string.dock_magnification), state.dockMagnify, model::setDockMagnify, "dock-magnification-switch")
                    }
                }
                CustomizationPage.GESTURES, CustomizationPage.NOTIFICATIONS, CustomizationPage.SEARCH, CustomizationPage.TODAY -> {
                    if (page == CustomizationPage.GESTURES) SettingsCard(stringResource(R.string.gestures)) {
                        IosMenuRow(stringResource(R.string.animation_speed), MotionSpeed.entries.map { it to stringResource(it.label) }, state.motionSpeed, model::setMotionSpeed, tag = "motion-speed")
                        IosMenuRow(stringResource(R.string.page_effects), PageEffect.entries.map { it to stringResource(it.label) },
                            state.pageEffect, model::setPageEffect, tag = "page-effect")
                        if (state.pageEffect != PageEffect.NONE) CardNote(stringResource(R.string.home_pages_turn_in_3d_as_you_swipe_off_w))
                        IosMenuRow(stringResource(R.string.swipe_down_on_home), listOf("SPOTLIGHT" to stringResource(R.string.spotlight), "NOTIFICATIONS" to stringResource(R.string.notification_center), "OFF" to stringResource(R.string.nothing)),
                            state.swipeDownHome, model::setSwipeDownHome, tag = "swipe-down-home")
                        IosMenuRow(stringResource(R.string.swipe_up_on_home), listOf("OFF" to stringResource(R.string.nothing), "LIBRARY" to stringResource(R.string.app_library), "SPOTLIGHT" to stringResource(R.string.spotlight), "NOTIFICATIONS" to stringResource(R.string.notification_center)),
                            state.swipeUpHome, model::setSwipeUpHome, tag = "swipe-up-home")
                        SettingsSwitch(stringResource(R.string.drag_page_dots_to_flip_pages), state.pageScrub, model::setPageScrub, "page-scrub-switch")
                        SettingsSwitch(stringResource(R.string.haptic_feedback), state.haptics, model::setHaptics, "haptics-switch")
                        CardNote(stringResource(R.string.pull_down_from_the_top_left_for_notifica))
                    }
                    // One page for the panels: the on/off switch and, when on, their options.
                    if (page == CustomizationPage.NOTIFICATIONS) SettingsCard(stringResource(R.string.panels)) {
                        SettingsSwitch(stringResource(R.string.iphone_style_control_center_and_notifica), state.folioPanels, model::setFolioPanels, "folio-panels-switch")
                        if (state.folioPanels) {
                        CardNote(stringResource(R.string.frost_and_outline_are_in_wallpaper_appea))
                        SettingsSwitch(stringResource(R.string.big_clock_in_notification_center), state.notificationClock, model::setNotificationClock, "notification-clock-switch")
                        SettingsSwitch(stringResource(R.string.notification_app_row), state.notificationAppRow, model::setNotificationAppRow, "notification-app-row-switch")
                        SettingsSwitch(stringResource(R.string.tint_notifications), state.tintNotifications, model::setTintNotifications, "tint-notifications-switch")
                        SettingsSwitch(stringResource(R.string.stack_notifications_by_app), state.groupNotifications, model::setGroupNotifications, "notification-group-switch")
                        SettingsSwitch(stringResource(R.string.unfolded_clock_beside_notifications), state.ncSplit, model::setNcSplit, "notification-split-switch")
                        IosMenuRow(stringResource(R.string.control_center_size), PanelSize.entries.map { it to stringResource(it.label) }, state.ccSize, model::setCcSize, tag = "cc-size")
                        SettingsSwitch(stringResource(R.string.unfolded_control_center_in_the_middle), state.ccCentered, model::setCcCentered, "cc-centered-switch")
                        CardNote(stringResource(R.string.tip_tap_at_the_top_of_control_center_to))
                        CardNote(stringResource(R.string.to_restyle_samsungs_own_pull_down_colors))
                        }
                    }
                    if (page == CustomizationPage.SEARCH) SettingsCard(stringResource(R.string.spotlight)) {
                        IosMenuRow(stringResource(R.string.search_with_enter), listOf(WebSearchTarget.GOOGLE.name to stringResource(R.string.google_no_ai), WebSearchTarget.DUCKDUCKGO.name to stringResource(R.string.duckduckgo)),
                            state.searchEngine, model::setSearchEngine, tag = "search-engine")
                        SpotlightSection.entries.forEach { section ->
                            SettingsSwitch(stringResource(section.title), section.name !in state.spotlightHidden,
                                { model.setSpotlightSection(section.name, it) }, "spotlight-${section.name.lowercase()}")
                        }
                        val messageContext = androidx.compose.ui.platform.LocalContext.current
                        val iMessageApps = remember { Messaging.iMessageApps.filter { Messaging.installed(messageContext, it.first) } }
                        if (iMessageApps.isNotEmpty()) {
                            IosMenuRow(stringResource(R.string.message_contacts_with), listOf<Pair<String?, String>>(null to stringResource(R.string.texting_app)) + iMessageApps.map { it.first to it.second },
                                state.messagesApp, model::setMessagesApp, tag = "messages-app")
                        }
                    }
                    if (page == CustomizationPage.SEARCH) SettingsCard(stringResource(R.string.device_search_access)) {
                        SpotlightDeviceSearch("", active = false, hidden = state.spotlightHidden, manageAccess = true, onClose = {})
                    }
                    if (page == CustomizationPage.GESTURES) SettingsCard(stringResource(R.string.actions)) {
                        CardNote(stringResource(R.string.pick_what_gestures_and_events_do_activat))
                        FolioTrigger.entries.forEach { trigger ->
                            val current = FolioAction.entries.firstOrNull { it.name == state.triggerActions[trigger.name] } ?: FolioAction.NONE
                            IosMenuRow(stringResource(trigger.label), FolioAction.entries.map { it to stringResource(it.label) }, current, { model.setTriggerAction(trigger, it) }, tag = "trigger-${trigger.name.lowercase()}")
                        }
                    }
                    if (page == CustomizationPage.TODAY) SettingsCard(stringResource(R.string.left_of_home)) {
                        // This used to ask for the screen to be rebuilt, through a cast that was always null in a
                        // sheet, so it never happened — and the pager has been following the page count from state
                        // on its own ever since. Restarting here would now throw you out of Settings to say so.
                        IosSegmented(listOf("TODAY" to stringResource(R.string.today_view), "DISCOVER" to stringResource(R.string.google_discover), "NONE" to stringResource(R.string.none)), state.leftPage,
                            { model.setLeftPage(it) }, Modifier.padding(vertical = FolioSpace.SNUG.dp), tag = "left-page")
                        CardNote(if (state.leftPage == "NONE") stringResource(R.string.nothing_to_the_left_of_home_swiping_righ)
                            else stringResource(R.string.today_view_is_iphone_s_widget_page_searc))
                        if (state.leftPage == "TODAY") {
                            IosMenuRow(stringResource(R.string.when_unfolded), listOf("PAGE" to stringResource(R.string.swipe_to_it), "BESIDE" to stringResource(R.string.beside_home), "OFF" to stringResource(R.string.none)),
                                state.todayUnfolded, model::setTodayUnfolded, tag = "today-unfolded")
                            CardNote(when (state.todayUnfolded) {
                                "BESIDE" -> stringResource(R.string.like_ipad_today_view_stays_on_the_left_o)
                                "OFF" -> stringResource(R.string.no_today_view_while_unfolded_it_s_still)
                                else -> stringResource(R.string.swipe_right_from_your_first_home_page_to)
                            })
                        }
                    }
                    if (page == CustomizationPage.SEARCH) SettingsCard(stringResource(R.string.app_library)) {
                        SettingsSwitch(stringResource(R.string.group_apps_into_categories), state.libraryCategories, model::setLibraryCategories, "library-categories-switch")
                        if (state.profiles.any { it.isWork }) SettingsSwitch(stringResource(R.string.work_apps), state.libraryWork, model::setLibraryWork, "library-work-switch")
                        HiddenAppsRow(state, model)
                        SettingsSwitch(stringResource(R.string.add_new_apps_to_home_screen), state.addNewAppsToHome, model::setAddNewAppsToHome, "add-new-apps-switch")
                        CardNote(stringResource(R.string.off_new_downloads_go_to_the_app_library))
                    }
                    if (page == CustomizationPage.SEARCH) SettingsCard(stringResource(R.string.search)) {
                        SettingsSwitch(stringResource(R.string.search_button_on_home), state.searchPill, model::setSearchPill, "search-pill-switch")
                        SettingsSwitch(stringResource(R.string.search_button_opens_the_google_app), state.googleSearch, model::setGoogleSearch, "google-search-switch")
                        CardNote(stringResource(R.string.when_off_the_search_button_opens_spotlig))
                    }
                }
                CustomizationPage.STATUS, CustomizationPage.ISLAND -> {
                    if (page == CustomizationPage.STATUS && !split) MiniHomePreview(backgrounds.previewBitmap, state, 210.dp)
                    val st = state.statusStyle
                    if (page == CustomizationPage.STATUS) SettingsCard(stringResource(R.string.app_icons)) {
                        val iconContext = androidx.compose.ui.platform.LocalContext.current
                        val packs = remember { IconPacks.installed(iconContext) }
                        IosMenuRow(stringResource(R.string.icon_pack), listOf<Pair<String?, String>>(null to stringResource(R.string.app_icons)) + packs.map { it.packageName to it.label },
                            state.iconPack, { IconPacks.clear(); model.setIconPack(it) }, tag = "icon-pack")
                        CardNote(stringResource(R.string.icon_packs_and_themes_are_made_by_indepe))
                        if (packs.isEmpty()) CardNote(stringResource(R.string.install_any_icon_pack_made_for_nova_styl))
                        CardAction(stringResource(R.string.refresh_icons), onClick = model::reloadIcons)
                        CardNote(stringResource(R.string.refresh_icons_note))
                        // Live Clock and Calendar: the app's own icon, or live icons that match the others, or always light/dark.
                        IosMenuRow(stringResource(R.string.clock_calendar), listOf("OFF" to stringResource(R.string.app_icons_2), "AUTO" to stringResource(R.string.live_automatic), "LIGHT" to stringResource(R.string.live_light), "DARK" to stringResource(R.string.live_dark)),
                            if (state.liveIcons) state.liveIconLook else "OFF",
                            { if (it == "OFF") model.setLiveIcons(false) else model.setLiveIconLook(it) }, tag = "live-icons-menu")
                        IosMenuRow(stringResource(R.string.shape), IconShape.entries.map { it to stringResource(it.label) }, state.iconShape, model::setIconShape, tag = "icon-shape")
                        IosMenuRow(stringResource(R.string.notification_badges), BadgeStyle.entries.map { it to stringResource(it.label) }, state.badgeStyle, model::setBadgeStyle, tag = "badge-style")
                        if (state.badgeStyle != BadgeStyle.OFF) {
                            IosMenuRow(stringResource(R.string.badge_color), BadgeColor.entries.map { it to stringResource(it.label) }, state.badgeColor, model::setBadgeColor, tag = "badge-color")
                            IosMenuRow(stringResource(R.string.badge_look), BadgeLook.entries.map { it to stringResource(it.label) }, state.badgeLook, model::setBadgeLook, tag = "badge-look")
                            IosMenuRow(stringResource(R.string.badge_size), BadgeSize.entries.map { it to stringResource(it.label) }, state.badgeSize, model::setBadgeSize, tag = "badge-size")
                            BadgePreviewRow(state)
                        }
                        // iOS Home Screen customization: Default, Dark and Tinted side by side.
                        Text(stringResource(R.string.style), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .6f), fontSize = FolioType.FOOTNOTE.sp, modifier = Modifier.padding(top = FolioSpace.SMALL.dp))
                        IosSegmented(IconStyle.entries.map { it to stringResource(it.label) }, state.iconStyle, { model.setIconStyle(it, state.iconTint) }, Modifier.padding(vertical = FolioSpace.TINY.dp), tag = "icon-style")
                        if (state.iconStyle == IconStyle.TINTED) Row(Modifier.fillMaxWidth().padding(vertical = FolioSpace.SNUG.dp), horizontalArrangement = Arrangement.spacedBy(FolioSpace.COMPACT.dp)) {
                            // Wallpaper color (follows the wallpaper when it changes)
                            val tone = LocalWallpaperTone.current
                            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(tone.primary?.let { androidx.compose.ui.graphics.Color(vividTint(it)) } ?: androidx.compose.ui.graphics.Color.Gray)
                                .then(if (state.iconTintFromWallpaper) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape) else Modifier)
                                .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { model.setIconTintFromWallpaper(true) }
                                .description(R.string.wallpaper_color).semantics { selected = state.iconTintFromWallpaper },
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Wallpaper, null, tint = androidx.compose.ui.graphics.Color.Black.copy(alpha = .6f), modifier = Modifier.size(18.dp))
                            }
                            listOf(0xFFFFB340, FolioColors.Value.RedSoft, 0xFFFF7EB6, 0xFFBF8CFF, 0xFF64B5FF, 0xFF5EE0C4, 0xFF9BE15D, 0xFFE8E8E8).forEach { c ->
                                Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(c))
                                    .then(if (!state.iconTintFromWallpaper && state.iconTint == c) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape) else Modifier)
                                    .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { model.setIconTintFromWallpaper(false); model.setIconStyle(IconStyle.TINTED, c) }
                                    .description(R.string.tint_color).semantics { selected = !state.iconTintFromWallpaper && state.iconTint == c })
                            }
                        }
                    }
                    if (page == CustomizationPage.STATUS) SettingsCard(stringResource(R.string.side_rail)) {
                        SettingsSwitch(stringResource(R.string.left_handed_layout_rail_on_the_left), state.leftHanded, model::setLeftHanded, "left-handed-switch")
                        SettingsSwitch(stringResource(R.string.show_app_names), state.labels, model::setLabels, "label-switch")
                        if (state.labels) IosMenuRow(stringResource(R.string.name_size), LabelSize.entries.map { it to stringResource(it.label) }, state.labelSize, model::setLabelSize, tag = "label-size")
                        CardNote(stringResource(R.string.frost_and_outline_are_in_wallpaper_appea))
                    }
                    if (page == CustomizationPage.STATUS) SettingsCard(stringResource(R.string.status)) {
                        SettingsSwitch(stringResource(R.string.show_system_status_bar), state.showSystemStatusBar, model::setShowSystemStatusBar, "system-status-bar-switch")
                        SettingsSwitch(stringResource(R.string.show_status_in_the_rail), state.verticalStatus, model::setVerticalStatus, "status-switch")
                        if (state.verticalStatus) {
                            SettingsSwitch(stringResource(R.string.time), st.showTime, { model.setStatusStyle(st.copy(showTime = it)) }, "status-time")
                            SettingsSwitch(stringResource(R.string.date), st.showDate, { model.setStatusStyle(st.copy(showDate = it)) }, "status-date")
                            SettingsSwitch(stringResource(R.string.battery_percentage), st.showBatteryPercent, { model.setStatusStyle(st.copy(showBatteryPercent = it)) }, "status-percent")
                            CustomizationSlider(stringResource(R.string.status_spacing), when (st.spacing.roundToInt()) {
                                StatusStyle.COMPACT_SPACING.roundToInt() -> stringResource(R.string.compact); StatusStyle.STANDARD_SPACING.roundToInt() -> stringResource(R.string.standard)
                                else -> stringResource(R.string.dp_value, st.spacing.roundToInt()) }, st.spacing, 0f..16f, StatusStyle.STANDARD_SPACING, peek = true) { model.setStatusStyle(st.copy(spacing = it)) }
                            SettingsSwitch(stringResource(R.string.background), st.background, { model.setStatusStyle(st.copy(background = it)) }, "status-background")
                            SettingsSwitch(stringResource(R.string.silent_mode_icon), st.showSilent, { model.setStatusStyle(st.copy(showSilent = it)) }, "status-silent")
                            SettingsSwitch(stringResource(R.string.color_battery_when_charging_or_low), st.colorfulBattery, { model.setStatusStyle(st.copy(colorfulBattery = it)) }, "status-color")
                            CardNote(stringResource(R.string.status_colors_green_while_charging_orang))
                        }
                    }
                    if (page == CustomizationPage.ISLAND) SettingsCard(stringResource(R.string.live_activities)) {
                        val islandContext = androidx.compose.ui.platform.LocalContext.current
                        SettingsSwitch(stringResource(R.string.show_live_activities), state.island, { on ->
                            model.setIsland(on)
                            if (on && !IslandListenerService.hasAccess(islandContext))
                                runCatching { islandContext.startActivity(IslandListenerService.accessSettingsIntent(islandContext)) }
                        }, "island-switch")
                        if (state.island) IosMenuRow(
                            stringResource(R.string.rail_activity_expansion),
                            listOf(false to stringResource(R.string.rail_activity_push_dock), true to stringResource(R.string.rail_activity_over_grid)),
                            state.railActivityOverlay, model::setRailActivityOverlay)
                        SettingsSwitch(stringResource(R.string.tint_media), state.tintMedia, model::setTintMedia, "tint-media-switch")
                        if (state.island && !waveformPermissionGranted) {
                            CardAction(stringResource(R.string.allow_waveform_audio), onClick = {
                                waveformPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                            })
                            CardNote(stringResource(R.string.media_waveform_detail))
                        }
                        if (state.island && !IslandListenerService.hasAccess(islandContext)) CardAction(stringResource(R.string.allow_notification_access), onClick = {
                            runCatching { islandContext.startActivity(IslandListenerService.accessSettingsIntent(islandContext)) }
                        })
                    }
                    if (page == CustomizationPage.ISLAND) SettingsCard(stringResource(R.string.brief_pop_ups)) {
                        if (state.island) {
                            listOf("CHARGING" to stringResource(R.string.charging), "SILENT" to stringResource(R.string.silent_mode), "FOCUS" to stringResource(R.string.do_not_disturb), "BLUETOOTH" to stringResource(R.string.headphones_speakers), "MESSAGE" to stringResource(R.string.new_messages_with_quick_reply), "CALL" to stringResource(R.string.calls_answer_decline_end)).forEach { (kind, label) ->
                                SettingsSwitch(label, kind !in state.islandEventsOff, { model.setIslandEvent(kind, it) }, "island-event-${kind.lowercase()}")
                            }
                            if ("MESSAGE" !in state.islandEventsOff) MessageBannerSettings(state.messagesAvoidDouble, model::setMessagesAvoidDouble)
                            IslandAlertSettings(state.islandAlerts, state.islandAlertAppsOff, model::setIslandAlerts) { onPage(CustomizationPage.ISLAND_APPS) }
                        } else {
                            if (state.messagesAvoidDouble) {
                                // Apps switched to Live Activities show no Folio pop-up while it is off.
                                CardNote(stringResource(R.string.message_pop_ups_come_from_the_island_whi))
                                MessageChannelList()
                            }
                        }
                        CardNote(stringResource(R.string.reads_only_music_calls_timers_navigation))
                    }
                }
                CustomizationPage.FOLD, CustomizationPage.SIDE_KEY, CustomizationPage.LOCK -> Unit
                CustomizationPage.BACKUP -> {
                    if (namingBackup) BackupNameAlert(onCancel = { namingBackup = false }) { name -> namingBackup = false; onSaveLayoutToFolder(name) }
                    SheetGroup {
                        IosActionRow(stringResource(R.string.save_backup), "layout-save-folder", onClick = { namingBackup = true })
                        MenuDivider()
                        IosActionRow(stringResource(R.string.save_backup_to_files), "layout-export", onClick = onExportLayout)
                        MenuDivider()
                        IosActionRow(stringResource(R.string.restore_from_backup), "layout-import", onClick = onImportLayout)
                    }
                    CardNote(stringResource(R.string.save_the_current_home_layout_folders_wid) +
                        stringResource(R.string.restore_shows_a_review_before_changing_h), Modifier.padding(horizontal = FolioSpace.TINY.dp))
                    LayoutHistoryCard(state, model, onClose)
                }
                CustomizationPage.CREDITS -> CreditsPage()
                CustomizationPage.ISLAND_APPS -> IslandAlertApps(state.islandAlertAppsOff, state.messagesAvoidDouble, model::setIslandAlertApp)
                CustomizationPage.HELP -> {
                    // Getting help lives here rather than as more rows in the main list (fewer choices there).
                    val helpContext = androidx.compose.ui.platform.LocalContext.current
                    SheetGroup {
                        val reportScope = rememberCoroutineScope()
                        TweakRow(Icons.Rounded.BugReport, FolioColors.Value.Red, stringResource(R.string.report_a_bug), "customization-report-bug") {
                            reportScope.launch {
                                Diagnostics.copy(helpContext)
                                runCatching { helpContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(BugReport.url(helpContext)))) }
                            }
                        }
                        MenuDivider()
                        TweakRow(Icons.Rounded.WavingHand, FolioColors.Value.Orange, stringResource(R.string.show_welcome_again), "customization-onboarding") { onClose(); onShowWelcome() }
                    }
                    CardNote(stringResource(R.string.report_a_bug_opens_github_in_your_browse), Modifier.padding(horizontal = FolioSpace.LARGE.dp))
                    LauncherHelp()
                }
                CustomizationPage.ADVANCED -> {
                    SettingsCard(stringResource(R.string.screenshot_mode)) {
                        val screenshot by ScreenshotMode.on.collectAsState()
                        SettingsSwitch(stringResource(R.string.screenshot_mode), screenshot, ScreenshotMode::set, "screenshot-mode-switch")
                        CardNote(stringResource(R.string.for_sharing_your_setup_folio_shows_9_41))
                    }
                    SettingsCard(stringResource(R.string.safe_mode)) {
                        CardNote(if (SafeMode.active) stringResource(R.string.folio_is_running_in_safe_mode_optional_f)
                            else stringResource(R.string.if_folio_closes_unexpectedly_twice_right))
                    }
                    CrashReportsCard()
                }
                CustomizationPage.PERMISSIONS -> PermissionsPage(isDefaultHome, onMakeDefault, onShadeSetup)
                CustomizationPage.FOCUS -> FocusListPage(state, model) { focusId = it; onPage(CustomizationPage.FOCUS_MODE) }
                CustomizationPage.FOCUS_MODE -> state.focusModes.firstOrNull { it.id == focusId }?.let { FocusModePage(it, state, model) }
                    ?: LaunchedEffect(Unit) { onPage(CustomizationPage.FOCUS) }
            }
    }
    if (!split) Column(Modifier.fillMaxSize().padding(horizontal = FolioSpace.LARGE.dp)) {
        SettingsNavBar(if (page == CustomizationPage.OVERVIEW) null else nestedBackLabel ?: stringResource(R.string.folio), onBack, onClose)
        if (page != CustomizationPage.OVERVIEW) SettingsLargeTitle(title)
        Column(Modifier.weight(1f).edgeFade(bodyScroll).verticalScroll(bodyScroll).padding(bottom = FolioSpace.XL.dp),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.COMPACT.dp)) { pageContent(page) }
    } else {
        // Half folded with the fold running down the screen, the divider goes on the fold, so no row sits on the
        // crease (iPhone Duo: controls move away from the fold). Flat, it's a share of the width.
        val hinge = LocalHinge.current?.takeIf { it.active && it.vertical }
        val density = LocalDensity.current
        val onFold = hinge?.let { with(density) { it.startPx.toDp() } }?.takeIf { it >= 280.dp && it <= fullWidth * .7f }
        val columns = settingsColumns(
            maxWidth.value, maxHeight.value, androidx.compose.ui.platform.LocalConfiguration.current.classScale,
            nested = page.parent != CustomizationPage.OVERVIEW,
            onFold = onFold != null,
            keyboardDp = keyboardDp,
        ).coerceAtMost(LocalSettingsMaxColumns.current)
        val tiled = columns >= 2
        val threeColumns = columns == 3
        // Tiled it shares the width; as an overlay it can be a little wider so rows don't wrap. With three, the list
        // of settings gives up some width so the other two stay readable.
        val sidebarWidth = onFold
            ?: when {
                threeColumns -> (fullWidth * .28f).coerceIn(260.dp, 320.dp)
                tiled -> (fullWidth * .4f).coerceIn(280.dp, 380.dp)
                else -> minOf(360.dp, fullWidth * .6f)
            }
        val middleWidth = ((fullWidth - sidebarWidth) * .44f).coerceIn(280.dp, 380.dp)
        val middleScroll = scrollForPage(page.parent)
        var sidebarOpen by rememberSaveable { mutableStateOf(tiled || page == CustomizationPage.OVERVIEW) }
        var shownPage by remember { mutableStateOf(page) }
        // Opening a page slides the list away; coming back to the top brings it back, since the list is all that page has.
        SideEffect { if (page != shownPage) { shownPage = page; if (!tiled) sidebarOpen = page == CustomizationPage.OVERVIEW } }
        val sidebar: @Composable () -> Unit = {
            Column(Modifier.width(sidebarWidth).fillMaxHeight().edgeFade(sidebarScroll).verticalScroll(sidebarScroll)
                .padding(horizontal = FolioSpace.LARGE.dp).padding(top = 44.dp, bottom = FolioSpace.XL.dp).testTag("settings-sidebar"), verticalArrangement = Arrangement.spacedBy(FolioSpace.COMPACT.dp)) {
                SettingsLargeTitle(stringResource(R.string.folio))
                SettingsSearchField(settingsQuery) { settingsQuery = it }
                if (settingsQuery.isNotBlank()) SettingsSearchResults(settingsQuery, onOpen = { onPage(it) })
                else {
                    // Like the account card at the top of iPad Settings: Folio's own page.
                    SheetGroup { SidebarAppRow(selected = page == CustomizationPage.OVERVIEW, setupLeft) { onPage(CustomizationPage.OVERVIEW) } }
                    overviewRows(page.root, true)
                }
            }
        }
        val divider: @Composable () -> Unit = {
            Box(Modifier.fillMaxHeight().width(.5.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = .14f)))
        }
        Row(Modifier.fillMaxSize()) {
            if (tiled) {
                sidebar()
                divider()
            }
            // The list you picked from stays where it was, and what you picked opens to the right of it - the way
            // Mail and Notes use an iPad's width. Only for a page that came from a list, so a column is never empty.
            if (threeColumns) {
                Column(Modifier.width(middleWidth).fillMaxHeight().padding(horizontal = FolioSpace.XL.dp)) {
                    Spacer(Modifier.height(44.dp))
                    nestedBackLabel?.let { SettingsLargeTitle(it) }
                    Column(Modifier.weight(1f).edgeFade(middleScroll).verticalScroll(middleScroll).padding(bottom = FolioSpace.XL.dp),
                        verticalArrangement = Arrangement.spacedBy(FolioSpace.COMPACT.dp)) { pageContent(page.parent) }
                }
                divider()
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = FolioSpace.XL.dp)) {
                // With the list still on screen there's nothing for Back to reveal, so the bar keeps only Done. Without
                // it, every page but the first gets a way back, not only the sidebar button, which doesn't read as one.
                SettingsNavBar(if (threeColumns) null else nestedBackLabel
                    ?: if (!tiled && page != CustomizationPage.OVERVIEW) stringResource(R.string.folio) else null, onBack, onClose,
                    leading = if (tiled) null else ({ SidebarButton { sidebarOpen = !sidebarOpen } }))
                if (page != CustomizationPage.OVERVIEW) SettingsLargeTitle(title)
                Column(Modifier.weight(1f).edgeFade(bodyScroll).verticalScroll(bodyScroll).padding(bottom = FolioSpace.XL.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpace.COMPACT.dp)) {
                        // The space beside the list shows what the page changes, drawn from your real Home.
                        if (page == CustomizationPage.HOME && wide) UnfoldedHomePreview(backgrounds.previewBitmap, state, 200.dp)
                        else if (page == CustomizationPage.HOME || page == CustomizationPage.STATUS)
                            MiniHomePreview(backgrounds.previewBitmap, state, 240.dp)
                        pageContent(page)
                    }
                }
            }
        }
        if (!tiled) {
            val reduceMotion = LocalReduceMotion.current
            androidx.compose.animation.AnimatedVisibility(sidebarOpen, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .4f))
                    .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { sidebarOpen = false })
            }
            androidx.compose.animation.AnimatedVisibility(sidebarOpen,
                enter = if (reduceMotion) androidx.compose.animation.fadeIn() else androidx.compose.animation.slideInHorizontally { -it },
                exit = if (reduceMotion) androidx.compose.animation.fadeOut() else androidx.compose.animation.slideOutHorizontally { -it }) {
                Box(Modifier.fillMaxHeight().background(FolioColors.SecondaryBackground)) { sidebar() }
            }
        }
    }
    }
}

/** The iPad/iPhone Duo sidebar toggle: shows or hides the settings list over the page. */
@Composable private fun SidebarButton(onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = stringResource(R.string.show_or_hide_the_settings_list), onClick = onClick)
        .testTag("settings-sidebar-toggle"), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.ViewSidebar, null, tint = LocalAccent.current.ink, modifier = Modifier.size(26.dp))
    }
}

/** A row with a title, a line under it and a switch: the shape most Folio settings take. */
@Composable private fun SwitchRow(title: String, value: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpace.COMFY.dp, vertical = FolioSpace.COMPACT.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = androidx.compose.ui.graphics.Color.White, fontSize = FolioType.BODY.sp)
            value?.let {
                Text(it, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .55f), fontSize = FolioType.FOOTNOTE.sp)
            }
        }
        IosSwitch(checked, onChange, Modifier.testTag("switch-${title.lowercase().replace(' ', '-')}"))
    }
}

@Composable private fun SettingsNavBar(backLabel: String?, onBack: () -> Unit, onClose: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    // iOS navigation bar: the sidebar button and "‹ Back" on the left, Done on the right.
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            if (backLabel != null) Row(Modifier.clip(RoundedCornerShape(FolioRadius.CONTROL.dp))
                .clickable(onClick = onBack).padding(vertical = FolioSpace.SMALL.dp, horizontal = FolioSpace.HAIR.dp).testTag("customization-back"),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ChevronLeft, null, tint = LocalAccent.current.ink, modifier = Modifier.size(28.dp))
                Text(backLabel, color = LocalAccent.current.ink, fontSize = FolioType.BODY.sp)
            }
        }
        Text(stringResource(R.string.done), color = LocalAccent.current.ink, fontSize = FolioType.BODY.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(FolioRadius.CONTROL.dp)).clickable(onClick = onClose)
                .padding(horizontal = FolioSpace.SMALL.dp, vertical = FolioSpace.SMALL.dp).description(R.string.close_customization))
    }
}

@Composable private fun SettingsLargeTitle(title: String) = Text(title, color = androidx.compose.ui.graphics.Color.White, fontSize = 32.sp,
    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = FolioSpace.TINY.dp, bottom = FolioSpace.SMALL.dp))

/** Sidebar header row: Folio's icon, name and setup state, like the account card in iPad Settings. */
@Composable private fun SidebarAppRow(selected: Boolean, setupLeft: Int, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon = remember { folioIconBitmap(context) }
    // iPad Settings: the selection is a rounded highlight inset from the group's edges, not a square band.
    Row(Modifier.fillMaxWidth().padding(FolioSpace.TINY.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) LocalAccent.current.fill else androidx.compose.ui.graphics.Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = FolioSpace.COMFY.dp, vertical = FolioSpace.COMPACT.dp).testTag("settings-sidebar-folio"), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Image(it, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.folio), color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (setupLeft > 0) pluralStringResource(R.plurals.setup_steps_left, setupLeft, setupLeft) else stringResource(R.string.home_panels_and_tweaks),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = if (selected) .85f else .55f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun LauncherHelp() {
    SheetGroup {
        HelpTip(Icons.Rounded.Home, FolioColors.Value.Blue, stringResource(R.string.edit_home), stringResource(R.string.duo_help_edit))
        MenuDivider()
        HelpTip(Icons.Rounded.Widgets, FolioColors.Value.Indigo, stringResource(R.string.widgets), stringResource(R.string.duo_help_widgets))
        MenuDivider()
        HelpTip(Icons.Rounded.Folder, FolioColors.Value.Orange, stringResource(R.string.folders), stringResource(R.string.duo_help_folders))
    }
    SheetGroup {
        HelpTip(Icons.Rounded.Apps, FolioColors.Value.Indigo, stringResource(R.string.app_library), stringResource(R.string.duo_help_library))
        MenuDivider()
        HelpTip(Icons.Rounded.Search, FolioColors.Value.Blue, stringResource(R.string.spotlight), stringResource(R.string.duo_help_spotlight))
        MenuDivider()
        HelpTip(Icons.Rounded.Lock, FolioColors.Value.Orange, stringResource(R.string.duo_help_security_title), stringResource(R.string.duo_help_security))
    }
    SheetGroup {
        HelpTip(Icons.Rounded.Notifications, FolioColors.Value.RedLight, stringResource(R.string.notifications_control_center), stringResource(R.string.duo_help_notifications))
        MenuDivider()
        HelpTip(Icons.Rounded.Circle, FolioColors.Value.SecondaryBackground, stringResource(R.string.live_activities), stringResource(R.string.duo_help_live_activities))
        MenuDivider()
        HelpTip(Icons.Rounded.Save, FolioColors.Value.Blue, stringResource(R.string.backup), stringResource(R.string.duo_help_backup))
        MenuDivider()
        HelpTip(Icons.Rounded.SystemUpdate, FolioColors.Value.Indigo, stringResource(R.string.app_update_title), stringResource(R.string.duo_help_updates))
    }
}

@Composable
private fun HelpTip(icon: ImageVector, color: Long, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpace.COMFY.dp, vertical = FolioSpace.COMPACT.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(androidx.compose.ui.graphics.Color(color)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = FolioGlass.ink, fontSize = FolioType.BODY.sp)
            Text(detail, color = FolioGlass.secondaryInk, fontSize = 14.sp)
        }
    }
}




/** Tweak-style header: Folio's icon, name and version, like a jailbreak tweak's preference banner. */
@Composable private fun TweakBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    Column(Modifier.fillMaxWidth().padding(top = FolioSpace.TINY.dp, bottom = FolioSpace.SNUG.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Folio's own launcher icon, like a tweak's preference banner.
        val icon = remember { folioIconBitmap(context) }
        if (icon != null) androidx.compose.foundation.Image(icon, null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
        Text(stringResource(R.string.folio), color = androidx.compose.ui.graphics.Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = FolioSpace.SMALL.dp))
        Text(stringResource(R.string.iphone_duo_for_your_fold_v, version), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .55f), fontSize = 14.sp)
    }
}

/** iOS Settings row: colored rounded icon square, title, optional value, chevron. */
@Composable private fun TweakRow(icon: ImageVector, color: Long, title: String, tag: String, value: String? = null,
    selected: Boolean = false, chevron: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .then(if (selected) Modifier.padding(horizontal = 5.dp, vertical = FolioSpace.HAIR.dp).clip(RoundedCornerShape(FolioRadius.CONTROL.dp)).background(LocalAccent.current.fill) else Modifier)
        // The inset is taken back from the content padding, so the icon and title don't shift when selected.
        .clickable(onClick = onClick).padding(horizontal = if (selected) 9.dp else FolioSpace.COMFY.dp, vertical = if (selected) FolioSpace.SNUG.dp else FolioSpace.SMALL.dp).testTag(tag).semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(androidx.compose.ui.graphics.Color(color)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        // A long title wraps to two lines rather than being cut off — iOS does the same in a narrow window or at a
        // large text size — and the value drops under it when there isn't room beside it.
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        val tight = config.screenWidthDp < 360 || config.fontScale >= 1.3f
        val valueColor = androidx.compose.ui.graphics.Color.White.copy(alpha = if (selected) .85f else .5f)
        Column(Modifier.weight(1f)) {
            Text(
                title, color = androidx.compose.ui.graphics.Color.White, fontSize = FolioType.BODY.sp,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (tight) value?.let { Text(it, color = valueColor, fontSize = FolioType.SUBHEAD.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        if (!tight) value?.let {
            Text(it, color = valueColor, fontSize = FolioType.BODY.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = FolioSpace.SMALL.dp))
        }
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = .3f))
    }
}

/** iOS Settings search field. */
/** A row that just says something: a title and a line under it, with no control. */
@Composable private fun SwitchlessRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpace.LARGE.dp, vertical = FolioSpace.MEDIUM.dp)) {
        Text(title, color = androidx.compose.ui.graphics.Color.White, fontSize = FolioType.BODY.sp)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingsSearchField(query: String, onQuery: (String) -> Unit) =
    IosSearchField(query, onQuery, stringResource(R.string.search), fieldModifier = Modifier.testTag("settings-search"))

@Composable private fun AppUpdateSettings(result: AppUpdateResult?, onCheck: () -> Unit) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var openFailed by remember(result) { mutableStateOf(false) }
    SettingsCard(stringResource(R.string.app_update_title)) {
        CardNote(when (result) {
            null -> stringResource(R.string.app_update_checking)
            is AppUpdateResult.Available -> stringResource(R.string.app_update_available, result.version)
            is AppUpdateResult.Current -> stringResource(R.string.app_update_current, result.version)
            AppUpdateResult.NoRelease -> stringResource(R.string.app_update_no_release)
            AppUpdateResult.Unavailable -> stringResource(R.string.app_update_unavailable)
            AppUpdateResult.UnknownVersion -> stringResource(R.string.app_update_unknown_version)
        })
        if (result is AppUpdateResult.Available || result == AppUpdateResult.UnknownVersion) {
            IosActionRow(stringResource(R.string.app_update_download), "update-download") {
                val url = (result as? AppUpdateResult.Available)?.url ?: FOLIO_RELEASES_URL
                openFailed = runCatching { uriHandler.openUri(url) }.isFailure
            }
        }
        IosActionRow(stringResource(R.string.app_update_check), "update-check", enabled = result != null, onClick = onCheck)
        if (openFailed) CardNote(stringResource(R.string.app_update_open_failed))
    }
}

/** Where each setting lives, for Settings search: its title as shown, and words people search for (settings_keywords_*). */
private val SettingsIndex: List<Triple<Int, Int, CustomizationPage>> = listOf(
    Triple(R.string.app_update_title, R.string.app_update_check, CustomizationPage.OVERVIEW),
    Triple(R.string.settings_background_wallpaper, R.string.settings_keywords_background_wallpaper, CustomizationPage.WALLPAPER),
    Triple(R.string.text_on_home, R.string.settings_keywords_text_on_home, CustomizationPage.WALLPAPER),
    Triple(R.string.settings_dock_and_status_position, R.string.settings_keywords_dock_and_status_position, CustomizationPage.HOME),
    Triple(R.string.background_material, R.string.settings_keywords_glass, CustomizationPage.WALLPAPER),
    Triple(R.string.folders, R.string.settings_keywords_folders, CustomizationPage.HOME),
    Triple(R.string.settings_animation_speed, R.string.settings_keywords_animation_speed, CustomizationPage.GESTURES),
    Triple(R.string.settings_app_name_size, R.string.settings_keywords_app_name_size, CustomizationPage.STATUS),
    Triple(R.string.tint_glass_with_wallpaper_color, R.string.settings_keywords_tint_glass_with_wallpaper_color, CustomizationPage.WALLPAPER),
    Triple(R.string.dark_appearance_dims_wallpaper, R.string.settings_keywords_dark_appearance_dims_wallpaper, CustomizationPage.WALLPAPER),
    Triple(R.string.settings_grid_icon_size_dock, R.string.settings_keywords_grid_icon_size_dock, CustomizationPage.HOME),
    Triple(R.string.rows, R.string.settings_keywords_rows, CustomizationPage.HOME),
    Triple(R.string.space_between_columns, R.string.settings_keywords_space_between_columns, CustomizationPage.HOME),
    Triple(R.string.widget_size, R.string.settings_keywords_widget_size, CustomizationPage.HOME),
    Triple(R.string.space_between_dock_apps, R.string.settings_keywords_space_between_dock_apps, CustomizationPage.HOME),
    Triple(R.string.status_spacing, R.string.settings_keywords_status_spacing, CustomizationPage.STATUS),
    Triple(R.string.settings_today_view_left_of_home, R.string.settings_keywords_today_view_left_of_home, CustomizationPage.TODAY),
    Triple(R.string.settings_icon_pack_shape_style, R.string.settings_keywords_icon_pack_shape_style, CustomizationPage.STATUS),
    Triple(R.string.notification_badges, R.string.settings_keywords_notification_badges, CustomizationPage.STATUS),
    Triple(R.string.settings_live_clock_and_calendar_icons, R.string.settings_keywords_live_clock_and_calendar_icons, CustomizationPage.STATUS),
    Triple(R.string.settings_side_bar_status_bar, R.string.settings_keywords_side_bar_status_bar, CustomizationPage.STATUS),
    Triple(R.string.show_system_status_bar, R.string.show_system_status_bar, CustomizationPage.STATUS),
    Triple(R.string.live_activities, R.string.settings_keywords_live_activities, CustomizationPage.ISLAND),
    Triple(R.string.settings_other_notifications_in_the_islan, R.string.settings_keywords_other_notifications_in_the_islan, CustomizationPage.ISLAND_APPS),
    Triple(R.string.notification_center, R.string.settings_keywords_notification_center, CustomizationPage.NOTIFICATIONS),
    Triple(R.string.control_center, R.string.settings_keywords_control_center, CustomizationPage.NOTIFICATIONS),
    Triple(R.string.spotlight, R.string.settings_keywords_spotlight, CustomizationPage.SEARCH),
    Triple(R.string.content_search_enabled, R.string.content_search_description, CustomizationPage.SEARCH),
    Triple(R.string.app_library, R.string.settings_keywords_app_library, CustomizationPage.SEARCH),
    Triple(R.string.settings_search_button_swipe_down, R.string.settings_keywords_search_button_swipe_down, CustomizationPage.SEARCH),
    Triple(R.string.settings_page_dots_haptics, R.string.settings_keywords_page_dots_haptics, CustomizationPage.GESTURES),
    Triple(R.string.settings_gestures_pull_downs, R.string.settings_keywords_gestures_pull_downs, CustomizationPage.GESTURES),
    Triple(R.string.swipe_up_on_home, R.string.settings_keywords_swipe_up_on_home, CustomizationPage.GESTURES),
    Triple(R.string.settings_swipe_down_on_home, R.string.settings_keywords_swipe_down_on_home, CustomizationPage.GESTURES),
    Triple(R.string.actions, R.string.settings_keywords_actions, CustomizationPage.GESTURES),
    Triple(R.string.focus, R.string.settings_keywords_focus, CustomizationPage.FOCUS),
    Triple(R.string.privacy_permissions, R.string.settings_keywords_privacy_permissions, CustomizationPage.PERMISSIONS),
    Triple(R.string.settings_safe_mode_crash_reports, R.string.settings_keywords_safe_mode_crash_reports, CustomizationPage.ADVANCED),
    Triple(R.string.screenshot_mode, R.string.settings_keywords_screenshot_mode, CustomizationPage.ADVANCED),
    Triple(R.string.settings_backup_restore, R.string.settings_keywords_backup_restore, CustomizationPage.BACKUP),
    Triple(R.string.help, R.string.settings_keywords_help, CustomizationPage.HELP),
    Triple(R.string.credits, R.string.settings_keywords_credits, CustomizationPage.CREDITS),
)

internal fun settingsMatches(query: String, title: String, keywords: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hay = "$title $keywords".lowercase()
    return words.isNotEmpty() && words.all { it in hay }
}

@Composable private fun SettingsSearchResults(query: String, onOpen: (CustomizationPage) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val index = remember(androidx.compose.ui.platform.LocalConfiguration.current) {
        SettingsIndex.map { (title, keywords, page) -> Triple(resources.getString(title), resources.getString(keywords), page) }
    }
    val results = index.filter { (title, keywords) -> settingsMatches(query, title, keywords) }
    if (results.isEmpty()) Text(stringResource(R.string.no_results_for_1, query.trim()), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .55f),
        modifier = Modifier.fillMaxWidth().padding(FolioSpace.XXL.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    else SheetGroup {
        results.forEachIndexed { index, (title, _, page) ->
            if (index > 0) MenuDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onOpen(page) }.padding(horizontal = FolioSpace.LARGE.dp, vertical = FolioSpace.SMALL.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = androidx.compose.ui.graphics.Color.White, fontSize = FolioType.BODY.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = .3f))
            }
        }
    }
}

/** Every permission Folio can use, whether it's allowed, and which features rely on it (shared ownership). */
@Composable private fun PermissionsPage(isDefaultHome: Boolean, onMakeDefault: () -> Unit, onShadeSetup: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { tick++ } }
    fun open(intent: android.content.Intent) { runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    val appSettings = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
    data class Perm(val name: String, val usedBy: String, val allowed: Boolean, val action: () -> Unit)
    val perms = remember(tick, isDefaultHome) { listOf(
        Perm(context.getString(R.string.home_app), context.getString(R.string.home_button_folding_gestures), isDefaultHome, onMakeDefault),
        Perm(context.getString(R.string.notification_access), context.getString(R.string.dynamic_island_notification_center_quick),
            IslandListenerService.hasAccess(context)) { open(IslandListenerService.accessSettingsIntent(context)) },
        Perm(context.getString(R.string.gestures_service_accessibility), context.getString(R.string.pull_down_panels_island_and_dock_in_othe),
            SystemShadeAccessibilityService.isConnected(), onShadeSetup),
        Perm(context.getString(R.string.do_not_disturb_access), context.getString(R.string.focus_control_center_actions),
            context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted) {
            open(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
        Perm(context.getString(R.string.modify_system_settings), context.getString(R.string.control_center_brightness_and_rotation_l), android.provider.Settings.System.canWrite(context)) {
            open(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) },
        Perm(context.getString(R.string.usage_access_optional), context.getString(R.string.better_suggestions_counts_apps_you_open), Suggestions.hasUsageAccess(context)) {
            open(Suggestions.usageAccessIntent(context)) },
        Perm(context.getString(R.string.calendar_optional), context.getString(R.string.up_next_widget), UpNext.hasCalendar(context)) { open(appSettings) },
        Perm(context.getString(R.string.contacts), context.getString(R.string.spotlight_contact_search), context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) { open(appSettings) },
        Perm(context.getString(R.string.digital_assistant), context.getString(R.string.side_key_picker), AssistPickerActivity.isDefaultAssistant(context)) { open(AssistPickerActivity.settingsIntent()) },
    ) }
    CardNote(stringResource(R.string.everything_stays_on_your_phone_folio_has), Modifier.padding(horizontal = FolioSpace.TINY.dp))
    SheetGroup {
        perms.forEachIndexed { index, perm ->
            if (index > 0) MenuDivider()
            Row(Modifier.fillMaxWidth().clickable(onClick = perm.action).padding(horizontal = FolioSpace.LARGE.dp, vertical = FolioSpace.COMPACT.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(perm.name, color = androidx.compose.ui.graphics.Color.White, fontSize = FolioType.BODY.sp)
                    Text(stringResource(R.string.used_by_1, perm.usedBy), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .55f), fontSize = FolioType.FOOTNOTE.sp)
                }
                Text(if (perm.allowed) stringResource(R.string.allowed) else "Off", color = if (perm.allowed) FolioColors.Green
                    else androidx.compose.ui.graphics.Color.White.copy(alpha = .5f), fontSize = FolioType.SUBHEAD.sp)
                Icon(Icons.Rounded.ChevronRight, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = .3f))
            }
        }
        // Some banking apps refuse to run while any accessibility service is on, Folio's included (reported on
        // r/GalaxyFold, 18 Sep 2026). Nothing Folio can do from its side, so say so before someone is caught out.
        CardNote(stringResource(R.string.banking_apps_note))
    }
}

/** iOS Settings › Focus: the list of Focuses, with the one that's on. */
@Composable private fun FocusListPage(state: LauncherState, model: LauncherModel, onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var access by remember { mutableStateOf(FocusController.hasAccess(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { access = FocusController.hasAccess(context) } }
    SheetGroup {
        state.focusModes.forEachIndexed { index, mode ->
            if (index > 0) MenuDivider()
            TweakRow(mode.icon(), mode.color, mode.name, "focus-${mode.id}", if (state.activeFocus == mode.id) stringResource(R.string.on) else if (mode.schedule != null) stringResource(R.string.scheduled) else null) { onOpen(mode.id) }
        }
    }
    CardNote(stringResource(R.string.focus_lets_you_silence_notifications_cha), Modifier.padding(horizontal = FolioSpace.TINY.dp))
    if (!access) {
        SheetGroup { IosActionRow(stringResource(R.string.allow_do_not_disturb_access), "focus-allow-access") {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } }
        CardNote(stringResource(R.string.without_it_a_focus_still_changes_home_bu), Modifier.padding(horizontal = FolioSpace.TINY.dp))
    }
}

@Composable private fun FocusModePage(mode: FocusMode, state: LauncherState, model: LauncherModel) {
    val on = state.activeFocus == mode.id
    SheetGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpace.COMFY.dp, vertical = FolioSpace.SMALL.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(androidx.compose.ui.graphics.Color(mode.color)), contentAlignment = Alignment.Center) {
                Icon(mode.icon(), null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(if (on) "On" else "Off", color = androidx.compose.ui.graphics.Color.White, fontSize = FolioType.BODY.sp, modifier = Modifier.weight(1f))
            IosSwitch(on, { model.setFocus(if (it) mode.id else null) }, Modifier.testTag("focus-switch-${mode.id}"))
        }
    }
    SettingsCard(stringResource(R.string.schedule)) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val schedule = mode.schedule
        SettingsSwitch(stringResource(R.string.turn_on_automatically), schedule != null, { on ->
            model.updateFocusMode(mode.copy(schedule = if (!on) null else when (mode.id) {
                "sleep" -> FocusSchedule(22 * 60, 7 * 60)
                "work" -> FocusSchedule(9 * 60, 17 * 60, setOf(1, 2, 3, 4, 5))
                else -> FocusSchedule(9 * 60, 17 * 60)
            }))
        }, "focus-schedule")
        if (schedule != null) {
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            val homeDismissal = (androidx.activity.compose.LocalActivity.current as? MainActivity)?.homeDismissal
            fun label(minute: Int) = java.time.LocalTime.of(minute / 60, minute % 60).format(java.time.format.DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a"))
            fun pick(minute: Int, onPicked: (Int) -> Unit) {
                val dialog = android.app.TimePickerDialog(context, android.R.style.Theme_DeviceDefault_Dialog_Alert,
                    { _, h, m -> onPicked(h * 60 + m) }, minute / 60, minute % 60, is24)
                val dismiss = { dialog.dismiss() }
                homeDismissal?.register(dismiss)
                dialog.setOnDismissListener { homeDismissal?.unregister(dismiss) }
                dialog.show()
            }
            // The row knows which end it sets by its own key, not by its label, which changes with the language.
            listOf(Triple("from", stringResource(R.string.from), schedule.startMinute),
                Triple("to", stringResource(R.string.to), schedule.endMinute)).forEach { (key, name, minute) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(FolioRadius.CONTROL.dp)).clickable {
                    pick(minute) { picked -> model.updateFocusMode(mode.copy(schedule = if (key == "from") schedule.copy(startMinute = picked) else schedule.copy(endMinute = picked))) }
                }.testTag("focus-schedule-$key"), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f))
                    Text(label(minute), color = LocalAccent.current.ink, fontSize = FolioType.BODY.sp)
                }
            }
            // iOS day picker: one letter per day, filled when the schedule runs that day.
            Row(Modifier.fillMaxWidth().padding(vertical = FolioSpace.SNUG.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                java.time.DayOfWeek.entries.forEach { day ->
                    val on = day.value in schedule.days
                    Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (on) androidx.compose.ui.graphics.Color(mode.color) else androidx.compose.ui.graphics.Color.White.copy(alpha = .1f))
                        .clickable(onClickLabel = day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())) {
                            val days = if (on) schedule.days - day.value else schedule.days + day.value
                            if (days.isNotEmpty()) model.updateFocusMode(mode.copy(schedule = schedule.copy(days = days)))
                        }, contentAlignment = Alignment.Center) {
                        Text(day.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                            color = androidx.compose.ui.graphics.Color.White, fontSize = FolioType.SUBHEAD.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            CardNote(stringResource(R.string.focus_schedule_note, mode.name))
        }
    }
    SettingsCard(stringResource(R.string.notifications_title)) {
        SettingsSwitch(stringResource(R.string.silence_notifications), mode.silence, { model.updateFocusMode(mode.copy(silence = it)) }, "focus-silence")
        CardNote(stringResource(R.string.calls_and_people_allowed_in_android_s_do))
    }
    SettingsCard(stringResource(R.string.home_screen)) {
        // The real page count: while a Focus hides pages, Home's own state is the filtered copy.
        val real by model.state.collectAsState()
        val pages = real.layout.pageCount
        Text(stringResource(R.string.show_pages), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = FolioSpace.TINY.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpace.SMALL.dp)) {
            IosChip(mode.pages == null, { model.updateFocusMode(mode.copy(pages = null)) }, label = { Text(stringResource(R.string.all)) })
            repeat(pages) { page ->
                val shown = mode.pages?.contains(page) == true
                IosChip(shown, {
                    val next = (mode.pages ?: emptySet()).let { if (shown) it - page else it + page }
                    model.updateFocusMode(mode.copy(pages = next.ifEmpty { null }))
                }, label = { Text(stringResource(R.string.page_1, page + 1)) }, modifier = Modifier.testTag("focus-page-$page"))
            }
        }
        CardNote(stringResource(R.string.only_these_pages_show_while_is_on_editin, mode.name))
        IosMenuRow(stringResource(R.string.open_on), listOf<Pair<Int?, String>>(null to stringResource(R.string.any_page)) + (0 until pages).filter { mode.pages == null || it in mode.pages }.map { it to stringResource(R.string.page_number, it + 1) },
            mode.homePage, { model.updateFocusMode(mode.copy(homePage = it)) }, tag = "focus-open-on")
        CardNote(stringResource(R.string.home_opens_on_this_page_while_is_on, mode.name))
    }
    if (android.os.Build.VERSION.SDK_INT >= 35) SettingsCard(stringResource(R.string.look)) {
        SettingsSwitch(stringResource(R.string.dim_wallpaper), mode.dimWallpaper, { model.updateFocusMode(mode.copy(dimWallpaper = it)) }, "focus-dim")
        SettingsSwitch(stringResource(R.string.dark_appearance), mode.darkTheme, { model.updateFocusMode(mode.copy(darkTheme = it)) }, "focus-dark")
        SettingsSwitch(stringResource(R.string.grayscale), mode.grayscale, { model.updateFocusMode(mode.copy(grayscale = it)) }, "focus-gray")
        CardNote(stringResource(R.string.android_applies_these_while_the_focus_is))
    }
}

@Composable private fun CrashReportsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var reports by remember { mutableStateOf(CrashLog.reports(context)) }
    SettingsCard(stringResource(R.string.crash_reports)) {
        Text(if (reports.isEmpty()) stringResource(R.string.no_problems_recorded) else pluralStringResource(R.plurals.reports_saved, reports.size, reports.size),
            style = MaterialTheme.typography.bodyMedium)
        reports.firstOrNull()?.let { latest ->
            CardNote(latest.readLines().take(5).joinToString("\n"))
            CardAction(stringResource(R.string.share_latest), Modifier.fillMaxWidth(), onClick = { runCatching { context.startActivity(CrashLog.shareIntent(latest).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } })
            CardAction(stringResource(R.string.clear), Modifier.fillMaxWidth(), destructive = true, onClick = { CrashLog.clear(context); reports = emptyList() })
        }
        val shareScope = rememberCoroutineScope()
        CardAction(stringResource(R.string.share_diagnostics), onClick = {
            shareScope.launch { runCatching { context.startActivity(Diagnostics.reportIntent(context)) } }
        }, modifier = Modifier.testTag("share-diagnostics"))
        CardNote(stringResource(R.string.diagnostics_file_note))
    }
}

@Composable private fun CreditsPage() {
    val credits = listOf(
        stringResource(R.string.duolauncher) to "jakesgoodapps (github.com/jakesgoodapps/DuoLauncher) · MIT · Folio’s starting codebase: iPhone Duo-style Home layouts for both screens, widgets, folders, work profile, wallpapers and Discover",
        "iphone-duo" to "chuspeeism · MIT · fold blur and darkening model",
        "iPhone Duo on Galaxy Z Fold 8 demo" to "u/moomanjohnny · screenshot + shader idea (no code)",
        stringResource(R.string.quicklaunch) to stringResource(R.string.ahmedthegeek_spotlight_ideas_no_code),
        stringResource(R.string.foldfx) to "u/FixHour8452 · fold transition ideas: halfway haptic tick, light sweep, slight scale, following a smooth hinge angle (no code)",
        stringResource(R.string.zfoldduo) to "nnnnnnn0090 · MIT · showed a Fold7/Fold8 can read Samsung's internal hinge angle without root; research for Enhanced Fold Tracking",
        stringResource(R.string.velox) to stringResource(R.string.phillip_tennen_app_panels_idea),
        stringResource(R.string.activator) to stringResource(R.string.ryan_petrich_gestures_and_events_idea),
        stringResource(R.string.axon) to stringResource(R.string.nepeta_notification_app_row_idea),
        stringResource(R.string.velvet) to stringResource(R.string.noisyflake_himynameisubik_tinted_notific),
        stringResource(R.string.colorflow) to stringResource(R.string.david_goldman_album_art_colors_idea),
        stringResource(R.string.harbor) to stringResource(R.string.evan_swick_dock_magnification_idea),
        stringResource(R.string.snowboard) to stringResource(R.string.sparkdev_themes_idea_no_code),
        stringResource(R.string.apex) to stringResource(R.string.sticktron_icon_stacks_idea_no_code),
        stringResource(R.string.icon_restore) to stringResource(R.string.layout_history_idea_no_code),
        stringResource(R.string.lynx_2) to "recent-app dots idea (no code)",
        stringResource(R.string.colorbadges) to "badges that match the app idea (no code)",
        stringResource(R.string.barrel) to stringResource(R.string.page_effects_idea_coming_soon_no_code),
        stringResource(R.string.contributor_covenant_3_0) to stringResource(R.string.organization_for_ethical_source_cc_by_sa),
    )
    SettingsCard(stringResource(R.string.thanks_to)) {
        credits.forEach { (name, detail) ->
            Column(Modifier.fillMaxWidth().padding(vertical = FolioSpace.SNUG.dp)) {
                Text(name)
                CardNote(detail)
            }
        }
    }
    CardNote(stringResource(R.string.tweak_ideas_were_re_created_from_scratch), Modifier.padding(horizontal = FolioSpace.TINY.dp))
}

@Composable private fun CustomizationDestination(icon: ImageVector, title: String, detail: String, tag: String,
    leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f), shape = RoundedCornerShape(FolioRadius.GROUPED_CARD.dp)) {
        Row(Modifier.padding(horizontal = FolioSpace.LARGE.dp, vertical = FolioSpace.COMPACT.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) leading() else Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); CardNote(detail) }
            Icon(Icons.Rounded.ChevronRight, null)
        }
    }
}

/**
 * Live preview of Home built from real data only: your Home and dock apps (with the current icon shape, pack,
 * tint, badges and live icons), your text, glass and dimming settings, and your background. Android's wallpaper
 * image can't be read by apps, so in that mode the preview uses the wallpaper's own reported colors and says so.
 */
@Composable private fun MiniHomePreview(stagedBitmap: android.graphics.Bitmap?, state: LauncherState,
    previewHeight: androidx.compose.ui.unit.Dp, framed: Boolean = true,
    /** The Side Bar (status, dock and search): off for the second page of an unfolded preview, which has one Side Bar. */
    sideBar: Boolean = true,
    /** The cover's layout by default; the inner screen's for an unfolded preview. */
    preset: LayoutPreset = state.compact) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val backgroundRevision = LauncherBackgroundCache.revision.intValue
    val committedBitmap = remember(backgroundRevision) { cachedLauncherBackground(context) }
    val bitmap = stagedBitmap ?: committedBitmap
    val previewBackdrop = rememberMaterialBackdrop()
    val apps = remember(state.apps) { state.apps.associateBy { it.id } }
    val tone = LocalWallpaperTone.current
    val ink = homeInkFor(state.homeInk, tone.prefersDarkText)
    val basePalette = LocalDuoPalette.current
    val glass = if (state.glassTintAmount > 0f) tintedGlass(basePalette.glass, tone.primary, state.glassTintAmount) else basePalette.glass
    // Home page 1 drawn at a real cover-screen size with Folio's own layout math and parts (widget cards, icons,
    // status rail, dock, search pill), then scaled down, so the preview matches Home instead of approximating it.
    val refW = 420f; val refH = 720f
    val geometry = homeGeometry(refW, refH, preset, state.labels, statusHeight = if (state.verticalStatus) 180f else 0f, labelHeight = 20f,
        appRows = state.homeAppRows)
    val placements = state.widgetPlacements.filter { it.page == 0 }
    val shownRows = shownHomeRows(state.homeAppRows, state.homeSlots.take(HOME_CELLS), placements)
    val cells = HomeCellLayout.forPage(geometry, placements.map { it.row to it.spanY })
    val (iconSize, labels) = (state.pageStyles[0] ?: PageStyle()).apply(geometry, state.labels)
    val scale = previewHeight.value / refH
    val left = state.leftHanded
    val railAlign = if (left) Alignment.TopStart else Alignment.TopEnd
    val railEdge = if (left) Modifier.padding(start = FolioSpace.MEDIUM.dp) else Modifier.padding(end = FolioSpace.MEDIUM.dp)
    // A thin black bezel with the screen's own corners inside it, so the preview reads as the phone, not a card.
    val corner = 26.dp * (previewHeight.value / 260f)
    val bezel = previewHeight * .022f
    Column(if (framed) Modifier.fillMaxWidth() else Modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(if (!framed) Modifier else Modifier.clip(RoundedCornerShape(corner + bezel)).background(androidx.compose.ui.graphics.Color(0xFF0B0B0C))
            .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = .2f), RoundedCornerShape(corner + bezel)).padding(bezel)) {
        Box(Modifier.height(previewHeight).width(previewHeight * (refW / refH))
            .clip(RoundedCornerShape(if (framed) corner else 0.dp))
            .testTag("customization-home-preview"), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalMaterialBackdrop provides previewBackdrop) {
            Box(Modifier.requiredSize(refW.dp, refH.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
                Box(Modifier.matchParentSize().captureMaterialBackdrop()) {
                    if (state.systemWallpaper) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                        androidx.compose.ui.graphics.Color(tone.primary ?: 0xFF5A6B78.toInt()), androidx.compose.ui.graphics.Color(tone.secondary ?: tone.primary ?: 0xFF2E3A42.toInt())))))
                    else {
                        DuneWallpaper()
                        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                    }
                    if (state.dimWallpaperDark && basePalette.dark) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .3f)))
                }
                CompositionLocalProvider(LocalHomeInk provides ink, LocalDuoPalette provides basePalette.copy(glass = glass)) {
                    Box(Modifier.offset(x = (if (left) refW - 16f - geometry.gridWidth else 16f).dp, y = geometry.contentTop.dp).width(geometry.gridWidth.dp).height((cells.height(shownRows)).dp)) {
                        placements.forEach { w ->
                            Box(Modifier.offset(x = (cells.x(w.column, w.row) + 5f).dp, y = cells.y(w.row).dp)
                                .size((geometry.cellWidth * w.spanX - 10f).dp, (cells.spanHeight(w.row, w.spanY) - 18f).coerceAtLeast(48f).dp)) {
                                if (w.id < 0) BuiltinWidgetCard(w.id, w.slot) {}
                                else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(FolioRadius.PANEL.dp)).background(glass.copy(alpha = LocalGlassLook.current.widget)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Widgets, null, tint = ink.secondary, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                        repeat(shownRows * GRID_COLUMNS) { local ->
                            val id = state.homeSlots.getOrNull(local) ?: return@repeat
                            val app = apps[id]
                            val folder = if (app == null) state.folders.firstOrNull { it.id == id } ?: return@repeat else null
                            val row = local / GRID_COLUMNS
                            Column(Modifier.offset(x = cells.x(local % GRID_COLUMNS, row).dp, y = cells.y(row).dp).width(geometry.cellWidth.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                if (app != null) AppIcon(app, null, Modifier.size(iconSize.dp), shape = RoundedCornerShape((iconSize * .24f).dp))
                                else if (folder != null) PreviewFolder(folder, apps, iconSize)
                                if (labels) Text(app?.label ?: folder?.title.orEmpty(), color = ink.primary, fontSize = LocalLabelSize.current.sp.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = FolioSpace.TINY.dp, start = FolioSpace.HAIR.dp, end = FolioSpace.HAIR.dp),
                                    style = androidx.compose.ui.text.TextStyle(shadow = ink.labelShadow))
                            }
                        }
                    }
                    if (sideBar && state.verticalStatus) StatusRail(DeviceStatus(battery = 80, wifiConnected = true, wifiLevel = 4, cellularLevel = 4),
                        Modifier.align(railAlign).then(railEdge).offset(y = geometry.statusTop.dp).width(preset.dockWidth.dp),
                        style = state.statusStyle)
                    if (sideBar && geometry.dockBesideRail) Box(Modifier.align(if (left) Alignment.BottomEnd else Alignment.BottomStart)
                        .width((refW - preset.dockWidth - 28f).dp).padding(bottom = 58.dp), contentAlignment = Alignment.Center) { Row(Modifier
                        .height(geometry.dockBarHeight.dp).materialBackground(SquircleCornerShape(30.dp))
                        .padding(horizontal = FolioSpace.SMALL.dp), verticalAlignment = Alignment.CenterVertically) {
                        state.dock.forEach { id ->
                            Box(Modifier.width(geometry.dockPitch.dp), contentAlignment = Alignment.Center) {
                                id?.let(apps::get)?.let { AppIcon(it, null, Modifier.size(dockIconSize(iconSize).dp), shape = RoundedCornerShape(11.dp)) }
                            }
                        }
                    } }
                    else if (sideBar) Column(Modifier.align(railAlign).then(railEdge).offset(y = geometry.dockTop.dp).width(preset.dockWidth.dp)
                        .height(geometry.dockHeight.dp).materialBackground(SquircleCornerShape(30.dp))
                        .padding(vertical = FolioSpace.SMALL.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        state.dock.forEach { id ->
                            Box(Modifier.fillMaxWidth().height(geometry.dockRowHeight.dp), contentAlignment = Alignment.Center) {
                                id?.let(apps::get)?.let { AppIcon(it, null, Modifier.size(dockIconSize(iconSize).dp), shape = RoundedCornerShape(11.dp)) }
                            }
                        }
                    }
                    if (sideBar && state.searchPill) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = FolioSpace.COMFY.dp)
                        .then(if (left) Modifier.padding(start = (preset.dockWidth + 24f).dp) else Modifier.padding(end = (preset.dockWidth + 24f).dp))) {
                        HomeSearchPill {}
                    }
                }
            }
            }
        }
        }
        if (framed && state.systemWallpaper) CardNote(stringResource(R.string.colors_from_your_android_wallpaper_apps), Modifier.padding(top = FolioSpace.SNUG.dp))
    }
}

/**
 * Hidden apps, like iOS: kept out of the App Library, and listed here only after unlocking with a fingerprint, face or
 * the phone's PIN (straight away on a phone with no screen lock, where there's nothing to ask).
 */
@Composable private fun HiddenAppsRow(state: LauncherState, model: LauncherModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hidden = state.apps.filter { it.id in state.hiddenApps && AppSecurity.isDisplayable(it, state.appSecurity) }.sortedBy { it.label.lowercase() }
    var unlocked by remember { mutableStateOf(false) }
    if (hidden.isEmpty()) return
    if (!unlocked) {
        IosActionRow(stringResource(R.string.hidden_apps_count, hidden.size), "hidden-apps") {
            val authenticators = android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            runCatching {
                android.hardware.biometrics.BiometricPrompt.Builder(context).setTitle(context.getString(R.string.hidden_apps_title))
                    .setSubtitle(context.getString(R.string.unlock_to_see_the_apps_you_ve_hidden)).setAllowedAuthenticators(authenticators).build()
                    .authenticate(android.os.CancellationSignal(), context.mainExecutor,
                        object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { unlocked = true }
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) unlocked = true
                            }
                        })
            }
        }
        return
    }
    Text(stringResource(R.string.hidden_apps), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = FolioSpace.SNUG.dp))
    hidden.forEach { app ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, null, Modifier.size(32.dp), shape = RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Text(app.label, Modifier.weight(1f))
            TextButton(onClick = { model.setHidden(app.id, false) }) { Text(stringResource(R.string.unhide)) }
        }
    }
}

/** A folder as Home draws it (first four apps on glass or the folder's color), without Home's drag and launch hooks. */
@Composable private fun PreviewFolder(folder: FolderEntry, apps: Map<String, AppEntry>, size: Float) {
    val shape = RoundedCornerShape((size * .24f).dp)
    val tint = LocalFolderColors.current[folder.id]?.let { androidx.compose.ui.graphics.Color(it) }
    Box(Modifier.size(size.dp).clip(shape).materialBackground(shape, tint ?: FolioGlass.panel)) {
        folder.appIds.take(4).forEachIndexed { index, id ->
            apps[id]?.let { app ->
                AppIcon(app, null, Modifier.align(when (index) {
                    0 -> Alignment.TopStart; 1 -> Alignment.TopEnd; 2 -> Alignment.BottomStart; else -> Alignment.BottomEnd
                }).padding(5.dp).size((size * .38f).dp).clip(RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable private fun HomeLayoutSettings(state: LauncherState, wide: Boolean, onWide: (Boolean) -> Unit,
    model: LauncherModel) {
    val p = if (wide) state.expanded else state.compact
    IosSegmented(listOf(false to stringResource(R.string.cover), true to stringResource(R.string.inner)), wide, onWide, Modifier.padding(vertical = FolioSpace.TINY.dp), tag = "layout-screen")
    SettingsCard(stringResource(R.string.layout)) {
        val d = LayoutPreset()
        CustomizationSlider(stringResource(R.string.app_icon_size), stringResource(R.string.dp_value, p.iconSize.toInt()), p.iconSize, 40f..68f, d.iconSize, peek = true) { model.setPreset(wide, p.copy(iconSize = it)) }
        CustomizationSlider(stringResource(R.string.space_between_rows), stringResource(R.string.dp_value, p.rowGap.toInt()), p.rowGap, 0f..28f, d.rowGap, peek = true) { model.setPreset(wide, p.copy(rowGap = it)) }
        CustomizationSlider(stringResource(R.string.space_between_columns), stringResource(R.string.dp_value, p.columnGap.toInt()), p.columnGap, 8f..40f, d.columnGap, peek = true) { model.setPreset(wide, p.copy(columnGap = it)) }
        CustomizationSlider(stringResource(R.string.widget_size), "${(p.widgetScale * 100).roundToInt()}%", p.widgetScale, .8f..1.25f, d.widgetScale, peek = true) { model.setPreset(wide, p.copy(widgetScale = it)) }
        // Automatic says which number it landed on, so the count is never a mystery.
        IosMenuRow(stringResource(R.string.rows), listOf(0 to stringResource(R.string.automatic_rows_count, state.homeAppRows), 4 to "4"), state.homeRows, model::setHomeRows, tag = "home-rows")
        CardNote(stringResource(R.string.automatic_adds_up_to_3_more_rows_of_apps))
        val rowsContext = androidx.compose.ui.platform.LocalContext.current
        CardNote(automaticRowsNote(state, rowsContext.strings()))
    }
    SettingsCard(stringResource(R.string.position)) {
        IosMenuRow(stringResource(R.string.dock), listOf(DockPlacement.AUTOMATIC to stringResource(R.string.automatic), DockPlacement.SIDE to stringResource(R.string.side_rail), DockPlacement.BOTTOM to stringResource(R.string.bottom)),
            p.dockPlacement, { model.setPreset(wide, p.copy(dockPlacement = it)) }, tag = "dock-placement")
        IosMenuRow(stringResource(R.string.apps), listOf(false to stringResource(R.string.centered), true to "Top"), p.pageTop,
            { model.setPreset(wide, p.copy(pageTop = it)) }, tag = "page-position")
        IosMenuRow(stringResource(R.string.status), listOf(true to stringResource(R.string.level_with_apps), false to stringResource(R.string.custom)), p.statusAlignToGrid,
            { model.setPreset(wide, p.copy(statusAlignToGrid = it)) }, tag = "status-position")
        if (!p.statusAlignToGrid) CustomizationSlider(stringResource(R.string.status_height), if (p.statusPosition < .01f) "Top" else "${(p.statusPosition * 100).toInt()}%",
            p.statusPosition, 0f..1f, peek = true) { model.setPreset(wide, p.copy(statusPosition = it)) }
        CardNote(when (p.dockPlacement) {
            DockPlacement.AUTOMATIC -> stringResource(R.string.the_dock_stays_on_the_side_bar_and_moves)
            DockPlacement.SIDE -> stringResource(R.string.the_dock_stays_on_the_side_bar_even_when)
            DockPlacement.BOTTOM -> if (wide) stringResource(R.string.the_dock_sits_along_the_bottom_under_you) else stringResource(R.string.the_dock_sits_along_the_bottom_in_landsc)
        } + if (!p.statusAlignToGrid) " The dock always stays below the status." else "")
    }
    SettingsCard(stringResource(R.string.side_rail)) {
        CustomizationSlider(stringResource(R.string.width), stringResource(R.string.dp_value, p.dockWidth.toInt()), p.dockWidth, 56f..84f, LayoutPreset().dockWidth, peek = true) { model.setPreset(wide, p.copy(dockWidth = it)) }
        CustomizationSlider(stringResource(R.string.space_between_dock_apps), stringResource(R.string.dp_value, p.dockSpacing.toInt()), p.dockSpacing, 0f..24f, 0f, peek = true) { model.setPreset(wide, p.copy(dockSpacing = it)) }
        if (p.dockPlacement != DockPlacement.BOTTOM) SettingsSwitch(stringResource(R.string.align_dock_with_app_rows), p.dockAlignToGrid, { model.setPreset(wide, p.copy(dockAlignToGrid = it)) })
        if (!p.dockAlignToGrid && p.dockPlacement != DockPlacement.BOTTOM) CustomizationSlider(stringResource(R.string.dock_height), "${(p.dockPosition * 100).toInt()}%", p.dockPosition, 0f..1f,
            LayoutPreset().dockPosition, peek = true) { model.setPreset(wide, p.copy(dockPosition = it)) }
    }
    SheetGroup { IosActionRow(stringResource(R.string.reset_this_layout), destructive = true, onClick = { model.setPreset(wide, LayoutPreset()) }) }
}

/** Keeps Android's pop-up and Folio's live activity from showing for the same message. */
@Composable private fun MessageBannerSettings(avoidDouble: Boolean, onAvoidDouble: (Boolean) -> Unit) {
    SettingsSwitch(stringResource(R.string.dont_double_up_with_android_pop_ups), avoidDouble, onAvoidDouble, "messages-avoid-double-switch")
    CardNote(if (avoidDouble) stringResource(R.string.messages_that_android_already_pops_up_ar)
        else stringResource(R.string.the_island_shows_every_new_message_even))
    if (!avoidDouble) return
    MessageChannelList()
}

/** Brief pop-ups › Other notifications: any app's new notifications in Live Activities. Off until turned on. */
@Composable private fun IslandAlertSettings(on: Boolean, appsOff: Set<String>, onOn: (Boolean) -> Unit, onChooseApps: () -> Unit) {
    SettingsSwitch(stringResource(R.string.other_notifications_2), on, onOn, "island-alerts-switch")
    if (on) {
        val channels by IslandListenerService.messageChannels.collectAsState()
        val seen = channels.values.filter { !it.isMessage }.map { it.packageName }.toSet() + appsOff
        IosNavRow(stringResource(R.string.apps), if (seen.isEmpty()) null else stringResource(R.string.count_of_total, (seen - appsOff).size, seen.size), onChooseApps, "island-alert-apps")
    }
    CardNote(if (on) stringResource(R.string.new_notifications_from_the_apps_you_choo)
        else stringResource(R.string.show_new_notifications_from_other_apps_i))
}

/** Live Activities › Other Notifications: which apps can show notification cards. */
@Composable private fun IslandAlertApps(appsOff: Set<String>, avoidDouble: Boolean, onApp: (String, Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val channels by IslandListenerService.messageChannels.collectAsState()
    val seen = channels.values.filter { !it.isMessage }.groupBy { it.packageName }
    val pm = context.packageManager
    fun label(pkg: String) = seen[pkg]?.first()?.appLabel
        ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    val apps = remember(seen.keys, appsOff) { (seen.keys + appsOff).map { it to label(it) }.sortedBy { it.second.lowercase() } }
    SettingsCard(stringResource(R.string.show_in_the_island)) {
        if (apps.isEmpty()) Text(stringResource(R.string.apps_show_up_here_after_they_send_a_noti),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = FolioSpace.MEDIUM.dp))
        apps.forEach { (pkg, name) -> key(pkg) {
            val enabled = pkg !in appsOff
            // With "don't double up" on, a channel Android pops up itself stays with Android; offer the same way over.
            val android = seen[pkg].orEmpty().firstOrNull { it.popsUp }?.takeIf { avoidDouble && enabled }
            val icon = remember(pkg) { runCatching { pm.getApplicationIcon(pkg).toBitmap(84, 84).asImageBitmap() }.getOrNull() }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Image(it, null, Modifier.size(29.dp).clip(RoundedCornerShape(7.dp))) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (android != null) Text(stringResource(R.string.android_shows_its_own_pop_up), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (android != null) CardAction(stringResource(R.string.use_island), onClick = { runCatching { context.startActivity(android.settingsIntent()) } })
                IosSwitch(enabled, { onApp(pkg, it) }, Modifier.testTag("island-alert-$pkg"))
            }
        } }
    }
    CardNote(if (avoidDouble) stringResource(R.string.apps_that_android_already_pops_up_stay_w)
        else stringResource(R.string.the_island_shows_these_apps_new_notifica), Modifier.padding(horizontal = FolioSpace.LARGE.dp))
}

/** Messaging apps Folio has seen, and whether each one pops up through Android or the island. */
@Composable private fun MessageChannelList() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val channels by IslandListenerService.messageChannels.collectAsState()
    val list = channels.values.filter { it.isMessage }.sortedWith(compareBy({ !it.popsUp }, { it.appLabel }))
    if (list.isEmpty()) CardNote(stringResource(R.string.messaging_apps_appear_here_after_they_po))
    list.forEach { channel ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(channel.appLabel, style = MaterialTheme.typography.bodyLarge)
                CardNote(listOfNotNull(channel.channelName, if (channel.popsUp) stringResource(R.string.android_pop_up) else stringResource(R.string.island)).joinToString(" · "))
            }
            // Both ways lead to the app's own notification settings: turn Android's pop-up off to use the island,
            // or back on to get Android's pop-up again (the only way back once an app was switched over).
            TextButton(onClick = { runCatching { context.startActivity(channel.settingsIntent()) } }) {
                Text(if (channel.popsUp) stringResource(R.string.use_island) else stringResource(R.string.android_popup_title))
            }
        }
    }
}

@Composable internal fun SettingsSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String? = null, enabled: Boolean = true) {
    // One accessible element for TalkBack ("label, switch, on"); the whole row toggles.
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).alpha(if (enabled) 1f else .4f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = FolioSpace.MEDIUM.dp, top = FolioSpace.SNUG.dp, bottom = FolioSpace.SNUG.dp), fontSize = FolioType.BODY.sp); IosSwitch(checked, onChecked, Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier), enabled = enabled)
    }
}

/**
 * [peek]: a Home layout slider, so Settings fades while it's dragged by touch (TalkBack and keys change it without a
 * press, so they don't). [default]: a light tick as the value crosses it.
 */
@Composable private fun CustomizationSlider(label: String, valueLabel: String, value: Float,
    range: ClosedFloatingPointRange<Float>, default: Float? = null, peek: Boolean = false, onChange: (Float) -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val touching = peek && (dragged || pressed)
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val span = range.endInclusive - range.start
    if (touching) SideEffect {
        SettingsPeek.value = PeekSlider(label, valueLabel, if (span > 0f) ((value - range.start) / span).coerceIn(0f, 1f) else 0f, bounds)
    }
    DisposableEffect(touching) { onDispose { if (touching) SettingsPeek.value = null } }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val change: (Float) -> Unit = { next ->
        if (default != null && next != value && kotlin.math.sign(next - default) != kotlin.math.sign(value - default))
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
        onChange(next)
    }
    Column(Modifier.padding(top = FolioSpace.COMPACT.dp, bottom = FolioSpace.HAIR.dp).onGloballyPositioned { bounds = it.boundsInWindow() }) {
        Row { Text(label, Modifier.weight(1f), fontSize = FolioType.BODY.sp); Text(valueLabel, fontSize = FolioType.BODY.sp, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .6f)) }
        IosSlider(value, change, valueRange = range, modifier = Modifier.semantics { contentDescription = label }, interactionSource = interaction) }
}

@Composable internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpace.SNUG.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = FolioSpace.LARGE.dp, top = FolioSpace.COMPACT.dp).semantics { heading() })
        GroupedCard(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.ui.graphics.Color.White.copy(alpha = .12f),
            Modifier.fillMaxWidth(), content)
    }
}

/** The open Fold for the Inner tab: two Home pages with one Side Bar, laid out with the inner screen's settings. */
@Composable private fun UnfoldedHomePreview(bitmap: android.graphics.Bitmap?, state: LauncherState, height: androidx.compose.ui.unit.Dp) {
    val corner = 18.dp
    val bezel = 5.dp
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(corner + bezel)).background(androidx.compose.ui.graphics.Color(0xFF0B0B0C))
            .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = .2f), RoundedCornerShape(corner + bezel)).padding(bezel)
            .clip(RoundedCornerShape(corner)).clearedDescription(R.string.preview_of_home_on_the_inner_screen)) {
            MiniHomePreview(bitmap, state, height, framed = false, sideBar = state.leftHanded, preset = state.expanded)
            MiniHomePreview(bitmap, state, height, framed = false, sideBar = !state.leftHanded, preset = state.expanded)
        }
    }
}

/** Folio's current app icon as its screens show it. */
internal fun folioIconBitmap(context: android.content.Context, size: Int = 216): androidx.compose.ui.graphics.ImageBitmap? =
    AppIconChoice.current(context).artwork(context, size)

/** iOS-style alternate app icons: tap one to use it for Folio's app entry. */
@Composable private fun AppIconCard(onChanged: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var current by remember { mutableStateOf(AppIconChoice.current(context)) }
    SettingsCard(stringResource(R.string.app_icon)) {
        Row(Modifier.fillMaxWidth().padding(vertical = FolioSpace.SNUG.dp), horizontalArrangement = Arrangement.spacedBy(FolioSpace.XL.dp)) {
            AppIconChoice.entries.forEach { choice ->
                val choiceName = stringResource(choice.label)
                val bitmap = remember(choice) { choice.artwork(context, 180) }
                val selected = choice == current
                Column(Modifier.clip(RoundedCornerShape(FolioRadius.GROUP.dp)).clickable {
                    if (!selected) { AppIconChoice.set(context, choice); current = choice; onChanged() }
                }.padding(FolioSpace.SNUG.dp).semantics { this.selected = selected; contentDescription = choiceName + " app icon" }.testTag("app-icon-${choice.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).then(if (selected) Modifier.border(2.5.dp, LocalAccent.current.ink, RoundedCornerShape(18.dp)).padding(FolioSpace.TINY.dp) else Modifier.padding(FolioSpace.TINY.dp))) {
                        bitmap?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize().clip(RoundedCornerShape(FolioRadius.CARD.dp))) }
                    }
                    Text(stringResource(choice.label), color = if (selected) LocalAccent.current.ink else androidx.compose.ui.graphics.Color.White, fontSize = FolioType.FOOTNOTE.sp, modifier = Modifier.padding(top = FolioSpace.SNUG.dp))
                }
            }
        }
        CardNote(stringResource(R.string.changes_folio_s_icon_in_the_app_library))
    }
}

/** Beta label beside a title, like TestFlight features. */
@Composable private fun BetaTag() {
    Text(stringResource(R.string.beta), color = FolioColors.Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        modifier = Modifier.padding(start = FolioSpace.SMALL.dp).border(1.dp, FolioColors.Orange, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp))
}

/** Layout History (Beta): automatic snapshots of Home before big changes, each restorable. */
@Composable private fun LayoutHistoryCard(state: LauncherState, model: LauncherModel, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { LayoutHistory.load(context) }
    val snapshots by LayoutHistory.snapshots.collectAsState()
    var confirm by remember { mutableStateOf<LayoutSnapshot?>(null) }
    SettingsCard(stringResource(R.string.layout_history)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.save_home_before_big_changes), Modifier.weight(1f, fill = false)); BetaTag() }
            IosSwitch(state.layoutHistory, model::setLayoutHistory, Modifier.testTag("layout-history-switch"))
        }
        if (state.layoutHistory) {
            // Read here rather than in the click: the name is what the snapshot is called in the list below it.
            val savedByYou = stringResource(R.string.saved_by_you)
            CardAction(stringResource(R.string.save_current_layout), onClick = { model.saveLayoutSnapshot(savedByYou, force = true) }, modifier = Modifier.testTag("layout-history-save"))
            snapshots.forEach { snapshot ->
                // Merged, like the switch row above: without it every saved layout offers a button TalkBack reads as
                // just "Restore", and there is no way to hear which layout it would put back. Merged, the row is one
                // thing to land on - "Before restoring a backup, 19 Sep 2026 8:14 PM, Restore" - carrying the button's
                // own action, and it is a bigger target besides.
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.reason, fontSize = 16.sp)
                        CardNote(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(snapshot.time)))
                    }
                    CardAction(stringResource(R.string.restore), onClick = { confirm = snapshot })
                }
            }
        }
        CardNote(stringResource(R.string.before_restoring_a_backup_arrange_like_i, LayoutHistory.MAX))
    }
    confirm?.let { snapshot ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.restore_this_layout)) },
            text = { Text(stringResource(R.string.home_goes_back_to_how_it_was_1, snapshot.reason.lowercase())) },
            confirmButton = { TextButton(onClick = { model.restoreLayoutSnapshot(snapshot); confirm = null; onClose() }) { Text(stringResource(R.string.restore)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Settings › Wallpaper & Appearance: one material for launcher backgrounds. */
@Composable private fun GlassCardSettings(state: LauncherState, model: LauncherModel) {
    val solid = LocalSolidGlass.current
    SettingsCard(stringResource(R.string.background_material)) {
        IosMenuRow(stringResource(R.string.style), BackgroundMaterial.entries.map { it to stringResource(it.label) },
            state.backgroundMaterial, model::setBackgroundMaterial, tag = "background-material")
        // Clear ↔ Tinted, like iOS: all the way left is clear glass, the middle is Folio's usual wallpaper tint.
        val tint = if (state.tintedGlass) state.glassTint else 0f
        CustomizationSlider(stringResource(R.string.wallpaper_tint), when { tint < .01f -> stringResource(R.string.clear); tint > .99f -> stringResource(R.string.tinted); else -> "${(tint * 100).toInt()}%" },
            tint, 0f..1f, default = .5f, peek = true, onChange = model::setGlassTint)
        SettingsSwitch(stringResource(R.string.reduce_transparency), state.reduceTransparency, model::setReduceTransparency, "reduce-transparency-switch")
        CardNote(stringResource(R.string.background_material_note))
        if (solid) CardNote(if (!state.reduceTransparency) stringResource(R.string.glass_is_nearly_solid_because_android_s)
            else stringResource(R.string.widgets_the_side_bar_and_the_dock_are_ne))
    }
}

/** A live sample of the badge settings on a plain icon, so each change shows right away. */
@Composable private fun BadgePreviewRow(state: LauncherState) {
    val look = LocalIconLook.current
    Row(Modifier.fillMaxWidth().padding(vertical = FolioSpace.SMALL.dp).clearedDescription(R.string.badge_preview),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
        listOf(1, 12).forEach { count ->
            Box(Modifier.size(56.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(FolioRadius.CARD.dp)).background(FolioColors.MenuSurface))
                val color = look.badgeColor.fixed?.let { androidx.compose.ui.graphics.Color(it) }
                    ?: if (look.badgeColor == BadgeColor.SOFT) androidx.compose.ui.graphics.Color(0xFFE5E5EA) else FolioColors.RedLight
                IconBadge(count, state.badgeStyle, color, state.badgeLook, state.badgeSize.scale)
            }
        }
    }
}

/** iOS-style "Save Backup" alert with a name field; the file lands in Download/Folio. */
@Composable private fun BackupNameAlert(onCancel: () -> Unit, onSave: (String) -> Unit) {
    val backupContext = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(backupContext.getString(R.string.default_backup_name, java.time.LocalDate.now().toString())) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.save_backup_2)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.saved_to_1, FolioFiles.displayPath), fontSize = FolioType.FOOTNOTE.sp)
                androidx.compose.foundation.text.BasicTextField(name, { name = it.take(60) },
                    Modifier.padding(top = FolioSpace.MEDIUM.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = .1f)).padding(horizontal = FolioSpace.COMPACT.dp, vertical = FolioSpace.SMALL.dp)
                        .focusRequester(focus).testTag("backup-name"),
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = FolioType.SUBHEAD.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSave(name) }))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}

/** Why Automatic landed on this many rows: which screens Folio has measured, and which one sets the limit. */
internal fun automaticRowsNote(state: LauncherState, strings: Strings): String {
    val cover = state.homeFitCompact
    val inner = state.homeFitExpanded
    return when {
        state.homeRows > 0 -> strings.plural(R.plurals.rows_note_fixed, state.homeRows, state.homeRows)
        cover == 0 && inner == 0 -> strings.get(R.string.rows_note_unmeasured)
        inner == 0 -> strings.get(R.string.rows_note_cover_only, cover)
        cover == 0 -> strings.get(R.string.rows_note_inner_only, inner)
        cover == inner && cover < MAX_APP_ROWS -> strings.plural(R.plurals.rows_note_same, cover, cover)
        cover == inner -> strings.plural(R.plurals.rows_note_same_max, cover, cover)
        else -> strings.get(R.string.rows_note_different, cover, inner, minOf(cover, inner))
    }
}

/** A content description from strings.xml; semantics blocks can't read resources themselves. */
@Composable internal fun Modifier.description(@androidx.annotation.StringRes id: Int): Modifier =
    stringResource(id).let { text -> semantics { contentDescription = text } }

/** Like [description], replacing whatever the children would have said. */
@Composable internal fun Modifier.clearedDescription(@androidx.annotation.StringRes id: Int): Modifier =
    stringResource(id).let { text -> clearAndSetSemantics { contentDescription = text } }
