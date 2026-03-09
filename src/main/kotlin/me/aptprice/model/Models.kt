package me.aptprice.model

import java.time.LocalDateTime

enum class MarketStatus {
    ACTIVE,
    OFF_MARKET_CANDIDATE,
    OFF_MARKET,
    RELISTED
}

data class Listing(
    val articleNo: String,
    val hscpNo: String = "",
    val title: String,
    val regionName: String,
    val price: Long,
    val floor: String,
    val areaSqm: Double,
    val pyeong: Int,
    val hsehCnt: Int, // 총 세대수
    val url: String,
    val updatedAt: String = LocalDateTime.now().toString(),
    val firstSeenAt: String = updatedAt,
    val lastSeenAt: String = updatedAt,
    val status: MarketStatus = MarketStatus.ACTIVE,
    val statusChangedAt: String = updatedAt,
    val offMarketAt: String? = null,
    val missCount: Int = 0,
)

data class TeamsMessage(
    val `@type`: String = "MessageCard",
    val themeColor: String = "0076D7",
    val summary: String,
    val sections: List<Section>,
) {
    data class Section(
        val activityTitle: String,
        val text: String,
        val markdown: Boolean = true,
    )
}
