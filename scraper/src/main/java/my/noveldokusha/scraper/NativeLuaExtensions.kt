package my.noveldokusha.scraper

import com.seanproctor.lua.LuaState
import com.seanproctor.lua.LuaValue

/**
 * Idiomatic Kotlin extensions for [LuaValue] and [LuaState] to simplify
 * interaction with the native Lua 5.4 engine.
 */

public fun LuaValue.optString(default: String? = null): String? = when (this) {
    is LuaValue.Str -> value
    is LuaValue.Integer -> value.toString()
    is LuaValue.Number -> value.toString()
    is LuaValue.Bool -> value.toString()
    else -> default
}

public fun LuaValue.optBoolean(default: Boolean = false): Boolean = when (this) {
    is LuaValue.Bool -> value
    is LuaValue.Integer -> value != 0L
    is LuaValue.Str -> value.equals("true", ignoreCase = true)
    else -> default
}

public fun LuaValue.optLong(default: Long = 0L): Long = when (this) {
    is LuaValue.Integer -> value
    is LuaValue.Number -> value.toLong()
    is LuaValue.Str -> value.toLongOrNull() ?: default
    else -> default
}

public fun LuaValue.optInt(default: Int = 0): Int = optLong(default.toLong()).toInt()

public fun LuaValue.isNil(): Boolean = this is LuaValue.Nil

public fun LuaValue.isTable(): Boolean = this is LuaValue.Table

public fun LuaValue.isFunction(): Boolean = this is LuaValue.Function

public operator fun LuaValue.Table.get(key: String): LuaValue = this[LuaValue.Str(key)]

public operator fun LuaValue.Table.get(index: Int): LuaValue = this[LuaValue.Integer(index.toLong())]

public operator fun LuaValue.Table.set(key: String, value: LuaValue) {
    this[LuaValue.Str(key)] = value
}

public operator fun LuaValue.Table.set(key: String, value: String) {
    this[LuaValue.Str(key)] = LuaValue.Str(value)
}

public operator fun LuaValue.Table.set(key: String, value: Boolean) {
    this[LuaValue.Str(key)] = LuaValue.Bool(value)
}

public operator fun LuaValue.Table.set(key: String, value: Long) {
    this[LuaValue.Str(key)] = LuaValue.Integer(value)
}

public operator fun LuaValue.Table.set(key: String, value: Int) {
    this[LuaValue.Str(key)] = LuaValue.Integer(value.toLong())
}

public operator fun LuaValue.Table.set(index: Int, value: LuaValue) {
    this[LuaValue.Integer(index.toLong())] = value
}

public fun LuaValue.Table.getString(key: String, default: String? = null): String? =
    this[key].optString(default)

public fun LuaValue.Table.getBoolean(key: String, default: Boolean = false): Boolean =
    this[key].optBoolean(default)

public fun LuaValue.Table.getLong(key: String, default: Long = 0L): Long =
    this[key].optLong(default)

public fun LuaValue.Table.getInt(key: String, default: Int = 0): Int =
    this[key].optInt(default)

public fun LuaValue.Table.toList(): List<LuaValue> {
    val list = ArrayList<LuaValue>(size)
    for (i in 1..size) {
        list.add(this[i])
    }
    return list
}

public fun LuaValue.Table.toStringList(): List<String> {
    val list = ArrayList<String>(size)
    for (i in 1..size) {
        val str = this[i].optString()
        if (str != null) list.add(str)
    }
    return list
}
