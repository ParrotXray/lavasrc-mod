package com.github.topi314.lavasrc.bilibili

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.util.EntityUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern

/**
 * Bilibili Cookie Refresh Reminder Manager
 *
 * Checks whether Bilibili cookies need refreshing, calls the refresh API, and logs
 * the new cookie values. The user must manually copy the logged values back into
 * application.yml and restart the service — this is a reminder flow, not a fully
 * automatic write-back.
 *
 * Reference: https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/login/cookie_refresh.md
 */
class CookieRefreshManager(
    private val canRefreshCookies: Boolean,
    private val biliJct: String,
    private val buvid3: String,
    private val buvid4: String,
    private val acTimeValue: String,
    private val httpInterface: HttpInterface
) {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(CookieRefreshManager::class.java)

        const val COOKIE_INFO_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
        const val COOKIE_REFRESH_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
        const val CONFIRM_REFRESH_URL = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh"
        const val CORRESPOND_BASE_URL = "https://www.bilibili.com/correspond/1/"

        private val REFRESH_CSRF_PATTERN = Pattern.compile("<div id=\"1-name\">([^<]+)</div>")
    }

    fun shouldRefreshCookies(): Boolean {
        if (!canRefreshCookies) {
            return false
        }

        return try {
            val response = httpInterface.execute(HttpGet(COOKIE_INFO_URL))
            val responseJson = JsonBrowser.parse(response.entity.content)

            val code = responseJson.get("code").asLong(0)
            val refresh = responseJson.get("data").get("refresh").asBoolean(false)

            log.debug("Cookie check result: code=$code, refresh=$refresh")

            code == 0L && refresh
        } catch (e: Exception) {
            log.warn("Failed to check cookie status", e)
            false
        }
    }

    fun refreshCookies(): RefreshResult {
        if (!canRefreshCookies) {
            return RefreshResult.error("ac_time_value not configured or authentication info incomplete")
        }

        return try {
            log.info("Starting Bilibili cookie refresh check (new values will be logged — please update application.yml manually)...")

            val correspondPath = generateCorrespondPath()
            log.debug("Generated CorrespondPath: $correspondPath")

            val refreshCsrf = getRefreshCsrf(correspondPath)
            log.debug("Retrieved refresh_csrf: $refreshCsrf")

            val refreshResult = performCookieRefresh(refreshCsrf)
            if (!refreshResult.success) {
                return refreshResult
            }

            val confirmResult = confirmRefresh(refreshResult.newRefreshToken!!)

            if (confirmResult.success) {
                log.info("Cookie refresh succeeded")
            }

            confirmResult

        } catch (e: Exception) {
            log.error("Error occurred during cookie refresh", e)
            RefreshResult.error("Refresh failed: ${e.message}")
        }
    }

    private fun generateCorrespondPath(): String {
        val timestamp = System.currentTimeMillis()

        val input = "${timestamp}_${ThreadLocalRandom.current().nextLong()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))

        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getRefreshCsrf(correspondPath: String): String {
        val correspondUrl = "$CORRESPOND_BASE_URL$correspondPath"
        val response = httpInterface.execute(HttpGet(correspondUrl))

        val htmlContent = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)
        val matcher = REFRESH_CSRF_PATTERN.matcher(htmlContent)

        if (matcher.find()) {
            return matcher.group(1)
        } else {
            throw IllegalStateException("Unable to extract refresh_csrf from correspond page")
        }
    }

    private fun performCookieRefresh(refreshCsrf: String): RefreshResult {
        val params = mapOf(
            "csrf" to biliJct,
            "refresh_csrf" to refreshCsrf,
            "source" to "main_web",
            "refresh_token" to acTimeValue
        )

        val postData = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

        val request = HttpPost(COOKIE_REFRESH_URL)
        request.setHeader("Content-Type", "application/x-www-form-urlencoded")
        request.entity = StringEntity(postData, StandardCharsets.UTF_8)

        val response = httpInterface.execute(request)
        val responseJson = JsonBrowser.parse(response.entity.content)

        val code = responseJson.get("code").asLong(-1)
        if (code != 0L) {
            val message = responseJson.get("message").text() ?: "Unknown error"
            return RefreshResult.error("Refresh failed: $message (code: $code)")
        }

        val newRefreshToken = responseJson.get("data").get("refresh_token").text()

        val newCookies = mutableMapOf<String, String>()
        response.allHeaders.filter { it.name.equals("Set-Cookie", ignoreCase = true) }
            .forEach { header ->
                val cookieValue = header.value
                val cookieParts = cookieValue.split(";")[0].split("=", limit = 2)
                if (cookieParts.size == 2) {
                    newCookies[cookieParts[0]] = cookieParts[1]
                }
            }

        log.info("=".repeat(80))
        log.info("Cookie refresh succeeded! Please update your application.yml configuration file:")

        newCookies["SESSDATA"]?.let {
            log.info("sessdata: $it")
        }
        newCookies["bili_jct"]?.let {
            log.info("biliJct: $it")
        }
        newCookies["DedeUserID"]?.let {
            log.info("dedeUserId: $it")
        }

        log.info("buvid3: $buvid3")
        log.info("buvid4: $buvid4")
        log.info("acTimeValue: $newRefreshToken")

        return RefreshResult.success(newRefreshToken, newCookies)
    }

    private fun confirmRefresh(newRefreshToken: String): RefreshResult {
        val params = mapOf(
            "csrf" to biliJct,
            "refresh_token" to acTimeValue
        )

        val postData = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

        val request = HttpPost(CONFIRM_REFRESH_URL)
        request.setHeader("Content-Type", "application/x-www-form-urlencoded")
        request.entity = StringEntity(postData, StandardCharsets.UTF_8)

        val response = httpInterface.execute(request)
        val responseJson = JsonBrowser.parse(response.entity.content)

        val code = responseJson.get("code").asLong(-1)
        if (code != 0L) {
            val message = responseJson.get("message").text() ?: "Unknown error"
            return RefreshResult.error("Confirm refresh failed: $message (code: $code)")
        }

        return RefreshResult.success(newRefreshToken, emptyMap())
    }

    data class RefreshResult(
        val success: Boolean,
        val message: String,
        val newRefreshToken: String? = null,
        val newCookies: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun success(newRefreshToken: String, newCookies: Map<String, String>) =
                RefreshResult(true, "Refresh succeeded", newRefreshToken, newCookies)

            fun error(message: String) = RefreshResult(false, message)
        }
    }
}