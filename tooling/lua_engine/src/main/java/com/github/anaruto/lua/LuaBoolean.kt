package com.github.anaruto.lua

/**
 * Represents Lua's boolean values (`true` and `false`).
 */
class LuaBoolean private constructor(private val value: Boolean) : LuaValue() {

    companion object {
        @JvmField
        val TRUE = LuaBoolean(true)

        @JvmField
        val FALSE = LuaBoolean(false)

        @JvmStatic
        fun valueOf(b: Boolean): LuaBoolean = if (b) TRUE else FALSE
    }

    override fun type(): Int = TBOOLEAN
    override fun isboolean(): Boolean = true
    override fun toboolean(): Boolean = value
    override fun checkboolean(): Boolean = value
    override fun tojstring(): String = if (value) "true" else "false"
    override fun toString(): String = tojstring()

    fun booleanValue(): Boolean = value

    override fun equals(other: Any?): Boolean {
        return other is LuaBoolean && other.value == this.value
    }

    override fun hashCode(): Int = if (value) 1 else 0
}
