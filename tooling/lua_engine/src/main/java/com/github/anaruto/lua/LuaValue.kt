package com.github.anaruto.lua

/**
 * Base abstract class for all Lua values, strictly adapting the design of LuaJ's `org.luaj.vm2.LuaValue`.
 *
 * Provides polymorphic type checking, conversions, table operations, and function execution.
 */
abstract class LuaValue : Varargs(), Comparable<LuaValue> {

    companion object {
        const val TNONE = -1
        const val TNIL = 0
        const val TBOOLEAN = 1
        const val TLIGHTUSERDATA = 2
        const val TNUMBER = 3
        const val TSTRING = 4
        const val TTABLE = 5
        const val TFUNCTION = 6
        const val TUSERDATA = 7
        const val TTHREAD = 8

        @JvmField
        val NIL: LuaNil = LuaNil.INSTANCE

        @JvmField
        val TRUE: LuaBoolean = LuaBoolean.TRUE

        @JvmField
        val FALSE: LuaBoolean = LuaBoolean.FALSE

        @JvmField
        val INDEX: LuaString = LuaString.valueOf("__index")

        @JvmField
        val CALL: LuaString = LuaString.valueOf("__call")

        @JvmField
        val NEWINDEX: LuaString = LuaString.valueOf("__newindex")

        @JvmField
        val METATABLE: LuaString = LuaString.valueOf("__metatable")

        @JvmField
        val TOSTRING: LuaString = LuaString.valueOf("__tostring")

        @JvmStatic
        fun valueOf(b: Boolean): LuaBoolean = if (b) TRUE else FALSE

        @JvmStatic
        fun valueOf(i: Int): LuaInteger = LuaInteger.valueOf(i.toLong())

        @JvmStatic
        fun valueOf(l: Long): LuaInteger = LuaInteger.valueOf(l)

        @JvmStatic
        fun valueOf(d: Double): LuaNumber {
            val l = d.toLong()
            return if (l.toDouble() == d) {
                LuaInteger.valueOf(l)
            } else {
                LuaDouble.valueOf(d)
            }
        }

        @JvmStatic
        fun valueOf(s: String?): LuaValue = if (s == null) NIL else LuaString.valueOf(s)

        @JvmStatic
        fun valueOf(obj: Any?): LuaValue {
            return when (obj) {
                null -> NIL
                is LuaValue -> obj
                is Boolean -> valueOf(obj)
                is Byte -> valueOf(obj.toLong())
                is Short -> valueOf(obj.toLong())
                is Int -> valueOf(obj.toLong())
                is Long -> valueOf(obj)
                is Float -> valueOf(obj.toDouble())
                is Double -> valueOf(obj)
                is String -> valueOf(obj)
                is ByteArray -> valueOf(String(obj, Charsets.UTF_8))
                is Map<*, *> -> {
                    val table = tableOf()
                    for ((k, v) in obj) {
                        table.set(valueOf(k), valueOf(v))
                    }
                    table
                }
                is List<*> -> {
                    val table = tableOf()
                    for (i in obj.indices) {
                        table.set(i + 1, valueOf(obj[i]))
                    }
                    table
                }
                else -> valueOf(obj.toString())
            }
        }

        @JvmStatic
        fun tableOf(): LuaTable = LuaTable()

        @JvmStatic
        fun tableOf(vararg keyValues: Pair<Any, Any>): LuaTable {
            val table = LuaTable()
            for ((k, v) in keyValues) {
                table.set(valueOf(k), valueOf(v))
            }
            return table
        }
    }

    // --- Type Inspection ---

    abstract fun type(): Int

    open fun typename(): String = when (type()) {
        TNIL -> "nil"
        TBOOLEAN -> "boolean"
        TLIGHTUSERDATA -> "userdata"
        TNUMBER -> "number"
        TSTRING -> "string"
        TTABLE -> "table"
        TFUNCTION -> "function"
        TUSERDATA -> "userdata"
        TTHREAD -> "thread"
        else -> "none"
    }

    open fun isnil(): Boolean = false
    open fun isboolean(): Boolean = false
    open fun isnumber(): Boolean = false
    open fun isint(): Boolean = false
    open fun islong(): Boolean = false
    open fun isstring(): Boolean = false
    open fun istable(): Boolean = false
    open fun isfunction(): Boolean = false
    open fun isuserdata(): Boolean = type() == TUSERDATA
    open fun isuserdata(c: Class<*>): Boolean = isuserdata() && (touserdata() != null && c.isAssignableFrom(touserdata()!!.javaClass))
    open fun isnoneornil(): Boolean = isnil()

    // --- Varargs Implementation (single-value Varargs) ---
    override fun narg(): Int = 1
    override fun arg(i: Int): LuaValue = if (i == 1) this else NIL
    override fun arg1(): LuaValue = this
    override fun subargs(start: Int): Varargs = if (start <= 1) this else EMPTY
    override fun toArray(): Array<LuaValue> = arrayOf(this)
    override fun toList(): List<LuaValue> = listOf(this)
    override fun iterator(): Iterator<LuaValue> = listOf(this).iterator()

    // --- Value Conversions ---

    /**
     * Lua boolean semantics: only `nil` and `false` evaluate to false; everything else evaluates to true.
     */
    open fun toboolean(): Boolean = true
    open fun toint(): Int = tolong().toInt()
    open fun tolong(): Long = 0L
    open fun todouble(): Double = tolong().toDouble()
    open fun tojstring(): String = toString()

