package com.yinpage.link.module.hook

import android.content.Context
import com.yinpage.link.module.ipc.ModuleIpc
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 *  作用域：com.android.systemui —— 状态栏耳机图标（`wireless_headset` 槽位）
 * ============================================================================
 *  问题：HyperOS 4 的 `CentralSurfacesImpl.start()` 会初始化并**隐藏**
 *  `wireless_headset` 这个状态栏图标槽位。第三方耳机（我们的 Feel 1 Pro）
 *  连接后系统不会自己把它点亮，图标因此永远不出现。
 *
 *  做法（与 HyperOriG 参考实现同路线，但状态源改走本项目的 IPC 快照）：
 *    1. `hookAfter(CentralSurfacesImpl.start())`：状态栏每次初始化/重建之后立刻补一次；
 *    2. 注册 [ModuleIpc] 状态接收器：之后每次耳机连接状态变化都同步；
 *    3. 图标控制全部走 `StatusBarManager` 的 @hide 反射：
 *         `setIcon("wireless_headset", "com.android.systemui", stat_sys_wireless_headset, 0, 描述)`
 *         `setIconVisibility("wireless_headset", connected)`
 *
 *  ⚠️ 红线：这段代码跑在 SystemUI 主线程上，任何未捕获异常都可能让状态栏消失。
 *  因此从 Hook 回调体到每一次反射调用全部包在 runCatching / 空值判断里，
 *  找不到符号只记日志、静默降级。
 *
 *  ⚠️ 槽位归属：`ModuleIpc` 的快照只描述**我们这一只耳机**。为避免误关系统
 *  或另一只小米耳机点亮的同一个槽位，只有当"图标是本模块点亮的"时才去熄灭它
 *  （见 [iconOwned]）；未连接且图标不是我们点亮的，就保持系统原状。
 * ============================================================================
 */
class SystemUIHeadsetIconHook : HookContext() {

    /** 状态接收器只注册一次（`start()` 在同进程里可能被多次调用）。 */
    private val receiverRegistered = AtomicBoolean(false)

    /** 降级警告只打一次，避免状态栏反复重建时刷屏。 */
    private val degradedWarned = AtomicBoolean(false)

    /** 缓存的 `StatusBarManager` 与图标资源 id（0 = 还没取到，下次重试）。 */
    @Volatile
    private var statusBarManager: Any? = null

    @Volatile
    private var iconResId: Int = 0

    /** 图标是否由本模块点亮 —— 只有点亮过的那一方才有资格熄灭。 */
    @Volatile
    private var iconOwned = false

    override fun onHook() {
        val start = findStartMethod()
        if (start == null) {
            ModuleLog.w(TAG, "未找到 CentralSurfacesImpl.start（本版本无法接管状态栏耳机图标），跳过")
            return
        }
        ModuleLog.i(TAG, "在 $packageName 安装状态栏耳机图标 Hook：${start.declaringClass.name}.start/${start.parameterTypes.size} 参")
        hookAfter(start) {
            // 已经回到 SystemUI 进程：异常绝不能冒泡，否则会连累状态栏
            runCatching { afterCentralSurfacesStarted(instance) }
                .onFailure { ModuleLog.w(TAG, "start() 后同步耳机图标失败：${it.message}") }
        }
    }

    // ------------------------------------------------------------------ Hook 点探测

    /**
     * `start()` 的候选签名依次探测：
     *   1. 无参 `start()`（OS4 现状）；
     *   2. `start(boolean)`（部分版本多一个标志位参数）；
     *   3. 按参数个数兜底（混淆改名后参数个数通常不变）。
     * 全部找不到返回 null，由调用方记日志静默返回。
     */
    private fun findStartMethod(): Method? {
        findMethodOrNull(CENTRAL_SURFACES_IMPL, "start")?.let {
            ModuleLog.d(TAG, "命中 start()（无参）")
            return it
        }
        findMethodOrNull(CENTRAL_SURFACES_IMPL, "start", Boolean::class.javaPrimitiveType!!)?.let {
            ModuleLog.d(TAG, "命中 start(boolean)")
            return it
        }
        for (count in 0..MAX_PARAM_PROBE) {
            findMethodByParamCount(CENTRAL_SURFACES_IMPL, "start", count)?.let {
                ModuleLog.d(TAG, "命中 start/$count 参（按参数个数兜底）")
                return it
            }
        }
        return null
    }

    // ------------------------------------------------------------------ start() 之后

