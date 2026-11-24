package com.github.anaruto.lua

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Represents a Lua table, adapting LuaJ's `LuaTable`.
 *
 * Can function both as a standalone in-memory table and as a bridge to native Lua tables.
 */
open class LuaTable : LuaValue() {

    private val map: MutableMap<LuaValue, LuaValue> = LinkedHashMap()
    private var metatable: LuaValue = NIL

    override fun type(): Int = TTABLE
    override fun istable(): Boolean = true
    override fun checktable(): LuaTable = this

    override fun getmetatable(): LuaValue = metatable

    override fun setmetatable(metatable: LuaValue): LuaValue {
        this.metatable = metatable
        return this
    }

    override operator fun get(key: LuaValue): LuaValue {
        val direct = map[key]
        if (direct != null && !direct.isnil()) {
            return direct
        }
        if (!metatable.isnil()) {
            val indexHandler = metatable.get(INDEX)
            if (indexHandler.istable()) {
                return indexHandler.get(key)
            } else if (indexHandler.isfunction()) {
                return indexHandler.call(this, key)
            }
        }
        return NIL
    }

    override operator fun set(key: LuaValue, value: LuaValue) {
        if (value.isnil()) {
            map.remove(key)
        } else {
            map[key] = value
        }
    }

    override fun rawget(key: LuaValue): LuaValue = map[key] ?: NIL
    override fun rawget(key: String): LuaValue = rawget(valueOf(key))
    override fun rawget(key: Int): LuaValue = rawget(valueOf(key))

    override fun rawset(key: LuaValue, value: LuaValue): LuaTable {
        if (value.isnil()) {
            map.remove(key)
        } else {
            map[key] = value
        }
        return this
    }
    override fun rawset(key: String, value: LuaValue): LuaTable = rawset(valueOf(key), value)
    override fun rawset(key: Int, value: LuaValue): LuaTable = rawset(valueOf(key), value)

    override fun length(): Int {
        var len = 0
        while (get(valueOf(len + 1)) !is LuaNil) {
            len++
        }
        return len
    }

    open fun keys(): List<LuaValue> {
        return ArrayList(map.keys)
    }

    open fun keysArray(): Array<LuaValue> = keys().toTypedArray()

    open fun toMap(): Map<LuaValue, LuaValue> {
        return Collections.unmodifiableMap(map)
    }

    override fun tojstring(): String = toString()

    override fun toString(): String {
        return map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "${k.tojstring()}=${v.tojstring()}"
        }
    }
}

/**
 * Native bridge implementation of a Lua table residing inside the C Lua state.
 *
 * Keeps a registry reference in `LUA_REGISTRYINDEX` to ensure the table is not garbage-collected.
 */
@androidx.annotation.Keep
class NativeLuaTable internal constructor(
    @JvmField internal val statePtr: Long,
    @JvmField internal val ref: Int
) : LuaTable(), AutoCloseable {

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val globals: Globals? = Globals.fromPointer(statePtr)

    init {
        globals?.registerAutoUnref(this, ref)
    }

    override operator fun get(key: LuaValue): LuaValue {
        val g = globals
        if (g != null) {
            return synchronized(g) {
                g.drainUnrefs()
                if (g.isClosed || ref == -1 || closed.get()) NIL
                else Globals.nativeTableGet(g.statePtr, ref, key)
            }
        }
        if (statePtr == 0L || ref == -1 || closed.get()) return NIL
        return Globals.nativeTableGet(statePtr, ref, key)
    }

    override operator fun set(key: LuaValue, value: LuaValue) {
        val g = globals
        if (g != null) {
            synchronized(g) {
                g.drainUnrefs()
                if (g.isClosed || ref == -1 || closed.get()) throw LuaError("Native table is closed or invalid")
                Globals.nativeTableSet(g.statePtr, ref, key, value)
            }
            return
        }
        if (statePtr == 0L || ref == -1 || closed.get()) throw LuaError("Native table is closed or invalid")
        Globals.nativeTableSet(statePtr, ref, key, value)
    }

    override fun rawget(key: LuaValue): LuaValue = get(key)

    override fun rawset(key: LuaValue, value: LuaValue): LuaTable {
        set(key, value)
        return this
    }

    override fun length(): Int {
        val g = globals
        if (g != null) {
            return synchronized(g) {
                g.drainUnrefs()
                if (g.isClosed || ref == -1 || closed.get()) 0
                else Globals.nativeTableLength(g.statePtr, ref)
            }
        }
        if (statePtr == 0L || ref == -1 || closed.get()) return 0
        return Globals.nativeTableLength(statePtr, ref)
    }

    override fun keys(): List<LuaValue> {
        val g = globals
        if (g != null) {
            return synchronized(g) {
                g.drainUnrefs()
                if (g.isClosed || ref == -1 || closed.get()) emptyList()
                else Globals.nativeTableKeys(g.statePtr, ref).toList()
            }
        }
        if (statePtr == 0L || ref == -1 || closed.get()) return emptyList()
        return Globals.nativeTableKeys(statePtr, ref).toList()
    }

    override fun toMap(): Map<LuaValue, LuaValue> {
        val result = LinkedHashMap<LuaValue, LuaValue>()
        for (k in keys()) {
            result[k] = get(k)
        }
        return result
    }

    override fun toString(): String {
        return "table: 0x" + Integer.toHexString(ref)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val g = globals
            if (g != null) {
                g.manualUnref(ref)
            } else if (statePtr != 0L && ref != -1) {
                Globals.nativeUnref(statePtr, ref)
            }
        }
    }
}
