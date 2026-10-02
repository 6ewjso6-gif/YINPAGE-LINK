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

            // 3) 按包名分派
            when (pkg) {
                "com.android.bluetooth" -> install(BluetoothUpstreamHeadsetHook(), loader, pkg)
                "com.milink.service" -> install(MiLinkServiceHook(), loader, pkg)
                "com.android.settings" -> install(SettingsHeadsetHook(), loader, pkg)
                "com.android.systemui" -> install(SystemUIHook(), loader, pkg)
                "com.xiaomi.bluetooth" -> install(MiLinkServiceHook(), loader, pkg)
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
                "com.android.bluetooth" -> install(BluetoothUpstreamHeadsetHook(), loader, pkg)
                "com.milink.service", "com.xiaomi.bluetooth" -> install(MiLinkServiceHook(), loader, pkg)
                "com.android.settings" -> install(SettingsHeadsetHook(), loader, pkg)
                "com.android.systemui" -> install(SystemUIHook(), loader, pkg)
            }
        }.onFailure { runCatching { Log.e(TAG, "onPackageLoaded 回退失败", it) } }
    }

    /** 已安装的包，避免两个回调重复安装。 */
    private val initialized = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 实例化 Hook 上下文并执行安装，全程 try-catch。 */
    private fun install(hook: HookContext, classLoader: ClassLoader, pkg: String) {
        if (!initialized.add(pkg)) {
            ModuleLog.d(TAG, "$pkg 已安装过，跳过")
            return
        }
        runCatching {
            hook.module = this
            hook.appClassLoader = classLoader
            hook.packageName = pkg
            hook.onHook()
        }.onFailure {
            ModuleLog.e(TAG, "$pkg 安装 Hook 失败：${it.message}", it)
            // 安装失败允许后续回退路径重试
            initialized.remove(pkg)
        }
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