    /** `start()` 返回后：注册状态接收器、按当前快照立即同步一次图标。 */
    private fun afterCentralSurfacesStarted(instance: Any?) {
        val context = appContext ?: contextFromInstance(instance)
        if (context == null) {
            ModuleLog.d(TAG, "拿不到 Context，本次跳过状态栏耳机图标接管")
            return
        }
        ensureStateReceiver(context)
        // 关键：SystemUI 重启后若耳机已经连着，必须立刻把被 start() 隐藏的图标补回来
        val connected = runCatching { ModuleIpc.snapshot().connected }.getOrDefault(false)
        syncIcon(context, connected)
        // 本进程的快照可能是"启动之前"的旧值（例如 SystemUI 崩溃重启时耳机已连着）：
        // 请求蓝牙进程重新查询一次，新状态会经上面的接收器再同步一遍图标。
        runCatching { ModuleIpc.broadcastRefresh(context) }
    }

    /** `appContext` 拿不到时的兜底：从 CentralSurfacesImpl 实例里挖 Context。 */
    private fun contextFromInstance(instance: Any?): Context? = runCatching {
        getObjectFieldAny(instance, "mContext", "mContextImpl", "context") as? Context
    }.getOrNull()

    /**
     * 注册耳机状态接收器（蓝牙进程 → SystemUI）。
     * 内部 [ModuleIpc.registerStateReceiver] 自带 try-catch，注册失败不影响宿主；
     * 这里再包一层，失败时允许下次 `start()` 重试。
     */
    private fun ensureStateReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        runCatching {
            ModuleIpc.registerStateReceiver(context) { snap ->
                runCatching { syncIcon(context, snap.connected) }
                    .onFailure { ModuleLog.w(TAG, "状态变化后同步耳机图标失败：${it.message}") }
            }
            ModuleLog.i(TAG, "耳机状态接收器已注册（${context.packageName}）")
        }.onFailure {
            receiverRegistered.set(false)
            ModuleLog.w(TAG, "注册耳机状态接收器失败：${it.message}")
        }
    }

    // ------------------------------------------------------------------ 图标控制

    /**
     * 把图标可见性对齐到耳机连接状态。
     * 只在"连接"和"图标是本模块点亮过"两种情况真正动手，避免抢别人的槽位。
     */
    private fun syncIcon(context: Context, connected: Boolean) {
        val manager = currentStatusBarManager(context)
        if (manager == null) {
            warnDegraded("拿不到 StatusBarManager")
            return
        }
        if (connected) {
            showIcon(manager, context)
            return
        }
        if (!iconOwned) {
            ModuleLog.d(TAG, "耳机未连接且图标非本模块点亮，保持系统原状")
            return
        }
        if (setSlotVisibility(manager, false)) {
            iconOwned = false
            ModuleLog.i(TAG, "状态栏耳机图标：隐藏（耳机已断开）")
        }
    }

    /**
     * 点亮图标。顺序很重要：先 `setIcon` 把图标项建出来，`setIconVisibility` 才会生效。
     * 资源 id 取不到（0）时**只**设置可见性，绝不拿 0 去调 `setIcon`。
     */
    private fun showIcon(manager: Any, context: Context) {
        val resId = iconResId.takeIf { it != 0 }
            ?: resolveIconResId(context).also { if (it != 0) iconResId = it }
        if (resId != 0) {
            if (!setSlotIcon(manager, resId)) {
                ModuleLog.d(TAG, "setIcon 未成功（$ICON_RES_NAME id=$resId）")
            }
        } else {
            ModuleLog.d(TAG, "SystemUI 里没有 $ICON_RES_NAME 资源，本次只设置可见性")
        }
        val wasOwned = iconOwned
        if (setSlotVisibility(manager, true)) {
            iconOwned = true
            if (!wasOwned) ModuleLog.i(TAG, "状态栏耳机图标：显示（耳机已连接）")
        } else {
            warnDegraded("setIconVisibility 调用失败")
        }
    }

    /** 取 `wireless_headset` 的 drawable id；取不到返回 0（调用方必须跳过 setIcon）。 */
    private fun resolveIconResId(context: Context): Int = runCatching {
        context.resources.getIdentifier(ICON_RES_NAME, "drawable", SYSTEMUI_PACKAGE)
    }.getOrDefault(0)

    /**
     * 取 `StatusBarManager`。
     * `android.app.StatusBarManager` 本体是公开类，但要调的方法全是 @hide，
     * 所以这里只把它当 `Any` 往下传，调用一律走反射。
     */
    private fun obtainStatusBarManager(context: Context): Any? {
        // 首选：公开 API 的字符串键，不需要编译期引用任何 @hide 成员
        runCatching { context.getSystemService(STATUS_BAR_SERVICE) }.getOrNull()?.let { return it }
        // 兜底：反射 android.app.StatusBarManager + Context.getSystemService(Class)
        return runCatching {
            val clazz = findClassOrNull(STATUS_BAR_MANAGER) ?: return@runCatching null
            Context::class.java.getMethod("getSystemService", Class::class.java).invoke(context, clazz)
        }.getOrNull()
    }

    /** 取一次并缓存 `StatusBarManager`（取不到不缓存，下次重试）。 */
    private fun currentStatusBarManager(context: Context): Any? {
        statusBarManager?.let { return it }
        val manager = obtainStatusBarManager(context) ?: return null
        statusBarManager = manager
        return manager
    }

    /**
     * `setIcon(slot, iconPackage, iconId, iconLevel, contentDescription)`（5 参 @hide 重载）。
     * 5 参重载不可用时退回 4 参重载（用调用方自己的包名解析资源，参考实现走的就是这条）。
     */
    private fun setSlotIcon(manager: Any, resId: Int): Boolean {
        if (invokeHidden(manager, "setIcon", SET_ICON_PARAMS, SLOT, SYSTEMUI_PACKAGE, resId, 0, DESCRIPTION)) {
            return true
        }
        return invokeHidden(manager, "setIcon", SET_ICON_LEGACY_PARAMS, SLOT, resId, 0, DESCRIPTION)
    }

    /** `setIconVisibility(slot, visible)`（2 参 @hide）。 */
    private fun setSlotVisibility(manager: Any, visible: Boolean): Boolean =
        invokeHidden(manager, "setIconVisibility", SET_ICON_VISIBILITY_PARAMS, SLOT, visible)

    /**
     * 反射调用 `StatusBarManager` 的 @hide 方法：全程 runCatching，绝不抛出。
     *
     * 解析顺序：
     *   1. [findMethodOrNull] 精确签名（[paramTypes]）；
     *   2. [findMethodByParamCount] 按"方法名 + 参数个数"（版本签名漂移时的兜底）；
     *   3. [callMethodOrNull] 沿运行时类的继承链再试一次（manager 是子类/代理时有用；
     *      该方法内部吞异常且返回 null，所以只作为最后尝试）。
     *
     * @return 是否解析到方法并调用成功（false 表示本版本没有这个 API 或调用被拒）
     */
    private fun invokeHidden(manager: Any, name: String, paramTypes: Array<Class<*>>, vararg args: Any?): Boolean {
        val method = findMethodOrNull(STATUS_BAR_MANAGER, name, *paramTypes)
            ?: findMethodByParamCount(STATUS_BAR_MANAGER, name, args.size)
        if (method == null) {
            runCatching { callMethodOrNull(manager, name, *args) }
            return false
        }
        return runCatching {
            method.invoke(manager, *args)
            true
        }.getOrElse { error ->
            ModuleLog.d(TAG, "$name 反射调用失败：${error.message}")
            // 精确签名存在但调用失败（例如隐藏 API 拦截）时，再按参数个数兜底一次
            runCatching { callMethodOrNull(manager, name, *args) }
            false
        }
    }

    /** 降级只警告一次，后续同一原因降到 debug。 */
    private fun warnDegraded(reason: String) {
        if (degradedWarned.compareAndSet(false, true)) {
            ModuleLog.w(TAG, "$reason：状态栏耳机图标接管降级（不影响状态栏本身）")
        } else {
            ModuleLog.d(TAG, reason)
        }
    }

    private companion object {
        const val TAG = "SysUI-Icon"

        /** 状态栏里的耳机图标槽位名（HyperOS 沿用 AOSP 命名）。 */
        const val SLOT = "wireless_headset"

        /** 槽位图标资源名（在 SystemUI 包里）。 */
        const val ICON_RES_NAME = "stat_sys_wireless_headset"

        /** 图标项与资源的归属包：图标由 SystemUI 自己绘制。 */
        const val SYSTEMUI_PACKAGE = "com.android.systemui"

        /** `Context.getSystemService` 的服务键（`Context.STATUS_BAR_SERVICE`）。 */
        const val STATUS_BAR_SERVICE = "statusbar"

        /** 图标的无障碍描述。 */
        const val DESCRIPTION = "耳机"

        const val CENTRAL_SURFACES_IMPL = "com.android.systemui.statusbar.phone.CentralSurfacesImpl"
        const val STATUS_BAR_MANAGER = "android.app.StatusBarManager"

        /** 按参数个数兜底探测的上限。 */
        const val MAX_PARAM_PROBE = 4

        /** `setIconVisibility(String slot, boolean visible)`。 */
        val SET_ICON_VISIBILITY_PARAMS: Array<Class<*>> = arrayOf(
            String::class.java,
            // javaPrimitiveType 的静态类型是 Class<Boolean>?，断言非空以满足 Array<Class<*>>
            Boolean::class.javaPrimitiveType!!,
        )

        /** `setIcon(String slot, String iconPackage, int iconId, int iconLevel, String desc)`。 */
        val SET_ICON_PARAMS: Array<Class<*>> = arrayOf(
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java,
        )

        /** `setIcon(String slot, int iconId, int iconLevel, String desc)`（旧重载，兜底用）。 */
        val SET_ICON_LEGACY_PARAMS: Array<Class<*>> = arrayOf(
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java,
        )
    }
}
