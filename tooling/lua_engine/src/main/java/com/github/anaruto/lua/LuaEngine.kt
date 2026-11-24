package com.github.anaruto.lua

import java.io.Closeable

/**
 * Idiomatic Kotlin wrapper for the embedded Lua 5.5 native engine.
 *
 * Implements [Closeable] to support Kotlin's `.use { ... }` block for automatic cleanup.
 *
 * Example usage:
 * ```kotlin
 * import com.github.anaruto.lua.LuaEngine
 *
 * LuaEngine.create().use { lua ->
 *     lua.setGlobal("appName", "MyAndroidApp")
 *     val result = lua.eval("appName .. ' is running on Lua ' .. _VERSION")
 *     println(result)
 * }
 * ```
 */
class LuaEngine private constructor(private var statePtr: Long) : Closeable {

    companion object {
        init {
            System.loadLibrary("lua")
        }

        /**
         * Initializes and returns a new Lua state with all standard libraries opened.
         *
         * @throws LuaException if Lua state initialization fails.
         */
        @JvmStatic
        fun create(): LuaEngine {
            val ptr = nativeNewState()
            if (ptr == 0L) {
                throw LuaException("Failed to allocate native Lua state")
            }
            return LuaEngine(ptr)
        }

        /**
         * Version of the underlying native Lua engine.
         */
        @JvmStatic
        val version: String
            get() = nativeGetVersion()

        // Native static bindings
        @JvmStatic
        private external fun nativeNewState(): Long

        @JvmStatic
        private external fun nativeClose(ptr: Long)

        @JvmStatic
        private external fun nativeGetVersion(): String

        @JvmStatic
        private external fun nativeDoString(ptr: Long, script: String): String?

        @JvmStatic
        private external fun nativeDoFile(ptr: Long, filePath: String): String?

        @JvmStatic
        private external fun nativeEval(ptr: Long, expression: String): String?

        @JvmStatic
        private external fun nativeSetGlobalString(ptr: Long, name: String, value: String?)

        @JvmStatic
        private external fun nativeSetGlobalNumber(ptr: Long, name: String, value: Double)

        @JvmStatic
        private external fun nativeSetGlobalInteger(ptr: Long, name: String, value: Long)

        @JvmStatic
        private external fun nativeSetGlobalBoolean(ptr: Long, name: String, value: Boolean)

        @JvmStatic
        private external fun nativeGetGlobalString(ptr: Long, name: String): String?

        @JvmStatic
        private external fun nativeGetGlobalNumber(ptr: Long, name: String): Double

        @JvmStatic
        private external fun nativeGetGlobalInteger(ptr: Long, name: String): Long

        @JvmStatic
        private external fun nativeGetGlobalBoolean(ptr: Long, name: String): Boolean

        @JvmStatic
        private external fun nativePushNil(ptr: Long)

        @JvmStatic
        private external fun nativePushBoolean(ptr: Long, value: Boolean)

        @JvmStatic
        private external fun nativePushInteger(ptr: Long, value: Long)

        @JvmStatic
        private external fun nativePushNumber(ptr: Long, value: Double)

        @JvmStatic
        private external fun nativePushString(ptr: Long, value: String?)

        @JvmStatic
        private external fun nativeToString(ptr: Long, index: Int): String?

        @JvmStatic
        private external fun nativeToNumber(ptr: Long, index: Int): Double

        @JvmStatic
        private external fun nativeToInteger(ptr: Long, index: Int): Long

        @JvmStatic
        private external fun nativeToBoolean(ptr: Long, index: Int): Boolean

        @JvmStatic
        private external fun nativeGetTop(ptr: Long): Int

        @JvmStatic
        private external fun nativePop(ptr: Long, n: Int)

        @JvmStatic
        private external fun nativePCall(ptr: Long, nArgs: Int, nResults: Int): String?
    }

    /**
     * Checks if this Lua state has been closed.
     */
    val isClosed: Boolean
        get() = statePtr == 0L

    private fun checkState() {
        if (statePtr == 0L) {
            throw LuaException("LuaEngine instance has already been closed")
        }
    }

