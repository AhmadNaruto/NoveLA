package com.github.anaruto.lua

import java.io.Closeable

/**
 * Global environment and Lua execution context, adapting LuaJ's `org.luaj.vm2.Globals`.
 *
 * Implements [Closeable] to support Kotlin's `.use { ... }` idiom.
 *
 * Example LuaJ-style usage:
 * ```kotlin
 * val globals = JsePlatform.standardGlobals()
 *
 * // Compile and run a script chunk
 * val chunk = globals.load("return 'Hello from ' .. _VERSION")
 * println(chunk.call().tojstring())
 *
 * // Access and invoke globals
 * val math = globals.get("math")
 * val sqrt = math.get("sqrt")
 * val result = sqrt.call(LuaValue.valueOf(144))
 * println(result.todouble()) // 12.0
 *
 * // Use built-in JSON without require
 * val json = globals.get("json")
 * val encode = json.get("encode")
 * val jsonStr = encode.call(LuaValue.tableOf("name" to "Android", "code" to 100))
 * println(jsonStr.tojstring())
 *
 * globals.close()
 * ```
 */
class Globals private constructor(internal var statePtr: Long) : LuaTable(), Closeable {

    companion object {
        private val activeGlobals = java.util.concurrent.ConcurrentHashMap<Long, java.lang.ref.WeakReference<Globals>>()

        internal fun fromPointer(ptr: Long): Globals? = activeGlobals[ptr]?.get()

        init {
            System.loadLibrary("lua")
        }

        /**
         * Creates a new Globals instance with standard libraries, JSON, and Regex loaded.
         */
        @JvmStatic
        fun create(): Globals {
            val ptr = nativeNewState()
            if (ptr == 0L) {
                throw LuaError("Failed to allocate native Lua state")
            }
            val globals = Globals(ptr)
            activeGlobals[ptr] = java.lang.ref.WeakReference(globals)
            return globals
        }

        // --- Native Bindings ---

        @JvmStatic
        @JvmName("nativeNewState")
        external fun nativeNewState(): Long

        @JvmStatic
        @JvmName("nativeClose")
        external fun nativeClose(ptr: Long)

        @JvmStatic
        @JvmName("nativeLoad")
        external fun nativeLoad(ptr: Long, script: String, chunkname: String): Int

        @JvmStatic
        @JvmName("nativeLoadBytes")
        external fun nativeLoadBytes(ptr: Long, bytes: ByteArray, chunkname: String): Int

        @JvmStatic
        @JvmName("nativeDump")
        external fun nativeDump(ptr: Long, funcRef: Int): ByteArray?

        @JvmStatic
        @JvmName("nativeLoadFile")
        external fun nativeLoadFile(ptr: Long, filePath: String): Int

        @JvmStatic
        @JvmName("nativeInvoke")
        external fun nativeInvoke(ptr: Long, funcRef: Int, args: Array<LuaValue>): Array<LuaValue>

        @JvmStatic
        @JvmName("nativeUnref")
        external fun nativeUnref(ptr: Long, ref: Int)

        @JvmStatic
        @JvmName("nativeGetGlobal")
        external fun nativeGetGlobal(ptr: Long, name: String): LuaValue

        @JvmStatic
        @JvmName("nativeSetGlobal")
        external fun nativeSetGlobal(ptr: Long, name: String, value: LuaValue)

        @JvmStatic
        @JvmName("nativeTableGet")
        external fun nativeTableGet(ptr: Long, tableRef: Int, key: LuaValue): LuaValue

        @JvmStatic
        @JvmName("nativeTableSet")
        external fun nativeTableSet(ptr: Long, tableRef: Int, key: LuaValue, value: LuaValue)

        @JvmStatic
        @JvmName("nativeTableLength")
        external fun nativeTableLength(ptr: Long, tableRef: Int): Int

        @JvmStatic
        @JvmName("nativeTableKeys")
        external fun nativeTableKeys(ptr: Long, tableRef: Int): Array<LuaValue>

        @JvmStatic
        @JvmName("nativeNewTable")
        external fun nativeNewTable(ptr: Long): Int

        @JvmStatic
        @JvmName("nativeDoString")
        external fun nativeDoString(ptr: Long, script: String): Array<LuaValue>?

        @JvmStatic
        @JvmName("nativeDoFile")
        external fun nativeDoFile(ptr: Long, filePath: String): Array<LuaValue>?
    }

