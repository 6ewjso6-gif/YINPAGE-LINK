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
import java.io.FileOutputStream
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
 *  ⚠️ 国内网络特性：`api.github.com` 与 release 资源 CDN（objects.githubusercontent.com）
 *  经常不可达/极慢。因此**检查和下载都走多线路自动回退**：
 *    直连 GitHub → ghproxy / ghfast.top / gh-proxy 等国内加速镜像，
 *  任一条线路成功即算成功；下载支持 Range 断点续传，失败自动换下一条线路。
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

    /**
     * Release 附件前缀：只认 `APP-t-*.apk` 形态的产物。
     */
    private const val APK_PREFIX = "APP-t-"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val CHECK_READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 20_000

    /**
     * 国内加速镜像前缀。用法：`前缀 + GitHub 完整 URL` 即为镜像地址。
     * 直连失败时按此顺序回退（谁先通就用谁）。
     */
    private data class Line(val name: String, val prefix: String)

    private val MIRRORS = listOf(
        Line("ghproxy", "https://mirror.ghproxy.com/"),
        Line("ghfast", "https://ghfast.top/"),
        Line("gh-proxy", "https://gh-proxy.com/"),
    )

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

    /**
     * 拉取 GitHub 最新 Release 并判断是否比当前版本新。
     * 直连失败会自动改走镜像线路（带时间戳防缓存）。
     */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        val apiLines = buildList {
            add(Line("GitHub 直连", LATEST_API))
            for (m in MIRRORS) {
                // ?t= 时间戳：避免个别镜像缓存了旧的 releases/latest 响应
                add(Line(m.name, "${m.prefix}$LATEST_API?t=${System.currentTimeMillis()}"))
            }
        }
        var lastError: String? = null
        for (line in apiLines) {
            val result = runCatching { fetchApi(line.prefix) }.getOrElse { e ->
                lastError = e.message ?: e.javaClass.simpleName
                EventLog.info(TAG, "检查线路失败（${line.name}）：$lastError")
                null
            }
            if (result != null) return@withContext result
        }
        CheckResult.Failed("所有检查线路均失败（最后错误：$lastError）")
    }

    /** 单条线路的检查请求。 */
    private fun fetchApi(urlText: String): CheckResult {
        val conn = URL(urlText).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = CHECK_READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "YINPAGE-LINK")
            val code = conn.responseCode
            if (code != 200) throw IOException("检查更新失败（HTTP $code）")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return parseLatest(text)
        } finally {
            conn.disconnect()
        }
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
     * 依次尝试 直连 → 国内镜像 各线路，任一条成功即返回；
     * 下载中断/失败时保留已下部分（断点续传），自动换下一条线路继续。
     * @param onProgress 已下载字节 / 总字节（总字节未知时为 0）
     * @throws IOException 所有线路都失败时，携带最后一条线路的错误
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, info.apkName)

        val lines = buildList {
            add(Line("GitHub 直连", info.apkUrl))
            for (m in MIRRORS) add(Line(m.name, m.prefix + info.apkUrl))
        }
        var lastError: String? = null
        for (line in lines) {
            // 本线路是否成功：异常为 null 说明下载完整
            val failure = runCatching {
                fetchToFile(line.prefix, target, info.apkSize, onProgress)
                null
            }.exceptionOrNull()
            if (failure == null) {
                EventLog.info(TAG, "下载完成（${line.name}）：${target.name}")
                return@withContext target
            }
            lastError = failure.message ?: failure.javaClass.simpleName
            // 下一线路继续：不清残片，fetchToFile 会按字节续传（各线路都是同一份 APK）
            EventLog.info(TAG, "线路失败（${line.name}）：$lastError → 换下一条")
        }
        throw IOException("所有 ${lines.size} 条下载线路均失败（最后错误：$lastError）")
    }

    /**
     * 单条线路的下载。若之前在同一文件留有未下完的残片（且小于目标体积），
     * 自动带 `Range` 头断点续传；服务器不支持续传时回退整包重下。
     */
    private fun fetchToFile(
        urlText: String,
        target: File,
        expectSize: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        // 期望体积已知且存在残片时，优先断点续传
        var partial = if (expectSize > 0 && target.exists() && target.length() in 1 until expectSize) {
            target.length()
        } else {
            if (target.exists()) target.delete()
            0L
        }

        val conn = URL(urlText).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            // identity：避免服务器对 APK 做 gzip 后 Content-Length 不可信
            conn.setRequestProperty("User-Agent", "YINPAGE-LINK")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (partial > 0) conn.setRequestProperty("Range", "bytes=$partial-")

            val code = conn.responseCode
            // 有残片且服务器响应 206 = 续传；响应 200 = 服务器忽略 Range，
            // 但响应体就是完整文件，直接从头写即可。
            if (code == 206 && partial > 0) {
                // 续传成功，继续往下
            } else if (code == 200) {
                if (target.exists()) target.delete()
                partial = 0L
            } else {
                throw IOException(code.toString())
            }
            val isResume = partial > 0

            val total = if (isResume) expectSize // 206 的 Content-Length 只剩余量
            else conn.contentLengthLong.takeIf { it > 0 } ?: expectSize

            val out = FileOutputStream(target, isResume)
            out.use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = if (isResume) partial else 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    // 完整性校验：期望体积已知时必须下载完整，否则不喂给安装器
                    if (expectSize > 0 && done < expectSize) {
                        throw IOException("下载不完整（$done/${expectSize}B）")
                    }
                }
            }
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