    /**
     * Executes the given Lua script snippet.
     *
     * @param script Lua source code string to run.
     * @throws LuaException if compilation or execution encounters an error.
     */
    fun doString(script: String) {
        checkState()
        val error = nativeDoString(statePtr, script)
        if (error != null) {
            throw LuaException(error)
        }
    }

    /**
     * Executes the Lua script file at the given absolute file path.
     *
     * @param filePath Absolute path on the Android filesystem.
     * @throws LuaException if file reading, compilation, or execution fails.
     */
    fun doFile(filePath: String) {
        checkState()
        val error = nativeDoFile(statePtr, filePath)
        if (error != null) {
            throw LuaException(error)
        }
    }

    /**
     * Evaluates a Lua expression (e.g. "1 + 2" or "math.sqrt(16)") and returns the string result,
     * or null if evaluation fails.
     */
    fun eval(expression: String): String? {
        checkState()
        return nativeEval(statePtr, expression)
    }

    // --- Global Variables ---

    fun setGlobal(name: String, value: String?) {
        checkState()
        nativeSetGlobalString(statePtr, name, value)
    }

    fun setGlobal(name: String, value: Double) {
        checkState()
        nativeSetGlobalNumber(statePtr, name, value)
    }

    fun setGlobal(name: String, value: Long) {
        checkState()
        nativeSetGlobalInteger(statePtr, name, value)
    }

    fun setGlobal(name: String, value: Int) {
        setGlobal(name, value.toLong())
    }

    fun setGlobal(name: String, value: Boolean) {
        checkState()
        nativeSetGlobalBoolean(statePtr, name, value)
    }

    fun getGlobalString(name: String): String? {
        checkState()
        return nativeGetGlobalString(statePtr, name)
    }

    fun getGlobalDouble(name: String): Double {
        checkState()
        return nativeGetGlobalNumber(statePtr, name)
    }

    fun getGlobalLong(name: String): Long {
        checkState()
        return nativeGetGlobalInteger(statePtr, name)
    }

    fun getGlobalInt(name: String): Int {
        return getGlobalLong(name).toInt()
    }

    fun getGlobalBoolean(name: String): Boolean {
        checkState()
        return nativeGetGlobalBoolean(statePtr, name)
    }

    // --- Lua Stack Operations ---

    fun pushNil() {
        checkState()
        nativePushNil(statePtr)
    }

    fun push(value: Boolean) {
        checkState()
        nativePushBoolean(statePtr, value)
    }

    fun push(value: Long) {
        checkState()
        nativePushInteger(statePtr, value)
    }

    fun push(value: Int) {
        push(value.toLong())
    }

    fun push(value: Double) {
        checkState()
        nativePushNumber(statePtr, value)
    }

    fun push(value: String?) {
        checkState()
        nativePushString(statePtr, value)
    }

    fun toString(index: Int = -1): String? {
        checkState()
        return nativeToString(statePtr, index)
    }

    fun toDouble(index: Int = -1): Double {
        checkState()
        return nativeToNumber(statePtr, index)
    }

    fun toLong(index: Int = -1): Long {
        checkState()
        return nativeToInteger(statePtr, index)
    }

    fun toInt(index: Int = -1): Int {
        return toLong(index).toInt()
    }

    fun toBoolean(index: Int = -1): Boolean {
        checkState()
        return nativeToBoolean(statePtr, index)
    }

    fun getTop(): Int {
        checkState()
        return nativeGetTop(statePtr)
    }

    fun pop(n: Int = 1) {
        checkState()
        nativePop(statePtr, n)
    }

    /**
     * Calls a Lua function already on top of the stack with [nArgs] arguments and [nResults] results.
     *
     * @throws LuaException if the function execution fails.
     */
    fun pcall(nArgs: Int, nResults: Int) {
        checkState()
        val error = nativePCall(statePtr, nArgs, nResults)
        if (error != null) {
            throw LuaException(error)
        }
    }

    /**
     * Closes the native Lua state and releases all associated memory.
     * Safe to call multiple times.
     */
    override fun close() {
        if (statePtr != 0L) {
            nativeClose(statePtr)
            statePtr = 0L
        }
    }
}
