package my.noveldokusha.tooling.native_scraper

/**
 * Bridge interface connecting Lua Native runtime to Android platform services:
 * OkHttp networking, SharedPreferences, CookieJar, local storage, and logging.
 */
interface LuaNativeBridge {
    fun httpGet(url: String, configJson: String?): String?
    fun httpPost(url: String, body: String?, configJson: String?): String?
    fun httpGetBatch(itemsJson: String, configJson: String?): String?
    fun getPreference(key: String): String?
    fun setPreference(key: String, value: String?)
    fun getCookies(url: String): String?
    fun setCookies(url: String, cookiesJson: String)
    fun getLocalStorage(url: String, key: String): String?
    fun showError(title: String, message: String, authUrl: String?)
    fun log(level: Int, message: String)
    fun sleep(ms: Long)
}
