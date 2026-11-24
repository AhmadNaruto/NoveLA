package com.github.anaruto.lua

/**
 * Platform factory helper adapting LuaJ's `org.luaj.vm2.lib.jse.JsePlatform`.
 */
object JsePlatform {

    /**
     * Creates and initializes a [Globals] instance equipped with all standard libraries,
     * including native JSON and Regex support.
     */
    @JvmStatic
    fun standardGlobals(): Globals {
        return Globals.create()
    }
}
