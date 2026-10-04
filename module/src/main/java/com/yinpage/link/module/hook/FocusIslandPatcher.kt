package com.yinpage.link.module.hook

import android.content.Context
import android.os.Bundle
import com.yinpage.link.module.ModuleConfigStore
import com.yinpage.link.module.ipc.ModuleIpc
import org.json.JSONObject

/**
 * ============================================================================
 *  灵动岛 / 焦点通知同步
 * ============================================================================
 *  系统本来就会为"耳机佩戴"生成一条灵动岛通知，入口是
 *  `com.android.bluetooth.ble.app.MiuiBluetoothNotification.invokeStatusBar(Context, String, Bundle)`，
 *  其中 bundle 的识别键是 `notifyId == "headset_wear_notification"`，
 *  岛内容 JSON 放在 `param` 与 `island_param` 两个 key 里。（均已确证）
 *
 *  **本实现不引入任何第三方通知库**：`com.xzakota.hyper.notification:focus-api`
 *  的 Kotlin metadata 是 2.2.0，与本项目的 Kotlin 2.0.21 不兼容（编译期报错）。
 *  因此改为**接管系统自带的这条通知**：
 *
 *    - 关闭"灵动岛状态" → 吞掉系统通知（`result = null`）
 *    - 开启（默认）     → 把左右耳文本改写成本项目读到的真实电量
 *
 *  优点：零新增依赖、跟随系统主题与动效、不受第三方库版本漂移影响。
 *  代价：无法自定义岛的图标与布局（那需要自建通知，见 doc/FUSION-CENTER.md §7.1）。
 *
 *  ⚠️ 这个类同时被 `com.android.bluetooth` 与 `com.xiaomi.bluetooth` 两个进程使用，
 *  因此做成"可复用安装器"而不是 HookContext 子类。
 * ============================================================================
 */
object FocusIslandPatcher {

    private const val TAG = "Island"

    /** 系统耳机佩戴灵动岛的 notifyId（已确证）。 */
    const val HEADSET_WEAR_NOTIFY_ID = "headset_wear_notification"

    /** 岛内容 JSON 所在的两个 key（已确证）。 */
    private val ISLAND_JSON_KEYS = listOf("param", "island_param")

    /**
     * 候选类名：`com.android.bluetooth` 与 `com.xiaomi.bluetooth` 两个进程各自
     * 有同一功能的通知类，但包前缀不同，且互相加载不到对方的类。
     * 因此按进程依次尝试，第一个能解析出 `invokeStatusBar` 的类生效。
     */
    private val NOTIFICATION_CLASS_CANDIDATES = listOf(
        "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
        "com.xiaomi.bluetooth.ble.app.MiuiBluetoothNotification",
    )

    /**
     * 在给定进程里安装灵动岛 Hook。
     * 全程静默降级：类或方法找不到只记日志，绝不影响宿主进程。
     */
    fun install(ctx: HookContext) {
        val invokeStatusBar = firstResolvableMethod(ctx) ?: run {
            ModuleLog.d(TAG, "未找到 invokeStatusBar（该版本可能不支持灵动岛）")
            return
        }
        ctx.hookBefore(invokeStatusBar) {
            val bundle = arg<Bundle>(2) ?: return@hookBefore
            if (!isHeadsetWearIsland(bundle)) return@hookBefore

            if (!ModuleConfigStore.current().focusIsland) {
                result = null
                ModuleLog.d(TAG, "已吞掉系统耳机灵动岛通知")
                return@hookBefore
            }
            if (patchIslandBattery(bundle)) {
                ModuleLog.d(TAG, "已改写耳机灵动岛电量文本")
            }
        }
        ModuleLog.i(TAG, "灵动岛 Hook 已安装（${ctx.packageName}）")

        // 通知参数更新入口（部分版本走这里），仅记录，内容改写交给 invokeStatusBar
        val updateParameters = invokeStatusBar.declaringClass.methods.firstOrNull {
            it.name == "updateParameters"
        }
        if (updateParameters != null) {
            ctx.hookAfter(updateParameters) {
                ModuleLog.d(TAG, "updateParameters 被调用（${args.size} 个参数）")
            }
        }
    }

    /** 遍历候选类，返回第一个同时解析出类与方法的目标。 */
    private fun firstResolvableMethod(ctx: HookContext): java.lang.reflect.Method? {
        for (className in NOTIFICATION_CLASS_CANDIDATES) {
            val clazz = ctx.findClassOrNull(className) ?: continue
            val method = ctx.findMethodOrNull(
                className,
                "invokeStatusBar",
                Context::class.java,
                String::class.java,
                Bundle::class.java,
            )
            if (method != null) {
                ModuleLog.d(TAG, "灵动岛类命中：$className")
                return method
            }
            ModuleLog.d(TAG, "$className 存在但无 invokeStatusBar")
        }
        return null
    }

    // ------------------------------------------------------------------ 识别

    /** 判断这是系统为耳机佩戴/电量生成的灵动岛通知。 */
    fun isHeadsetWearIsland(bundle: Bundle): Boolean = runCatching {
        bundle.getString("notifyId") == HEADSET_WEAR_NOTIFY_ID
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 改写

    /**
     * 把岛内的左右耳电量文本替换成本项目读到的真实值。
     * @return 是否真的改动了内容
     */
    fun patchIslandBattery(bundle: Bundle): Boolean {
        val snap = ModuleIpc.snapshot()
        if (!snap.connected) return false

        val leftText = batteryText(snap.left, snap.leftCharging)
        val rightText = batteryText(snap.right, snap.rightCharging)

        var changed = false
        for (key in ISLAND_JSON_KEYS) {
            val raw = runCatching { bundle.getString(key) }.getOrNull() ?: continue
            val patched = runCatching { patchIslandJson(raw, leftText, rightText) }.getOrNull() ?: continue
            if (patched != raw) {
                runCatching { bundle.putString(key, patched) }
                changed = true
            }
        }
        return changed
    }

    /**
     * 改写岛 JSON 里的 left / right 文本。结构与系统一致：
     * ```json
     * { "left":  { "textParams": { "text": "80%", "textColor": -1, "turnAnim": true } },
     *   "right": { "textParams": { ... } } }
     * ```
     */
    private fun patchIslandJson(raw: String, leftText: String?, rightText: String?): String {
        val json = JSONObject(raw)
        leftText?.let { putTextParams(json.optJSONObject("left"), it) }
        rightText?.let { putTextParams(json.optJSONObject("right"), it) }
        return json.toString()
    }

    private fun putTextParams(area: JSONObject?, text: String) {
        if (area == null) return
        runCatching {
            area.put(
                "textParams",
                JSONObject().apply {
                    put("text", text)
                    put("textColor", -1)   // -1 = 跟随系统前景色
                    put("turnAnim", true)
                },
            )
        }
    }

    /** 电量文本：未知显示 `--`，充电追加闪电。 */
    private fun batteryText(level: Int, charging: Boolean): String {
        if (level == ModuleIpc.UNKNOWN_LEVEL) return "--"
        val base = "$level%"
        return if (charging) "$base⚡" else base
    }
}
