package com.github.topi314.lavasrc.plugin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "plugins.lavasrc.bilibili")
@Component
data class BilibiliConfig(
    var allowSearch: Boolean = true,
    var playlistPageCount: Int = -1,
    var auth: Authentication = Authentication()
) {
    data class Authentication(
        var enabled: Boolean = false,
        var sessdata: String = "",
        var biliJct: String = "",
        var dedeUserId: String = "",
        var buvid3: String = "",
        var buvid4: String = "",
        var acTimeValue: String = ""
    )
}