    val isClosed: Boolean
        get() = statePtr == 0L

    private fun checkState() {
        if (statePtr == 0L) {
            throw LuaError("Globals state has already been closed")
        }
    }

    /**
     * Compiles a Lua script chunk into an executable [LuaFunction].
     *
     * @param script Lua source code string.
     * @param chunkname Identifying name for error reporting (default: "chunk").
     * @return [LuaFunction] chunk ready to be called via `.call()` or `.invoke()`.
     */
    fun load(script: String, chunkname: String = "chunk"): LuaFunction = synchronized(this) {
        checkState()
        val ref = nativeLoad(statePtr, script, chunkname)
        if (ref == -1) {
            throw LuaError("Failed to compile script chunk: $chunkname")
        }
        NativeLuaFunction(statePtr, ref)
    }

    /**
     * Compiles a Lua script file from the given path into an executable [LuaFunction].
     */
    fun loadfile(filePath: String): LuaFunction = synchronized(this) {
        checkState()
        val ref = nativeLoadFile(statePtr, filePath)
        if (ref == -1) {
            throw LuaError("Failed to load script file: $filePath")
        }
        NativeLuaFunction(statePtr, ref)
    }

    /**
     * Executes the script chunk immediately and returns all return values as [Varargs].
     */
    fun doString(script: String): Varargs = synchronized(this) {
        checkState()
        val results = nativeDoString(statePtr, script)
            ?: throw LuaError("Failed to execute Lua script")
        Varargs.of(*results)
    }

    /**
     * Executes the script file immediately and returns all return values as [Varargs].
     */
    fun doFile(filePath: String): Varargs = synchronized(this) {
        checkState()
        val results = nativeDoFile(statePtr, filePath)
            ?: throw LuaError("Failed to execute Lua script file: $filePath")
        Varargs.of(*results)
    }

    /**
     * Evaluates a single Lua expression (e.g. `1 + 2` or `json.encode(data)`) and returns the resulting [LuaValue].
     */
    fun eval(expression: String): LuaValue {
        val chunk = load("return $expression", "eval")
        return chunk.call()
    }

    /**
     * Creates a new native Lua table inside the Lua state.
     */
    fun newTable(): LuaTable = synchronized(this) {
        checkState()
        val ref = nativeNewTable(statePtr)
        NativeLuaTable(statePtr, ref)
    }

    // --- Global Variable Access (LuaJ Globals as environment table) ---

    override operator fun get(key: LuaValue): LuaValue = synchronized(this) {
        checkState()
        if (key.isstring()) {
            nativeGetGlobal(statePtr, key.tojstring())
        } else {
            NIL
        }
    }

    override operator fun get(key: String): LuaValue = synchronized(this) {
        checkState()
        nativeGetGlobal(statePtr, key)
    }

    override operator fun set(key: LuaValue, value: LuaValue) = synchronized(this) {
        checkState()
        if (key.isstring()) {
            nativeSetGlobal(statePtr, key.tojstring(), value)
        } else {
            throw LuaError("Global variable keys must be strings, got " + key.typename())
        }
    }

    override operator fun set(key: String, value: LuaValue) = synchronized(this) {
        checkState()
        nativeSetGlobal(statePtr, key, value)
    }

    private val unrefQueue = java.lang.ref.ReferenceQueue<Any>()
    private val phantomRefs = java.util.concurrent.ConcurrentHashMap<PhantomRefKey, Boolean>()

    private class PhantomRefKey(
        referent: Any,
        queue: java.lang.ref.ReferenceQueue<Any>,
        val globalsPtr: Long,
        val ref: Int
    ) : java.lang.ref.PhantomReference<Any>(referent, queue)

    internal fun registerAutoUnref(obj: Any, ref: Int) {
        if (ref == -1 || statePtr == 0L) return
        drainUnrefs()
        val pRef = PhantomRefKey(obj, unrefQueue, statePtr, ref)
        phantomRefs[pRef] = true
    }

    internal fun drainUnrefs() = synchronized(this) {
        if (statePtr == 0L) {
            phantomRefs.clear()
            return
        }
        var p = unrefQueue.poll() as? PhantomRefKey
        while (p != null) {
            phantomRefs.remove(p)
            if (p.globalsPtr == statePtr && statePtr != 0L && p.ref != -1) {
                nativeUnref(statePtr, p.ref)
            }
            p = unrefQueue.poll() as? PhantomRefKey
        }
    }

