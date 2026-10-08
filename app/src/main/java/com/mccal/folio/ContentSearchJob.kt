package com.mccal.folio

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Resumable indexing in Android's background scheduler; finished files survive process death. */
class ContentSearchJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        ContentSearchIndex.queued.value = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            var retry = false
            try {
                retry = ContentSearchIndex.mutex.withLock {
                    try { updateIndex() }
                    finally { ContentSearchIndex.status.value = ContentSearchIndex.status.value.copy(running = false) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { ContentSearchIndex.status.value = ContentSearchIndex.status.value.copy(sourceError = true) }
            finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    // A stopped job is removed by onStopJob; Android owns its retry then.
                    if (work[params.jobId] === owner) {
                        work.remove(params.jobId)
                        ContentSearchIndex.queued.value = retry
                        jobFinished(params, retry)
                    }
                }
            }
        }
        work[params.jobId] = job
        job.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        work.remove(params.jobId)?.cancel()
        ContentSearchIndex.queued.value = ContentSearchIndex.enabled(this)
        return ContentSearchIndex.enabled(this)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun updateIndex(): Boolean {
        val db = ContentSearchIndex.database(this)
        if (!ContentSearchIndex.enabled(this)) {
            db.delete("entries", null, null)
            ContentSearchIndex.publish(this, running = false, sourceError = false)
            return false
        }
        ContentSearchIndex.publish(this, running = true, sourceError = false)
        val prefs = getSharedPreferences("spotlight", 0)
        if (prefs.getBoolean("content_retry_failed", false)) {
            prefs.edit().remove("content_retry_failed").apply()
            db.execSQL("UPDATE entries SET status = 0 WHERE status = 2")
        }
        val roots = DeviceSearch.folders(this)
        val photoAccess = DeviceSearch.hasPhotos(this)
        val allowedRoots = roots + if (photoAccess) setOf(ContentSearchIndex.PHOTOS) else emptySet()
        if (allowedRoots.isEmpty()) db.delete("entries", null, null)
        else db.delete("entries", "root NOT IN (${allowedRoots.joinToString { "?" }})", allowedRoots.toTypedArray())
        val scan = UUID.randomUUID().toString()
        var sourceError = false
        if (photoAccess) {
            try { scanPhotos(scan); prune(ContentSearchIndex.PHOTOS, scan) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { sourceError = true }
        }
        for (root in roots) {
            try { scanFolder(root, scan); prune(root, scan) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { sourceError = true }
        }
        ContentSearchIndex.publish(this, sourceError = sourceError)
        val deadline = SystemClock.elapsedRealtime() + 3 * 60_000
        ContentTextExtractor(this).use { extractor ->
            while (SystemClock.elapsedRealtime() < deadline && ContentSearchIndex.enabled(this)) {
                currentCoroutineContext().ensureActive()
                val next = db.rawQuery("SELECT uri, title, mime, root, version FROM entries WHERE status = 0 ORDER BY id LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) Array(5) { c.getString(it) } else null
                } ?: break
                val (uri, title, mime, root, version) = next
                // A folder/photo grant may have been withdrawn since this batch started.
                if (root != ContentSearchIndex.PHOTOS && root !in DeviceSearch.folders(this) ||
                    root == ContentSearchIndex.PHOTOS && !DeviceSearch.hasPhotos(this)) {
                    db.delete("entries", "root = ?", arrayOf(root)); continue
                }
                val values = ContentValues()
                try {
                    val result = extractor.extract(Uri.parse(uri), title, mime, currentCoroutineContext())
                    values.put("body", result.body)
                    values.put("status", if (result.partial) 3 else 1)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { values.put("body", ""); values.put("status", 2) }
                currentCoroutineContext().ensureActive()
                if (!ContentSearchIndex.enabled(this)) break
                db.update("entries", values, "uri = ? AND version = ?", arrayOf(uri, version))
                ContentSearchIndex.publish(this, sourceError = sourceError)
            }
        }
        if (!ContentSearchIndex.enabled(this)) db.delete("entries", null, null)
        ContentSearchIndex.publish(this, running = false, sourceError = sourceError)
        return ContentSearchIndex.enabled(this) && (ContentSearchIndex.status.value.pending > 0 ||
            prefs.getBoolean("content_retry_failed", false))
    }

    private fun prune(root: String, scan: String) {
        ContentSearchIndex.database(this).delete("entries", "root = ? AND seen != ?", arrayOf(root, scan))
    }

    private suspend fun scanPhotos(scan: String) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.GENERATION_MODIFIED)
        val filter = "${MediaStore.Images.Media.IS_TRASHED} = 0 AND ${MediaStore.Images.Media.IS_PENDING} = 0"
        val cursor = contentResolver.query(collection, columns, filter, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            ?: throw java.io.IOException("Photo collection unavailable")
        cursor.use { c ->
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                ContentSearchIndex.seen(this, ContentUris.withAppendedId(collection, c.getLong(0)).toString(), ContentSearchIndex.PHOTOS,
                    ContentSearchIndex.PHOTOS, c.getString(1).orEmpty(), c.getString(2) ?: "image/jpeg",
                    "${c.getLong(3)}:${c.getLong(4)}:${c.getLong(5)}", scan)
            }
        }
    }

    private suspend fun scanFolder(root: String, scan: String) {
        val tree = Uri.parse(root)
        val queue = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        val visited = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parent = queue.removeFirst()
            if (!visited.add(parent)) continue
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
            val cursor = contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)
                ?: throw java.io.IOException("Folder unavailable")
            cursor.use { c ->
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.addLast(id)
                    else if (ContentTextExtractor.supports(name, mime)) {
                        // Some providers omit modification times; refresh those files at least daily.
                        val modified = c.getLong(3).takeIf { it > 0 } ?: (System.currentTimeMillis() / 86_400_000)
                        ContentSearchIndex.seen(this, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), ContentSearchIndex.FILES,
                            root, name, mime.ifBlank { if (name.endsWith(".pdf", true)) "application/pdf" else "text/plain" },
                            "$modified:${c.getLong(4)}", scan)
                    }
                }
            }
        }
    }

    companion object {
        private const val PERIODIC = 4110
        private const val REFRESH = 4111
        fun schedule(context: Context, refresh: Boolean = false, retryFailed: Boolean = false) {
            if (retryFailed) context.getSharedPreferences("spotlight", 0).edit().putBoolean("content_retry_failed", true).apply()
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, ContentSearchJob::class.java)
            if (ContentSearchIndex.enabled(context)) {
                if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(JobInfo.Builder(PERIODIC, component)
                    .setPeriodic(12 * 60 * 60_000L).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            } else scheduler.cancel(PERIODIC)
            if (refresh && scheduler.getPendingJob(REFRESH) == null) {
                ContentSearchIndex.queued.value = scheduler.schedule(JobInfo.Builder(REFRESH, component).setMinimumLatency(1_000)
                    .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) == JobScheduler.RESULT_SUCCESS
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.let { it.cancel(PERIODIC); it.cancel(REFRESH) }
            ContentSearchIndex.queued.value = false
        }
    }
}
