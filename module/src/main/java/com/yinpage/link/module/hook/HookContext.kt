package com.yinpage.link.module.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import com.yinpage.link.module.ModuleConfig
import com.yinpage.link.module.ModuleConfigStore
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * ============================================================================
 *  Hook 工具层
 * ============================================================================
 *  被 Hook 的都是系统私有类，**每个 OS 小版本都可能改类名/字段名/方法名**，
 *  因此这里的每个查找函数都必须"找得到就用，找不到就静默返回 null"，
 *  绝不允许因为某个符号缺失而让整个进程崩掉。
 *
 *  设计参考 HyperOriG 的 HookContext（同为 libxposed API 102 形态），
 *  但做了两点收紧：
 *   1. 所有查找返回可空，调用方必须显式处理失败；
 *   2. 安装 hook 统一走 [safeHook]，内部 try-catch 并带可读日志。
 * ============================================================================
 */
abstract class HookContext {

    lateinit var module: XposedModule
    lateinit var appClassLoader: ClassLoader
    lateinit var packageName: String

    /** 子类实现：安装本作用域内的所有 Hook。 */
    abstract fun onHook()

    // ------------------------------------------------------------------ 类与方法查找

    /** 类查找：找不到返回 null（不抛异常）。 */
    fun findClassOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name, false, appClassLoader) }.getOrNull()

    fun findClass(name: String): Class<*>? = findClassOrNull(name)

    /** 按精确参数类型找方法。 */
    fun findMethodOrNull(className: String, methodName: String, vararg parameterTypes: Class<*>): Method? {
        val clazz = findClassOrNull(className) ?: return null
        return runCatching {
            clazz.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
        }.getOrNull()
    }

    /** 按参数个数找方法（应对混淆改名后签名不变的情况）。 */
    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method? {
        val clazz = findClassOrNull(className) ?: return null
        return runCatching {
            clazz.declaredMethods
                .firstOrNull { it.name == methodName && it.parameterTypes.size == paramCount }
                ?.apply { isAccessible = true }
        }.getOrNull()
    }

    /**
     * 多候选方法名探测：混淆后同一方法在不同版本可能叫 `isMiTWS` / `mo19771O0` / `O0`。
     * 依次尝试，第一个存在的胜出。
     */
    fun findMethodByNames(
        className: String,
        names: List<String>,
        vararg parameterTypes: Class<*>,
    ): Method? {
        val clazz = findClassOrNull(className) ?: return null
        for (name in names) {
            runCatching {
                return clazz.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            }
        }
        return null
    }

    fun findConstructorOrNull(className: String, vararg parameterTypes: Class<*>): Constructor<*>? {
        val clazz = findClassOrNull(className) ?: return null
        return runCatching {
            clazz.getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 字段与调用

    /** 沿继承链找字段。 */
    fun getObjectFieldOrNull(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            runCatching {
                return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            }
            cls = cls.superclass
        }
        return null
    }

    /** 多候选字段名（混淆名漂移时用）。 */
    fun getObjectFieldAny(instance: Any?, vararg fieldNames: String): Any? {
        for (name in fieldNames) {
            getObjectFieldOrNull(instance, name)?.let { return it }
        }
        return null
    }

    /** 按名字取 Field 对象（用于类型校验）。 */
    fun findFieldOrNull(clazz: Class<*>?, vararg fieldNames: String): Field? {
        if (clazz == null) return null
        for (name in fieldNames) {
            runCatching {
                return clazz.getDeclaredField(name).apply { isAccessible = true }
            }
        }
        return null
    }

    fun setObjectField(instance: Any?, fieldName: String, value: Any?): Boolean {
        if (instance == null) return false
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            val ok = runCatching {
                cls.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
                true
            }.getOrDefault(false)
            if (ok) return true
            cls = cls.superclass
        }
        return false
    }

    /** 按参数个数调用方法（返回值可空）。 */
    fun callMethodOrNull(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            val m = cls.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == args.size
            }
            if (m != null) {
                m.isAccessible = true
                return runCatching { m.invoke(instance, *args) }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    // ------------------------------------------------------------------ 安装 Hook

    /** 包装后的 hook 参数，见 [HookParam]。 */
    fun hookAfter(method: Method, block: HookParam.() -> Unit) {
        runCatching {
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching { HookParam(chain, result).apply(block) }
                result
            }
        }.onFailure { ModuleLog.w(TAG, "hookAfter 失败 ${method.name}: ${it.message}") }
    }

    fun hookBefore(method: Method, block: HookParam.() -> Unit) {
        runCatching {
            module.hook(method).intercept { chain ->
                val param = runCatching { HookParam(chain, null).apply(block) }.getOrNull()
                if (param != null && param.hasResult) param.result else chain.proceed()
            }
        }.onFailure { ModuleLog.w(TAG, "hookBefore 失败 ${method.name}: ${it.message}") }
    }

    fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit) {
        runCatching {
            module.hook(constructor).intercept { chain ->
                val instance = chain.proceed()
                runCatching { HookParam(chain, instance).apply(block) }
                instance
            }
        }.onFailure { ModuleLog.w(TAG, "hookConstructorAfter 失败: ${it.message}") }
    }

    /**
     * 对一个类的多个候选名同时装 hook（新旧版本方法名并存时用）。
     * 返回成功安装的数量。
     */
    fun hookAll(
        className: String,
        methodNames: List<String>,
        paramTypes: Array<Class<*>>,
        block: HookParam.() -> Unit,
    ): Int {
        var count = 0
        for (name in methodNames) {
            val m = findMethodOrNull(className, name, *paramTypes) ?: continue
            hookBefore(m, block)
            count++
        }
        return count
    }

    /** 当前进程名（用于日志与"是否目标进程"判断）。 */
    val processName: String get() = packageName

    /**
     * 反射获取当前进程的 Application Context。
     * 被 Hook 的进程里拿不到 Activity，但广播收发、查包名等都需要 Context，
     * 因此统一从 ActivityThread 取。
     */
    val appContext: android.content.Context?
        get() = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            activityThread.getMethod("getApplication").invoke(thread) as? android.content.Context
                ?: activityThread.getMethod("getSystemContext").invoke(thread) as? android.content.Context
        }.getOrNull()

    private companion object {
        const val TAG = "YINPAGE-Hook"
    }
}

