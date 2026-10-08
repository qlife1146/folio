package com.mccal.folio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal const val FOLIO_RELEASES_URL = "https://github.com/qlife1146/folio/releases"

internal sealed interface AppUpdateResult {
    data class Available(val version: String, val url: String) : AppUpdateResult
    data class Current(val version: String) : AppUpdateResult
    data object NoRelease : AppUpdateResult
    data object Unavailable : AppUpdateResult
    data object UnknownVersion : AppUpdateResult
}

internal suspend fun checkAppUpdate(context: Context): AppUpdateResult = withContext(Dispatchers.IO) {
    var connection: HttpsURLConnection? = null
    try {
        ensureActive()
        val request = URL("https://api.github.com/repos/qlife1146/folio/releases/latest")
            .openConnection() as HttpsURLConnection
        connection = request
        request.connectTimeout = 8_000
        request.readTimeout = 8_000
        request.setRequestProperty("Accept", "application/vnd.github+json")
        request.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
        request.setRequestProperty("User-Agent", "Folio-Update-Check")
        val status = request.responseCode
        ensureActive()
        if (status == 404) return@withContext AppUpdateResult.NoRelease
        if (status != 200) return@withContext AppUpdateResult.Unavailable
        val release = JSONObject(request.inputStream.bufferedReader().use { it.readText() })
        ensureActive()
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext AppUpdateResult.NoRelease
        val assets = release.optJSONArray("assets") ?: return@withContext AppUpdateResult.NoRelease
        val hasApk = (0 until assets.length()).any { index ->
            assets.optJSONObject(index)?.let { asset ->
                asset.optString("state") == "uploaded" && asset.optString("name").endsWith(".apk", ignoreCase = true) &&
                    asset.optString("browser_download_url").isNotBlank()
            } == true
        }
        if (!hasApk) return@withContext AppUpdateResult.NoRelease
        val tag = release.optString("tag_name").trim()
        val url = release.optString("html_url")
        if (!validReleasePage(url, tag)) return@withContext AppUpdateResult.Unavailable
        val installedName = installedVersion(context) ?: return@withContext AppUpdateResult.UnknownVersion
        val installed = parseReleaseVersion(installedName) ?: return@withContext AppUpdateResult.UnknownVersion
        val latest = parseReleaseVersion(tag) ?: return@withContext AppUpdateResult.UnknownVersion
        if (compareReleaseVersions(latest, installed) > 0)
            AppUpdateResult.Available(tag.removePrefix("v").removePrefix("V"), url)
        else AppUpdateResult.Current(installedName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ensureActive()
        AppUpdateResult.Unavailable
    } finally {
        connection?.disconnect()
    }
}

private fun validReleasePage(url: String, tag: String): Boolean = runCatching {
    val page = URI(url)
    page.scheme.equals("https", ignoreCase = true) && page.host.equals("github.com", ignoreCase = true) &&
        page.userInfo == null && page.port == -1 && page.query == null && page.fragment == null &&
        tag.isNotEmpty() && page.path == "/qlife1146/folio/releases/tag/$tag"
}.getOrDefault(false)

@Suppress("DEPRECATION") // The flags-object overload starts at API 33; API 31/32 still use integer flags.
private fun installedVersion(context: Context): String? = if (Build.VERSION.SDK_INT >= 33)
    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
else context.packageManager.getPackageInfo(context.packageName, 0).versionName

private data class ReleaseVersion(val numbers: List<Long>, val stage: Int, val revision: Long)
private val releaseVersionPattern = Regex(
    """^[vV]?(\d+(?:\.\d+){2,3})(?:-(alpha|beta|rc)(?:[.-]?(\d+))?)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
    RegexOption.IGNORE_CASE,
)

private fun parseReleaseVersion(value: String): ReleaseVersion? {
    val match = releaseVersionPattern.matchEntire(value.trim()) ?: return null
    val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
    val stage = when (match.groupValues[2].lowercase(Locale.ROOT)) { "alpha" -> 0; "beta" -> 1; "rc" -> 2; else -> 3 }
    val revision = match.groupValues[3].takeIf(String::isNotEmpty)?.toLongOrNull()
        ?: if (match.groupValues[3].isEmpty()) 0 else return null
    return ReleaseVersion(numbers, stage, revision)
}

private fun compareReleaseVersions(a: ReleaseVersion, b: ReleaseVersion): Int {
    repeat(4) { index ->
        val difference = (a.numbers.getOrElse(index) { 0 }).compareTo(b.numbers.getOrElse(index) { 0 })
        if (difference != 0) return difference
    }
    val stage = a.stage.compareTo(b.stage)
    return if (stage != 0) stage else a.revision.compareTo(b.revision)
}
