package com.yinpage.link.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import java.security.MessageDigest

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
 *
 *  🔒 完整性防线（issue #6 重做）：
 *    1. **证书指纹白名单**：安装前用 `getPackageArchiveInfo` 取下载包签名，
 *       必须等于内置的官方发布证书 SHA-256（[TRUSTED_SIGNER_SHA256]）。
 *       不再用「与本机签名一致」做判据 —— 早期分发的 debug 包签名密钥公开可查，
 *       "签名一致"对它们等于没有校验；
 *    2. **SHA-256 附件必填**：Release 必须附 `<apk>.sha256`，缺失时直接拒绝检查/安装，
 *       不再"取不到就跳过"；
 *    3. **元数据不信任镜像**：镜像只当传输通道。`tag_name` / 附件名都要过严格白名单，
 *       下载地址由本地按 `https://github.com/<repo>/releases/download/<tag>/<name>`
 *       重新拼装，镜像无法把下载指向任意主机；
 *    4. **识别非官方构建**：若当前安装包不是官方证书签名（如 debug 包），
 *       直接禁用应用内更新并提示重新安装官方版本。
 * ============================================================================
 */
object UpdateManager {

    private const val TAG = "更新"

    /** 发布仓库：main 分支的 Release 才是面向用户的版本。 */
    private const val REPO = "6ewjso6-gif/YINPAGE-LINK"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    /**
     * 官方发布证书 SHA-256（DER 编码，小写十六进制）。
     * 取自 `APP-t-v0.17-signed.apk`：`CN=YINPAGE-LINK, O=YINPAGE-LINK, C=CN`。
     * 这是更新链路唯一的信任锚点，轮换发布密钥时必须同步修改此处。
     */
    private const val TRUSTED_SIGNER_SHA256 =
        "c2a08c607f0164bcb28cf57cba7571410d358870eafa570a34291af2c1bb2571"

    /**
     * 仓库历史 debug keystore 的证书指纹（`CN=Android Debug`）。
     * 该私钥曾随仓库公开，仅用于识别「不应支持自助更新」的旧构建。
     */
    private const val LEAKED_DEBUG_SHA256 =
        "84cf2da177fb58e90bca950846d668a02170527e37db9b62431c743f480d6ee5"

    /** Release tag 白名单：只接受 `APP-t-v1.2.3` 形态，杜绝任意字符串拼进 URL。 */
    private val TAG_PATTERN = Regex("^APP-t-v\\d+(?:\\.\\d+)*$")

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val CHECK_READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 20_000

