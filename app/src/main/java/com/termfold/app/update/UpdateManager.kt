package com.termfold.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.termfold.app.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Checks GitHub for a newer TermFold, downloads it and hands it to Android's installer.
 *
 * Only this project's own releases are used: the release list comes from the GitHub API for
 * [REPO], the APK must be one of that release's assets under the same repository, its SHA-256 is
 * checked against the one printed in the release notes, and it must be signed with the same key as
 * the installed app. Android's installer then asks the user before anything is replaced.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val REPO = "Bibarud/TermFold"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val PREFS = "updates"

    /** How long a result is trusted before the launch-time check asks GitHub again. */
    private const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

    data class Release(
        val version: String,
        val name: String,
        val notes: String,
        val url: String,
        val sha256: String?,
        val size: Long,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State

        /** [needsPermission] is set when Android first has to be told that this app may install. */
        data class Ready(val release: Release, val file: File, val needsPermission: Boolean = false) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _hasUpdate = MutableStateFlow(false)

    /** True while a newer version is known, for the dot on the Settings tab. */
    val hasUpdate: StateFlow<Boolean> = _hasUpdate.asStateFlow()

    private var restored = false

    /** Picks up what the last check found, so the dot survives a restart without a network call. */
    private fun restore(context: Context) {
        if (restored) return
        restored = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString("latest_version", null) ?: return
        if (isNewer(version, BuildConfig.VERSION_NAME)) {
            _hasUpdate.value = true
            if (_state.value == State.Idle) {
                _state.value = State.Available(
                    Release(
                        version = version,
                        name = prefs.getString("latest_name", "TermFold $version").orEmpty(),
                        notes = prefs.getString("latest_notes", "").orEmpty(),
                        url = prefs.getString("latest_url", "").orEmpty(),
                        sha256 = prefs.getString("latest_sha", null),
                        size = prefs.getLong("latest_size", 0L),
                    )
                )
            }
        }
    }

    /** Checks at most every [CHECK_EVERY_MS]; for app start and for opening Settings. */
    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        restore(app)
        val last = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("checked_at", 0L)
        if (System.currentTimeMillis() - last >= CHECK_EVERY_MS) check(app, quiet = true)
    }

    /** Asks GitHub for the newest release. [quiet] keeps failures out of the UI (a launch-time check). */
    fun check(context: Context, quiet: Boolean = false) {
        val app = context.applicationContext
        restore(app)
        val now = _state.value
        if (now is State.Checking || now is State.Downloading) return
        if (!quiet) _state.value = State.Checking
        scope.launch {
            try {
                val release = fetchLatest()
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
                if (isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    prefs.edit()
                        .putString("latest_version", release.version)
                        .putString("latest_name", release.name)
                        .putString("latest_notes", release.notes)
                        .putString("latest_url", release.url)
                        .putString("latest_sha", release.sha256)
                        .putLong("latest_size", release.size)
                        .apply()
                    _hasUpdate.value = true
                    val ready = downloaded(app, release)
                    _state.value = if (ready != null) State.Ready(release, ready) else State.Available(release)
                } else {
                    prefs.edit().remove("latest_version").apply()
                    _hasUpdate.value = false
                    cleanUp(app)
                    _state.value = State.UpToDate
                }
            } catch (error: Exception) {
                Log.w(TAG, "Update check failed", error)
                if (!quiet) _state.value = State.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** Downloads the release that [State.Available] describes. */
    fun download(context: Context) {
        val app = context.applicationContext
        val current = _state.value
        val release = (current as? State.Available)?.release ?: (current as? State.Failed)?.let { latestKnown(app) } ?: return
        scope.launch {
            try {
                _state.value = State.Downloading(release, 0f)
                val file = fetchApk(app, release) { _state.value = State.Downloading(release, it) }
                _state.value = State.Ready(release, file)
            } catch (error: Exception) {
                Log.w(TAG, "Update download failed", error)
                _state.value = State.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** Hands the downloaded APK to Android's installer, which asks the user to confirm. */
    fun install(context: Context) {
        val ready = _state.value as? State.Ready ?: return
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            // Android keeps a per-app switch for installing; send the user to it once.
            _state.value = ready.copy(needsPermission = true)
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", ready.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        _state.value = ready.copy(needsPermission = false)
    }

    private fun latestKnown(context: Context): Release? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString("latest_version", null) ?: return null
        return Release(
            version, prefs.getString("latest_name", "").orEmpty(), prefs.getString("latest_notes", "").orEmpty(),
            prefs.getString("latest_url", "").orEmpty(), prefs.getString("latest_sha", null), prefs.getLong("latest_size", 0L),
        )
    }

    private fun fetchLatest(): Release {
        val json = httpGet("https://api.github.com/repos/$REPO/releases/latest")
        val root = JSONObject(json)
        val tag = root.getString("tag_name")
        val assets = root.getJSONArray("assets")
        var url: String? = null
        var size = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                url = asset.getString("browser_download_url")
                size = asset.optLong("size", 0L)
                break
            }
        }
        if (url == null) throw IOException("The release has no APK attached.")
        if (!url.startsWith(DOWNLOAD_PREFIX)) throw IOException("The release points outside this project; not downloading.")
        val body = root.optString("body", "")
        val sha = Regex("""SHA-256\s+([0-9a-fA-F]{64})""").find(body)?.groupValues?.get(1)?.lowercase()
        return Release(
            version = tag.removePrefix("v"),
            name = root.optString("name", tag),
            notes = tidyNotes(body),
            url = url,
            sha256 = sha,
            size = size,
        )
    }

    /** The release notes as plain text, without markdown marks or the checksum line. */
    private fun tidyNotes(body: String): String =
        body.lines()
            .filterNot { it.contains("SHA-256") }
            .joinToString("\n") { it.replace("**", "").replace("`", "").trimEnd().removePrefix("### ").removePrefix("- ") }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(1200)

    private fun httpGet(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "TermFold/${BuildConfig.VERSION_NAME}")
        try {
            if (connection.responseCode != 200) throw IOException("GitHub answered ${connection.responseCode}.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun updatesDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun apkFile(context: Context, release: Release) = File(updatesDir(context), "TermFold-${release.version}.apk")

    /** The APK for [release] if an earlier download finished and still checks out. */
    private fun downloaded(context: Context, release: Release): File? {
        val file = apkFile(context, release)
        if (!file.isFile) return null
        if (release.sha256 != null && sha256(file) != release.sha256) {
            file.delete()
            return null
        }
        return file
    }

    private fun fetchApk(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        downloaded(context, release)?.let { return it }
        if (!release.url.startsWith(DOWNLOAD_PREFIX)) throw IOException("Refusing to download from outside this project.")
        cleanUp(context)
        val target = apkFile(context, release)
        val part = File(target.parentFile, target.name + ".part")
        val connection = URL(release.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "TermFold/${BuildConfig.VERSION_NAME}")
        try {
            if (connection.responseCode != 200) throw IOException("The download failed (${connection.responseCode}).")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            var read = 0L
            var lastReported = -1
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (total > 0 && read != total) throw IOException("The download was cut short.")
        } catch (error: Exception) {
            part.delete()
            throw error
        } finally {
            connection.disconnect()
        }

        if (release.sha256 != null && sha256(part) != release.sha256) {
            part.delete()
            throw IOException("The download does not match the checksum in the release notes; discarded.")
        }
        if (!signedByUs(context, part)) {
            part.delete()
            throw IOException("The download is not signed with TermFold's key; discarded.")
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not store the download.")
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** False only when the APK is positively signed by somebody else; unknown counts as fine. */
    @Suppress("DEPRECATION")
    private fun signedByUs(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val theirs = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
        val ours = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        if (theirs == null || theirs.isEmpty() || ours == null) true
        else theirs.map { it.toByteArray().toList() }.toSet() == ours.map { it.toByteArray().toList() }.toSet()
    }.getOrDefault(true)

    /** Removes APKs of versions that are not newer than the installed one, and unfinished downloads. */
    private fun cleanUp(context: Context) {
        updatesDir(context).listFiles()?.forEach { file ->
            val version = file.name.removePrefix("TermFold-").removeSuffix(".apk").removeSuffix(".apk.part")
            if (file.name.endsWith(".part") || !isNewer(version, BuildConfig.VERSION_NAME)) file.delete()
        }
    }

    /** True when [candidate] is a higher dotted version than [current]. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
