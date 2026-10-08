package com.mccal.folio

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private enum class BackupPreferenceType { STRING, BOOLEAN, FLOAT, LONG }

/** User options only: Android grants, widget IDs, usage history and pending operations stay on their install. */
private val backupPreferenceFields = mapOf(
    "side_key" to mapOf("hold" to BackupPreferenceType.STRING),
    "spotlight" to mapOf("content_search_enabled" to BackupPreferenceType.BOOLEAN),
    "setup_experience" to mapOf("finished" to BackupPreferenceType.BOOLEAN, "started" to BackupPreferenceType.BOOLEAN,
        "onboardingPage" to BackupPreferenceType.STRING),
    "folio" to mapOf("island_pos_inner" to BackupPreferenceType.STRING, "island_pos_inner_portrait" to BackupPreferenceType.STRING,
        "island_pos_cover" to BackupPreferenceType.STRING, "island_pos_cover_landscape" to BackupPreferenceType.STRING,
        "button_bar_lift_inner" to BackupPreferenceType.FLOAT, "button_bar_lift_inner_landscape" to BackupPreferenceType.FLOAT,
        "button_bar_lift_cover" to BackupPreferenceType.FLOAT, "button_bar_lift_cover_landscape" to BackupPreferenceType.FLOAT),
    "launcher_background" to mapOf("photoEnabled" to BackupPreferenceType.BOOLEAN, "photoId" to BackupPreferenceType.STRING),
)

