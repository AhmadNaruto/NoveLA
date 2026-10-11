package my.noveldokusha.tooling.native_scraper

object LuaNative {
    init {
        try {
            System.loadLibrary("native_scraper")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    external fun nativeCreateState(): Long

    @JvmStatic
    external fun nativeCloseState(statePtr: Long)

    @JvmStatic
    external fun nativeExecuteString(statePtr: Long, code: String): String?

    @JvmStatic
    external fun nativeCallFunctionJson(statePtr: Long, functionName: String, args: Array<String>?): String?

    @JvmStatic
    external fun nativeCallFunctionString(statePtr: Long, functionName: String, arg: String?): String?
}
