package com.yinpage.link.module.hook

import android.content.Context
import java.lang.reflect.Method

/**
 * ============================================================================
 *  作用域：com.android.systemui —— 设备中心卡片（P1）
 * ============================================================================
 *  HyperOS 4 的坑：设备中心与控制中心的代码**不在 SystemUI 主 ClassLoader 里**，
 *  而是由插件框架（`PluginInstance$PluginFactory`）为 `miui.systemui.plugin`
 *  单独创建 ClassLoader 后加载的。
 *
 *  所以这里必须先去拿插件 ClassLoader，再装插件里的 Hook：
 *   1. 优先拦 `PluginFactory.createPluginContext`（OS4 在插件回调之前就会创建插件
 *      Context，时机最早、最可靠）；
 *   2. 回退拦 `PluginInstance.loadPlugin`，用三级策略取 ClassLoader
 *      （pluginData.context → mPluginFactory.mClassLoaderFactory.get()）。
 *
 *  拿到 loader 后**只装两个点**（其余交给系统原生逻辑）：
 *   - `DeviceInfoWrapper.performClicked(Context)`：判断卡片类型，
 *     `third_headset` 时只记日志——**不启动 Activity、不 import 独立 App 的类**，
 *     跨模块启动会失败；
 *   - `MainPanelController.onCreate()`：缓存实例，仅供日志/调试，为将来扩展留口。
 *
 *  ⚠️ 所有 Hook 独立 try-catch；插件里找不到符号只记日志，绝不影响系统 UI 进程。
 * ============================================================================
 */
class SystemUIHook : HookContext() {

    /** 已初始化过的插件 ClassLoader（同一个 loader 只装一次）。 */
    private val initialized: MutableSet<ClassLoader> =
        java.util.Collections.synchronizedSet(mutableSetOf<ClassLoader>())

    /** 控制中心主面板实例（暂只用于日志/调试）。 */
    @Volatile
    private var mainPanelInstance: Any? = null

    override fun onHook() {
        ModuleLog.i(TAG, "开始在 $packageName 安装 SystemUI Hook（插件 ClassLoader 路线）")
        hookPluginFactory()
        hookLoadPlugin()
    }

    // ------------------------------------------------------------------ 插件 ClassLoader 获取

    /** 首选：OS4 在插件回调之前创建插件 Context 时立刻拿到 loader。 */
    private fun hookPluginFactory() {
        val method = findCreatePluginContextMethod()
        if (method == null) {
            ModuleLog.d(TAG, "未找到 createPluginContext（版本不同），改用 loadPlugin 回退")
            return
        }
        hookAfter(method) {
            val context = result as? Context ?: return@hookAfter
            if (context.packageName != PLUGIN_PACKAGE) return@hookAfter
            ModuleLog.i(TAG, "插件 Context 已创建：${context.packageName}")
            initialize(context.classLoader)
        }
    }

    /** 回退：`PluginInstance.loadPlugin` 之后按三级策略取插件 ClassLoader。 */
    private fun hookLoadPlugin() {
        val method = findAnyMethod(PLUGIN_INSTANCE_CLASS, "loadPlugin")
        if (method == null) {
            ModuleLog.d(TAG, "未找到 PluginInstance.loadPlugin，插件内 Hook 不可用")
            return
        }
        hookAfter(method) {
            runCatching {
                val pluginPackage = (
                    callMethodOrNull(instance, "getPackageName")
                        ?: callMethodOrNull(instance, "getPackage")
                    )?.toString()
                val loader = loaderFromPluginData(instance) ?: loaderFromClassLoaderFactory(instance)
                if (loader == null) {
                    ModuleLog.d(TAG, "loadPlugin($pluginPackage) 未取到插件 ClassLoader，放弃本次")
                    return@runCatching
                }
                ModuleLog.i(TAG, "loadPlugin($pluginPackage) → 插件 ClassLoader=${loader.javaClass.name}")
                initialize(loader)
            }.onFailure { ModuleLog.w(TAG, "loadPlugin 回退策略失败：${it.message}") }
        }
    }

    /** 一级：`pluginData.context.classLoader`。 */
    private fun loaderFromPluginData(instance: Any?): ClassLoader? = runCatching {
        val data = getObjectFieldAny(instance, "pluginData", "mPluginData") ?: return@runCatching null
        (getObjectFieldAny(data, "context", "mContext") as? Context)?.classLoader
    }.getOrNull()

    /** 二级：`mPluginFactory.mClassLoaderFactory.get()`。 */
    private fun loaderFromClassLoaderFactory(instance: Any?): ClassLoader? = runCatching {
        val factory = getObjectFieldAny(instance, "mPluginFactory", "pluginFactory")
            ?: return@runCatching null
        val classLoaderFactory = getObjectFieldAny(factory, "mClassLoaderFactory", "classLoaderFactory")
            ?: return@runCatching null
        callMethodOrNull(classLoaderFactory, "get") as? ClassLoader
    }.getOrNull()

    /** 同一个 ClassLoader 只初始化一次。 */
    private fun initialize(classLoader: ClassLoader?) {
        if (classLoader == null) return
        if (!initialized.add(classLoader)) {
            ModuleLog.d(TAG, "该插件 ClassLoader 已初始化过，跳过")
            return
        }
        ModuleLog.i(TAG, "初始化插件 Hook：loader=${classLoader.javaClass.name}")
        hookDeviceInfoWrapper(classLoader)
        hookMainPanelController(classLoader)
    }

