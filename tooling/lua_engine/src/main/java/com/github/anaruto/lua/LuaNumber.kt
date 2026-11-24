package com.github.anaruto.lua

/**
 * Base abstract class for Lua numbers.
 */
sealed class LuaNumber : LuaValue() {
    override fun type(): Int = TNUMBER
    override fun isnumber(): Boolean = true
    override fun toboolean(): Boolean = true
}

/**
 * Represents Lua integer values (64-bit integer in Lua 5.5 / LuaJ).
 */
class LuaInteger private constructor(private val value: Long) : LuaNumber() {

    companion object {
        private const val CACHE_MIN = -128
        private const val CACHE_MAX = 127
        private val CACHE = Array(CACHE_MAX - CACHE_MIN + 1) { i -> LuaInteger((CACHE_MIN + i).toLong()) }

        @JvmStatic
        fun valueOf(l: Long): LuaInteger {
            return if (l in CACHE_MIN..CACHE_MAX) {
                CACHE[(l - CACHE_MIN).toInt()]
            } else {
                LuaInteger(l)
            }
        }
    }

    override fun isint(): Boolean = value in Int.MIN_VALUE..Int.MAX_VALUE
    override fun islong(): Boolean = true

    override fun toint(): Int = value.toInt()
    override fun tolong(): Long = value
    override fun todouble(): Double = value.toDouble()
    override fun tojstring(): String = value.toString()
    override fun toString(): String = tojstring()

    override fun checkint(): Int = value.toInt()
    override fun checklong(): Long = value
    override fun checkdouble(): Double = value.toDouble()

    fun longValue(): Long = value

    override fun equals(other: Any?): Boolean {
        if (other is LuaInteger) return other.value == this.value
        if (other is LuaDouble) return other.todouble() == this.value.toDouble()
        return false
    }

    override fun hashCode(): Int = value.hashCode()
}

/**
 * Represents Lua double precision floating point numbers.
 */
class LuaDouble private constructor(private val value: Double) : LuaNumber() {

    companion object {
        @JvmStatic
        fun valueOf(d: Double): LuaDouble = LuaDouble(d)
    }

    override fun isint(): Boolean {
        val l = value.toLong()
        return l.toDouble() == value && l in Int.MIN_VALUE..Int.MAX_VALUE
    }

    override fun islong(): Boolean {
        val l = value.toLong()
        return l.toDouble() == value
    }

    override fun toint(): Int = value.toInt()
    override fun tolong(): Long = value.toLong()
    override fun todouble(): Double = value
    override fun tojstring(): String {
        val l = value.toLong()
        return if (l.toDouble() == value) l.toString() else value.toString()
    }
    override fun toString(): String = tojstring()

    override fun checkint(): Int = value.toInt()
    override fun checklong(): Long = value.toLong()
    override fun checkdouble(): Double = value

    fun doubleValue(): Double = value

    override fun equals(other: Any?): Boolean {
        if (other is LuaDouble) return other.value == this.value
        if (other is LuaInteger) return other.tolong().toDouble() == this.value
        return false
    }

    override fun hashCode(): Int = value.hashCode()
}
