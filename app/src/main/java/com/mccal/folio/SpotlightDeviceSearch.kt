package com.mccal.folio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun SpotlightDeviceSearch(query: String, active: Boolean, hidden: Set<String>, manageAccess: Boolean = false, onClose: () -> Unit) {
    if (!manageAccess && query.isBlank()) return
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var deniedPermissions by remember { mutableStateOf(emptySet<String>()) }
    val indexRevision by ContentSearchIndex.revision.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                revision++
                ContentSearchJob.schedule(context, refresh = true)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val contactsGranted = remember(revision) { context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
    val calendarGranted = remember(revision) { UpNext.hasCalendar(context) }
    val photosGranted = remember(revision) { DeviceSearch.hasPhotos(context) }
    val folders = remember(revision) { DeviceSearch.folders(context) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        deniedPermissions = (deniedPermissions - it.keys) + it.filterValues { granted -> !granted }.keys
        revision++
        ContentSearchJob.schedule(context, refresh = true)
    }
    val folderPicker = rememberLauncherForActivityResult(remember {
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent =
                super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }) { uri ->
        if (uri != null) {
            runCatching { DeviceSearch.addFolder(context, uri) }.onFailure {
                Toast.makeText(context, R.string.device_search_folder_error, Toast.LENGTH_SHORT).show()
            }
            revision++
        }
    }
    fun shows(section: SpotlightSection) = section.name !in hidden
    val calendar = deviceSearchResults(query, active, shows(SpotlightSection.CALENDAR) && calendarGranted, revision) {
        DeviceSearch.calendar(context, query)
    }
    val photos = deviceSearchResults(query, active, shows(SpotlightSection.PHOTOS) && photosGranted, revision + indexRevision) {
        DeviceSearch.photos(context, query)
    }
    val files = deviceSearchResults(query, active, shows(SpotlightSection.FILES) && folders.isNotEmpty(), revision + indexRevision) {
        DeviceSearch.files(context, query, folders)
    }
    if (!manageAccess && calendar?.hits.isNullOrEmpty() && photos?.hits.isNullOrEmpty() && files?.hits.isNullOrEmpty()) return
    fun open(intent: Intent, close: Boolean = true) {
        runCatching { AppSecurity.startActivity(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { if (close) onClose() }
            .onFailure { Toast.makeText(context, R.string.device_search_open_error, Toast.LENGTH_SHORT).show() }
    }
    fun appSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")), close = false)
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.COMFY.dp)) {
        if (manageAccess) ContentSearchSettings()
        if (manageAccess && shows(SpotlightSection.CONTACTS)) SpotlightSectionCard(stringResource(R.string.contacts)) {
            SpotlightResultRow(Icons.Rounded.PersonSearch, stringResource(R.string.search_your_contacts),
                stringResource(if (contactsGranted) R.string.device_search_enabled else R.string.allow_contacts_access)) {
                if (contactsGranted || Manifest.permission.READ_CONTACTS in deniedPermissions) appSettings()
                else permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            }
        }
        if (shows(SpotlightSection.CALENDAR)) {
            if (manageAccess) SpotlightSectionCard(stringResource(R.string.device_search_calendar)) {
                SpotlightResultRow(Icons.Rounded.Event, stringResource(R.string.device_search_calendar_hint),
                    stringResource(if (calendarGranted) R.string.device_search_enabled else R.string.allow_access)) {
                    if (calendarGranted || Manifest.permission.READ_CALENDAR in deniedPermissions) appSettings()
                    else permissions.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                }
            } else DeviceResults(SpotlightSection.CALENDAR.title, Icons.Rounded.Event, calendar, { revision++ }) { open(it) }
        }
        if (shows(SpotlightSection.PHOTOS)) {
            if (manageAccess) SpotlightSectionCard(stringResource(R.string.device_search_photos)) {
                SpotlightResultRow(Icons.Rounded.Photo, stringResource(R.string.device_search_photos_hint),
                    stringResource(if (photosGranted) R.string.device_search_photo_access else R.string.allow_access)) {
                    if (!photosGranted && DeviceSearch.photoPermissions().all { it in deniedPermissions }) appSettings()
                    else permissions.launch(DeviceSearch.photoPermissions())
                }
            } else DeviceResults(SpotlightSection.PHOTOS.title, Icons.Rounded.Photo, photos, { revision++ }, thumbnails = true, revision = revision) { open(it) }
        }
        if (shows(SpotlightSection.FILES)) {
            if (manageAccess) SpotlightSectionCard(stringResource(R.string.device_search_files)) {
                folders.sorted().forEach { folder ->
                    SpotlightResultRow(Icons.Rounded.Folder, DeviceSearch.folderName(folder), stringResource(R.string.device_search_files_hint), trailing = {
                        IconButton(onClick = { DeviceSearch.removeFolder(context, folder); revision++ }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.remove), tint = FolioGlass.ink)
                        }
                    }) { folderPicker.launch(Uri.parse(folder)) }
                }
                SpotlightResultRow(Icons.Rounded.CreateNewFolder, stringResource(R.string.device_search_add_folder),
                    stringResource(R.string.device_search_files_hint)) { folderPicker.launch(null) }
            } else DeviceResults(SpotlightSection.FILES.title, Icons.Rounded.Description, files, { revision++ }) { open(it) }
        }
    }
}

