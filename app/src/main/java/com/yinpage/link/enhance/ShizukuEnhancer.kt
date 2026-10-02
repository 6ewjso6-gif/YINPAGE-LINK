package com.yinpage.link.enhance

import android.content.pm.PackageManager
import android.os.Build
import com.yinpage.link.core.EventLog
import com.yinpage.link.transport.TransportEnv
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ============================================================================
 *  Shizuku 可选增强（尽力而为，不是主链路）
 * ============================================================================
 *  ## 能力边界（必须说清楚，避免误期待）
 *   Shizuku 本质上是「免 root 拿到 shell(uid 2000) 权限」的通道，
 *   它**不能**：
 *    - 往 SystemUI / 系统蓝牙设置页注入耳机卡片或自定义控件
 *      （那是 LSPosed/Xposed 模块的能力边界，本 App 明确不做）；
 *    - 绕过 Android 的蓝牙 ACL / GATT 访问规则，也不能替 App 建立 SPP 连接；
 *    - 修改系统框架行为、Hook 任意进程。
 *   它**能**：
 *    - 以 shell 身份执行命令（例如 `dumpsys bluetooth_manager`），
 *      读取系统蓝牙管理器里更全的已连接/已配对设备信息，用于诊断与逆向取证。
 *
 *  ## 实现约束（任务硬性要求）
 *   - **不新增任何 Gradle 依赖**，因此这里全部用反射调用
 *     `rikka.shizuku.Shizuku` 的静态方法：pingBinder() / getVersion() /
 *     checkSelfPermission() / requestPermission(int) / newProcess(...)。
 *   - Shizuku 不存在、版本不匹配、反射失败、命令执行失败 —— 一律静默降级，
 *     绝不抛异常，绝不阻塞 UI。
 *
 *  ## 已知限制（诚实声明，需要 Lead 知晓）
 *   官方 Shizuku API 是编译期依赖（`dev.rikka.shizuku:api`）。本工程没有引入它，
 *   且普通 App 的 ClassLoader 里不会有 `rikka.shizuku.Shizuku`，
 *   因此**在真机上 [isAvailable] / [hasPermission] 会返回 false，
 *   [querySystemBluetoothInfo] 会返回 null**；[isSupported] 通过
 *   「是否安装了 Shizuku App（moe.shizuku.privileged.api）」判断，仍然可用。
 *   一旦将来允许加入该依赖（或提供一个自带的 API 桩类），本文件无需改动即可生效。
 * ============================================================================
 */
object ShizukuEnhancer {

    private const val TAG = "Shizuku"

    /** Shizuku API 入口类（编译期不存在，只能反射）。 */
    private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"

    /** Shizuku 管理器 App 的包名，用于判断「本机是否装了 Shizuku」。 */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val MAX_DUMP_CHARS = 200_000
    private const val READ_TIMEOUT_MS = 5_000L

    @Volatile
    private var resolvedClass: Class<*>? = null

    @Volatile
    private var classResolved = false

    @Volatile
    private var cachedVersion = -1

    // ------------------------------------------------------------------ 状态查询

