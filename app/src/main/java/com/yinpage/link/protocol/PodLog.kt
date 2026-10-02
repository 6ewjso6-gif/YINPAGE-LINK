package com.yinpage.link.protocol

/**
 * ============================================================================
 *  协议层日志接口  ——  让协议实现与宿主环境解耦
 * ============================================================================
 *  背景：`protocol/` 下的代码被两个宿主共用：
 *    - `:app`    免 root 独立应用（有 Context，日志进 EventLog + UI 调试面板）
 *    - `:module` LSPosed 模块（运行在系统进程里，日志只能走 logcat）
 *
 *  若协议实现直接依赖 `core.EventLog`（`:app` 专有且依赖 ConfigManager/Android），
 *  `:module` 就无法复用同一份协议代码。因此把日志收敛到这个最小接口。
 * ============================================================================
 */
interface PodLog {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)

    companion object {
        /** 默认实现：丢弃全部日志（协议层在做单元测试或无人接收时用）。 */
        val NONE: PodLog = object : PodLog {
            override fun debug(tag: String, message: String) = Unit
            override fun info(tag: String, message: String) = Unit
        }

        /**
         * 全局默认日志实现。宿主在启动时替换：
         *   `:app`    -> AndroidPodLog（写入 EventLog）
         *   `:module` -> AndroidPodLog（写入 logcat）
         */
        @Volatile
        var default: PodLog = NONE
    }
}

/** 便捷入口：协议层用 `Log.d(tag, msg)` 风格调用。 */
object Log {
    fun d(tag: String, message: String) = PodLog.default.debug(tag, message)
    fun i(tag: String, message: String) = PodLog.default.info(tag, message)
}
