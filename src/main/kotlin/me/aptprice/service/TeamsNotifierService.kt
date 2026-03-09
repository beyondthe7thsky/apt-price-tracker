package me.aptprice.service

import me.aptprice.model.Listing
import me.aptprice.model.TeamsMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class TeamsNotifierService(
    @Value("\${TEAMS_WEBHOOK_URL:}") private val webhookUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restTemplate = RestTemplate()

    fun sendGroupedNotification(notifyItems: List<Pair<Listing, String>>) {
        if (webhookUrl.isBlank()) {
            log.warn("팀즈 웹후크 URL이 없어 알림을 스킵합니다. (JSON 파일만 업데이트됩니다.)")
            return
        }
        if (notifyItems.isEmpty()) {
            log.debug("전송할 알림 항목이 없습니다.")
            return
        }

        val pyeong20s = notifyItems.filter { it.first.pyeong in 20..29 }
        val pyeong30s = notifyItems.filter { it.first.pyeong in 30..39 }

        val sections = mutableListOf<TeamsMessage.Section>()

        if (pyeong20s.isNotEmpty()) sections.add(createSection("🏠 20평대", pyeong20s))
        if (pyeong30s.isNotEmpty()) sections.add(createSection("🏢 30평대", pyeong30s))

        val message = TeamsMessage(
            summary = "부동산 새 매물 리포트",
            sections = sections
        )

        try {
            restTemplate.postForEntity(webhookUrl, message, String::class.java)
            log.info("팀즈 알림 전송 완료! (총 {}개 섹션 발송)", sections.size)
        } catch (e: Exception) {
            log.error("팀즈 알림 전송 중 오류 발생", e)
        }
    }

    private fun createSection(title: String, items: List<Pair<Listing, String>>): TeamsMessage.Section {
        val listMarkdown = items.joinToString("\n\n") { (listing, type) ->
            val householdInfo = if (listing.hsehCnt > 0) ", ${listing.hsehCnt}세대" else ""
            "**[$type]** ${listing.regionName} ${listing.title} **${listing.price}만** (${listing.pyeong}평$householdInfo, ${listing.floor}) - [상세보기](${listing.url})"
        }
        return TeamsMessage.Section(activityTitle = title, text = listMarkdown, markdown = true)
    }
}
