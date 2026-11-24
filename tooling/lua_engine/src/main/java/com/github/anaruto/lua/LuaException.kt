package com.github.anaruto.lua

/**
 * Exception thrown when a Lua script execution, syntax, or runtime error occurs.
 */
open class LuaException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
