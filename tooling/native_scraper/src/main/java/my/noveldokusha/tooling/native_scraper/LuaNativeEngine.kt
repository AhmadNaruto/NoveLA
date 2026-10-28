package my.noveldokusha.tooling.native_scraper

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class LuaNativeEngine(bridge: LuaNativeBridge? = null) : Closeable {

    private val closed = AtomicBoolean(false)
    private val statePtr: Long

    init {
        check(LuaNative.isAvailable) { "libnative_scraper.so is not available on this device" }
        statePtr = LuaNative.nativeCreateState(bridge)
        require(statePtr != 0L) { "Failed to initialize Lua native state" }
    }

    fun execute(luaCode: String) {
        val error = LuaNative.nativeExecuteString(statePtr, luaCode)
        if (error != null) {
            throw IllegalStateException("Lua error: $error")
        }
    }

    fun hasFunction(functionName: String): Boolean {
        return LuaNative.nativeHasFunction(statePtr, functionName)
    }

    fun call(functionName: String, vararg args: Any?): String? {
        val argsJson = if (args.isNotEmpty()) {
            buildString {
                append("[")
                args.forEachIndexed { i, arg ->
                    if (i > 0) append(",")
                    when (arg) {
                        null -> append("null")
                        is Number -> append(arg.toString())
                        is Boolean -> append(if (arg) "true" else "false")
                        else -> {
                            append("\"")
                            val s = arg.toString()
                            for (c in s) {
                                when (c) {
                                    '\\' -> append("\\\\")
                                    '"' -> append("\\\"")
                                    '\b' -> append("\\b")
                                    '\u000C' -> append("\\f")
                                    '\n' -> append("\\n")
                                    '\r' -> append("\\r")
                                    '\t' -> append("\\t")
                                    else -> {
                                        if (c.code in 0x00..0x1F) {
                                            append("\\u")
                                            append(c.code.toString(16).padStart(4, '0'))
                                        } else {
                                            append(c)
                                        }
                                    }
                                }
                            }
                            append("\"")
                        }
                    }
                }
                append("]")
            }
        } else null
        return LuaNative.nativeCall(statePtr, functionName, argsJson)
    }

    fun callFunction(functionName: String, arg: String? = null): String? {
        return LuaNative.nativeCallFunctionString(statePtr, functionName, arg)
    }

    fun callFunctionJson(functionName: String, vararg args: String): String? {
        val argsArray = if (args.isNotEmpty()) Array(args.size) { args[it] } else null
        return LuaNative.nativeCallFunctionJson(statePtr, functionName, argsArray)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            if (statePtr != 0L) {
                LuaNative.nativeCloseState(statePtr)
            }
        }
    }

    protected fun finalize() {
        close()
    }
}