    /** 本机是否装了 Shizuku（装了管理器 App 即视为支持）。 */
    fun isSupported(): Boolean {
        if (resolveClass() != null) return true
        return runCatching {
            val context = TransportEnv.context() ?: return false
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /** Shizuku 服务是否在运行（pingBinder 返回 true）。 */
    fun isAvailable(): Boolean {
        val clazz = resolveClass() ?: return false
        val alive = runCatching { invokeStaticNoArg(clazz, "pingBinder") as? Boolean ?: false }
            .getOrDefault(false)
        if (alive && cachedVersion < 0) {
            cachedVersion = runCatching { invokeStaticNoArg(clazz, "getVersion") as? Int ?: -1 }
                .getOrDefault(-1)
            EventLog.info(TAG, "Shizuku 服务可用，API 版本=$cachedVersion")
        }
        return alive
    }

    /** 本 App 是否已获得 Shizuku 授权。 */
    fun hasPermission(): Boolean {
        val clazz = resolveClass() ?: return false
        return runCatching {
            (invokeStaticNoArg(clazz, "checkSelfPermission") as? Int) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** 请求 Shizuku 授权（需要在有前台 Activity 时调用才会弹窗）。 */
    fun requestPermission(requestCode: Int) {
        val clazz = resolveClass() ?: return
        runCatching {
            val method = clazz.methods.firstOrNull {
                it.name == "requestPermission" &&
                    it.parameterCount == 1 &&
                    Modifier.isStatic(it.modifiers)
            } ?: return
            method.invoke(null, requestCode)
            EventLog.info(TAG, "已请求 Shizuku 授权，requestCode=$requestCode")
        }.onFailure { EventLog.info(TAG, "请求 Shizuku 授权失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 增强能力

    /**
     * 用 shell 权限执行 `dumpsys bluetooth_manager`，返回 stdout 文本。
     * 失败（未安装/未授权/方法不存在/超时）一律返回 null。
     */
    fun querySystemBluetoothInfo(): String? {
        if (!isAvailable()) return null
        val clazz = resolveClass() ?: return null
        return runCatching {
            val process = invokeNewProcess(
                clazz,
                arrayOf("sh", "-c", "dumpsys bluetooth_manager"),
            ) ?: return null
            readProcessOutput(process)
        }.getOrNull()
    }

    /**
     * 读某设备在系统蓝牙管理器里的电量（Android 14+ 公开 API
     * `BluetoothDevice.getBatteryLevel()`）。失败返回 null。
     *
     * 这里仍走反射：一是该方法在旧 SDK 上不存在，二是避免为一处调用
     * 提高 minSdk 约束。
     */
    fun systemBatteryLevel(address: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            val adapter = TransportEnv.adapter() ?: return null
            val device = adapter.getRemoteDevice(address) ?: return null
            val method = device.javaClass.getMethod("getBatteryLevel")
            val value = method.invoke(device) as? Int ?: return null
            value.takeIf { it in 0..100 }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 反射工具

    private fun resolveClass(): Class<*>? {
        if (classResolved) return resolvedClass
        synchronized(this) {
            if (classResolved) return resolvedClass
            resolvedClass = runCatching { Class.forName(SHIZUKU_CLASS) }.getOrNull()
            classResolved = true
            return resolvedClass
        }
    }

    private fun invokeStaticNoArg(clazz: Class<*>, name: String): Any? {
        val method = clazz.methods.firstOrNull {
            it.name == name && it.parameterCount == 0 && Modifier.isStatic(it.modifiers)
        } ?: return null
        return method.invoke(null)
    }

    /**
     * `Shizuku.newProcess(String[] cmd, String[] env, String dir)`。
     * 找不到方法时返回 null（不同 Shizuku 版本可能没有这个方法）。
     */
    private fun invokeNewProcess(clazz: Class<*>, command: Array<String>): Any? {
        val method: Method = clazz.methods.firstOrNull {
            it.name == "newProcess" && it.parameterCount == 3 && Modifier.isStatic(it.modifiers)
        } ?: return null
        return method.invoke(null, command, null, null)
    }

    /** 带看门狗地读取进程 stdout，避免 dumpsys 卡死拖住调用方。 */
    private fun readProcessOutput(process: Any): String? {
        val stream = runCatching {
            process.javaClass.getMethod("getInputStream").invoke(process) as? InputStream
        }.getOrNull() ?: return null

        val buffer = StringBuilder()
        val reader = Thread {
            runCatching {
                stream.bufferedReader().use { source ->
                    val chunk = CharArray(8192)
                    while (true) {
                        val read = source.read(chunk)
                        if (read < 0) break
                        synchronized(buffer) { buffer.append(chunk, 0, read) }
                        if (buffer.length >= MAX_DUMP_CHARS) break
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        runCatching { reader.join(READ_TIMEOUT_MS) }

        runCatching { process.javaClass.getMethod("destroy").invoke(process) }
        runCatching { process.javaClass.getMethod("waitFor").invoke(process) }

        val text = synchronized(buffer) { buffer.toString() }
        if (text.isBlank()) {
            EventLog.info(TAG, "dumpsys bluetooth_manager 无输出（可能未授权或命令不可用）")
            return null
        }
        EventLog.info(TAG, "已读取 dumpsys bluetooth_manager，共 ${text.length} 字符")
        return text
    }
}