@Composable
private fun deviceSearchResults(query: String, active: Boolean, enabled: Boolean, revision: Int,
    search: suspend () -> DeviceSearchResults): DeviceSearchResults? = key(query, active, enabled, revision) {
    produceState<DeviceSearchResults?>(null) {
        if (!active || !enabled || query.isBlank()) { value = DeviceSearchResults(); return@produceState }
        delay(180)
        value = withContext(Dispatchers.IO) { search() }
    }.value
}

@Composable
private fun DeviceResults(title: Int, icon: ImageVector, result: DeviceSearchResults?, retry: () -> Unit,
    thumbnails: Boolean = false, revision: Int = 0, open: (Intent) -> Unit) {
    if (result == null || result.hits.isEmpty()) return
    SpotlightSectionCard(stringResource(title)) {
        result.hits.forEach { hit ->
            SpotlightResultRow(icon, hit.title, hit.detail, leading = if (thumbnails) {
                { PhotoPreview(hit.intent.data, revision) }
            } else null, subtitleLines = if (hit.contentMatch) 3 else 1) { open(hit.intent) }
        }
        if (result.failed) SpotlightResultRow(Icons.Rounded.Refresh, stringResource(R.string.device_search_read_error), stringResource(R.string.try_again), onClick = retry)
        if (result.incomplete) Text(stringResource(R.string.device_search_partial), color = FolioGlass.ink.copy(alpha = .6f), fontSize = FolioType.FOOTNOTE.sp,
            modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun PhotoPreview(uri: Uri?, revision: Int) {
    val context = LocalContext.current
    val bitmap = key(uri, revision) {
        produceState<Bitmap?>(null) {
            if (uri != null) value = withContext(Dispatchers.IO) {
                try { context.contentResolver.loadThumbnail(uri, Size(192, 192), null) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
        }.value
    }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(9.dp)).background(FolioGlass.ink.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Photo, contentDescription = null, tint = FolioGlass.ink, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ContentSearchSettings() {
    val context = LocalContext.current
    val revision by ContentSearchIndex.revision.collectAsState()
    val status by ContentSearchIndex.status.collectAsState()
    val queued by ContentSearchIndex.queued.collectAsState()
    val enabled = remember(revision) { ContentSearchIndex.enabled(context) }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { ContentSearchIndex.publish(context) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { ContentSearchIndex.status.value = status.copy(sourceError = true) }
    }
    SettingsSwitch(stringResource(R.string.content_search_enabled), enabled,
        { ContentSearchIndex.setEnabled(context, it) }, "content-search-enabled")
    Text(stringResource(R.string.content_search_description), color = Color.White.copy(alpha = .6f), fontSize = FolioType.FOOTNOTE.sp)
    if (enabled) {
        if (queued && !status.running) Text(stringResource(R.string.content_search_queued), color = Color.White.copy(alpha = .7f), fontSize = FolioType.FOOTNOTE.sp)
        Text(stringResource(if (status.running) R.string.content_search_progress else R.string.content_search_status,
            status.ready, status.pending, status.failed, status.partial), color = Color.White.copy(alpha = .7f), fontSize = FolioType.FOOTNOTE.sp)
        if (status.sourceError) Text(stringResource(R.string.content_search_source_error), color = Color.White.copy(alpha = .7f), fontSize = FolioType.FOOTNOTE.sp)
        SpotlightResultRow(Icons.Rounded.Refresh, stringResource(R.string.content_search_refresh), stringResource(R.string.content_search_refresh_hint)) {
            ContentSearchJob.schedule(context, refresh = true, retryFailed = true)
        }
        Text(stringResource(R.string.content_search_limits), color = Color.White.copy(alpha = .6f), fontSize = FolioType.FOOTNOTE.sp)
    }
}
