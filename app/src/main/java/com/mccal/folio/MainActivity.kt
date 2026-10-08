package com.mccal.folio

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import android.app.role.RoleManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

class MainActivity : ComponentActivity() {
    private val model: LauncherModel by lazy { (application as DuoApplication).launcherModel }
    private lateinit var widgets: WidgetController
    internal lateinit var backups: BackupController
        private set
    internal lateinit var backgrounds: LauncherBackgroundController
        private set
    private val homeRequests = mutableIntStateOf(0)
    internal val homeDismissal = HomeDismissal()
    private val homeButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra("reason") == "homekey") returnHome()
        }
    }
    private val searchRequests = mutableIntStateOf(0)
    /** Opened from Android Settings (Home app gear / "Additional settings in the app"). */
    private val settingsRequests = mutableIntStateOf(0)
    private val defaultHome = mutableStateOf(false)
    private val showFirstRun = mutableStateOf(false)
    private lateinit var setupExperience: SetupExperience
    private lateinit var status: DeviceStatusMonitor
    private lateinit var appearance: AppearanceStore
    private var appearanceLocationGeneration = 0
    private var appearancePermissionGeneration = -1
    private var appearanceLocationCancellation: CancellationSignal? = null
    private var timeReceiverRegistered = false
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { appearance.refresh(systemDark()) }
    }
    private val locationPermission = activityResultRegistry.register("duo.appearance.location", this,
        ActivityResultContracts.RequestPermission(), permissionResult@{ granted ->
        if (appearancePermissionGeneration != appearanceLocationGeneration || isDestroyed) return@permissionResult
        appearancePermissionGeneration = -1
        if (granted) requestAppearanceLocation(keepPending = true)
        else finishAppearanceLocation(getString(R.string.location_permission_wasn_t_granted_using))
    })
    private val phonePermission = activityResultRegistry.register("duo.phone.state", this,
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && ::status.isInitialized) status.refreshPhoneAccess()
    }
    private var openingDiscover = false
    private var shadeSetupDialog: android.app.AlertDialog? = null
    private var returningFromShadeSettings = false
    private var shadeSetupOwnsExternalUi = false
    private var recreatingShadeSetup = false
    override fun onCreate(savedInstanceState: Bundle?) {
        // The wallpaper theme must be chosen before the window exists (switching to it starts the screen again).
        if (usesSystemWallpaper(this)) setTheme(R.style.Theme_Duo_Wallpaper)
        super.onCreate(savedInstanceState)
        AppSecurity.initialize(this)
        setupExperience = SetupExperience(this)
        Installs.start(this); NewApps.load(this)
        FocusScheduler.run(this)
        // USER_PRESENT is a protected system broadcast delivered to runtime receivers.
        androidx.core.content.ContextCompat.registerReceiver(this, unlockReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // Receive the system's Home notification even when a dialog owns focus. Never send it.
        ContextCompat.registerReceiver(this, homeButtonReceiver,
            IntentFilter("android.intent.action.CLOSE_SYSTEM_DIALOGS"), ContextCompat.RECEIVER_NOT_EXPORTED)
        showFirstRun.value = setupExperience.entryDecision(SetupExperience.hadLauncherState(this)) ==
            SetupEntryDecision.SHOW
        returningFromShadeSettings = savedInstanceState?.getBoolean(SHADE_SETTINGS_PENDING) == true
        val restoreShadeDialog = savedInstanceState?.getBoolean(SHADE_DIALOG_VISIBLE) == true
        appearance = AppearanceStore(this)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        widgets = WidgetController(this, model) { active ->
            LiveDiscover.setExternalResultPending(this, "main", "widget-setup", active)
        }.also { it.restore(savedInstanceState) }
        backups = BackupController(this, model, widgets) { active ->
            LiveDiscover.setExternalResultPending(this, "main", "layout-backup", active)
        }.also { it.restore() }
        backgrounds = LauncherBackgroundController(this) { active ->
            LiveDiscover.setExternalResultPending(this, "main", "launcher-background", active)
        }
        status = DeviceStatusMonitor(this).also { lifecycle.addObserver(it) }
        lifecycle.addObserver(IslandEvents.Observer(this))
        updateDefaultHome()
        if (savedInstanceState == null && intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        if (savedInstanceState == null && opensSettings(intent)) settingsRequests.intValue++
        intent.removeExtra("duo_destination")
        setContent {
            val savedState = model.state.collectAsStateWithLifecycle().value
            val safeMode = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(SafeMode.active) }
            val solidGlass = savedState.reduceTransparency || rememberSystemHighContrast()
            val state = FocusPages.effective(if (safeMode.value) SafeMode.effective(savedState) else savedState)
                .withCommonMaterial()
                .let { if (solidGlass) it.withSolidGlass() else it }
            androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(31_000); SafeMode.markStable(this@MainActivity) }
            val safeAcknowledged = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            if (safeMode.value && !safeAcknowledged.value) AlertDialog(onDismissRequest = {},
                title = { androidx.compose.material3.Text(getString(R.string.folio_started_in_safe_mode)) },
                text = { androidx.compose.material3.Text(stringResource(R.string.folio_closed_unexpectedly_twice_so_opt)) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { SafeMode.exit(this@MainActivity); safeMode.value = false }) {
                    androidx.compose.material3.Text(getString(R.string.restart_normally)) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { safeAcknowledged.value = true }) {
                    androidx.compose.material3.Text(getString(R.string.continue_in_safe_mode)) } })
            // After a crash or a freeze, offer to report it once, on the next launch. Safe Mode already has its own
            // alert for repeated crashes, so this waits until that one is answered rather than stacking on top of it.
            val unreported = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<java.io.File?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                unreported.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Diagnostics.unaskedFailure(this@MainActivity) }
            }
            val reportScope = androidx.compose.runtime.rememberCoroutineScope()
            if (unreported.value != null && (!safeMode.value || safeAcknowledged.value)) AlertDialog(
                onDismissRequest = { unreported.value?.let { Diagnostics.markAsked(this@MainActivity, it) }; unreported.value = null },
                title = { androidx.compose.material3.Text(stringResource(R.string.folio_closed_unexpectedly)) },
                text = { androidx.compose.material3.Text(stringResource(R.string.send_a_report_to_help_fix_it)) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = {
                    val report = unreported.value
                    unreported.value = null
                    reportScope.launch {
                        runCatching { startActivity(Diagnostics.reportIntent(this@MainActivity, email = true)) }
                            .onSuccess { report?.let { Diagnostics.markAsked(this@MainActivity, it) } }
                            .onFailure { IslandEvents.notice(this@MainActivity, getString(R.string.the_report_couldn_t_be_opened)) }
                    }
                }) { androidx.compose.material3.Text(stringResource(R.string.send_report)) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = {
                    unreported.value?.let { Diagnostics.markAsked(this@MainActivity, it) }; unreported.value = null
                }) { androidx.compose.material3.Text(stringResource(R.string.not_now)) } })
            val deviceStatus = ScreenshotMode.status(status.state.collectAsStateWithLifecycle().value, ScreenshotMode.on.collectAsStateWithLifecycle().value)
            val dragHost = androidx.compose.runtime.remember { HomeDragHost() }
            androidx.compose.runtime.LaunchedEffect(state.showSystemStatusBar) {
                setStatusMode(!state.showSystemStatusBar)
            }
            val activityOverlayOpen = topPanel.value != null || spotlightVisible.value ||
                // Only a Lock Cover that's actually drawn blurs Home (turning the setting off mid-way must not leave a blur).
                (lockCoverVisible.value && state.lockCover)
            val overlayOpen = activityOverlayOpen || homeDismissal.hasOpenPopup
            MotionSpeed.current = state.motionSpeed
            // The trail for bug reports: what was open, and a heartbeat while Home is showing.
            val overlayName = listOfNotNull(topPanel.value?.name, getString(R.string.spotlight).takeIf { spotlightVisible.value },
                "sheet".takeIf { LauncherSheetsOpen.intValue > 0 }, getString(R.string.lock_cover).takeIf { lockCoverVisible.value && state.lockCover })
                .joinToString(" + ").ifEmpty { null }
            androidx.compose.runtime.LaunchedEffect(overlayName) { Diagnostics.event(overlayName?.let { "Open: $it" } ?: getString(R.string.nothing_open_over_home)) }
            val regular = androidx.compose.ui.platform.LocalConfiguration.current.fitsRegularHomeLayout()
            androidx.compose.runtime.LaunchedEffect(regular) { Diagnostics.event(if (regular) getString(R.string.unfolded_layout) else getString(R.string.folded_layout)) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    while (true) { kotlinx.coroutines.delay(30_000); Diagnostics.checkpoint(this@MainActivity, visible = true) }
                }
            }
            val blurOverlayOpen = topPanel.value != null || spotlightVisible.value ||
                LauncherSheetsOpen.intValue > LauncherContextMenusOpen.intValue ||
                (lockCoverVisible.value && state.lockCover)
            val overlayProgress by rememberSettlingProgress(if (blurOverlayOpen) 1f else 0f,
                MotionSpeed.spring(.86f, androidx.compose.animation.core.Spring.StiffnessMediumLow))
            val backdropBlurPx = with(androidx.compose.ui.platform.LocalDensity.current) { (state.panelBlur * 32).dp.toPx() }
            val backdropBlur = androidx.compose.runtime.remember(backdropBlurPx) {
                androidx.compose.ui.graphics.BlurEffect(backdropBlurPx, backdropBlurPx, androidx.compose.ui.graphics.TileMode.Clamp)
            }
            DuoTheme(appearance.state.dark) { val notificationItems = IslandListenerService.notifications.collectAsStateWithLifecycle().value
            val installSessions = Installs.active.collectAsStateWithLifecycle().value
            val installProgress = androidx.compose.runtime.remember(installSessions) { installSessions.values.associate { it.packageName to it.progress } }
            val newApps = NewApps.packages.collectAsStateWithLifecycle().value
            // The Discover host is a not-touchable window stacked above the keyboard; Android drops every key
            // tap "due to occlusion" while it exists. Remove it whenever a keyboard can be up.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            val imeUp = WindowInsets.isImeVisible ||
                WindowInsets.imeAnimationTarget.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            val typing = spotlightVisible.value || imeUp
            androidx.compose.runtime.DisposableEffect(typing) {
                if (typing) LiveDiscover.setExternalResultPending(this@MainActivity, "main", "keyboard", true)
                onDispose { if (typing) LiveDiscover.setExternalResultPending(this@MainActivity, "main", "keyboard", false) }
            }
            val clearedBadges = BadgeClears.cleared.collectAsStateWithLifecycle().value
            val securityRevision = AppSecurity.revision.collectAsStateWithLifecycle().value
            val badgeRevision = IslandListenerService.badgeRevision.collectAsStateWithLifecycle().value
            // Recent-app dots (Beta): refreshed every minute while Home is showing.
            val recentPackages by androidx.compose.runtime.produceState(emptySet<String>(), state.dockRecentDots) {
                if (!state.dockRecentDots) { value = emptySet(); return@produceState }
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    while (true) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RecentUse.packages(this@MainActivity) }
                        kotlinx.coroutines.delay(60_000)
                    }
                }
            }
            val iconsAreDark by androidx.compose.runtime.produceState<Boolean?>(null, state.apps) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    iconsMostlyDark(state.apps.filter { LiveIcons.kind(this@MainActivity, it.packageName) == null && it.shortcutId == null }.map { it.icon })
                }
            }
            val postedBadges = androidx.compose.runtime.remember(badgeRevision, securityRevision, clearedBadges) {
                IslandListenerService.badgeCounts(clearedBadges)
            }
            // Clear Badges When Opened: the counts already seen are hidden, and a seen count follows its app down when
            // notifications go away elsewhere. Nothing of this runs while the switch is off or the gate is shut.
            val clearsBadgesWhenOpened = state.badgesWhenOpened
            val seenBadges = if (clearsBadgesWhenOpened) state.badgesSeen else emptyMap()
            val badgeCounts = androidx.compose.runtime.remember(postedBadges, seenBadges) { BadgesWhenOpened.visible(postedBadges, seenBadges) }
            val profileBadgeCounts = androidx.compose.runtime.remember(badgeRevision, securityRevision, clearedBadges, badgeCounts) {
                IslandListenerService.profileBadgeCounts(clearedBadges).filterKeys { it.packageName in badgeCounts }
            }
            if (clearsBadgesWhenOpened) androidx.compose.runtime.LaunchedEffect(postedBadges) { model.trimBadgesSeen(postedBadges) }
            androidx.compose.runtime.SideEffect { latestNotifications = notificationItems }
            val wallpaperTone = rememberWallpaperTone(state.systemWallpaper)
            // Re-read on every resume so turning Remove animations on/off applies without restarting.
            val reduceMotionState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(reduceMotionEnabled(this@MainActivity)) }
            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { reduceMotionState.value = reduceMotionEnabled(this@MainActivity); onPauseOrDispose { } }
            val reduceMotion = reduceMotionState.value
            val iconTint = if (state.iconTintFromWallpaper) wallpaperTone.primary?.let(::vividTint)?.toLong()?.and(0xFFFFFFFFL) ?: state.iconTint else state.iconTint
            val materialBackdrop = rememberMaterialBackdrop()
            androidx.compose.runtime.CompositionLocalProvider(
                LocalSystemStatusBarVisible provides state.showSystemStatusBar,
                LocalWallpaperTone provides wallpaperTone,
                LocalGlassLook provides GlassLook(state.widgetGlass, state.glassOutline),
                LocalBackgroundMaterial provides state.backgroundMaterial,
                LocalMaterialBackdrop provides materialBackdrop,
                LocalSolidGlass provides solidGlass,
                LocalFolderLook provides FolderLook(state.folderColumns, state.folderBackground, state.folderBackdropOpacity),
                LocalLabelSize provides state.labelSize,
                LocalReduceMotion provides reduceMotion,
                LocalHinge provides rememberHinge(this@MainActivity),
                // Tablets and desktop windows draw Folio proportionally larger instead of a phone-sized layout lost in a
                // big window; phones and foldables stay at exactly the system density (see uiScale).
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.platform.LocalDensity.current.let { d ->
                    val config = androidx.compose.ui.platform.LocalConfiguration.current
                    val scale = uiScale(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat(), config.classScale)
                    if (scale == 1f) d else androidx.compose.ui.unit.Density(d.density * scale, d.fontScale)
                },
                LocalTintOptions provides androidx.compose.ui.platform.LocalConfiguration.current.let { config ->
                    val screen = screenFor(config.fitsRegularHomeLayout())
                    TintOptions(FeatureScopes.on(state.featureScopes, "tintNotifications", state.tintNotifications, screen),
                        FeatureScopes.on(state.featureScopes, "tintMedia", state.tintMedia, screen),
                        FeatureScopes.on(state.featureScopes, "notificationAppRow", state.notificationAppRow, screen))
                },
                androidx.compose.ui.platform.LocalHapticFeedback provides (if (state.haptics) androidx.compose.ui.platform.LocalHapticFeedback.current else NoHaptics),
                LocalIconLook provides IconLook(state.iconStyle, androidx.compose.ui.graphics.Color(iconTint), state.iconShape, state.iconPack, state.badgeStyle, state.badgeColor, state.liveIcons, state.liveIconLook, state.badgeLook, state.badgeSize),
                LocalFocusLock provides FocusPages.lockingFocus(savedState)?.let { FocusLock(it, savedState.layout.pageCount) },
                LocalIconsAreDark provides iconsAreDark,
                LocalRecentPackages provides recentPackages,
                LocalBadgeCounts provides badgeCounts, LocalProfileBadgeCounts provides profileBadgeCounts,
                LocalInstallProgress provides installProgress, LocalNewApps provides newApps, LocalFolderColors provides state.folderColors) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().homeDragInput(dragHost.drag) {
                    dragHost.input?.let { it.copy(enabled = it.enabled && topPanel.value == null &&
                        !(lockCoverVisible.value && state.lockCover)) }
                }) {
                FoldTransitionHost(state.foldEffect && !reduceMotion, state.foldIntensity, state.stayAwakeOnFold, state.foldSnapshot, state.haptics) {
                // Home's own background layer handles blur; its foreground popups remain sharp.
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                LauncherScreen(state, model, widgets, homeRequests.intValue, dragHost,
                    onLaunch = { launchApp(it) }, onMakeDefault = ::makeDefault, onAppInfo = ::appInfo, onUninstall = ::uninstallApp,
                    isDefaultHome = defaultHome.value, deviceStatus = deviceStatus, onStatusMode = ::setStatusMode, onWallpaperPreview = ::previewWallpaper,
                    onDiscover = ::openDiscover, searchRequests = searchRequests.intValue, settingsRequests = settingsRequests.intValue,
                    onLaunchFrom = ::launchApp, onGoogleSearch = ::openGoogleSearch,
                    appearance = appearance.state,
                    onAppearanceMode = { cancelAppearanceLocation(); appearance.setMode(it, systemDark()) },
                    onAppearanceAccent = { appearance.setAccent(it, systemDark()) },
                    onAppearanceManual = { place, lat, lon -> cancelAppearanceLocation(); appearance.setManual(place, lat, lon, systemDark()) },
                    onAppearanceDeviceLocation = ::useAppearanceLocation,
                    onAppearanceClear = { cancelAppearanceLocation(); appearance.clearLocation(systemDark()) },
                    showFirstRun = showFirstRun.value,
                    onFinishFirstRun = ::finishFirstRun,
                    onShadeSetup = ::showShadeSetup, onShowWelcome = { showFirstRun.value = true },
                    popupBackdropOpen = activityOverlayOpen, spotlightProgress = { overlayProgress })
                }
                LockCover(lockCoverVisible.value && state.lockCover) { lockCoverVisible.value = false }
                SetupReminderCard(defaultHome.value, blocked = overlayOpen || showFirstRun.value || !defaultHome.value, onMakeDefault = ::makeDefault,
                    onShadeSetup = ::showShadeSetup) { SettingsLink.page = CustomizationPage.PERMISSIONS; settingsRequests.intValue++ }
                PopupBackdropContent {
                TopPanels(topPanel.value, { overlayProgress }, deviceStatus, onClose = { topPanel.value = null },
                    onSystemPanel = { openAndroidShade(it) }, showClock = state.notificationClock, grouped = state.groupNotifications,
                    ccControls = state.ccControls, onCcControls = model::setCcControls,
                    ccSize = state.ccSize, ccCentered = state.ccCentered, ncSplit = state.ncSplit,
                    focusModes = state.focusModes, activeFocus = state.activeFocus, onFocus = model::setFocus)
                }
                PopupBackdropContent {
                SpotlightOverlay(spotlightVisible.value, { overlayProgress }, state, onClose = { spotlightVisible.value = false },
                    onLaunch = { launchApp(it) }, fromBottom = spotlightFromBottom.value, drag = dragHost.drag,
                    onAppMenu = { dragHost.onAppMenu(it) },
                    dismissImmediately = LauncherContextMenusOpen.intValue > 0 || dragHost.drag.source?.scope == SPOTLIGHT_DRAG_SCOPE,
                    backgroundBlur = backdropBlur.takeIf { dragHost.spotlightMenuOpen && backdropBlurPx >= 2f })
                }
                if (dragHost.spotlightMenuOpen) dragHost.spotlightMenuContent?.invoke()
                }
                // StandBy must remain sharp and visible while the Home fold shader/snapshot is active.
                StandByOverlay(this@MainActivity, state.standBy,
                    blocked = overlayOpen || showFirstRun.value, status = deviceStatus)
                // Last, so the corners sit above everything in Home's window.
                if (state.roundedCorners) RoundedScreenCorners(state.cornerRadius.dp)
            } } }
        }
        FoldRenderExperiment.attach(this)
        // Reassert the token after recreation (and after process restoration, where the
        // in-memory owner set is empty) before any external UI can uncover Discover.
        if (returningFromShadeSettings || restoreShadeDialog) ownShadeSetupExternally()
        if (restoreShadeDialog) window.decorView.post { if (!isFinishing && !isDestroyed) showShadeSetup() }
    }

    private fun requestPhonePermission() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) return
        // Permission grants belong to this installation, so this prompt history is not part of layout backups.
        val permissions = getSharedPreferences("runtime_permissions", MODE_PRIVATE)
        if (permissions.getBoolean("phone_state_requested", false)) return
        permissions.edit().putBoolean("phone_state_requested", true).apply()
        runCatching { phonePermission.launch(android.Manifest.permission.READ_PHONE_STATE) }
            .onFailure { permissions.edit().remove("phone_state_requested").apply() }
    }

    override fun onStart() {
        super.onStart(); widgets.startListening()
        if (!timeReceiverRegistered) {
            ContextCompat.registerReceiver(this, timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            timeReceiverRegistered = true
        }
        appearance.refresh(systemDark())
    }
    override fun onStop() {
        AppSecurity.closeFolder()
        closeOverlays() // never come back to a blurred Home
        if (timeReceiverRegistered) { unregisterReceiver(timeReceiver); timeReceiverRegistered = false }
        widgets.host.stopListening(); super.onStop()
    }
    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        runCatching { unregisterReceiver(homeButtonReceiver) }
        recreatingShadeSetup = isChangingConfigurations
        shadeSetupDialog?.dismiss()
        if (!isChangingConfigurations) releaseShadeSetupOwnership()
        cancelAppearanceLocation()
        super.onDestroy()
    }
    override fun onPause() {
        super.onPause()
        FolioForeground.visible.value = false
        Diagnostics.event(getString(R.string.home_hidden))
        Diagnostics.checkpoint(this, visible = false)
    }
    override fun onResume() {
        super.onResume()
        requestPhonePermission()
        FolioForeground.visible.value = true
        Diagnostics.event("Home shown (${Diagnostics.screenSummary(this).substringBefore(',')})")
        Diagnostics.checkpoint(this, visible = true)
        FolioActions.home = java.lang.ref.WeakReference(this)
        model.syncFocus()
        // Unlock arrived just before Home resumed: show the cover now.
        if (unlockedAt > 0 && android.os.SystemClock.uptimeMillis() - unlockedAt < 2_000 && model.state.value.lockCover) lockCoverVisible.value = true
        unlockedAt = 0L
        if (SpotlightRequest.consume()) openSpotlight()
        FolioActions.pendingPanel?.let { FolioActions.pendingPanel = null; showPanel(it) }
        if (returningFromShadeSettings) {
            returningFromShadeSettings = false
            releaseShadeSetupOwnership()
        }
        val discover = DiscoverSession.host.get()
        if (discover != null) window.decorView.doOnPreDraw {
            it.postOnAnimation { if (DiscoverSession.host.get() === discover) DiscoverSession.dismiss() }
        }
        model.refresh(); appearance.refresh(systemDark()); updateDefaultHome()
        window.decorView.post {
            if (!isFinishing && !isDestroyed && !LiveDiscover.viewport.isEmpty)
                LiveDiscover.prepare(this, LiveDiscover.viewport, LiveDiscover.pageWidth)
        }
    }

    /** Folio's own iOS-style panels on Home; the Android shade when that setting is off. */
    internal val topPanel = androidx.compose.runtime.mutableStateOf<ShadePanel?>(null)
    /** The notifications on screen now, for Clear Badge. */
    internal var latestNotifications: List<NotificationItem> = emptyList()
    internal val spotlightVisible = androidx.compose.runtime.mutableStateOf(false)
    private val spotlightFromBottom = androidx.compose.runtime.mutableStateOf(false)
    /** Lock Cover: shown when the phone is unlocked straight to Home. */
    private val lockCoverVisible = androidx.compose.runtime.mutableStateOf(false)
    private var unlockedAt = 0L
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (!model.state.value.lockCover) return
            unlockedAt = android.os.SystemClock.uptimeMillis()
            if (FolioForeground.visible.value) lockCoverVisible.value = true
        }
    }
    /** Opens Spotlight, Notification Center or Control Center (Folio's own panels when enabled). */
    internal fun showPanel(panel: ShadePanel) { if (panel == ShadePanel.SEARCH) openSpotlight() else openSystemShade(panel) }

    internal fun openSpotlight(fromBottom: Boolean = false) {
        topPanel.value = null
        if (!spotlightVisible.value) spotlightFromBottom.value = fromBottom
        spotlightVisible.value = true
    }
    private fun closeOverlays() { topPanel.value = null; spotlightVisible.value = false }

    /**
     * The Home button is the way out of anything. Setup is shown again next time (or from Settings › Help › Show Welcome
     * Again), so an overlay can never leave Home stuck behind it with no way back.
     */
    private fun closeEverything() {
        homeDismissal.dismissAll()
        shadeSetupDialog?.dismiss()
        closeOverlays()
        showFirstRun.value = false; lockCoverVisible.value = false
    }

    private fun returnHome() {
        AppSecurity.lock()
        closeEverything()
        homeRequests.intValue++
    }

    internal fun openSystemShade(panel: ShadePanel) {
        if (model.state.value.folioPanels) topPanel.value = panel else openAndroidShade(panel)
    }

    internal fun openAndroidShade(panel: ShadePanel) {
        when (SystemShadeAccessibilityService.open(this, panel)) {
            ShadeOpenResult.OPENED -> Unit
            ShadeOpenResult.SERVICE_DISABLED -> showShadeSetup()
            ShadeOpenResult.SERVICE_STARTING -> IslandEvents.notice(this, getString(R.string.folio_gestures_are_starting_swipe_down_a))
            ShadeOpenResult.ACTION_REJECTED -> IslandEvents.notice(this, getString(R.string.android_couldn_t_open_the_system_panel))
        }
    }

    private fun showShadeSetup() {
        if (shadeSetupDialog?.isShowing == true) return
        ownShadeSetupExternally()
        shadeSetupDialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.turn_on_folio_gestures))
            .setMessage("Android asks you to turn this on yourself:\n\n" +
                "1. Tap Open Settings, find “Folio gestures & overlays” (often under Installed apps) and turn it on.\n" +
                "2. If it's greyed out, or you see “App was denied access” or “Restricted setting”, tap App Info below, open the ⋮ menu " +
                "(top right), choose “Allow restricted settings” and confirm, then come back and turn it on. Android does this for apps " +
                "installed from a browser or file; it's a one-time step.\n\n" +
                getString(R.string.folio_uses_it_to_open_notification_cente) +
                getString(R.string.it_can_t_read_what_s_on_your_screen))
            .setNegativeButton(getString(R.string.not_now), null)
            .setNeutralButton(getString(R.string.app_info)) { _, _ ->
                returningFromShadeSettings = true
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                    .onFailure { returningFromShadeSettings = false; releaseShadeSetupOwnership() }
            }
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                returningFromShadeSettings = true
                val opened = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.isSuccess
                if (!opened) {
                    returningFromShadeSettings = false
                    releaseShadeSetupOwnership()
                    IslandEvents.notice(this, getString(R.string.accessibility_settings_are_unavailable))
                }
            }
            .also { dialog -> dialog.setOnDismissListener {
                shadeSetupDialog = null
                if (!returningFromShadeSettings && !recreatingShadeSetup) releaseShadeSetupOwnership()
            } }
            .show()
    }

    private fun finishFirstRun() {
        setupExperience.finish()
        showFirstRun.value = false
    }

    private fun ownShadeSetupExternally() {
        if (shadeSetupOwnsExternalUi) return
        shadeSetupOwnsExternalUi = true
        LiveDiscover.setExternalResultPending(this, "main", "shade-service-setup", true)
    }

    private fun releaseShadeSetupOwnership() {
        if (!shadeSetupOwnsExternalUi) return
        shadeSetupOwnsExternalUi = false
        LiveDiscover.setExternalResultPending(this, "main", "shade-service-setup", false)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setStatusMode(!model.state.value.showSystemStatusBar)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        widgets.save(outState)
        outState.putBoolean(SHADE_DIALOG_VISIBLE, shadeSetupDialog?.isShowing == true && !returningFromShadeSettings)
        outState.putBoolean(SHADE_SETTINGS_PENDING, returningFromShadeSettings)
        super.onSaveInstanceState(outState)
    }
    /** Android's "Home app settings" gear, or Folio's own app icon (the FolioSettingsApp alias). Until Folio is the
     * Home app, its icon opens Home instead, as a preview you can leave with Back or the Home gesture. */
    private fun opensSettings(intent: Intent) = intent.action == Intent.ACTION_APPLICATION_PREFERENCES ||
        (fromAppIcon(intent) && defaultHome.value)
    private fun fromAppIcon(intent: Intent) = intent.component?.className?.startsWith("$FOLIO_CLASSES.${AppIconChoice.ALIAS_PREFIX}") == true

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)
        FoldRenderExperiment.onNewIntent(this, intent)
        updateDefaultHome()
        if (intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        // One chain: tapping Folio's icon opens Settings *or* goes Home, never both.
        if (opensSettings(intent)) settingsRequests.intValue++
        else if (intent.hasCategory(Intent.CATEGORY_HOME) || fromAppIcon(intent) || intent.getStringExtra("duo_destination") == "home") {
            returnHome()
        }
        intent.removeExtra("duo_destination")
    }

    @Deprecated("Widget configuration uses the platform host request-code API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onActivityResult(requestCode, resultCode)) {
            // AppWidgetHost returns cross-profile configuration here; forward other results to AndroidX.
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /** The badge showing on [packageName] now counts as seen, so it goes away until a new notification arrives. */
    private fun noteBadgeSeen(packageName: String) {
        if (!model.state.value.badgesWhenOpened) return
        model.noteBadgeSeen(packageName, IslandListenerService.badgeCounts(BadgeClears.cleared.value)[packageName] ?: 0)
    }

    private fun launchApp(app: AppEntry, bounds: android.graphics.Rect? = null) =
        AppSecurity.run(this, app.packageName, app.user) { launchAuthenticatedApp(app, bounds) }

    private fun launchAuthenticatedApp(app: AppEntry, bounds: android.graphics.Rect?) {
        RecentApps.record(this, app.id)
        NewApps.opened(this, app.packageName)
        noteBadgeSeen(app.packageName)
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException(getString(R.string.profile_is_unavailable))
            val launcherApps = getSystemService(LauncherApps::class.java)
            val shortcut = app.shortcutId
            if (shortcut != null) launcherApps.startShortcut(app.packageName, shortcut, screenBounds(bounds), launchOptions(bounds), user)
            else launcherApps.startMainActivity(app.component, user, screenBounds(bounds), launchOptions(bounds))
        } catch (_: Exception) {
            val hidden = AppSecurity.isHidden(app)
            IslandEvents.notice(this, getString(R.string.app_is_unavailable, if (hidden) getString(R.string.security_hidden) else app.label),
                app.icon.takeUnless { hidden })
            model.refresh()
        }
    }

    private fun screenBounds(bounds: android.graphics.Rect?): android.graphics.Rect? = bounds?.takeUnless { it.isEmpty }?.let {
        val location = IntArray(2); window.decorView.getLocationOnScreen(location)
        android.graphics.Rect(it).apply { offset(location[0], location[1]) }
    }
    private fun launchOptions(bounds: android.graphics.Rect?): Bundle? = bounds?.takeUnless { it.isEmpty }?.let {
        android.app.ActivityOptions.makeScaleUpAnimation(window.decorView, it.left, it.top, it.width(), it.height()).toBundle()
    }
    private fun openGoogleSearch(bounds: android.graphics.Rect?): Boolean = try {
        AppSecurity.startActivity(this, googleSearchIntent().apply { sourceBounds = screenBounds(bounds) }, launchOptions(bounds))
        true
    } catch (_: android.content.ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }

    private fun openDiscover() {
        if (AppSecurity.isProtected(DiscoverClient.GOOGLE_PACKAGE)) return
        if (DiscoverEmbedding.supported(this)) {
            if (openingDiscover) return
            openingDiscover = true
            // A very quick reopen can arrive before the previous return's deferred cleanup.
            // Finish that session before taking a new image, so it cannot invalidate this copy.
            DiscoverSession.dismiss()
            DiscoverMotion.capture(this) {
                openingDiscover = false
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@capture
                DiscoverSession.apps = model.state.value.apps
                startActivity(Intent(this, DiscoverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
        else showDiscoverFallback()
    }

    private fun showDiscoverFallback() {
        val google = packageManager.getLaunchIntentForPackage(DiscoverClient.GOOGLE_PACKAGE)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.discover_isn_t_available_here))
            .setMessage(getString(R.string.folio_can_t_place_the_discover_feed_besi))
            .setNegativeButton(getString(R.string.stay_on_home), null)
            .apply {
                if (google != null) setPositiveButton(getString(R.string.open_google)) { _, _ ->
                    runCatching { AppSecurity.startActivity(this@MainActivity, google) }
                }
            }
            .show()
    }

    private fun makeDefault() {
        // Samsung may immediately cancel a role request; its Home settings is reliable.
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            val role = getSystemService(RoleManager::class.java)
            if (role.isRoleAvailable(RoleManager.ROLE_HOME)) startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME))
            else startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun updateDefaultHome() {
        defaultHome.value = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun systemDark() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun useAppearanceLocation() {
        cancelAppearanceLocation()
        appearance.locationStatus(getString(R.string.waiting_for_approximate_device_location))
        LiveDiscover.setExternalResultPending(this, "main", "appearance-location", true)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            requestAppearanceLocation(keepPending = true)
        else {
            appearancePermissionGeneration = appearanceLocationGeneration
            runCatching { locationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }
                .onFailure { finishAppearanceLocation(getString(R.string.location_permission_couldn_t_be_requeste)) }
        }
    }

    private fun requestAppearanceLocation(keepPending: Boolean = false) {
        if (!keepPending) LiveDiscover.setExternalResultPending(this, "main", "appearance-location", true)
        val generation = ++appearanceLocationGeneration
        val manager = getSystemService(LocationManager::class.java)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            finishAppearanceLocation(getString(R.string.location_permission_isn_t_available_usin)); return
        }
        val cached = runCatching { manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time <= 15 * 60_000 } }.getOrNull()
        if (cached != null) {
            if (generation == appearanceLocationGeneration) appearance.setDeviceLocation(cached.latitude, cached.longitude, systemDark())
            finishAppearanceLocation(null); return
        }
        val provider = runCatching { when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        } }.getOrNull() ?: run { finishAppearanceLocation(getString(R.string.no_approximate_location_provider_is_avai)); return }
        val cancellation = CancellationSignal()
        appearanceLocationCancellation = cancellation
        window.decorView.postDelayed({
            if (generation == appearanceLocationGeneration && appearanceLocationCancellation === cancellation) {
                cancellation.cancel(); finishAppearanceLocation(getString(R.string.location_timed_out_using_the_system_them))
            }
        }, 10_000)
        runCatching { manager.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(this)) { location ->
            if (generation != appearanceLocationGeneration || isDestroyed) return@getCurrentLocation
            if (location != null) appearance.setDeviceLocation(location.latitude, location.longitude, systemDark())
            finishAppearanceLocation(if (location == null) getString(R.string.location_is_unavailable_using_the_system) else null)
        } }.onFailure { finishAppearanceLocation(getString(R.string.location_is_unavailable_using_the_system)) }
    }

    private fun cancelAppearanceLocation() {
        appearanceLocationGeneration++
        appearancePermissionGeneration = -1
        appearanceLocationCancellation?.cancel(); appearanceLocationCancellation = null
        if (::appearance.isInitialized) appearance.locationStatus(null)
        LiveDiscover.setExternalResultPending(this, "main", "appearance-location", false)
    }

    private fun finishAppearanceLocation(message: String?) {
        appearanceLocationGeneration++
        appearancePermissionGeneration = -1
        appearanceLocationCancellation = null
        appearance.locationStatus(message)
        LiveDiscover.setExternalResultPending(this, "main", "appearance-location", false)
    }

    /** The window's size in dp, to tell a fold or a resize from a change that leaves the layout alone. */
    private var lastWindowSize: Pair<Int, Int>? = null
        get() = field ?: (resources.configuration.screenWidthDp to resources.configuration.screenHeightDp).also { field = it }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // A panel is drawn for the screen it opened on. Folding, unfolding or being resized leaves it laid out for a
        // screen that is no longer there, and on some phones it can't be dismissed at all (reported on r/GalaxyFold
        // from a Fold8 Ultra), so a real size change closes it and Home comes back clean.
        val size = newConfig.screenWidthDp to newConfig.screenHeightDp
        val was = lastWindowSize
        lastWindowSize = size
        // Only the panel: Spotlight lays itself out for the new screen, and closing it would throw away a search
        // somebody is halfway through typing.
        if (windowChangedShape(was, size)) topPanel.value = null
        // Folding, Display size, Smallest width or split screen can bring Android's status bar back over the Side Bar
        // status; hide it again once the new layout is in place.
        window.decorView.post { setStatusMode(!model.state.value.showSystemStatusBar) }
    }

    private fun setStatusMode(hidden: Boolean) {
        LiveDiscover.host.get()?.statusMode(hidden)
        window.setSystemStatusBarVisible(!hidden)
    }

    private fun previewWallpaper() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, DuneWallpaperService::class.java)))
        } catch (_: android.content.ActivityNotFoundException) {
            IslandEvents.notice(this, getString(R.string.the_system_wallpaper_preview_is_unavaila))
        }
    }

    private fun appInfo(app: AppEntry) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException(getString(R.string.profile_is_unavailable))
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, user, null, null)
        } catch (_: Exception) {
            IslandEvents.notice(this, getString(R.string.app_is_unavailable, app.label), app.icon)
            model.refresh()
        }
    }

    private fun uninstallApp(app: AppEntry) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException(getString(R.string.profile_is_unavailable))
            startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", app.packageName, null))
                .putExtra(Intent.EXTRA_USER, user))
        } catch (_: Exception) {
            IslandEvents.notice(this, getString(R.string.app_uninstall_unavailable, app.label), app.icon)
            model.refresh()
        }
    }

    private companion object {
        const val SHADE_DIALOG_VISIBLE = "duo.shade.dialog_visible"
        const val SHADE_SETTINGS_PENDING = "duo.shade.settings_pending"
    }
}