/** Hook 回调参数包装。 */
class HookParam(
    private val chain: XposedInterface.Chain,
    initialResult: Any?,
) {
    val args: List<Any?> = chain.args

    /** 被 Hook 的对象（静态方法为 null）。 */
    val instance: Any? = chain.thisObject

    /** 被 Hook 的可执行体。 */
    val executable: java.lang.reflect.Executable = chain.executable

    var hasResult: Boolean = false
        private set

    var result: Any? = initialResult
        set(value) {
            hasResult = true
            field = value
        }

    /** 取第 index 个参数并安全转型。 */
    inline fun <reified T> arg(index: Int): T? = args.getOrNull(index) as? T
}

/**
 * 模块日志。级别由配置控制，避免刷屏。
 * 被 Hook 进程里没有 Context，用 android.util.Log 输出到 logcat 即可
 * （LSPosed 管理器也能看到模块日志）。
 */
object ModuleLog {
    private const val TAG = "YINPAGE-LINK"

    private fun enabled(level: Int): Boolean =
        runCatching { ModuleConfigStore.current().logLevel >= level }.getOrDefault(true)

    fun i(tag: String, msg: String) {
        if (!enabled(ModuleConfig.LOG_BASIC)) return
        runCatching { Log.i(TAG, "[$tag] $msg") }
    }

    fun d(tag: String, msg: String) {
        if (!enabled(ModuleConfig.LOG_DEBUG)) return
        runCatching { Log.d(TAG, "[$tag] $msg") }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { Log.w(TAG, "[$tag] $msg", tr) }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { Log.e(TAG, "[$tag] $msg", tr) }
    }
}
