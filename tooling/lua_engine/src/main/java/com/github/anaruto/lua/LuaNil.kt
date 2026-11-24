package com.github.anaruto.lua

/**
 * Represents Lua's `nil` singleton value.
 */
class LuaNil private constructor() : LuaValue() {

    companion object {
        @JvmField
        val INSTANCE = LuaNil()
    }

    override fun type(): Int = TNIL
    override fun isnil(): Boolean = true
    override fun toboolean(): Boolean = false
    override fun tojstring(): String = "nil"
    override fun toString(): String = "nil"

    override fun equals(other: Any?): Boolean = other is LuaNil
    override fun hashCode(): Int = 0
}