internal fun encodePreferenceBackup(context: Context): JSONObject {
    val result = JSONObject().put("appIcon", AppIconChoice.current(context).name)
    backupPreferenceFields.forEach { (store, fields) ->
        val prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE)
        val options = JSONObject()
        fields.forEach { (key, type) -> if (prefs.contains(key)) when (type) {
            BackupPreferenceType.STRING -> prefs.getString(key, null)?.let { options.put(key, it) }
            BackupPreferenceType.BOOLEAN -> options.put(key, prefs.getBoolean(key, false))
            BackupPreferenceType.FLOAT -> options.put(key, prefs.getFloat(key, 0f))
            BackupPreferenceType.LONG -> options.put(key, prefs.getLong(key, 0L))
        } }
        result.put(store, options)
    }
    val background = result.getJSONObject("launcher_background")
    val photoEnabled = launcherBackgroundEnabled(context)
    background.put("photoEnabled", photoEnabled)
    if (photoEnabled) {
        val file = launcherBackgroundFile(context)
        require(file.length() in 1..maximumBackupPhotoBytes().toLong()) { "The background photo is too large to back up" }
        val bytes = file.readBytes()
        require(bytes.size <= maximumBackupPhotoBytes()) { "The background photo is too large to back up" }
        background.put("photoId", launcherBackgroundIdentity(context))
            .put("photoJpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    } else background.remove("photoId")
    return validatePreferenceBackup(result)
}

/** Clone the known fields only, and reject invalid values before restore can modify anything. */
internal fun validatePreferenceBackup(json: JSONObject): JSONObject {
    require(json.toString().toByteArray(Charsets.UTF_8).size <= MAX_LAYOUT_BACKUP_BYTES) { "Settings backup is too large" }
    val result = JSONObject()
    if (json.has("appIcon")) {
        val icon = json.get("appIcon") as? String ?: error("Invalid app icon")
        require(icon in AppIconChoice.entries.map { it.name }) { "Invalid app icon" }
        result.put("appIcon", icon)
    }
    backupPreferenceFields.forEach { (store, fields) -> if (json.has(store)) {
        val source = json.get(store) as? JSONObject ?: error("$store must be an object")
        val options = JSONObject()
        fields.forEach { (key, type) -> if (source.has(key)) {
            val value = source.get(key)
            val checked = when (type) {
                BackupPreferenceType.STRING -> (value as? String ?: error("$key must be a string")).also {
                    require(it.length <= 1_024) { "$key is too long" }
                }
                BackupPreferenceType.BOOLEAN -> value as? Boolean ?: error("$key must be a boolean")
                BackupPreferenceType.FLOAT -> (value as? Number ?: error("$key must be a number")).toFloat().also {
                    require(it.isFinite() && it >= 0f) { "$key must be finite and nonnegative" }
                }
                BackupPreferenceType.LONG -> {
                    require(value is Int || value is Long) { "$key must be an integer" }
                    (value as Number).toLong().also { require(it >= 0L) { "$key must be nonnegative" } }
                }
            }
            options.put(key, checked)
        } }
        when (store) {
            "side_key" -> if (options.has("hold")) require(options.getString("hold") in SideKeyHold.entries.map { it.name })
            "folio" -> fields.keys.filter { it.startsWith("island_pos_") && options.has(it) }.forEach { key ->
                val coordinates = options.getString(key).split(',').map { it.toFloatOrNull() }
                require(coordinates.size == 2 && coordinates.all { it != null && it.isFinite() } &&
                    coordinates[0]!! in 0f..1f && coordinates[1]!! >= 0f) { "Invalid $key" }
            }
            "launcher_background" -> {
                require(options.has("photoEnabled")) { "Missing background selection" }
                if (options.getBoolean("photoEnabled")) {
                    require(options.has("photoId") && options.getString("photoId").isNotBlank()) { "Missing background identity" }
                    val encoded = source.get("photoJpeg") as? String ?: error("Missing background photo")
                    decodeBackupPhoto(encoded)
                    options.put("photoJpeg", encoded)
                } else require(!options.has("photoId") && !source.has("photoJpeg")) { "Unexpected background photo" }
            }
        }
        result.put(store, options)
    } }
    return result
}

internal fun restorePreferenceBackup(context: Context, json: JSONObject): Boolean {
    val validated = validatePreferenceBackup(json)
    val background = validated.optJSONObject("launcher_background")
    val photo = background?.optString("photoJpeg")?.takeIf { it.isNotEmpty() }?.let(::decodeBackupPhoto)
    photo?.let { requireNotNull(BitmapFactory.decodeByteArray(it, 0, it.size)) { "Invalid background photo" }.recycle() }
    // Stage beside the destination so a successful rename never exposes a partially written photo.
    if (photo != null) {
        val destination = launcherBackgroundFile(context)
        val temporary = File.createTempFile("${destination.name}.", ".tmp", destination.parentFile)
        try {
            FileOutputStream(temporary).use { output -> output.write(photo); output.fd.sync() }
            try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
    var changed = photo != null
    val searchWasEnabled = ContentSearchIndex.enabled(context)
    backupPreferenceFields.forEach { (store, fields) -> validated.optJSONObject(store)?.let { options ->
        val prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        fields.forEach { (key, type) ->
            val value = if (options.has(key)) options.get(key) else null
            val old = if (!prefs.contains(key)) null else when (type) {
                BackupPreferenceType.STRING -> prefs.getString(key, null)
                BackupPreferenceType.BOOLEAN -> prefs.getBoolean(key, false)
                BackupPreferenceType.FLOAT -> prefs.getFloat(key, 0f)
                BackupPreferenceType.LONG -> prefs.getLong(key, 0L)
            }
            if (old != value) changed = true
            if (value == null) editor.remove(key) else when (type) {
                BackupPreferenceType.STRING -> editor.putString(key, value as String)
                BackupPreferenceType.BOOLEAN -> editor.putBoolean(key, value as Boolean)
                BackupPreferenceType.FLOAT -> editor.putFloat(key, (value as Number).toFloat())
                BackupPreferenceType.LONG -> editor.putLong(key, (value as Number).toLong())
            }
        }
        require(editor.commit()) { "Could not restore $store settings" }
    } }
    if (validated.has("spotlight")) {
        val searchEnabled = ContentSearchIndex.enabled(context)
        if (searchEnabled != searchWasEnabled) ContentSearchIndex.setEnabled(context, searchEnabled)
    }
    if (validated.has("appIcon")) {
        val icon = AppIconChoice.valueOf(validated.getString("appIcon"))
        if (icon != AppIconChoice.current(context)) { AppIconChoice.set(context, icon); changed = true }
    }
    return changed
}

private fun maximumBackupPhotoBytes() = MAX_LAYOUT_BACKUP_BYTES / 4 * 3

private fun decodeBackupPhoto(encoded: String): ByteArray {
    require(encoded.length <= (maximumBackupPhotoBytes() + 2) / 3 * 4) { "The background photo is too large" }
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    require(bytes.size in 4..maximumBackupPhotoBytes() && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "Invalid background JPEG" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) {
        "Invalid background photo dimensions"
    }
    return bytes
}