    open fun optboolean(default: Boolean): Boolean = if (isnoneornil()) default else toboolean()
    open fun optint(default: Int): Int = if (isnoneornil()) default else toint()
    open fun optlong(default: Long): Long = if (isnoneornil()) default else tolong()
    open fun optdouble(default: Double): Double = if (isnoneornil()) default else todouble()

    @JvmName("optjstringNullable")
    open fun optjstring(default: String?): String? = if (isnoneornil()) default else tojstring()

    open fun optjstring(default: String): String = if (isnoneornil()) default else tojstring()

    @JvmName("opttableNullable")
    open fun opttable(default: LuaTable?): LuaTable? = if (isnoneornil()) default else checktable()

    open fun opttable(default: LuaTable): LuaTable = if (isnoneornil()) default else checktable()

    @JvmName("optfunctionNullable")
    open fun optfunction(default: LuaFunction?): LuaFunction? = if (isnoneornil()) default else checkfunction()

    open fun optfunction(default: LuaFunction): LuaFunction = if (isnoneornil()) default else checkfunction()

    open fun checkboolean(): Boolean = throw LuaError("expected boolean, got " + typename())
    open fun checkint(): Int = throw LuaError("expected int, got " + typename())
    open fun checklong(): Long = throw LuaError("expected long, got " + typename())
    open fun checkdouble(): Double = throw LuaError("expected double, got " + typename())
    open fun checkjstring(): String = throw LuaError("expected string, got " + typename())
    open fun checkjstring(default: String): String = if (isnoneornil()) default else checkjstring()
    open fun checktable(): LuaTable = throw LuaError("expected table, got " + typename())
    open fun checkfunction(): LuaFunction = throw LuaError("expected function, got " + typename())

    open fun checkuserdata(): Any = throw LuaError("expected userdata, got " + typename())
    @Suppress("UNCHECKED_CAST")
    open fun <T> checkuserdata(clazz: Class<T>): T = throw LuaError("expected userdata of type " + clazz.name + ", got " + typename())
    open fun touserdata(): Any? = null
    @Suppress("UNCHECKED_CAST")
    open fun <T> touserdata(clazz: Class<T>): T? = if (isuserdata(clazz)) touserdata() as? T else null

    open fun getmetatable(): LuaValue = NIL
    open fun setmetatable(metatable: LuaValue): LuaValue {
        throw LuaError("cannot set metatable on " + typename())
    }

    // --- Table Operations ---

    open fun length(): Int = 0

    open fun rawget(key: LuaValue): LuaValue = get(key)
    open fun rawget(key: String): LuaValue = get(key)
    open fun rawget(key: Int): LuaValue = get(key)

    open fun rawset(key: LuaValue, value: LuaValue): LuaValue {
        set(key, value)
        return this
    }
    open fun rawset(key: String, value: LuaValue): LuaValue = rawset(valueOf(key), value)
    open fun rawset(key: Int, value: LuaValue): LuaValue = rawset(valueOf(key), value)

    open operator fun get(key: LuaValue): LuaValue = NIL
    open operator fun get(key: String): LuaValue = get(valueOf(key))
    open operator fun get(key: Int): LuaValue = get(valueOf(key))

    open operator fun set(key: LuaValue, value: LuaValue) {
        throw LuaError("attempt to index a " + typename() + " value")
    }
    open operator fun set(key: String, value: LuaValue) = set(valueOf(key), value)
    open operator fun set(key: Int, value: LuaValue) = set(valueOf(key), value)

    operator fun set(key: String, value: String?) = set(valueOf(key), valueOf(value))
    operator fun set(key: String, value: Double) = set(valueOf(key), valueOf(value))
    operator fun set(key: String, value: Long) = set(valueOf(key), valueOf(value))
    operator fun set(key: String, value: Int) = set(valueOf(key), valueOf(value))
    operator fun set(key: String, value: Boolean) = set(valueOf(key), valueOf(value))

    operator fun set(key: Int, value: String?) = set(valueOf(key), valueOf(value))
    operator fun set(key: Int, value: Double) = set(valueOf(key), valueOf(value))
    operator fun set(key: Int, value: Long) = set(valueOf(key), valueOf(value))
    operator fun set(key: Int, value: Int) = set(valueOf(key), valueOf(value))
    operator fun set(key: Int, value: Boolean) = set(valueOf(key), valueOf(value))

    // --- Function Invocation ---

    open fun call(): LuaValue = invoke(Varargs.EMPTY).arg1()
    open fun call(arg1: LuaValue): LuaValue = invoke(Varargs.of(arg1)).arg1()
    open fun call(arg1: LuaValue, arg2: LuaValue): LuaValue = invoke(Varargs.of(arg1, arg2)).arg1()
    open fun call(arg1: LuaValue, arg2: LuaValue, arg3: LuaValue): LuaValue = invoke(Varargs.of(arg1, arg2, arg3)).arg1()

    open operator fun invoke(args: Varargs): Varargs {
        throw LuaError("attempt to call a " + typename() + " value")
    }

    operator fun invoke(vararg args: LuaValue): Varargs = invoke(Varargs.of(*args))

    override fun compareTo(other: LuaValue): Int {
        if (this === other) return 0
        if (this.isnumber() && other.isnumber()) {
            return this.todouble().compareTo(other.todouble())
        }
        if (this.isstring() && other.isstring()) {
            return this.tojstring().compareTo(other.tojstring())
        }
        throw LuaError("attempt to compare " + typename() + " with " + other.typename())
    }
}
