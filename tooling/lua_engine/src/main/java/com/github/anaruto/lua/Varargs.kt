package com.github.anaruto.lua

/**
 * Represents variable arguments or multiple return values in Lua, adapting the LuaJ [Varargs] interface.
 *
 * Lua uses 1-based indexing for arguments (`arg(1)` is the first argument).
 * Both [LuaValue] (single-value Varargs) and [LuaVarargs] (multi-value Varargs) extend this class.
 */
abstract class Varargs : Iterable<LuaValue> {

    companion object {
        @JvmField
        val EMPTY: Varargs = LuaVarargs(emptyArray())

        @JvmStatic
        fun of(vararg values: LuaValue): Varargs {
            return when (values.size) {
                0 -> EMPTY
                1 -> values[0]
                else -> LuaVarargs(Array(values.size) { values[it] })
            }
        }

        @JvmStatic
        fun of(list: List<LuaValue>): Varargs {
            return when (list.size) {
                0 -> EMPTY
                1 -> list[0]
                else -> LuaVarargs(list.toTypedArray())
            }
        }
    }

    /**
     * Number of arguments in this vararg list.
     */
    abstract fun narg(): Int

    /**
     * Gets the argument at 1-based index [i].
     * Returns [LuaValue.NIL] if [i] is out of range.
     */
    abstract fun arg(i: Int): LuaValue

    /**
     * First argument, equivalent to `arg(1)`.
     */
    open fun arg1(): LuaValue = arg(1)

    /**
     * Returns a slice of arguments starting from 1-based index [start].
     */
    open fun subargs(start: Int): Varargs {
        val count = narg()
        val s = (start - 1).coerceAtLeast(0)
        if (s >= count) return EMPTY
        val len = count - s
        val arr = Array(len) { i -> arg(start + i) }
        return LuaVarargs(arr)
    }

    // Type query helpers for argument [i]
    open fun isnumber(i: Int = 1): Boolean = arg(i).isnumber()
    open fun isstring(i: Int = 1): Boolean = arg(i).isstring()
    open fun isboolean(i: Int = 1): Boolean = arg(i).isboolean()
    open fun isnil(i: Int = 1): Boolean = arg(i).isnil()
    open fun istable(i: Int = 1): Boolean = arg(i).istable()
    open fun isfunction(i: Int = 1): Boolean = arg(i).isfunction()
    open fun isuserdata(i: Int = 1): Boolean = arg(i).isuserdata()
    open fun isuserdata(i: Int, c: Class<*>): Boolean = arg(i).isuserdata(c)

    // Conversion helpers for argument [i]
    open fun toboolean(i: Int = 1): Boolean = arg(i).toboolean()
    open fun toint(i: Int = 1): Int = arg(i).toint()
    open fun tolong(i: Int = 1): Long = arg(i).tolong()
    open fun todouble(i: Int = 1): Double = arg(i).todouble()
    open fun tojstring(i: Int = 1): String = arg(i).tojstring()

    open fun checkboolean(i: Int = 1): Boolean = arg(i).checkboolean()
    open fun checkint(i: Int = 1): Int = arg(i).checkint()
    open fun checklong(i: Int = 1): Long = arg(i).checklong()
    open fun checkdouble(i: Int = 1): Double = arg(i).checkdouble()
    open fun checkjstring(i: Int = 1): String = arg(i).checkjstring()
    open fun checktable(i: Int = 1): LuaTable = arg(i).checktable()
    open fun checkfunction(i: Int = 1): LuaFunction = arg(i).checkfunction()

    open fun optboolean(i: Int, default: Boolean): Boolean = arg(i).optboolean(default)
    open fun optint(i: Int, default: Int): Int = arg(i).optint(default)
    open fun optlong(i: Int, default: Long): Long = arg(i).optlong(default)
    open fun optdouble(i: Int, default: Double): Double = arg(i).optdouble(default)
    open fun optjstring(i: Int, default: String): String = arg(i).optjstring(default)
    open fun optjstring(i: Int, default: String?): String? = arg(i).optjstring(default)
    open fun opttable(i: Int, default: LuaTable): LuaTable = arg(i).opttable(default)
    open fun opttable(i: Int, default: LuaTable?): LuaTable? = arg(i).opttable(default)

    open fun toArray(): Array<LuaValue> {
        val n = narg()
        return Array(n) { i -> arg(i + 1) }
    }

    open fun toList(): List<LuaValue> = toArray().toList()

    override fun iterator(): Iterator<LuaValue> {
        return object : Iterator<LuaValue> {
            private var idx = 1
            override fun hasNext(): Boolean = idx <= narg()
            override fun next(): LuaValue {
                if (!hasNext()) throw NoSuchElementException()
                return arg(idx++)
            }
        }
    }

    override fun toString(): String {
        return toList().joinToString(separator = ", ", prefix = "(", postfix = ")") { it.tojstring() }
    }
}

/**
 * Multi-value implementation of [Varargs].
 */
class LuaVarargs internal constructor(private val values: Array<LuaValue>) : Varargs() {
    override fun narg(): Int = values.size
    override fun arg(i: Int): LuaValue {
        val idx = i - 1
        return if (idx in values.indices) values[idx] else LuaValue.NIL
    }
    override fun arg1(): LuaValue = if (values.isNotEmpty()) values[0] else LuaValue.NIL
    override fun toArray(): Array<LuaValue> = values.copyOf()
}
