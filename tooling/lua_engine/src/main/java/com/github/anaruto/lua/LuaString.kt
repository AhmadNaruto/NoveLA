package com.github.anaruto.lua

/**
 * Represents Lua string values.
 */
class LuaString private constructor(private val value: String) : LuaValue() {

    companion object {
        @JvmField
        val s_metatable: LuaTable = LuaTable()

        @JvmStatic
        fun valueOf(s: String): LuaString = LuaString(s)
    }

    override fun type(): Int = TSTRING
    override fun isstring(): Boolean = true

    override fun toint(): Int = value.toIntOrNull() ?: 0
    override fun tolong(): Long = value.toLongOrNull() ?: 0L
    override fun todouble(): Double = value.toDoubleOrNull() ?: 0.0

    override fun tojstring(): String = value
    override fun toString(): String = value

    override fun checkjstring(): String = value

    override fun length(): Int = value.length

    override fun equals(other: Any?): Boolean {
        return other is LuaString && other.value == this.value
    }

    override fun hashCode(): Int = value.hashCode()
}
