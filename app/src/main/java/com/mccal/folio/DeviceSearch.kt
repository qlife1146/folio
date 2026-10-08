package com.mccal.folio

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DeviceSearchHit(val title: String, val detail: String?, val intent: Intent, val contentMatch: Boolean = false)
internal data class DeviceSearchResults(val hits: List<DeviceSearchHit> = emptyList(), val incomplete: Boolean = false, val failed: Boolean = false)

/** Metadata searches merged with the local content index. Call off the main thread. */
internal object DeviceSearch {
    private const val LIMIT = 12
    private const val FOLDERS = "spotlight_search_folders"

    fun photoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasPhotos(context: Context) = photoPermissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun folders(context: Context): Set<String> = context.getSharedPreferences("spotlight", 0).getStringSet(FOLDERS, emptySet()).orEmpty().toSet()
    fun addFolder(context: Context, uri: Uri) {
        require(DocumentsContract.isTreeUri(uri))
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences("spotlight", 0).edit().putStringSet(FOLDERS, folders(context) + uri.toString()).apply()
        ContentSearchJob.schedule(context, refresh = true)
    }
    fun removeFolder(context: Context, uri: String) {
        context.getSharedPreferences("spotlight", 0).edit().putStringSet(FOLDERS, folders(context) - uri).apply()
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        ContentSearchJob.schedule(context, refresh = true)
    }
    fun folderName(uri: String): String = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifBlank { uri } }.getOrDefault(uri)

    private suspend fun search(block: suspend () -> DeviceSearchResults): DeviceSearchResults = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        DeviceSearchResults(failed = true)
    }

    suspend fun calendar(context: Context, query: String): DeviceSearchResults = search {
        val hits = mutableListOf<DeviceSearchHit>()
        val columns = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY)
        val filter = "${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.VISIBLE} = 1 AND (instr(lower(${CalendarContract.Events.TITLE}), lower(?)) > 0 OR instr(lower(${CalendarContract.Events.EVENT_LOCATION}), lower(?)) > 0)"
        val cursor = context.contentResolver.query(CalendarContract.Events.CONTENT_URI, columns, filter, arrayOf(query, query), "${CalendarContract.Events.DTSTART} DESC")
            ?: return@search DeviceSearchResults(failed = true)
        cursor.use { c ->
            while (hits.size < LIMIT && c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val begin = c.getLong(3)
                val allDay = c.getInt(5) == 1
                val formatter = if (allDay) java.text.DateFormat.getDateInstance() else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                if (allDay) formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val detail = listOfNotNull(formatter.format(java.util.Date(begin)), c.getString(2)?.takeIf { it.isNotBlank() }).joinToString(" · ")
                hits += DeviceSearchHit(c.getString(1).orEmpty(), detail,
                    Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, c.getLong(0)))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.getLong(4)))
            }
            DeviceSearchResults(hits, incomplete = c.moveToNext())
        }
    }

    suspend fun photos(context: Context, query: String): DeviceSearchResults = merge(
        photoNames(context, query), search { ContentSearchIndex.search(context, query, ContentSearchIndex.PHOTOS) })

    suspend fun files(context: Context, query: String, roots: Set<String>): DeviceSearchResults = merge(
        fileNames(context, query, roots), search { ContentSearchIndex.search(context, query, ContentSearchIndex.FILES) })

    private fun merge(names: DeviceSearchResults, contents: DeviceSearchResults): DeviceSearchResults {
        // Put body matches first so their matching passage survives URI deduplication.
        val hits = (contents.hits + names.hits).distinctBy { it.intent.data }
        return DeviceSearchResults(hits.take(LIMIT), names.incomplete || contents.incomplete || hits.size > LIMIT, names.failed || contents.failed)
    }

    private suspend fun photoNames(context: Context, query: String): DeviceSearchResults = search {
        val hits = mutableListOf<DeviceSearchHit>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE)
        val filter = "${MediaStore.Images.Media.IS_TRASHED} = 0 AND ${MediaStore.Images.Media.IS_PENDING} = 0 AND (instr(lower(${MediaStore.Images.Media.DISPLAY_NAME}), lower(?)) > 0 OR instr(lower(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}), lower(?)) > 0)"
        val cursor = context.contentResolver.query(collection, columns, filter, arrayOf(query, query), "${MediaStore.Images.Media.DATE_ADDED} DESC")
            ?: return@search DeviceSearchResults(failed = true)
        cursor.use { c ->
            while (hits.size < LIMIT && c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                hits += DeviceSearchHit(c.getString(1).orEmpty(), c.getString(2),
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, c.getString(3) ?: "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            DeviceSearchResults(hits, incomplete = c.moveToNext())
        }
    }

    private suspend fun fileNames(context: Context, query: String, roots: Set<String>): DeviceSearchResults = search {
        val hits = linkedMapOf<String, DeviceSearchHit>()
        val queue = ArrayDeque<Pair<Uri, String>>()
        roots.forEach { value -> Uri.parse(value).let { queue.addLast(it to DocumentsContract.getTreeDocumentId(it)) } }
        val visited = mutableSetOf<Pair<String?, String>>()
        var incomplete = false
        var failed = false
        while (queue.isNotEmpty() && hits.size < LIMIT) {
            currentCoroutineContext().ensureActive()
            val (root, id) = queue.removeFirst()
            if (!visited.add(root.authority to id)) continue
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
                val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                val cursor = context.contentResolver.query(children, columns, null, null, null)
                if (cursor == null) { failed = true; continue }
                cursor.use { c ->
                    while (hits.size < LIMIT && c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val childId = c.getString(0) ?: continue
                        val name = c.getString(1).orEmpty()
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.addLast(root to childId)
                        else if (name.contains(query, ignoreCase = true)) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(root, childId)
                            hits["${root.authority}:$childId"] = DeviceSearchHit(name, folderName(root.toString()),
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }
                    if (c.moveToNext()) incomplete = true
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        DeviceSearchResults(hits.values.toList(), incomplete || queue.isNotEmpty(), failed)
    }
}
