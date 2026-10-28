package my.noveldokusha.tooling.native_scraper

object LuaNative {
    val isAvailable: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("native_scraper")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        isAvailable = loaded
    }

    @JvmStatic
    external fun nativeCreateState(bridge: LuaNativeBridge? = null): Long

    @JvmStatic
    external fun nativeCloseState(statePtr: Long)

    @JvmStatic
    external fun nativeExecuteString(statePtr: Long, code: String): String?

    @JvmStatic
    external fun nativeHasFunction(statePtr: Long, functionName: String): Boolean

    @JvmStatic
    external fun nativeCall(statePtr: Long, functionName: String, argsJson: String? = null): String?

    @JvmStatic
    external fun nativeCallFunctionJson(statePtr: Long, functionName: String, args: Array<String>?): String?

    @JvmStatic
    external fun nativeCallFunctionString(statePtr: Long, functionName: String, arg: String?): String?
}
