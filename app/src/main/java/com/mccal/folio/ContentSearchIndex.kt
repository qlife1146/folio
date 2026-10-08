package com.mccal.folio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ContentIndexStatus(val running: Boolean = false, val ready: Int = 0, val pending: Int = 0,
    val failed: Int = 0, val partial: Int = 0, val sourceError: Boolean = false)

/** Private, rebuildable full-text index. URI access is rechecked before exposing any stored excerpt. */
internal object ContentSearchIndex {
    const val PHOTOS = "photos"
    const val FILES = "files"
    val mutex = Mutex()
    val revision = MutableStateFlow(0)
    val status = MutableStateFlow(ContentIndexStatus())
    val queued = MutableStateFlow(false)
    private var lastSearchUpdate = 0L
    private var helper: Database? = null
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized fun database(context: Context): SQLiteDatabase {
        val db = helper ?: Database(context.applicationContext).also { helper = it }
        return db.writableDatabase
    }
    private fun prefs(context: Context) = context.getSharedPreferences("spotlight", 0)
    fun enabled(context: Context) = prefs(context).getBoolean("content_search_enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("content_search_enabled", enabled).apply()
        revision.update { it + 1 }
        val app = context.applicationContext
        if (enabled) ContentSearchJob.schedule(app, refresh = true) else {
            ContentSearchJob.cancel(app)
            maintenance.launch {
                try {
                    mutex.withLock {
                        if (!ContentSearchIndex.enabled(app)) {
                            database(app).delete("entries", null, null)
                            publish(app, running = false, sourceError = false)
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { status.value = status.value.copy(sourceError = true) }
            }
        }
    }

    fun publish(context: Context, running: Boolean = status.value.running, sourceError: Boolean = status.value.sourceError) {
        val counts = IntArray(4)
        database(context).rawQuery("SELECT status, COUNT(*) FROM entries GROUP BY status", null).use { c ->
            while (c.moveToNext()) counts[c.getInt(0)] = c.getInt(1)
        }
        status.value = ContentIndexStatus(running, counts[1] + counts[3], counts[0], counts[2], counts[3], sourceError)
        val now = SystemClock.elapsedRealtime()
        if (!running || now - lastSearchUpdate >= 1_000) {
            lastSearchUpdate = now
            revision.update { it + 1 }
        }
    }

    fun seen(context: Context, uri: String, kind: String, root: String, title: String, mime: String, version: String, scan: String) {
        val db = database(context)
        val previous = db.rawQuery("SELECT version FROM entries WHERE uri = ?", arrayOf(uri)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val values = ContentValues().apply {
            put("uri", uri); put("kind", kind); put("root", root); put("title", title); put("mime", mime)
            put("version", version); put("seen", scan)
            if (previous != version) { put("body", ""); put("status", 0) }
        }
        if (previous == null) db.insertOrThrow("entries", null, values) else db.update("entries", values, "uri = ?", arrayOf(uri))
    }

    suspend fun search(context: Context, query: String, kind: String): DeviceSearchResults {
        if (!enabled(context)) return DeviceSearchResults()
        val roots = if (kind == PHOTOS) {
            if (!DeviceSearch.hasPhotos(context)) return DeviceSearchResults()
            setOf(PHOTOS)
        } else DeviceSearch.folders(context)
        if (roots.isEmpty()) return DeviceSearchResults()
        // Only words become FTS syntax. User punctuation/operators cannot alter the query.
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.take(16).toList()
        if (words.isEmpty()) return DeviceSearchResults()
        val match = words.joinToString(" ") { "\"$it*\"" }
        val hits = mutableListOf<DeviceSearchHit>()
        val sql = "SELECT e.uri, e.title, e.mime, snippet(content_text, '', '', ' … ', 0, 24), e.status " +
            "FROM content_text JOIN entries e ON e.id = content_text.docid WHERE content_text MATCH ? " +
            "AND e.kind = ? AND e.root IN (${roots.joinToString { "?" }}) ORDER BY e.id DESC"
        database(context).rawQuery(sql, arrayOf(match, kind, *roots.toTypedArray())).use { c ->
            while (hits.size < 13 && c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (!enabled(context)) return DeviceSearchResults()
                val uri = Uri.parse(c.getString(0))
                val allowed = try {
                    if (kind == PHOTOS) context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media._ID),
                        "${android.provider.MediaStore.Images.Media.IS_TRASHED} = 0 AND ${android.provider.MediaStore.Images.Media.IS_PENDING} = 0",
                        null, null)?.use { it.moveToFirst() } == true
                    else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                } catch (_: Exception) { false }
                if (!allowed) continue
                val excerpt = c.getString(3).orEmpty().replace(Regex("\\s+"), " ").trim()
                val detail = if (c.getInt(4) == 3) context.getString(R.string.content_search_excerpt_partial, excerpt) else excerpt
                hits += DeviceSearchHit(c.getString(1), detail,
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, c.getString(2)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), contentMatch = true)
            }
        }
        return DeviceSearchResults(hits.take(12), incomplete = hits.size > 12)
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "spotlight-content.db", null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, uri TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, root TEXT NOT NULL, title TEXT NOT NULL, mime TEXT NOT NULL, version TEXT NOT NULL, seen TEXT NOT NULL, body TEXT NOT NULL DEFAULT '', status INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX entries_pending ON entries(status)")
            // FTS4 tokenizer options are valid SQLite syntax that generic SQL parsers may reject.
            // language=TEXT
            db.execSQL("CREATE VIRTUAL TABLE content_text USING fts4(body, tokenize=unicode61)")
            db.execSQL("CREATE TRIGGER entries_insert AFTER INSERT ON entries BEGIN INSERT INTO content_text(docid, body) VALUES (new.id, new.body); END")
            db.execSQL("CREATE TRIGGER entries_delete AFTER DELETE ON entries BEGIN DELETE FROM content_text WHERE docid = old.id; END")
            db.execSQL("CREATE TRIGGER entries_update AFTER UPDATE OF body ON entries BEGIN UPDATE content_text SET body = new.body WHERE docid = new.id; END")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