    // ------------------------------------------------------------------ 插件内的两个 Hook 点

    /**
     * 设备中心卡片点击：只识别类型并记日志。
     * **故意不设置 result**——不拦截系统原生逻辑，也绝不启动 Activity
     * （跨模块启动独立 App 的 Activity 会失败）。
     */
    private fun hookDeviceInfoWrapper(classLoader: ClassLoader) {
        val clazz = loadClassOrNull(classLoader, DEVICE_INFO_WRAPPER)
        if (clazz == null) {
            ModuleLog.d(TAG, "插件里没有 $DEVICE_INFO_WRAPPER，跳过")
            return
        }
        val method = firstMethodOrNull(clazz, "performClicked", 1)
            ?: firstMethodOrNull(clazz, "performClicked", -1)
        if (method == null) {
            ModuleLog.d(TAG, "未找到 DeviceInfoWrapper.performClicked，跳过")
            return
        }
        hookBefore(method) {
            runCatching {
                val deviceInfo = callMethodOrNull(instance, "getDeviceInfo")
                val deviceType = callMethodOrNull(deviceInfo, "getDeviceType")?.toString()
                if (deviceType == THIRD_HEADSET) {
                    ModuleLog.i(TAG, "设备中心卡片被点击：third_headset（伪设备，交给系统原生逻辑）")
                } else {
                    ModuleLog.d(TAG, "设备中心卡片被点击：$deviceType")
                }
            }.onFailure { ModuleLog.w(TAG, "读取卡片类型失败：${it.message}") }
        }
    }

    /** 控制中心主面板：缓存实例，便于后续扩展（当前只用于日志）。 */
    private fun hookMainPanelController(classLoader: ClassLoader) {
        val clazz = loadClassOrNull(classLoader, MAIN_PANEL_CONTROLLER)
        if (clazz == null) {
            ModuleLog.d(TAG, "插件里没有 $MAIN_PANEL_CONTROLLER，跳过")
            return
        }
        val method = firstMethodOrNull(clazz, "onCreate", 0) ?: firstMethodOrNull(clazz, "onCreate", -1)
        if (method == null) {
            ModuleLog.d(TAG, "未找到 MainPanelController.onCreate，跳过")
            return
        }
        hookAfter(method) {
            runCatching {
                mainPanelInstance = instance
                ModuleLog.i(TAG, "MainPanelController 已就绪：${mainPanelInstance?.javaClass?.name}")
            }.onFailure { ModuleLog.w(TAG, "缓存 MainPanelController 失败：${it.message}") }
        }
    }

    // ------------------------------------------------------------------ 插件反射工具

    /**
     * 插件类必须用**插件自己的 ClassLoader** 加载：
     * [HookContext.findClassOrNull] 固定在宿主 ClassLoader 上查找，
     * 拿不到 `miui.systemui.*`，因此这里单独加载一次（找不到返回 null）。
     */
    private fun loadClassOrNull(classLoader: ClassLoader, className: String): Class<*>? =
        runCatching { Class.forName(className, false, classLoader) }.getOrNull()

    /** 取第一个同名的可访问方法；`paramCount < 0` 表示不限参数个数。 */
    private fun firstMethodOrNull(clazz: Class<*>, name: String, paramCount: Int): Method? {
        val method = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == name && (paramCount < 0 || it.parameterTypes.size == paramCount) && !it.isSynthetic
            }
        }.getOrNull() ?: return null
        runCatching { method.isAccessible = true }
        return method
    }

    /** 在宿主 ClassLoader 上按名字找方法：先 0 参，再逐个参数个数探测。 */
    private fun findAnyMethod(className: String, name: String): Method? {
        findMethodOrNull(className, name)?.let { return it }
        for (count in 1..MAX_PARAM_PROBE) {
            findMethodByParamCount(className, name, count)?.let { return it }
        }
        return null
    }

    /** `createPluginContext` 的类名在不同 OS 版本可能挂在内/外部类上，逐个候选查。 */
    private fun findCreatePluginContextMethod(): Method? {
        for (className in PLUGIN_FACTORY_CLASSES) {
            findAnyMethod(className, "createPluginContext")?.let {
                ModuleLog.d(TAG, "命中 $className.createPluginContext")
                return it
            }
        }
        return null
    }

    private companion object {
        const val TAG = "SysUI"

        /** 设备中心/控制中心插件包名。 */
        const val PLUGIN_PACKAGE = "miui.systemui.plugin"

        val PLUGIN_FACTORY_CLASSES = listOf(
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory",
            "com.android.systemui.shared.plugins.PluginInstance",
        )
        const val PLUGIN_INSTANCE_CLASS = "com.android.systemui.shared.plugins.PluginInstance"

        const val DEVICE_INFO_WRAPPER = "miui.systemui.devicecenter.devices.DeviceInfoWrapper"
        const val MAIN_PANEL_CONTROLLER = "miui.systemui.controlcenter.panel.main.MainPanelController"

        /** 卡片类型标记：第三方耳机。 */
        const val THIRD_HEADSET = "third_headset"

        /** 参数个数探测上限。 */
        const val MAX_PARAM_PROBE = 4
    }
}
