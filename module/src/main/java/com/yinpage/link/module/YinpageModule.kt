package com.yinpage.link.module

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import com.yinpage.link.module.hook.BluetoothUpstreamHeadsetHook
import com.yinpage.link.module.hook.HookContext
import com.yinpage.link.module.hook.MiLinkServiceHook
import com.yinpage.link.module.hook.ModuleLog
import com.yinpage.link.module.hook.SettingsHeadsetHook
import com.yinpage.link.module.hook.SystemUIHeadsetIconHook
import com.yinpage.link.module.hook.SystemUIHook
import com.yinpage.link.protocol.PodLog

/**
 * ============================================================================
 *  YINPAGE-LINK 模块入口
 * ============================================================================
 *  LSPosed 会在**每个被勾选作用域的进程**里创建本类实例，并根据包名分派 Hook。
 *  入口类名写在 `module/src/main/resources/META-INF/xposed/java_init.list`。
 *
 *  作用域（见 scope.list）：
 *    com.android.bluetooth   ← 核心：伪装受支持设备 + SPP 通信 + 状态推送
 *    com.milink.service      ← 设备中心 / 电量 / ANC 状态
 *    com.android.settings    ← 蓝牙设置页高级耳机界面
 *    com.android.systemui    ← 设备中心卡片
 *    com.xiaomi.bluetooth    ← 连接通知 / 灵动岛
 *
 *  ⚠️ 使用 libxposed API 102 的现代接口（onPackageReady），
 *  与 HyperOriG 的选型一致；不调用 legacy Xposed API。
 * ============================================================================
 */
class YinpageModule : XposedModule() {

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        runCatching {
            if (!param.isFirstPackage) return

            val pkg = param.packageName
            val loader = param.classLoader

            // 1) 注入日志实现：让共享的协议层能把日志打到 logcat
            PodLog.default = object : PodLog {
                override fun debug(tag: String, message: String) = ModuleLog.d(tag, message)
                override fun info(tag: String, message: String) = ModuleLog.i(tag, message)
            }

            // 2) 安装远程配置：ModuleActivity 写入后各进程都能读到
            runCatching {
                val prefs = getRemotePreferences(ModuleConfigStore.PREFS_NAME)
                ModuleConfigStore.install(prefs)
            }.onFailure { ModuleLog.w(TAG, "远程配置不可用，使用默认值：${it.message}") }

            ModuleLog.i(TAG, "模块加载：pkg=$pkg api=${getApiVersion()}")

            // 3) 按包名分派。
            //    同一进程可能有多个 Hook，必须一次装完——install() 按包名去重，
            //    分两次调用第二次会被跳过。
            when (pkg) {
                "com.android.bluetooth" ->
                    install(setOf(BluetoothUpstreamHeadsetHook()), loader, pkg)
                "com.milink.service" ->
                    install(setOf(MiLinkServiceHook()), loader, pkg)
                "com.android.settings" ->
                    install(setOf(SettingsHeadsetHook()), loader, pkg)
                "com.android.systemui" ->
                    install(setOf(SystemUIHook(), SystemUIHeadsetIconHook()), loader, pkg)
                // xiaomi.bluetooth 负责连接通知与灵动岛，该进程没有 MiLink 的类，
                // 因此用只做灵动岛接管的轻量上下文
                "com.xiaomi.bluetooth" ->
                    install(setOf(FocusIslandHook()), loader, pkg)
                else -> ModuleLog.d(TAG, "非目标包，跳过：$pkg")
            }
        }.onFailure {
            // 入口出问题绝不能让宿主进程崩
            runCatching { Log.e(TAG, "模块初始化失败", it) }
        }
    }

    /**
     * 兼容旧版框架（API 101）：部分环境只回调 onPackageLoaded。
     * 与 onPackageReady 的区别是 ClassLoader 字段名不同（defaultClassLoader）。
     */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        runCatching {
            if (!param.isFirstPackage) return
            val pkg = param.packageName
            if (pkg !in TARGET_PACKAGES) return
            // 若 onPackageReady 已经装过，这里会因 initialized 集合而去重
            if (initialized.contains(pkg)) return
            val loader = runCatching { param.defaultClassLoader }.getOrNull() ?: return
            ModuleLog.i(TAG, "通过 onPackageLoaded 回退安装：$pkg")
            when (pkg) {
                "com.android.bluetooth" -> install(setOf(BluetoothUpstreamHeadsetHook()), loader, pkg)
                "com.milink.service" -> install(setOf(MiLinkServiceHook()), loader, pkg)
                "com.xiaomi.bluetooth" -> install(setOf(FocusIslandHook()), loader, pkg)
                "com.android.settings" -> install(setOf(SettingsHeadsetHook()), loader, pkg)
                "com.android.systemui" ->
                    install(setOf(SystemUIHook(), SystemUIHeadsetIconHook()), loader, pkg)
            }
        }.onFailure { runCatching { Log.e(TAG, "onPackageLoaded 回退失败", it) } }
    }

    /** 已安装的包，避免两个回调重复安装。 */
    private val initialized = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 为一个包安装一个或多个 Hook。
     *
     * 关键点：**同一进程内的多个 Hook 必须在这一层合并成一次安装**。
     * 早期版本按包名去重后只接受单个 Hook，导致 `com.android.systemui`
     * 只能装 `SystemUIHook`、`SystemUIHeadsetIconHook` 被静默跳过。
     * 现在每个 Hook 各自持有独立的 [HookContext] 实例，互不干扰。
     */
    private fun install(hooks: Set<HookContext>, classLoader: ClassLoader, pkg: String) {
        if (!initialized.add(pkg)) {
            ModuleLog.d(TAG, "$pkg 已安装过，跳过")
            return
        }
        var okCount = 0
        for (hook in hooks) {
            runCatching {
                hook.module = this
                hook.appClassLoader = classLoader
                hook.packageName = pkg
                hook.onHook()
                okCount++
            }.onFailure {
                ModuleLog.e(TAG, "$pkg 安装 ${hook.javaClass.simpleName} 失败：${it.message}", it)
            }
        }
        ModuleLog.i(TAG, "$pkg 安装完成：$okCount/${hooks.size} 个 Hook 生效")
        // 一个都没装上 → 允许后续回退路径重试（例如 onPackageLoaded）
        if (okCount == 0) initialized.remove(pkg)
    }

    private companion object {
        const val TAG = "YINPAGE-LINK"

        val TARGET_PACKAGES = setOf(
            "com.android.bluetooth",
            "com.milink.service",
            "com.android.settings",
            "com.android.systemui",
            "com.xiaomi.bluetooth",
        )
    }
}

/**
 * `com.xiaomi.bluetooth` 进程的轻量 Hook：只接管连接通知与灵动岛。
 *
 * 该进程里**没有** MiLink 的任何类（`com.miui.headset.runtime.*` 属于 device 互联），
 * 因此不能复用 [MiLinkServiceHook]，否则会做一堆无用的类查找。
 */
private class FocusIslandHook : HookContext() {
    override fun onHook() {
        ModuleLog.i(TAG, "开始在 $packageName 安装灵动岛 Hook")
        runCatching { com.yinpage.link.module.hook.FocusIslandPatcher.install(this) }
            .onFailure { ModuleLog.w(TAG, "灵动岛 Hook 安装失败：${it.message}") }
    }

    private companion object {
        const val TAG = "xiaomi.bt"
    }
}
