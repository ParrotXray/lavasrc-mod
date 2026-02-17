package com.github.topi314.lavasrc.bilibili

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.client.protocol.HttpClientContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class BilibiliHttpContextFilter(
    private val isAuthenticated: Boolean,
    private val canRefreshCookies: Boolean,
    private val sessdata: String,
    private val biliJct: String,
    private val dedeUserId: String,
    private val buvid3: String,
    private val buvid4: String,
    private val acTimeValue: String,
    private val httpInterface: HttpInterface? = null
) : HttpContextFilter {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(BilibiliHttpContextFilter::class.java)
    }

    private val cookieRefreshManager: AtomicReference<CookieRefreshManager?> = AtomicReference(null)

    private fun getCookieRefreshManager(): CookieRefreshManager? {
        if (!canRefreshCookies || httpInterface == null) return null

        return cookieRefreshManager.updateAndGet { current ->
            current ?: CookieRefreshManager(canRefreshCookies, biliJct, buvid3, buvid4, acTimeValue, httpInterface)
        }
    }

    private fun generateBuvid3(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random = Random()
        val length = 32
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    private fun generateBuvid4(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random = Random()
        val length = 36
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    override fun onContextOpen(context: HttpClientContext) {
        //
    }

    override fun onContextClose(context: HttpClientContext) {
        //
    }

    override fun onRequest(context: HttpClientContext, request: HttpUriRequest, isRepetition: Boolean) {
        request.setHeader("Referer", "https://www.bilibili.com/")
        request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        request.setHeader("Origin", "https://www.bilibili.com")
        request.setHeader("Accept", "application/json, text/plain, */*")
        request.setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

        val cookieBuilder = StringBuilder()

        if (isAuthenticated) {
            if (sessdata.isNotEmpty()) {
                cookieBuilder.append("SESSDATA=${sessdata}; ")
            }

            if (biliJct.isNotEmpty()) {
                cookieBuilder.append("bili_jct=${biliJct}; ")
            }

            if (dedeUserId.isNotEmpty()) {
                cookieBuilder.append("DedeUserID=${dedeUserId}; ")
            }

            if (acTimeValue.isNotEmpty()) {
                cookieBuilder.append("ac_time_value=${acTimeValue}; ")
            }

            val resolvedBuvid3 = if (buvid3.isNotEmpty()) buvid3 else generateBuvid3()
            val resolvedBuvid4 = if (buvid4.isNotEmpty()) buvid4 else generateBuvid4()

            cookieBuilder.append("buvid3=${resolvedBuvid3}; ")
            cookieBuilder.append("buvid4=${resolvedBuvid4}; ")
        } else {
            val resolvedBuvid3 = generateBuvid3()
            val resolvedBuvid4 = generateBuvid4()
            cookieBuilder.append("buvid3=${resolvedBuvid3}; ")
            cookieBuilder.append("buvid4=${resolvedBuvid4}; ")
        }

        cookieBuilder.append("CURRENT_FNVAL=4048")

        request.setHeader("Cookie", cookieBuilder.toString())

        if (request.uri.host?.contains("api.bilibili.com") == true) {
            request.setHeader("X-Requested-With", "XMLHttpRequest")

            if (request.uri.path?.contains("/search/") == true) {
                request.setHeader("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                request.setHeader("Sec-Ch-Ua-Mobile", "?0")
                request.setHeader("Sec-Ch-Ua-Platform", "\"Windows\"")
                request.setHeader("Sec-Fetch-Dest", "empty")
                request.setHeader("Sec-Fetch-Mode", "cors")
                request.setHeader("Sec-Fetch-Site", "same-site")
                request.setHeader("Referer", "https://search.bilibili.com/")
            }

            if (isAuthenticated && biliJct.isNotEmpty()) {
                val uri = request.uri.toString()
                if (uri.contains("/web-interface/") || uri.contains("/pgc/player/")) {
                    request.setHeader("X-CSRF-Token", biliJct)
                }
            }
        }
    }

    override fun onRequestResponse(
        context: HttpClientContext,
        request: HttpUriRequest,
        response: HttpResponse
    ): Boolean {
        if (response.statusLine.statusCode == 401 ||
            response.statusLine.statusCode == 403) {

            log.warn("Received authentication error response (${response.statusLine.statusCode}), cookies may need to be refreshed")

            if (canRefreshCookies) {
                val refreshManager = getCookieRefreshManager()
                if (refreshManager != null) {
                    try {
                        val result = refreshManager.refreshCookies()
                        if (result.success) {
                            log.info("Cookie refresh reminder: new cookie values have been logged. Please update your application.yml and restart the service.")
                        } else {
                            log.warn("Cookie refresh check failed: ${result.message}")
                        }
                    } catch (e: Exception) {
                        log.error("Exception occurred while checking cookie refresh", e)
                    }
                }
            } else {
                log.warn("Received authentication error, but ac_time_value is not configured, refresh reminder disabled")
                log.info("Please manually update the cookie configuration or add ac_time_value to enable the refresh reminder")
            }
        }

        return false
    }

    override fun onRequestException(context: HttpClientContext?, request: HttpUriRequest, error: Throwable): Boolean {
        return false
    }
}