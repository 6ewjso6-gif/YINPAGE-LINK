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

    /**
     * 按参数个数找方法（应对混淆改名后签名不变的情况）。
     *
     * 注意：同名同参数个数的重载其顺序未定义，命中多个时无法保证选对，
     * 这里打印告警以便定位；调用方能拿到实参时应优先用 [callMethodOrNull]
     * （它会进一步按参数类型做兼容性筛选）。
     */
    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method? {
        val clazz = findClassOrNull(className) ?: return null
        val candidates = runCatching {
            clazz.declaredMethods.filter { it.name == methodName && it.parameterTypes.size == paramCount }
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        if (candidates.size > 1) {
            ModuleLog.w(
                TAG,
                "findMethodByParamCount 命中多个重载 $className#$methodName/$paramCount，" +
                    "取第一个：${candidates.first().parameterTypes.joinToString { it.simpleName }}",
            )
        }
        return candidates.first().apply { isAccessible = true }
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
        val field = findFieldInChainOrNull(instance, fieldName) ?: return null
        return runCatching { field.get(instance) }.getOrNull()
    }

    /**
     * 多候选字段名（混淆名漂移时用）。
     *
     * 判据是「字段是否存在」而非「字段值是否为 null」：
     * 一旦某个候选字段**存在**（哪怕其值为 null），就采纳它、不再继续探测，
     * 避免把「字段存在但值为 null」误判为「字段不存在」而落到错误的候选名上。
     */
    fun getObjectFieldAny(instance: Any?, vararg fieldNames: String): Any? {
        if (instance == null) return null
        for (name in fieldNames) {
            val field = findFieldInChainOrNull(instance, name) ?: continue
            return runCatching { field.get(instance) }.getOrNull()
        }
        return null
    }

    /** 沿继承链查找 Field 对象，找不到返回 null（用于区分"字段不存在"与"值为 null"）。 */
    private fun findFieldInChainOrNull(instance: Any?, fieldName: String): Field? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            runCatching {
                return cls.getDeclaredField(fieldName).apply { isAccessible = true }
            }
            cls = cls.superclass
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

    /**
     * 按名字调用方法（返回值可空）。
     *
     * 同名同参数个数的重载可能多个，这里在个数匹配之外再做**参数类型兼容性**筛选，
     * 避免选中错误重载后 invoke 抛 [IllegalArgumentException] 被静默吞掉；调用失败会打日志。
     */
    fun callMethodOrNull(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            val m = cls.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == args.size && acceptsArgs(it, args)
            }
            if (m != null) {
                m.isAccessible = true
                return runCatching { m.invoke(instance, *args) }
                    .onFailure { ModuleLog.w(TAG, "callMethodOrNull 调用 $methodName 失败: ${it.message}") }
                    .getOrNull()
            }
            cls = cls.superclass
        }
        ModuleLog.d(TAG, "callMethodOrNull 未找到 $methodName/${args.size}（${instance.javaClass.name}）")
        return null
    }

    /** 实参能否赋给该方法的形参：null 仅可赋给引用类型；非 null 用装箱后类型判断。 */
    private fun acceptsArgs(method: Method, args: Array<out Any?>): Boolean {
        val types = method.parameterTypes
        for (i in types.indices) {
            val arg = args[i]
            if (arg == null) {
                if (types[i].isPrimitive) return false
            } else if (!boxedType(types[i]).isInstance(arg)) {
                return false
            }
        }
        return true
    }

    private fun boxedType(t: Class<*>): Class<*> = when (t) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> t
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
     *
     * 成功结果会被缓存（每次访问都重做 Class.forName / getMethod 开销过大）；
     * **null 不缓存**，以便目标进程稍后才具备 Context 时能重新取到。
     */
    @Volatile
    private var cachedAppContext: android.content.Context? = null

    val appContext: android.content.Context?
        get() {
            cachedAppContext?.let { return it }
            val ctx = runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                val thread = activityThread.getMethod("currentActivityThread").invoke(null)
                activityThread.getMethod("getApplication").invoke(thread) as? android.content.Context
                    ?: activityThread.getMethod("getSystemContext").invoke(thread) as? android.content.Context
            }.getOrNull() ?: return null
            cachedAppContext = ctx
            return ctx
        }

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
