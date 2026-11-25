package com.astryxtv.installer.core

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(
    val tagName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

object ReleaseFetcher {
    private const val GITHUB_REPO = "BurbsKlopek/astryx-rust-launcher"
    private const val LATEST_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    fun fetchLatestReleaseInfo(): ReleaseAsset {
        val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "astryx-tv-installer-android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("GitHub API returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "latest")
            val assets = json.optJSONArray("assets")
                ?: throw IOException("Release $tag has no downloadable assets.")

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return ReleaseAsset(
                        tagName = tag,
                        fileName = File(name).name,
                        downloadUrl = asset.getString("browser_download_url"),
                        sizeBytes = asset.optLong("size", 0L)
                    )
                }
            }
            throw IOException("Release $tag has no .apk asset.")
        } finally {
            conn.disconnect()
        }
    }

    fun downloadReleaseApk(
        asset: ReleaseAsset,
        cacheDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        cacheDir.mkdirs()
        val dest = File(cacheDir, asset.fileName)
        if (dest.isFile && dest.length() > 100_000L && hasApkMagic(dest)) {
            if (asset.sizeBytes <= 0L || dest.length() == asset.sizeBytes) {
                onProgress(dest.length(), dest.length())
                return dest
            }
        }

        val part = File(cacheDir, "${asset.fileName}.part")
        val conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "astryx-tv-installer-android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("Download returned HTTP $code")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
            var downloaded = 0L
            val buf = ByteArray(32 * 1024)
            conn.inputStream.use { inp ->
                FileOutputStream(part).use { out ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            if (!hasApkMagic(part)) {
                part.delete()
                throw IOException("Downloaded file is not a valid APK (missing ZIP header).")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return dest
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun hasApkMagic(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return runCatching {
            val header = ByteArray(2)
            FileInputStream(file).use { it.read(header) }
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }.getOrDefault(false)
    }
}
