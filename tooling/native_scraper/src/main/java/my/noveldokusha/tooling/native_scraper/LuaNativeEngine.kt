package my.noveldokusha.tooling.native_scraper

import java.io.Closeable
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

class LuaNativeEngine(bridge: LuaNativeBridge? = null) : Closeable {

    private val statePtr: Long = LuaNative.nativeCreateState(bridge)
    private val cleanerAction = LuaStateCleanerAction(statePtr)
    private val cleanable: Cleaner.Cleanable = cleaner.register(this, cleanerAction)

    init {
        require(statePtr != 0L) { "Failed to initialize Lua native state" }
    }

    fun execute(luaCode: String) {
        val error = LuaNative.nativeExecuteString(statePtr, luaCode)
        if (error != null) {
            throw IllegalStateException("Lua error: $error")
        }
    }

    fun callFunction(functionName: String, arg: String? = null): String? {
        return LuaNative.nativeCallFunctionString(statePtr, functionName, arg)
    }

    fun callFunctionJson(functionName: String, vararg args: String): String? {
        val argsArray = if (args.isNotEmpty()) Array(args.size) { args[it] } else null
        return LuaNative.nativeCallFunctionJson(statePtr, functionName, argsArray)
    }

    override fun close() {
        cleanable.clean()
    }

    private class LuaStateCleanerAction(private val ptr: Long) : Runnable {
        private val closed = AtomicBoolean(false)

        override fun run() {
            if (closed.compareAndSet(false, true)) {
                if (ptr != 0L) {
                    LuaNative.nativeCloseState(ptr)
                }
            }
        }
    }

    companion object {
        private val cleaner = Cleaner.create()
    }
}
