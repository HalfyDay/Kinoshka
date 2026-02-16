package hd.kinoshka.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import hd.kinoshka.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class AppUpdateManager(private val appContext: Context) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkForUpdate(
        releasesUrl: String,
        currentVersionName: String
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val repository = parseRepositoryFromUrl(releasesUrl)
            ?: return@withContext UpdateCheckResult.Error(
                message = "Invalid GitHub Releases URL."
            )
        val latestReleaseApiUrl =
            "https://api.github.com/repos/${repository.owner}/${repository.repo}/releases/latest"

        val request = Request.Builder()
            .url(latestReleaseApiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Kinoshka-Android/${BuildConfig.VERSION_NAME}")
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use UpdateCheckResult.Error(
                        message = "GitHub API error: HTTP ${response.code}."
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@use UpdateCheckResult.Error(
                        message = "GitHub API returned an empty response."
                    )
                }
                val payload = JSONObject(body)
                val latestTag = payload.optString("tag_name").trim()
                val releasePageUrl = payload.optString("html_url")
                    .takeIf { it.isNotBlank() }
                    ?: releasesUrl
                if (latestTag.isBlank()) {
                    return@use UpdateCheckResult.Error(
                        message = "The latest release has no tag_name."
                    )
                }

                val apkAsset = pickApkAsset(payload)
                    ?: return@use UpdateCheckResult.NoApkAsset(
                        latestTag = latestTag,
                        htmlUrl = releasePageUrl
                    )

                if (!isRemoteVersionNewer(latestTag, currentVersionName)) {
                    return@use UpdateCheckResult.UpToDate(
                        latestTag = latestTag,
                        releasesUrl = releasePageUrl
                    )
                }

                UpdateCheckResult.UpdateAvailable(
                    release = AppRelease(
                        tagName = latestTag,
                        apkName = apkAsset.name,
                        apkDownloadUrl = apkAsset.downloadUrl,
                        htmlUrl = releasePageUrl
                    )
                )
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(
                message = error.message ?: "Failed to check for updates."
            )
        }
    }

    suspend fun downloadApk(release: AppRelease): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            val safeFileName = sanitizeApkFileName(release.apkName)
            val tempFile = File(updatesDir, "$safeFileName.part")
            val targetFile = File(updatesDir, safeFileName)

            val request = Request.Builder()
                .url(release.apkDownloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "Kinoshka-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Unable to download APK (HTTP ${response.code}).")
                }
                val body = response.body ?: throw IllegalStateException("APK download response is empty.")
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("Downloaded APK is empty.")
            }
            if (targetFile.exists() && !targetFile.delete()) {
                throw IllegalStateException("Unable to replace existing APK file.")
            }
            if (!tempFile.renameTo(targetFile)) {
                throw IllegalStateException("Unable to prepare APK for installation.")
            }
            updatesDir.listFiles()
                ?.filter { it != targetFile }
                ?.forEach { staleFile ->
                    runCatching { staleFile.delete() }
                }
            targetFile
        }
    }

    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()
    }

    fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    fun launchApkInstaller(apkFile: File): Result<Unit> = runCatching {
        val apkUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        appContext.startActivity(installIntent)
    }

    private fun parseRepositoryFromUrl(url: String): GitHubRepository? {
        if (url.isBlank()) return null
        val uri = Uri.parse(url.trim())
        val host = uri.host?.lowercase() ?: return null
        val segments = uri.pathSegments.filter { it.isNotBlank() }

        return when (host) {
            "github.com", "www.github.com" -> {
                if (segments.size < 2) return null
                GitHubRepository(
                    owner = segments[0],
                    repo = segments[1].removeSuffix(".git")
                )
            }

            "api.github.com" -> {
                val reposIndex = segments.indexOf("repos")
                if (reposIndex < 0 || segments.size <= reposIndex + 2) return null
                GitHubRepository(
                    owner = segments[reposIndex + 1],
                    repo = segments[reposIndex + 2].removeSuffix(".git")
                )
            }

            else -> null
        }
    }

    private fun pickApkAsset(payload: JSONObject): ApkAsset? {
        val assets = payload.optJSONArray("assets") ?: return null
        var selectedAsset: ApkAsset? = null
        var bestScore = Int.MIN_VALUE

        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            val downloadUrl = asset.optString("browser_download_url").trim()
            val contentType = asset.optString("content_type").trim()
            if (downloadUrl.isBlank()) continue

            val isApk = name.endsWith(".apk", ignoreCase = true) ||
                downloadUrl.contains(".apk", ignoreCase = true) ||
                contentType.equals("application/vnd.android.package-archive", ignoreCase = true)
            if (!isApk) continue

            val score = when {
                name.contains("release", ignoreCase = true) -> 3
                name.contains("universal", ignoreCase = true) -> 2
                else -> 1
            }
            if (score > bestScore) {
                bestScore = score
                selectedAsset = ApkAsset(
                    name = if (name.isBlank()) "app-release.apk" else name,
                    downloadUrl = downloadUrl
                )
            }
        }

        return selectedAsset
    }

    private fun isRemoteVersionNewer(remoteTag: String, currentVersion: String): Boolean {
        val remoteParts = parseVersionParts(remoteTag)
        val currentParts = parseVersionParts(currentVersion)

        if (remoteParts.isEmpty()) return false
        if (currentParts.isEmpty()) return true

        val maxLength = maxOf(remoteParts.size, currentParts.size)
        for (index in 0 until maxLength) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val currentValue = currentParts.getOrElse(index) { 0 }
            if (remoteValue > currentValue) return true
            if (remoteValue < currentValue) return false
        }
        return false
    }

    private fun parseVersionParts(rawVersion: String): List<Int> {
        return Regex("\\d+")
            .findAll(rawVersion)
            .mapNotNull { matchResult -> matchResult.value.toIntOrNull() }
            .toList()
    }

    private fun sanitizeApkFileName(rawName: String): String {
        val cleaned = rawName
            .ifBlank { "app-release.apk" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
        val normalized = if (cleaned.isBlank()) "app-release.apk" else cleaned
        return if (normalized.endsWith(".apk", ignoreCase = true)) normalized else "$normalized.apk"
    }
}

private data class GitHubRepository(
    val owner: String,
    val repo: String
)

private data class ApkAsset(
    val name: String,
    val downloadUrl: String
)

data class AppRelease(
    val tagName: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val htmlUrl: String
)

sealed interface UpdateCheckResult {
    data class UpToDate(
        val latestTag: String,
        val releasesUrl: String
    ) : UpdateCheckResult

    data class UpdateAvailable(
        val release: AppRelease
    ) : UpdateCheckResult

    data class NoApkAsset(
        val latestTag: String,
        val htmlUrl: String
    ) : UpdateCheckResult

    data class Error(
        val message: String
    ) : UpdateCheckResult
}
