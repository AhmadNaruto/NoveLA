package com.github.anaruto.lua

/**
 * Exception thrown when a Lua script runtime, compilation, or type error occurs.
 *
 * Compatible with LuaJ's `LuaError` semantics while maintaining inheritance from [LuaException].
 */
open class LuaError(
    message: String,
    cause: Throwable? = null
) : LuaException(message, cause)
