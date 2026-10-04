package com.yinpage.link.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yinpage.link.BuildConfig
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 *  应用内更新：检查 GitHub 最新 Release → 自动下载 → 调起系统安装器
 * ============================================================================
 *  - 检查：`GET api.github.com/repos/6ewjso6-gif/YINPAGE-LINK/releases/latest`，
 *    取 `tag_name`（形如 `APP-t-v0.5`）与 APK 直链；
 *  - 比较：tag 里的数字段（v0.5 → [0,5]）与本机 `BuildConfig.VERSION_NAME`
 *    逐段比较，远端更大才算有更新（`versionCode` 是时间戳不可用于判新旧）；
 *  - 下载：流式写入 cacheDir/updates/，边下边回调进度；
 *  - 安装：FileProvider 授权后 `ACTION_VIEW` 调起系统安装器。
 *
 *  ⚠️ 免责声明：正常 App 无法静默覆盖安装，系统安装器确认弹窗不可绕过；
 *  且 Android 8+ 需要用户在「安装未知应用」里允许本应用。
 * ============================================================================
 */
object UpdateManager {

    private const val TAG = "更新"

    /** 发布仓库：main 分支的 Release 才是面向用户的版本。 */
    private const val REPO = "6ewjso6-gif/YINPAGE-LINK"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** Release 附件前缀：只认 `APP-t-*.apk` 形态的产物。 */
    private const val APK_PREFIX = "APP-t-"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** GitHub 最新 Release 里对安装有意义的信息。 */
    data class ReleaseInfo(
        val tagName: String,
        val publishedAt: String,
        val body: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
    )

    sealed interface CheckResult {
        /** 有新版本，附带下载所需信息。 */
        data class Available(val info: ReleaseInfo) : CheckResult

        /** 当前版本已经是最新。 */
        data object UpToDate : CheckResult

        /** 检查失败（网络 / 解析 / 无 APK）。 */
        data class Failed(val message: String) : CheckResult
    }

    // ------------------------------------------------------------------ 检查

    /** 拉取 GitHub 最新 Release 并判断是否比当前版本新。 */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(LATEST_API).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "YINPAGE-LINK")
                val code = conn.responseCode
                if (code != 200) return@runCatching CheckResult.Failed("检查更新失败（HTTP $code）")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parseLatest(text)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { CheckResult.Failed(it.message ?: "网络异常，请稍后重试") }
    }

    private fun parseLatest(json: String): CheckResult {
        val root = JSONObject(json)
        val tag = root.optString("tag_name", "")
        val published = root.optString("published_at", "")
        val body = root.optString("body", "")
        val assets = root.optJSONArray("assets") ?: return CheckResult.Failed("Release 中没有可下载的文件")

        var apkUrl = ""
        var apkName = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.startsWith(APK_PREFIX) && name.endsWith(".apk")) {
                apkName = name
                apkUrl = asset.optString("browser_download_url", "")
                apkSize = asset.optLong("size", 0L)
                break
            }
        }
        if (apkUrl.isBlank()) return CheckResult.Failed("Release 中没有找到 APK 安装包")

        val info = ReleaseInfo(tag, published, body, apkUrl, apkName, apkSize)
        return if (isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
            CheckResult.Available(info)
        } else {
            CheckResult.UpToDate
        }
    }

    // ------------------------------------------------------------------ 版本比较

    /** 从 `APP-t-v0.5` / `v1.2.3` 里取出数字段。 */
    private fun versionNumbers(tag: String): List<Int> {
        val match = Regex("v(\\d+(?:\\.\\d+)*)").find(tag) ?: return emptyList()
        return match.groupValues[1].split(".").mapNotNull { it.toIntOrNull() }
    }

    /** 远端 tag 比本机 versionName 新才算有更新；解析不出数字段时按"无更新"处理。 */
    fun isNewerVersion(latestTag: String, localVersion: String): Boolean {
        val remote = versionNumbers(latestTag)
        val local = versionNumbers(localVersion)
        if (remote.isEmpty() || local.isEmpty()) return false
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 流式下载 APK 到 cacheDir/updates/（FileProvider 可读）。
     * @param onProgress 已下载字节 / 总字节（总字节未知时为 0）
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, info.apkName)
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "YINPAGE-LINK")
            val code = conn.responseCode
            if (code != 200) throw IOException("下载失败（HTTP $code）")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize

            target.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            target
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 安装

    /** 调起系统安装器。Android 8+ 需已允许「安装未知应用」。 */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val appContext = context.applicationContext
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            // 通知系统"这是更新已安装应用"，尽量少打扰用户
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        appContext.startActivity(intent)
        true
    }.getOrNull() ?: false
}