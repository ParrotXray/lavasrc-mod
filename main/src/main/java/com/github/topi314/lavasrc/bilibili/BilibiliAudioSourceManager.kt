package com.github.topi314.lavasrc.bilibili

import com.github.topi314.lavalyrics.AudioLyricsManager
import com.github.topi314.lavalyrics.lyrics.AudioLyrics
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics
import com.github.topi314.lavasearch.AudioSearchManager
import com.github.topi314.lavasearch.result.AudioSearchResult
import com.github.topi314.lavasearch.result.BasicAudioSearchResult
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.DataInput
import java.io.DataOutput
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.regex.Pattern

class BilibiliAudioSourceManager(
    private val allowSearch: Boolean = true,
    private val authEnabled: Boolean = false,
    private val sessdata: String = "",
    private val biliJct: String = "",
    private val dedeUserId: String = "",
    private val buvid3: String = "",
    private val buvid4: String = "",
    private val acTimeValue: String = ""
) : AudioSourceManager, AudioSearchManager, AudioLyricsManager {
    val log: Logger = LoggerFactory.getLogger(BilibiliAudioSourceManager::class.java)

    val httpInterface: HttpInterface
    private var playlistPageCountConfig: Int = -1

    private val isAuthenticated: Boolean get() = authEnabled && sessdata.isNotEmpty()
    private val canRefreshCookies: Boolean get() = isAuthenticated && acTimeValue.isNotEmpty()

    init {
        val httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()

        val httpContextFilter = BilibiliHttpContextFilter(
            isAuthenticated, canRefreshCookies, sessdata, biliJct, dedeUserId, buvid3, buvid4, acTimeValue, null
        )
        httpInterfaceManager.setHttpContextFilter(httpContextFilter)
        httpInterface = httpInterfaceManager.`interface`

        val updatedFilter = BilibiliHttpContextFilter(
            isAuthenticated, canRefreshCookies, sessdata, biliJct, dedeUserId, buvid3, buvid4, acTimeValue, httpInterface
        )
        httpInterfaceManager.setHttpContextFilter(updatedFilter)

        // Check cookie state on startup and remind user if refresh is needed
        when {
            canRefreshCookies -> {
                try {
                    val cookieRefreshManager = CookieRefreshManager(
                        canRefreshCookies, biliJct, buvid3, buvid4, acTimeValue, httpInterface
                    )
                    if (cookieRefreshManager.shouldRefreshCookies()) {
                        log.info("Detected cookies need refresh on startup, checking new values...")
                        val result = cookieRefreshManager.refreshCookies()
                        if (result.success) {
                            log.info("Cookie refresh reminder: new cookie values have been logged above. Please update your application.yml and restart the service.")
                        } else {
                            log.warn("Cookie refresh check failed: ${result.message}")
                        }
                    } else {
                        log.info("Cookie check: current cookie state is normal")
                    }
                } catch (e: Exception) {
                    log.warn("Failed to check cookie state: ${e.message}")
                }
            }
            isAuthenticated -> {
                log.info("Using fixed cookie authentication mode (ac_time_value not configured, refresh reminder disabled)")
            }
        }
    }

    override fun getSourceName(): String {
        return "bilibili"
    }

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        log.debug("DEBUG: reference.identifier: ${reference.identifier}")

        if (reference.identifier.startsWith(SEARCH_PREFIX)) {
            if (!allowSearch) {
                log.debug("Bilibili search is disabled in configuration")
                return BasicAudioPlaylist("Bilibili Search Disabled", emptyList(), null, true)
            }
            val searchQuery = reference.identifier.removePrefix(SEARCH_PREFIX).trim()
            log.debug("DEBUG: Bilibili search query: $searchQuery")
            val tracks = doSearch(searchQuery)
            return BasicAudioPlaylist("Bilibili Search: $searchQuery", tracks, null, true)
        }

        // Handle b23.tv short URLs by resolving them first
        val resolvedUrl = if (reference.identifier.contains("b23.tv")) {
            resolveShortUrl(reference.identifier)
        } else {
            reference.identifier
        }

        log.debug("DEBUG: resolved URL: $resolvedUrl")

        val matcher = URL_PATTERN.matcher(resolvedUrl)
        if (matcher.find()) {
            when (matcher.group("type")) {
                "video" -> {
                    log.debug("DEBUG: type: video")
                    val bvid = matcher.group("id")

                    val page = extractPageParameter(resolvedUrl)
                    log.debug("DEBUG: extracted page parameter: $page")

                    val type: String? = when (matcher.group("audioType")) {
                        "av" -> "av"
                        else -> null
                    }

                    var response: CloseableHttpResponse
                    if (type != null) {
                        val aid = bvid.removePrefix("av")
                        response = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/view?aid=$aid"))
                    } else {
                        response = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/view?bvid=$bvid"))
                    }

                    log.debug("DEBUG: attempt GET with URL: ${BASE_URL}x/web-interface/view?bvid=$bvid")
                    val responseJson = JsonBrowser.parse(response.entity.content)

                    val statusCode = responseJson.get("code").`as`(Int::class.java)
                    log.debug("DEBUG: statusCode: $statusCode")

                    if (statusCode != 0) {
                        val message = responseJson.get("message").text() ?: "Unknown error"
                        log.debug("Failed to load video: $message (code: $statusCode)")
                        return AudioReference.NO_TRACK
                    }

                    val trackData = responseJson.get("data")
                    val pagesCount = trackData.get("pages").values().size
                    val hasPageParameter = page > 0

                    return if (pagesCount > 1) {
                        if (hasPageParameter) {
                            loadVideoFromAnthology(trackData, page - 1)
                        } else {
                            loadVideoAnthology(trackData, 0)
                        }
                    } else {
                        loadVideo(trackData)
                    }
                }
                "audio" -> {
                    val type = when (matcher.group("audioType")) {
                        "am" -> "menu"
                        "au" -> "song"
                        else -> return AudioReference.NO_TRACK
                    }
                    val sid = matcher.group("audioId")

                    val response = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/$type/info?sid=$sid"))
                    val responseJson = JsonBrowser.parse(response.entity.content)

                    val statusCode = responseJson.get("code").`as`(Int::class.java)
                    if (statusCode != 0) {
                        val message = responseJson.get("message").text() ?: "Unknown error"
                        log.warn("Failed to load audio: $message (code: $statusCode)")
                        return AudioReference.NO_TRACK
                    }

                    return when (type) {
                        "song" -> loadAudio(responseJson.get("data"))
                        "menu" -> loadAudioPlaylist(responseJson.get("data"))
                        else -> AudioReference.NO_TRACK
                    }
                }
            }
        }
        return null
    }

    private fun extractPageParameter(url: String): Int {
        return try {
            val pageRegex = Regex("[?&]p=(\\d+)")
            val matchResult = pageRegex.find(url)
            matchResult?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            log.debug("Failed to extract page parameter from URL: $url", e)
            0
        }
    }

    override fun loadSearch(query: String, types: Set<AudioSearchResult.Type>): AudioSearchResult? {
        if (!query.startsWith(SEARCH_PREFIX)) return null
        val searchQuery = query.removePrefix(SEARCH_PREFIX).trim()
        val tracks = doSearch(searchQuery)
        return BasicAudioSearchResult(tracks, emptyList(), emptyList(), emptyList(), emptyList())
    }

    private fun doSearch(query: String): List<AudioTrack> {
        return try {
            val searchParams = mapOf(
                "search_type" to "video",
                "keyword" to query,
                "page" to "1",
                "page_size" to "20",
                "order" to "totalrank",
                "duration" to "0",
                "tids_1" to "0"
            )

            val searchUrl = "${BASE_URL}x/web-interface/wbi/search/type?${signWbi(searchParams)}"

            log.debug("DEBUG: Bilibili search URL: $searchUrl")

            val response = httpInterface.execute(HttpGet(searchUrl))
            val responseJson = JsonBrowser.parse(response.entity.content)

            val statusCode = responseJson.get("code").`as`(Int::class.java)
            if (statusCode != 0) {
                val message = responseJson.get("message").text() ?: "Unknown error"
                log.warn("Bilibili search failed with status code: $statusCode, message: $message")

                when (statusCode) {
                    -412 -> log.error("Search blocked (-412): Need cookies. ${if (!isAuthenticated) "Configure authentication" else "Cookies may be expired"}")
                    -403 -> log.error("Access forbidden (-403): Rate limited or banned")
                    -400 -> log.error("Bad request (-400): Invalid parameters")
                }

                return emptyList()
            }

            val searchResults = responseJson.get("data").get("result")
            val tracks = ArrayList<AudioTrack>()

            for (item in searchResults.values()) {
                try {
                    val bvid = item.get("bvid")?.text()
                    val title = item.get("title")?.text()
                    val author = item.get("author")?.text()
                    val duration = item.get("duration")?.text()
                    val pic = item.get("pic")?.text()

                    if (bvid != null && title != null && author != null) {
                        val durationMs = parseDuration(duration)
                        val cleanTitle = cleanHtmlTags(title)
                        val cleanAuthor = cleanHtmlTags(author)

                        tracks.add(BilibiliAudioTrack(
                            AudioTrackInfo(
                                cleanTitle,
                                cleanAuthor,
                                durationMs,
                                bvid,
                                false,
                                getVideoUrl(bvid),
                                pic,
                                if (pic != null) "" else null
                            ),
                            BilibiliAudioTrack.TrackType.VIDEO,
                            bvid,
                            item.get("cid")?.asLong(0) ?: 0L,
                            this
                        ))
                    }
                } catch (e: Exception) {
                    log.warn("Failed to parse search result item", e)
                }
            }

            log.debug("DEBUG: Found ${tracks.size} tracks for query: $query")
            tracks

        } catch (e: Exception) {
            log.error("Error during Bilibili search", e)
            emptyList()
        }
    }

    private fun parseDuration(duration: String?): Long {
        if (duration == null) return 0L

        return try {
            val parts = duration.split(":")
            when (parts.size) {
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toLong()
                    (minutes * 60 + seconds) * 1000
                }
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toLong()
                    (hours * 3600 + minutes * 60 + seconds) * 1000
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun cleanHtmlTags(text: String): String {
        return text.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun resolveShortUrl(shortUrl: String): String {
        return try {
            val response = httpInterface.execute(HttpGet(shortUrl))
            val location = response.getFirstHeader("Location")?.value
            if (location != null && location.contains("bilibili.com")) {
                location
            } else {
                response.getFirstHeader("Content-Location")?.value ?: shortUrl
            }
        } catch (e: Exception) {
            log.warn("Failed to resolve short URL: $shortUrl", e)
            shortUrl
        }
    }

    fun setPlaylistPageCount(count: Int): BilibiliAudioSourceManager {
        playlistPageCountConfig = count
        return this
    }

    private fun loadVideo(trackData: JsonBrowser): AudioTrack {
        val bvid = trackData.get("bvid").`as`(String::class.java)
        log.debug("DEBUG: ${trackData.text()}")

        val artworkUrl: String? = if (trackData.get("pic").text() != null) {
            trackData.get("pic").text()
        } else {
            trackData.get("first_frame").text()
        }

        return BilibiliAudioTrack(
            AudioTrackInfo(
                trackData.get("title").`as`(String::class.java),
                trackData.get("owner").get("name").`as`(String::class.java),
                trackData.get("duration").asLong(0) * 1000,
                bvid,
                false,
                getVideoUrl(bvid),
                artworkUrl,
                if (artworkUrl != null) "" else null
            ),
            BilibiliAudioTrack.TrackType.VIDEO,
            bvid,
            trackData.get("cid").asLong(0),
            this
        )
    }

    private fun loadVideoFromAnthology(trackData: JsonBrowser, pageIndex: Int): AudioTrack {
        log.debug("DEBUG: Loading single track from anthology, page: $pageIndex")
        log.debug("DEBUG: ${trackData.text()}")

        val author = trackData.get("owner").get("name").`as`(String::class.java)
        val bvid = trackData.get("bvid").`as`(String::class.java)
        val pages = trackData.get("pages").values()

        if (pageIndex < 0 || pageIndex >= pages.size) {
            log.warn("Invalid page index: $pageIndex, total pages: ${pages.size}")
            return loadVideo(trackData)
        }

        val pageData = pages[pageIndex]

        val artworkUrl: String? = if (trackData.get("pic").text() != null) {
            trackData.get("pic").text()
        } else {
            trackData.get("first_frame").text()
        }

        return BilibiliAudioTrack(
            AudioTrackInfo(
                pageData.get("part").`as`(String::class.java),
                author,
                pageData.get("duration").asLong(0) * 1000,
                bvid,
                false,
                getVideoUrl(bvid, pageData.get("page").`as`(Int::class.java)),
                artworkUrl,
                if (artworkUrl != null) "" else null
            ),
            BilibiliAudioTrack.TrackType.VIDEO,
            bvid,
            pageData.get("cid").asLong(0),
            this
        )
    }

    private fun loadVideoAnthology(trackData: JsonBrowser, selectedPage: Int): AudioPlaylist {
        log.debug("DEBUG: ${trackData.text()}")

        val playlistName = trackData.get("title").`as`(String::class.java)
        val author = trackData.get("owner").get("name").`as`(String::class.java)
        val bvid = trackData.get("bvid").`as`(String::class.java)

        val tracks = ArrayList<AudioTrack>()

        for (item in trackData.get("pages").values()) {
            log.debug("DEBUG: ${item.text()}")
            val artworkUrl: String? = if (trackData.get("pic").text() != null) {
                trackData.get("pic").text()
            } else {
                trackData.get("first_frame").text()
            }

            tracks.add(BilibiliAudioTrack(
                AudioTrackInfo(
                    item.get("part").`as`(String::class.java),
                    author,
                    item.get("duration").asLong(0) * 1000,
                    bvid,
                    false,
                    getVideoUrl(bvid, item.get("page").`as`(Int::class.java)),
                    artworkUrl,
                    if (artworkUrl != null) "" else null
                ),
                BilibiliAudioTrack.TrackType.VIDEO,
                bvid,
                item.get("cid").asLong(0),
                this
            ))
        }

        val selectedTrack = if (selectedPage in 0 until tracks.size) tracks[selectedPage] else null

        return BasicAudioPlaylist(playlistName, tracks, selectedTrack, false)
    }

    private fun loadAudio(trackData: JsonBrowser): AudioTrack {
        val sid = trackData.get("statistic").get("sid").asLong(0).toString()
        log.debug("DEBUG: ${trackData.text()}")

        return BilibiliAudioTrack(
            AudioTrackInfo(
                trackData.get("title").`as`(String::class.java),
                trackData.get("uname").`as`(String::class.java),
                trackData.get("duration").asLong(0) * 1000,
                "au$sid",
                false,
                getAudioUrl("au$sid")
            ),
            BilibiliAudioTrack.TrackType.AUDIO,
            sid,
            null,
            this
        )
    }

    private fun loadAudioPlaylist(playlistData: JsonBrowser): AudioPlaylist {
        log.debug("DEBUG: ${playlistData.text()}")

        val playlistName = playlistData.get("title").`as`(String::class.java)
        val sid = playlistData.get("statistic").get("sid").asLong(0).toString()

        val response = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/song/of-menu?sid=$sid&pn=1&ps=100"))
        val responseJson = JsonBrowser.parse(response.entity.content)

        val tracksData = responseJson.get("data").get("data").values()
        val tracks = ArrayList<AudioTrack>()

        var curPage = responseJson.get("data").get("curPage").`as`(Int::class.java)
        val pageCount = responseJson.get("data").get("pageCount").`as`(Int::class.java).let {
            if (playlistPageCountConfig == -1) it
            else if (it <= playlistPageCountConfig) it
            else playlistPageCountConfig
        }

        while (curPage <= pageCount) {
            val responsePage = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/song/of-menu?sid=$sid&pn=${++curPage}&ps=100"))
            val responseJsonPage = JsonBrowser.parse(responsePage.entity.content)
            tracksData.addAll(responseJsonPage.get("data").get("data").values())
        }

        for (track in tracksData) {
            tracks.add(loadAudio(track))
        }

        return BasicAudioPlaylist(playlistName, tracks, null, false)
    }

    override fun isTrackEncodable(track: AudioTrack): Boolean {
        return true
    }

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        track as BilibiliAudioTrack
        DataFormatTools.writeNullableText(output, track.type.toString())
        DataFormatTools.writeNullableText(output, track.id)
        DataFormatTools.writeNullableText(output, track.cid.toString())
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack {
        val inputString = DataFormatTools.readNullableText(input)
        log.debug("DEBUG: $inputString")
        val trackType: BilibiliAudioTrack.TrackType = when (inputString) {
            "VIDEO" -> BilibiliAudioTrack.TrackType.VIDEO
            "AUDIO" -> BilibiliAudioTrack.TrackType.AUDIO
            else -> throw IllegalArgumentException("ERROR: Must be VIDEO or AUDIO")
        }
        return BilibiliAudioTrack(trackInfo, trackType, DataFormatTools.readNullableText(input), DataFormatTools.readNullableText(input).toLong(), this)
    }

    // ── WBI signing ──────────────────────────────────────────────────────────

    @Volatile private var wbiKeys: String? = null
    @Volatile private var wbiKeysExpiry: Long = 0L

    private fun getWbiKeys(): String {
        wbiKeys?.takeIf { System.currentTimeMillis() < wbiKeysExpiry }?.let { return it }

        val response = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/nav"))
        val json = JsonBrowser.parse(response.entity.content)

        val wbiImg = json.get("data").get("wbi_img")
        val imgUrl = wbiImg.get("img_url").text()
            ?: throw IllegalStateException("Missing img_url in WBI response")
        val subUrl = wbiImg.get("sub_url").text()
            ?: throw IllegalStateException("Missing sub_url in WBI response")

        val imgKey = imgUrl.substring(imgUrl.lastIndexOf('/') + 1, imgUrl.lastIndexOf('.'))
        val subKey = subUrl.substring(subUrl.lastIndexOf('/') + 1, subUrl.lastIndexOf('.'))
        val rawKey = imgKey + subKey

        val sb = StringBuilder()
        for (index in MIXIN_KEY_ENC_TAB) {
            if (index < rawKey.length) sb.append(rawKey[index])
        }

        wbiKeys = sb.toString().take(32)
        wbiKeysExpiry = System.currentTimeMillis() + 60 * 60 * 1000
        return wbiKeys!!
    }

    internal fun signWbi(params: Map<String, String>): String {
        val mixinKey = getWbiKeys()
        val currTime = System.currentTimeMillis() / 1000
        val allParams = params.toMutableMap().apply { put("wts", currTime.toString()) }

        val query = allParams.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                val cleanValue = value.replace(Regex("[!'()*]"), "")
                "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(cleanValue, StandardCharsets.UTF_8)}"
            }

        val md5Bytes = MessageDigest.getInstance("MD5")
            .digest((query + mixinKey).toByteArray(StandardCharsets.UTF_8))
        val wRid = md5Bytes.joinToString("") { "%02x".format(it) }
        return "$query&w_rid=$wRid"
    }

    // ── Bilibili lyrics via CC subtitles ─────────────────────────────────────

    override fun loadLyrics(audioTrack: AudioTrack): AudioLyrics? {
        val bilibiliTrack = audioTrack as? BilibiliAudioTrack ?: return null
        if (bilibiliTrack.type != BilibiliAudioTrack.TrackType.VIDEO) return null

        return try {
            val bvid = bilibiliTrack.id
            var cid = bilibiliTrack.cid ?: 0L

            if (cid == 0L) {
                val viewResponse = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/view?bvid=$bvid"))
                val viewJson = JsonBrowser.parse(viewResponse.entity.content)
                if (viewJson.get("code").asLong(-1) != 0L) return null
                cid = viewJson.get("data").get("cid").asLong(0)
            }
            if (cid == 0L) return null

            val query = signWbi(
                mapOf("bvid" to bvid, "cid" to cid.toString(), "fnval" to "16", "qn" to "120")
            )
            val playerResponse = httpInterface.execute(HttpGet("${BASE_URL}x/player/wbi/v2?$query"))
            val playerJson = JsonBrowser.parse(playerResponse.entity.content)

            if (playerJson.get("code").asLong(-1) != 0L) return null

            val subtitles = playerJson.get("data").get("subtitle").get("subtitles")
            if (subtitles.values().isEmpty()) return null

            val rawSubUrl = subtitles.index(0).get("subtitle_url").text() ?: return null
            val subUrl = if (rawSubUrl.startsWith("//")) "https:$rawSubUrl" else rawSubUrl

            val subResponse = httpInterface.execute(HttpGet(subUrl))
            val subJson = JsonBrowser.parse(subResponse.entity.content)

            val lines = ArrayList<AudioLyrics.Line>()
            for (item in subJson.get("body").values()) {
                val from = item.get("from").safeText().toDoubleOrNull() ?: continue
                val to = item.get("to").safeText().toDoubleOrNull() ?: continue
                val content = item.get("content").text() ?: continue
                lines.add(
                    BasicAudioLyrics.BasicLine(
                        Duration.ofMillis((from * 1000).toLong()),
                        Duration.ofMillis(((to - from) * 1000).toLong()),
                        content
                    )
                )
            }

            if (lines.isEmpty()) return null

            BasicAudioLyrics("bilibili", "Bilibili CC", null, lines)
        } catch (e: Exception) {
            log.error("Failed to load Bilibili lyrics: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun shutdown() {
        //
    }

    companion object {
        const val BASE_URL = "https://api.bilibili.com/"
        const val SEARCH_PREFIX = "bilisearch:"

        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52
        )

        private val URL_PATTERN = Pattern.compile(
            "^https?://(?:(?:www|m)\\.)?(?:bilibili\\.com|b23\\.tv)/(?<type>video|audio)/(?<id>(?:(?<audioType>am|au|av)?(?<audioId>[0-9]+))|[A-Za-z0-9]+)/?(?:\\?.*)?$"
        )

        private fun getVideoUrl(id: String, page: Int? = null): String {
            return "https://www.bilibili.com/video/$id${if (page != null) "?p=$page" else ""}"
        }

        private fun getAudioUrl(id: String): String {
            return "https://www.bilibili.com/audio/$id"
        }
    }
}