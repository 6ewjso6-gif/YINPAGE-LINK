package com.yinpage.link.core

import com.yinpage.link.config.AppConfig
import com.yinpage.link.config.ConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 进程内事件日志（调试面板数据源）。
 * 与 LSPosed 参考项目不同，这里没有 logcat 注入，全部走内存环形缓冲 + 可选 logcat。
 */
object EventLog {
    private const val MAX_LINES = 400
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val buffer = Collections.synchronizedList(ArrayDeque<String>())

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** 是否记录 debug 级（详细收发字节）。由设置页开关控制。 */
    @Volatile
    private var verbose: Boolean = false

    fun setVerbose(enabled: Boolean) {
        verbose = enabled
        append("系统", if (enabled) "已开启详细日志" else "已关闭详细日志", force = true)
    }

    fun info(tag: String, message: String) = append(tag, message, force = false)

    fun debug(tag: String, message: String) {
        if (!verbose) return
        append(tag, message, force = true)
    }

    /** 记录一次收发（十六进制 + 可打印字符）。 */
    fun bytes(tag: String, direction: String, data: IntArray) {
        if (!verbose) return
        append(tag, "$direction ${data.size}B  ${toHex(data)}  |${toAscii(data)}|", force = true)
    }

    private fun append(tag: String, message: String, force: Boolean) {
        val configLevel = if (ConfigManager.initialized) ConfigManager.get().current.logLevel else 1
        if (!force && configLevel < AppConfig.LOG_BASIC) return
        val line = "${timeFmt.format(Date())}  [$tag]  $message"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            _lines.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    fun dump(): String = synchronized(buffer) { buffer.joinToString("\n") }

    fun toHex(data: IntArray): String = data.joinToString(" ") { "%02X".format(it and 0xFF) }

    fun toAscii(data: IntArray): String = buildString {
        data.forEach { b ->
            val v = b and 0xFF
            append(if (v in 0x20..0x7E) v.toChar() else '.')
        }
    }
}
