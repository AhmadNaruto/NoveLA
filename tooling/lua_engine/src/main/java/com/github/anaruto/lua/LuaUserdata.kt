package com.github.anaruto.lua

/**
 * Represents a host Java/Kotlin object wrapped as a Lua userdata, adapting LuaJ's `org.luaj.vm2.LuaUserdata`.
 *
 * Frequently used in Android/NoveLA to wrap host objects (such as `org.jsoup.nodes.Element` or `Context`)
 * so they can be passed seamlessly through Lua scripts and callbacks.
 */
open class LuaUserdata(
    @JvmField
    val m_instance: Any?
) : LuaValue() {

    override fun type(): Int = TUSERDATA
    override fun typename(): String = "userdata"

    override fun checkuserdata(): Any {
        return m_instance ?: throw LuaError("userdata instance is null")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> checkuserdata(clazz: Class<T>): T {
        if (m_instance != null && clazz.isAssignableFrom(m_instance.javaClass)) {
            return m_instance as T
        }
        throw LuaError("expected userdata of type " + clazz.name + ", got " + (m_instance?.javaClass?.name ?: "null"))
    }

    override fun touserdata(): Any? = m_instance

    override fun tojstring(): String = m_instance?.toString() ?: "userdata: null"
    override fun toString(): String = tojstring()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is LuaUserdata) return this.m_instance == other.m_instance
        return false
    }

    override fun hashCode(): Int = m_instance?.hashCode() ?: 0
}
