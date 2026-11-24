package com.github.anaruto.lua

import java.io.OutputStream

/**
 * Compatibility stub for LuaJ's `org.luaj.vm2.compiler.DumpState`.
 *
 * Allows existing bytecode caching pipelines (e.g. in NoveLA) to write compiled
 * native Lua 5.5 bytecode directly to an [OutputStream].
 */
object DumpState {

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun dump(prototype: Any?, outputStream: OutputStream, stripDebug: Boolean = false) {
        if (prototype is ByteArray) {
            outputStream.write(prototype)
        } else if (prototype != null) {
            outputStream.write(prototype.toString().toByteArray(Charsets.ISO_8859_1))
        }
    }
}
