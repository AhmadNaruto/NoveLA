package com.github.anaruto.lua

/**
 * Represents variable arguments or multiple return values in Lua, adapting the LuaJ [Varargs] interface.
 *
 * Lua uses 1-based indexing for arguments (`arg(1)` is the first argument).
 */
class Varargs private constructor(private val values: Array<LuaValue>) : Iterable<LuaValue> {

    companion object {
        @JvmField
        val EMPTY: Varargs = Varargs(emptyArray())

        @JvmStatic
        fun of(vararg values: LuaValue): Varargs {
            return if (values.isEmpty()) EMPTY else Varargs(Array(values.size) { values[it] })
        }

        @JvmStatic
        fun of(list: List<LuaValue>): Varargs {
            return if (list.isEmpty()) EMPTY else Varargs(list.toTypedArray())
        }
    }

    /**
     * Number of arguments in this vararg list.
     */
    fun narg(): Int = values.size

    /**
     * Gets the argument at 1-based index [i].
     * Returns [LuaValue.NIL] if [i] is out of range.
     */
    fun arg(i: Int): LuaValue {
        val idx = i - 1
        return if (idx in values.indices) values[idx] else LuaValue.NIL
    }

    /**
     * First argument, equivalent to `arg(1)`.
     */
    fun arg1(): LuaValue = arg(1)

    /**
     * Returns a slice of arguments starting from 1-based index [start].
     */
    fun subargs(start: Int): Varargs {
        val idx = (start - 1).coerceAtLeast(0)
        if (idx >= values.size) return EMPTY
        return Varargs(values.copyOfRange(idx, values.size))
    }

    // Type query helpers for first argument (common LuaJ convenience)
    fun isnumber(i: Int = 1): Boolean = arg(i).isnumber()
    fun isstring(i: Int = 1): Boolean = arg(i).isstring()
    fun isboolean(i: Int = 1): Boolean = arg(i).isboolean()
    fun isnil(i: Int = 1): Boolean = arg(i).isnil()
    fun istable(i: Int = 1): Boolean = arg(i).istable()
    fun isfunction(i: Int = 1): Boolean = arg(i).isfunction()

    // Conversion helpers for first argument
    fun toboolean(i: Int = 1): Boolean = arg(i).toboolean()
    fun toint(i: Int = 1): Int = arg(i).toint()
    fun tolong(i: Int = 1): Long = arg(i).tolong()
    fun todouble(i: Int = 1): Double = arg(i).todouble()
    fun tojstring(i: Int = 1): String = arg(i).tojstring()

    fun checkboolean(i: Int = 1): Boolean = arg(i).checkboolean()
    fun checkint(i: Int = 1): Int = arg(i).checkint()
    fun checklong(i: Int = 1): Long = arg(i).checklong()
    fun checkdouble(i: Int = 1): Double = arg(i).checkdouble()
    fun checkjstring(i: Int = 1): String = arg(i).checkjstring()
    fun checktable(i: Int = 1): LuaTable = arg(i).checktable()
    fun checkfunction(i: Int = 1): LuaFunction = arg(i).checkfunction()

    fun optboolean(i: Int, default: Boolean): Boolean = arg(i).optboolean(default)
    fun optint(i: Int, default: Int): Int = arg(i).optint(default)
    fun optlong(i: Int, default: Long): Long = arg(i).optlong(default)
    fun optdouble(i: Int, default: Double): Double = arg(i).optdouble(default)
    fun optjstring(i: Int, default: String?): String? = arg(i).optjstring(default)

    fun toArray(): Array<LuaValue> = values.copyOf()

    fun toList(): List<LuaValue> = values.toList()

    override fun iterator(): Iterator<LuaValue> = values.iterator()

    override fun toString(): String {
        return values.joinToString(separator = ", ", prefix = "(", postfix = ")") { it.tojstring() }
    }
}
