package com.mccal.folio

import android.appwidget.AppWidgetManager
import android.content.Context
import android.net.Uri
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupController(
    private val activity: ComponentActivity,
    private val model: LauncherModel,
    private val widgets: WidgetController,
    private val onExternalResultChanged: (Boolean) -> Unit,
) {
    var preview by mutableStateOf<LayoutImportPreview?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var successMessage by mutableStateOf<String?>(null)
        private set
    var pickerPending by mutableStateOf(false)
        private set

    private val store = activity.getSharedPreferences("layout_backup_pending", Context.MODE_PRIVATE)
    private val userManager = activity.getSystemService(UserManager::class.java)
    private val scope = layoutBackupScope(activity)
    @Volatile private var operation: String? = null
    @Volatile private var generation = 0
    private var importRaw: String? = null
    private var applying = false
    private data class Authorization(val transaction: Int, val session: Int, val foreground: Int)
    @Volatile private var foregroundGeneration = 0
    @Volatile private var exportAuthorization: Authorization? = null
    @Volatile private var importAuthorization: Authorization? = null
    private var authenticatingBackup = false

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                foregroundGeneration++
                exportAuthorization = null; importAuthorization = null
                if (!authenticatingBackup && operation == OP_PREVIEW && importRequiresAuthentication()) clearTransaction()
            }
        })
        activity.lifecycleScope.launch {
            AppSecurity.revision.collect {
                if (exportAuthorization?.let { it.session != AppSecurity.authenticationEpoch } == true) exportAuthorization = null
                if (importAuthorization?.let { it.session != AppSecurity.authenticationEpoch } == true) {
                    importAuthorization = null
                    if (!authenticatingBackup && operation == OP_PREVIEW && importRequiresAuthentication()) clearTransaction()
                }
            }
        }
    }

    private fun authorization(token: Int) = Authorization(token, AppSecurity.authenticationEpoch, foregroundGeneration)
    private fun authorized(value: Authorization?, token: Int) = value == authorization(token)
    private fun importRequiresAuthentication() = model.state.value.appSecurity.isNotEmpty() ||
        importRaw?.let(::backupHasAppSecurity) == true

    private fun authenticateBackup(onSuccess: () -> Unit, onFailure: () -> Unit = {}) {
        authenticatingBackup = true
        AppSecurity.authenticate(activity, activity.getString(R.string.security_auth_title), onSuccess = {
            authenticatingBackup = false
            onSuccess()
        }, onFailure = {
            authenticatingBackup = false
            onFailure()
        })
    }
    private val createDocument = activity.activityResultRegistry.register(
        "duo.backup.create", activity, ActivityResultContracts.CreateDocument("application/json")
    ) createCallback@{ uri ->
        if (operation != OP_EXPORT) return@createCallback
        if (uri == null) clearTransaction() else {
            store.edit().putString(KEY_URI, uri.toString()).apply()
            writeExport(uri, generation)
        }
    }
    private val openDocument = activity.activityResultRegistry.register(
        "duo.backup.open", activity, ActivityResultContracts.OpenDocument()
    ) openCallback@{ uri ->
        if (operation != OP_IMPORT) return@openCallback
        if (uri == null) clearTransaction() else {
            store.edit().putString(KEY_URI, uri.toString()).apply()
            readImport(uri, generation)
        }
    }

    fun restore() {
        val restoredMessage = store.getString(KEY_RESULT, null)
        store.edit().remove(KEY_RESULT).apply()
        val saved = runCatching { Triple(store.getString(KEY_OPERATION, null), store.getString(KEY_PREVIEW, null), store.getString(KEY_URI, null)) }.getOrNull()
        operation = saved?.first
        val raw = saved?.second
        importRaw = raw.takeIf { operation == OP_PREVIEW }
        if (raw != null || operation != null) onExternalResultChanged(true)
        if (operation == OP_PREVIEW && raw == null) clearTransaction()
        else if (raw != null) parsePreview(raw, persist = false)
        else if (saved?.third != null && operation == OP_IMPORT) readImport(Uri.parse(saved.third), generation)
        else if (saved?.third != null && operation == OP_EXPORT) writeExport(Uri.parse(saved.third), generation)
        else if (operation != null) {
            pickerPending = true
            onExternalResultChanged(true)
        } else onExternalResultChanged(false)
        successMessage = restoredMessage
    }

    fun startExport(fileName: String = "folio-backup.json") = prepareExport { raw ->
        begin(OP_EXPORT, raw)
        exportAuthorization = authorization(generation)
        try { createDocument.launch(fileName) }
        catch (error: Exception) { errorMessage = error.message ?: "The document picker is unavailable."; clearTransaction(false) }
    }

    /** Saves a backup straight to Download/Folio, no picker. */
    fun saveToFolioFolder(name: String? = null) = prepareExport { raw ->
        val name = FolioFiles.fileName(name, "folio-backup")
        val token = generation
        val permission = authorization(token)
        activity.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                if (token != generation || (backupHasAppSecurity(raw) && !authorized(permission, token))) return@withContext null
                FolioFiles.save(activity, name, "application/json", raw.toByteArray())?.let { FolioFiles.displayName(activity, it) ?: name }
            }
            if (token != generation || (backupHasAppSecurity(raw) && !authorized(permission, token))) return@launch
            if (saved != null) successMessage = activity.getString(R.string.saved_to_as, FolioFiles.displayPath, saved)
            else errorMessage = activity.getString(R.string.layout_backup_could_not_be_saved)
        }
    }

    fun startImport() {
        val open = {
            begin(OP_IMPORT)
            importAuthorization = authorization(generation)
            try { openDocument.launch(arrayOf("application/json", "text/json", "text/plain")) }
            catch (error: Exception) { errorMessage = error.message ?: "The document picker is unavailable."; clearTransaction(false) }
        }
        if (model.state.value.appSecurity.isNotEmpty()) authenticateBackup(onSuccess = open)
        else {
            open()
            importAuthorization = null
        }
    }

    fun applyImport(): Boolean {
        if (applying) return false
        val raw = importRaw ?: return false
        val token = generation
        if (importRequiresAuthentication() && !authorized(importAuthorization, token)) {
            authenticateBackup(onSuccess = {
                if (token == generation && operation == OP_PREVIEW) {
                    importAuthorization = authorization(token)
                    applyImport()
                }
            }, onFailure = { if (token == generation) clearTransaction() })
            return true
        }
        applying = true
        preview = null
        activity.lifecycleScope.launch {
            val state = model.state.first { !it.loading }
            val result = runCatching {
                val imported = withContext(Dispatchers.Default) { decodeLayoutBackup(raw, state.apps, state.profiles, scope) }
                if (token != generation || operation != OP_PREVIEW ||
                    (importRequiresAuthentication() && !authorized(importAuthorization, token))) throw CancellationException()
                val preferencesChanged = withContext(Dispatchers.IO) {
                    if (token != generation || operation != OP_PREVIEW ||
                        (importRequiresAuthentication() && !authorized(importAuthorization, token))) throw CancellationException()
                    imported.preferenceSettings?.let { restorePreferenceBackup(activity, it) } ?: false
                }
                imported to preferencesChanged
            }
            applying = false
            result.rethrowCancellation()
            if (token != generation || operation != OP_PREVIEW ||
                (importRequiresAuthentication() && !authorized(importAuthorization, token))) return@launch
            result.onSuccess { (imported, preferencesChanged) ->
                val changed = model.applyImportedLayout(imported) || preferencesChanged
                successMessage = activity.getString(if (changed) R.string.layout_restored_widgets_are_ready_to_rec else R.string.this_layout_is_already_active)
                clearTransaction(clearMessages = false)
                if (preferencesChanged) {
                    LauncherBackgroundCache.changed(null)
                    store.edit().putString(KEY_RESULT, successMessage).commit()
                    activity.recreate()
                }
            }.onFailure {
                errorMessage = it.message ?: activity.getString(R.string.this_layout_backup_is_no_longer_valid)
                clearTransaction(clearMessages = false)
            }
        }
        return true
    }

    fun cancelImport() { if (!applying) clearTransaction() }
    fun clearMessage() { errorMessage = null; successMessage = null }

    fun resumePendingPicker(): Boolean = when (operation) {
        OP_EXPORT -> runCatching { createDocument.launch("folio-backup.json") }.isSuccess
        OP_IMPORT -> runCatching { openDocument.launch(arrayOf("application/json", "text/json", "text/plain")) }.isSuccess
        else -> false
    }

    private fun encodeBackup(state: LauncherState): String = encodeLayoutBackup(state, widgetDescriptors(state), scope,
        encodePreferenceBackup(activity))

    private fun prepareExport(onReady: (String) -> Unit) {
        activity.lifecycleScope.launch {
            val state = model.state.first { !it.loading }
            val token = generation
            val prepare = {
                val permission = authorization(token)
                activity.lifecycleScope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { encodeBackup(state) } }
                    result.rethrowCancellation()
                    if (token != generation || (state.appSecurity.isNotEmpty() && !authorized(permission, token))) return@launch
                    result.onSuccess(onReady).onFailure {
                        errorMessage = it.message ?: activity.getString(R.string.layout_backup_could_not_be_prepared)
                    }
                }
            }
            if (state.appSecurity.isNotEmpty()) authenticateBackup(onSuccess = { prepare(); Unit })
            else prepare()
        }
    }

    private fun begin(value: String, payload: String? = null) {
        generation++
        preview = null; errorMessage = null; successMessage = null
        importRaw = null
        exportAuthorization = null
        importAuthorization = null
        operation = value; pickerPending = true
        val editor = store.edit().clear().putString(KEY_OPERATION, value)
        payload?.let { editor.putString(KEY_EXPORT, it) }
        editor.apply()
        onExternalResultChanged(true)
    }

    private fun writeExport(uri: Uri, token: Int) {
        val raw = store.getString(KEY_EXPORT, null)
        if (token != generation || operation != OP_EXPORT) return
        if (raw != null && backupHasAppSecurity(raw) && !authorized(exportAuthorization, token)) {
            authenticateBackup(onSuccess = {
                if (token == generation && operation == OP_EXPORT) {
                    exportAuthorization = authorization(token)
                    writeExport(uri, token)
                }
            }, onFailure = { if (token == generation) clearTransaction() })
            return
        }
        activity.lifecycleScope.launch {
            val result = runCatching {
                val raw = store.getString(KEY_EXPORT, null) ?: error("The export snapshot is unavailable")
                withContext(Dispatchers.IO) {
                    if (token != generation || operation != OP_EXPORT ||
                        (backupHasAppSecurity(raw) && !authorized(exportAuthorization, token))) throw CancellationException()
                    activity.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(raw) }
                        ?: error("The selected document could not be opened")
                }
            }
            result.rethrowCancellation()
            if (token != generation || operation != OP_EXPORT) return@launch
            result.onSuccess { successMessage = activity.getString(R.string.layout_backup_saved) }
                .onFailure { errorMessage = it.message ?: activity.getString(R.string.layout_backup_could_not_be_saved) }
            clearTransaction(clearMessages = false)
        }
    }

    private fun readImport(uri: Uri, token: Int) {
        activity.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { readBounded(uri) } }
            result.rethrowCancellation()
            if (token != generation || operation != OP_IMPORT) return@launch
            result.onSuccess {
                operation = OP_PREVIEW; importRaw = it
                store.edit().putString(KEY_OPERATION, OP_PREVIEW).putString(KEY_PREVIEW, it).remove(KEY_URI).apply()
                parsePreview(it, persist = false)
            }
                .onFailure {
                    errorMessage = it.message ?: activity.getString(R.string.layout_backup_could_not_be_read)
                    clearTransaction(clearMessages = false)
                }
        }
    }

    private fun parsePreview(raw: String, persist: Boolean) {
        val token = generation
        activity.lifecycleScope.launch {
            val state = model.state.first { !it.loading }
            val result = runCatching { withContext(Dispatchers.Default) {
                decodeLayoutBackup(raw, state.apps, state.profiles, scope)
            } }
            result.rethrowCancellation()
            result.onSuccess { imported ->
                if (token != generation || operation != OP_PREVIEW) return@onSuccess
                val show = {
                    if (token == generation && operation == OP_PREVIEW) {
                        importRaw = raw
                        preview = imported; pickerPending = false
                        if (persist) store.edit().putString(KEY_OPERATION, OP_PREVIEW).putString(KEY_PREVIEW, raw).apply()
                        onExternalResultChanged(true)
                    }
                }
                if ((state.appSecurity.isNotEmpty() || imported.settings?.appSecurity?.isNotEmpty() == true) &&
                    !authorized(importAuthorization, token)) {
                    authenticateBackup(onSuccess = {
                        if (token == generation) { importAuthorization = authorization(token); show() }
                    }, onFailure = { if (token == generation) clearTransaction() })
                } else show()
            }.onFailure {
                if (token != generation) return@onFailure
                errorMessage = it.message ?: activity.getString(R.string.this_layout_backup_is_invalid)
                clearTransaction(clearMessages = false)
            }
        }
    }

    private fun readBounded(uri: Uri): String {
        val input = activity.contentResolver.openInputStream(uri) ?: error("The selected document could not be opened")
        return input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_LAYOUT_BACKUP_BYTES) { "Backup is larger than 16 MB" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun widgetDescriptors(state: LauncherState): List<BackupWidgetDescriptor> = state.widgetPlacements.mapNotNull { placement ->
        if (placement.id < 0) return@mapNotNull null
        val info = widgets.manager.getAppWidgetInfo(placement.id) ?: error("Widget ${placement.slot} is unavailable")
        BackupWidgetDescriptor(placement.slot, info.provider.flattenToString(), userManager.getSerialNumberForUser(info.profile),
            info.loadLabel(activity.packageManager).toString(), if (info.profile == android.os.Process.myUserHandle()) activity.getString(R.string.personal) else activity.getString(R.string.work),
            isWork = info.profile != android.os.Process.myUserHandle())
    }

    private fun clearTransaction(clearMessages: Boolean = true) {
        generation++
        operation = null; pickerPending = false; preview = null
        importRaw = null
        exportAuthorization = null
        importAuthorization = null
        store.edit().clear().apply()
        onExternalResultChanged(false)
        if (clearMessages) clearMessage()
    }

    private fun Result<*>.rethrowCancellation() {
        exceptionOrNull()?.let { if (it is CancellationException) throw it }
    }

    private fun backupHasAppSecurity(raw: String): Boolean = runCatching {
        org.json.JSONObject(raw).optJSONObject("settings")?.optJSONArray("appSecurity")?.length()?.let { it > 0 } == true
    }.getOrDefault(false)

    companion object {
        private const val KEY_OPERATION = "operation"
        private const val KEY_PREVIEW = "preview"
        private const val KEY_RESULT = "result"
        private const val KEY_EXPORT = "export"
        private const val KEY_URI = "uri"
        private const val OP_EXPORT = "export"
        private const val OP_IMPORT = "import"
        private const val OP_PREVIEW = "preview"

    }
}