    /**
     * 国内加速镜像前缀。用法：`前缀 + GitHub 完整 URL` 即为镜像地址。
     * 直连失败时按此顺序回退（谁先通就用谁）。
     *
     * ⚠️ 镜像站返回的内容完全由第三方决定，因此它们**只承担传输**：
     * 元数据要过白名单、下载地址由本地重新拼装，安装前还要过证书指纹白名单校验。
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
        /** 可选的 SHA-256 附件直链（Release 未附带时为空串）。 */
        val apkSha256Url: String = "",
        /** SHA-256 附件名，仅用于日志。 */
        val apkSha256Name: String = "",
    )

    sealed interface CheckResult {
        /** 有新版本，附带下载所需信息。 */
        data class Available(val info: ReleaseInfo) : CheckResult

        /** 当前版本已经是最新。 */
        data object UpToDate : CheckResult

        /** 检查失败（网络 / 解析 / 无 APK）。 */
        data class Failed(val message: String) : CheckResult

        /** 当前安装包不是官方签名（debug / 第三方重签名），不支持应用内更新。 */
        data object UnofficialBuild : CheckResult
    }

    /**
     * 安装阶段结果。
     *
     * 为什么不再返回 Boolean：调用方只能提示"失败"，用户无法判断是
     * 「没给安装未知应用权限」还是「签名不一致」，排查成本极高。
     */
    sealed interface InstallResult {
        /** 安装会话已提交 / 安装界面已打开。 */
        data object Launched : InstallResult

        /** 下载的 APK 文件不存在。 */
        data object FileMissing : InstallResult

        /** 安装包签名与本机不一致（无法覆盖安装）。 */
        data object SignatureMismatch : InstallResult

        /** 当前安装包不是官方签名版本，无法自助更新到官方包。 */
        data object NotOfficialBuild : InstallResult

        /** 未授予「安装未知应用」权限，需要引导用户去设置页。 */
        data object UnknownSourcesRequired : InstallResult

        /** 其它失败，[message] 为技术原因。 */
        data class Failed(val message: String) : InstallResult
    }

    // ------------------------------------------------------------------ 检查

    /**
     * 拉取 GitHub 最新 Release 并判断是否比当前版本新。
     * 直连失败会自动改走镜像线路（带时间戳防缓存）。
     */
    suspend fun checkForUpdate(context: Context): CheckResult = withContext(Dispatchers.IO) {
        // issue #6-4：debug / 第三方重签名的安装包无法验证更新来源，直接禁用。
        if (!isOfficialBuild(context)) {
            val legacyDebug = installedSigners(context).contains(LEAKED_DEBUG_SHA256)
            EventLog.info(
                TAG,
                if (legacyDebug) "当前为历史 debug 签名版本（密钥已公开），已禁用应用内更新，请安装官方签名版本"
                else "当前安装包不是官方签名，已禁用应用内更新",
            )
            return@withContext CheckResult.UnofficialBuild
        }
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

    /**
     * 解析 Release JSON。
     *
     * issue #6-3：**不再使用响应里的 `browser_download_url`**。
     * 直连和镜像返回的是同一份 JSON，但镜像那份额外可被篡改；因此这里只取
     * `tag_name` 与附件**名字**，并通过白名单校验后用本地规则拼出官方下载地址。
     * 攻击者即使控制镜像，也无法把下载指向任意主机或伪造 tag。
     */
    private fun parseLatest(json: String): CheckResult {
        val root = JSONObject(json)
        val tag = root.optString("tag_name", "").trim()
        if (!TAG_PATTERN.matches(tag)) {
            return CheckResult.Failed("Release 版本号格式异常，已拒绝更新：$tag")
        }
        val published = root.optString("published_at", "")
        val body = root.optString("body", "")
        val assets = root.optJSONArray("assets") ?: return CheckResult.Failed("Release 中没有可下载的文件")

        // 附件名必须与 tag 严格对应，避免"名字像 APK 但内容/主机由响应决定"。
        val expectedApkNames = setOf("$tag-signed.apk", "$tag.apk")
        var apkName = ""
        var apkSize = 0L
        var hasChecksum = false
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            when {
                apkName.isBlank() && name in expectedApkNames -> {
                    apkName = name
                    apkSize = asset.optLong("size", 0L)
                }
                name == "$apkName.sha256" && apkName.isNotBlank() -> hasChecksum = true
                expectedApkNames.any { name == "$it.sha256" } -> hasChecksum = true
            }
        }
        if (apkName.isBlank()) {
            return CheckResult.Failed("Release 中没有找到与 $tag 匹配的 APK 安装包")
        }
        if (!hasChecksum) {
            return CheckResult.Failed("Release 未提供 SHA-256 校验文件，已拒绝更新（请等待发布方补齐）")
        }

        val base = "https://github.com/$REPO/releases/download/$tag"
        val info = ReleaseInfo(
            tagName = tag,
            publishedAt = published,
            body = body,
            apkUrl = "$base/$apkName",
            apkName = apkName,
            apkSize = apkSize,
            apkSha256Url = "$base/$apkName.sha256",
            apkSha256Name = "$apkName.sha256",
        )
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
     * @throws IOException 所有线路都失败，或完整性校验（SHA-256）不通过
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, info.apkName)
        // N11：清理上一次下载遗留的其它 APK / 残片，避免 cache 无限膨胀
        cleanupStaleFiles(dir, target)

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
                // N1：SHA-256 校验（Release 提供 .sha256 时才做；不通过会删除并抛异常）
                verifyDownloaded(info, target)
                // 下载完成，续传校验文件不再需要
                runCatching { File(dir, "${target.name}.etag").delete() }
                return@withContext target
            }
            lastError = failure.message ?: failure.javaClass.simpleName
            // 下一线路继续：不清残片，fetchToFile 会按字节续传（各线路都是同一份 APK）
            EventLog.info(TAG, "线路失败（${line.name}）：$lastError → 换下一条")
        }
        throw IOException("所有 ${lines.size} 条下载线路均失败（最后错误：$lastError）")
    }

    /** 清理 updates/ 下与本次目标无关的历史下载（旧版本 APK、其它残片）。 */
    private fun cleanupStaleFiles(dir: File, keep: File) {
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.name == keep.name || file.name == "${keep.name}.etag") return@forEach
                if (file.delete()) EventLog.debug(TAG, "已清理旧下载文件：${file.name}")
            }
        }
    }

    /**
     * 单条线路的下载。若之前在同一文件留有未下完的残片（且小于目标体积），
     * 自动带 `Range` 头断点续传；服务器不支持续传时回退整包重下。
     *
     * 续传安全（N8）：同时带上 `If-Range`（上一次的 ETag / Last-Modified）。
     * 若资源已变（换镜像续传、发布被覆盖），服务器会返回 200 整包，
     * 从而避免「两段不同版本的数据拼成一个体积正确但内容损坏的文件」。
     */
    private fun fetchToFile(
        urlText: String,
        target: File,
        expectSize: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val metaFile = File(target.parentFile, "${target.name}.etag")

        // 期望体积已知且存在残片时，优先断点续传
        var partial = if (expectSize > 0 && target.exists() && target.length() in 1 until expectSize) {
            target.length()
        } else {
            if (target.exists()) target.delete()
            metaFile.delete()
            0L
        }

        val conn = URL(urlText).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            // identity：避免服务器对 APK 做 gzip 后 Content-Length 不可信
            conn.setRequestProperty("User-Agent", "YINPAGE-LINK")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (partial > 0) {
                conn.setRequestProperty("Range", "bytes=$partial-")
                readValidator(metaFile)?.let { conn.setRequestProperty("If-Range", it) }
            }

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

            // 记录本资源的校验器，供后续线路续传时用 If-Range 判定资源是否一致
            val validator = conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified")
            if (!validator.isNullOrBlank()) {
                runCatching { metaFile.writeText(validator) }
            }

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

    private fun readValidator(file: File): String? = runCatching {
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ------------------------------------------------------------------ 完整性校验

    /**
     * 下载 SHA-256 附件并比对；任何一步失败都抛异常（fail-closed）。
     * issue #6-3：以前"取不到就跳过"，等于把完整性校验变成可选项。
     */
    private fun verifyDownloaded(info: ReleaseInfo, apk: File) {
        if (info.apkSha256Url.isBlank()) {
            runCatching { apk.delete() }
            throw IOException("Release 未提供 SHA-256 校验文件，已拒绝安装")
        }
        val expected = fetchSha256(info)
            ?: run {
                runCatching { apk.delete() }
                throw IOException("无法获取 SHA-256 校验文件，已拒绝安装")
            }
        val actual = fileSha256(apk)
        if (!expected.equals(actual, ignoreCase = true)) {
            runCatching { apk.delete() }
            throw IOException("安装包 SHA-256 校验失败（疑似被镜像/中间人替换），已删除并拒绝安装")
        }
        EventLog.info(TAG, "SHA-256 校验通过")
    }

    /**
     * 下载 `<apk>.sha256` 附件并解析出 64 位十六进制哈希（全部线路失败返回 null）。
     *
     * 允许镜像，是因为信任锚点已经变成[TRUSTED_SIGNER_SHA256]：
     * 敌手即使同时替换 APK 与哈希，也无法伪造官方证书签名，安装阶段仍会拒绝。
     * 哈希在这里只承担"传输完整性/损坏检测"，不承担来源认证。
     */
    private fun fetchSha256(info: ReleaseInfo): String? {
        val lines = buildList {
            add(Line("GitHub 直连", info.apkSha256Url))
            for (m in MIRRORS) add(Line(m.name, m.prefix + info.apkSha256Url))
        }
        for (line in lines) {
            val text = runCatching { fetchText(line.prefix) }.getOrElse { e ->
                EventLog.info(TAG, "SHA-256 获取失败（${line.name}）：${e.message}")
                null
            } ?: continue
            Regex("[0-9a-fA-F]{64}").find(text)?.value?.let { return it.lowercase() }
        }
        return null
    }

    private fun fetchText(urlText: String): String {
        val conn = URL(urlText).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = CHECK_READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "YINPAGE-LINK")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fileSha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 取一个已安装/归档包的签名指纹集合（排序后便于比较）。 */
    private fun signerFingerprints(info: PackageInfo?): List<String> {
        if (info == null) return emptyList()
        val signers: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signers.map { sha256Hex(it.toByteArray()) }.sorted()
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** 当前安装包是否由官方发布证书签名（issue #6 的信任锚点）。 */
    private fun isOfficialBuild(context: Context): Boolean =
        installedSigners(context.applicationContext).contains(TRUSTED_SIGNER_SHA256)

    /** 本机已安装版本的签名指纹。 */
    private fun installedSigners(context: Context): List<String> = runCatching {
        signerFingerprints(
            context.packageManager.getPackageInfo(context.packageName, signatureFlags()),
        )
    }.getOrDefault(emptyList())

    /** 下载到的 APK 归档的签名指纹。 */
    private fun archiveSigners(context: Context, apk: File): List<String> = runCatching {
        signerFingerprints(
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags()),
        )
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ 安装

    /**
     * 安装前是否已允许「安装未知应用」。Android 8+ 未授权时 PackageInstaller
     * 会**静默失败**，用户只看到"没反应"，因此必须先检查并引导（N10）。
     */
    fun canInstallPackages(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    } else {
        true
    }

    /** 「安装未知应用」授权页（用于引导用户）。 */
    fun installPermissionIntent(context: Context): Intent? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runCatching null
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    /**
     * 调起系统安装器。会依次校验：
     *   1. 文件存在；
     *   2. **签名与本应用一致**（不同签名无法覆盖安装，也拦截了镜像替换的恶意包）；
     *   3. 已允许「安装未知应用」。
     *
     * v0.15：优先使用 [PackageInstaller] 会话，而不是 `ACTION_VIEW + content://`。
     * 原因：HyperOS / MIUI 等 OEM 对 `ACTION_VIEW` 打开 APK 有额外拦截，
     * 常见表现就是"点了下载完成，但安装界面根本不出现"（且不会抛异常）；
     * 会话安装由系统弹出统一的确认框，行为稳定，还能通过
     * [InstallResultReceiver] 拿到失败原因。
     * 会话创建失败（极少数老 ROM）才退回 `ACTION_VIEW`。
     */
    fun install(context: Context, apk: File): InstallResult {
        val appContext = context.applicationContext
        if (!apk.exists()) {
            EventLog.info(TAG, "安装失败：APK 不存在 ${apk.absolutePath}")
            return InstallResult.FileMissing
        }
        // issue #6-3：判据从"与本机签名一致"改为"等于内置官方证书"。
        // 本机不是官方包时（debug / 第三方重签名），即使下载包签名与本机相同也不能装。
        if (!isOfficialBuild(appContext)) {
            EventLog.info(TAG, "拒绝安装：当前不是官方签名版本，无法覆盖更新")
            return InstallResult.NotOfficialBuild
        }
        val downloaded = archiveSigners(appContext, apk)
        if (downloaded != listOf(TRUSTED_SIGNER_SHA256)) {
            EventLog.info(
                TAG,
                "拒绝安装：更新包签名不在官方证书白名单（包签名=${downloaded.firstOrNull() ?: "无"}）——疑似镜像/中间人替换",
            )
            return InstallResult.SignatureMismatch
        }
        if (!canInstallPackages(appContext)) {
            EventLog.info(TAG, "未授权「安装未知应用」，需引导用户去系统设置开启")
            return InstallResult.UnknownSourcesRequired
        }

        val viaSession = runCatching { launchSession(appContext, apk) }
            .onFailure { EventLog.info(TAG, "安装会话创建失败，改用 ACTION_VIEW：${it.message}") }
            .getOrDefault(false)
        if (viaSession) {
            EventLog.info(TAG, "已提交安装会话：${apk.name}")
            return InstallResult.Launched
        }
        return launchViewIntent(appContext, apk)
    }

    /**
     * 通过 PackageInstaller 会话安装：把 APK 流式写入会话后 commit，
     * 系统会弹出安装确认框，结果回调到 [InstallResultReceiver]。
     */
    private fun launchSession(context: Context, apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, InstallResultReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
        return true
    }

    /** 兜底：`ACTION_VIEW` 打开 APK（并显式把读权限授予实际处理它的安装器）。 */
    private fun launchViewIntent(context: Context, apk: File): InstallResult = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved == null) {
            EventLog.info(TAG, "系统没有可处理 APK 安装的 Activity")
            return InstallResult.Failed("系统没有可用的安装器")
        }
        // 部分 OEM 的安装器只认「显式授予的 URI 权限」，仅靠 Intent flag 会读不到文件
        context.grantUriPermission(
            resolved.activityInfo.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        context.startActivity(intent)
        EventLog.info(TAG, "已调起系统安装器：${apk.name}")
        InstallResult.Launched
    }.getOrElse {
        EventLog.info(TAG, "安装失败：${it.message}")
        InstallResult.Failed(it.message ?: it.javaClass.simpleName)
    }
}