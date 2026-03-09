package me.aptprice.model

import java.time.LocalDateTime

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