    internal fun manualUnref(ref: Int) = synchronized(this) {
        if (statePtr != 0L && ref != -1) {
            nativeUnref(statePtr, ref)
        }
    }

    override fun close() = synchronized(this) {
        if (statePtr != 0L) {
            val ptr = statePtr
            statePtr = 0L
            activeGlobals.remove(ptr)
            drainUnrefs()
            nativeClose(ptr)
        }
    }

    /**
     * Compiles a Lua script directly from a Java [java.io.InputStream].
     */
    @Suppress("UNUSED_PARAMETER")
    fun load(
        inputStream: java.io.InputStream,
        chunkname: String = "stream",
        mode: String = "bt",
        env: LuaValue = this
    ): LuaFunction {
        val bytes = inputStream.readBytes()
        return load(bytes, chunkname)
    }

    /**
     * Loads a pre-compiled binary bytecode chunk or Lua source script directly from a [ByteArray].
     */
    fun load(bytes: ByteArray, chunkname: String = "chunk"): LuaFunction = synchronized(this) {
        checkState()
        drainUnrefs()
        val ref = nativeLoadBytes(statePtr, bytes, chunkname)
        if (ref == -1) {
            throw LuaError("Failed to compile script chunk: $chunkname")
        }
        NativeLuaFunction(statePtr, ref)
    }

    /**
     * Dumps an executable [LuaFunction] into native Lua 5.5 binary bytecode.
     */
    fun dump(function: LuaFunction): ByteArray = synchronized(this) {
        checkState()
        drainUnrefs()
        if (function is NativeLuaFunction) {
            return nativeDump(statePtr, function.ref)
                ?: throw LuaError("Failed to dump function bytecode")
        }
        val stringLib = get("string")
        val dumpFn = stringLib.get("dump")
        if (!dumpFn.isfunction()) {
            throw LuaError("string.dump is not available")
        }
        val byteStr = dumpFn.call(function).tojstring()
        return byteStr.toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * Compiles a Lua script string directly into serialized binary bytecode.
     */
    fun compileBytecode(script: String, chunkname: String = "script"): ByteArray {
        val fn = load(script, chunkname)
        return dump(fn)
    }

    /**
     * Compatibility helper for LuaJ's `compilePrototype(Reader, chunkName)`.
     */
    fun compilePrototype(reader: java.io.Reader, chunkname: String = "script"): ByteArray {
        val script = reader.readText()
        return compileBytecode(script, chunkname)
    }

    /**
     * Loads a script file directly from Android's assets folder via reflection,
     * maintaining compatibility with both Android and standard JVM runtimes.
     *
     * @param assetManager An instance of `android.content.res.AssetManager`.
     * @param assetPath Path within the `assets/` directory (e.g. "scripts/main.lua").
     */
    fun loadAsset(assetManager: Any, assetPath: String): LuaFunction {
        val method = assetManager.javaClass.getMethod("open", String::class.java)
        val stream = method.invoke(assetManager, assetPath) as java.io.InputStream
        return load(stream, assetPath)
    }

    /**
     * Installs a custom loader into Lua's `package.searchers` so that calls like `require("my.module")`
     * will automatically resolve and execute `assets/{basePath}/my/module.lua`.
     *
     * @param assetManager An instance of `android.content.res.AssetManager`.
     * @param basePath Base subdirectory inside `assets/` (default: "lua").
     */
    fun installAssetSearcher(assetManager: Any, basePath: String = "lua") {
        val packageTable = get("package")
        val searchers = packageTable.get("searchers")
        if (searchers.istable()) {
            val searcherFunc = object : OneArgFunction() {
                override fun call(arg1: LuaValue): LuaValue {
                    val modName = arg1.checkjstring()
                    val relativePath = (if (basePath.isEmpty()) "" else "$basePath/") +
                        modName.replace('.', '/') + ".lua"
                    return try {
                        val method = assetManager.javaClass.getMethod("open", String::class.java)
                        val stream = method.invoke(assetManager, relativePath) as java.io.InputStream
                        val script = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        load(script, relativePath)
                    } catch (e: Exception) {
                        LuaValue.valueOf("\n\tno asset '$relativePath'")
                    }
                }
            }
            val len = searchers.length()
            searchers.set(len + 1, searcherFunc)
        }
    }
}
