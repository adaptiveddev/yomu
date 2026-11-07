package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {

    /** Null when WebView is not available on the device (missing, disabled or being updated): requests go without cookies. */
    private val manager: CookieManager? by lazy { runCatching { CookieManager.getInstance() }.getOrNull() }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = manager ?: return
        val urlString = url.toString()

        cookies.forEach { m.setCookie(urlString, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return get(url)
    }

    fun get(url: HttpUrl): List<Cookie> {
        val cookies = manager?.getCookie(url.toString())

        return if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val m = manager ?: return 0
        val urlString = url.toString()
        val cookies = m.getCookie(urlString) ?: return 0

        fun List<String>.filterNames(): List<String> {
            return if (cookieNames != null) {
                this.filter { it in cookieNames }
            } else {
                this
            }
        }

        return cookies.split(";")
            .map { it.substringBefore("=") }
            .filterNames()
            .onEach { m.setCookie(urlString, "$it=;Max-Age=$maxAge") }
            .count()
    }

    fun removeAll() {
        manager?.removeAllCookies {}
    }
}
