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

        val cards = buildCards(
            listOf(
                "🏠 20평대" to pyeong20s,
                "🏢 30평대" to pyeong30s
            )
        )

        var successCount = 0
        cards.forEachIndexed { index, card ->
            try {
                restTemplate.postForEntity(webhookUrl, card, String::class.java)
                successCount += 1
                log.info("팀즈 알림 전송 완료 ({}/{})", index + 1, cards.size)
            } catch (e: Exception) {
                log.error("팀즈 알림 전송 중 오류 발생 ({}/{})", index + 1, cards.size, e)
            }
        }
        log.info("팀즈 알림 전송 요약 - 성공: {}건, 전체: {}건", successCount, cards.size)
    }

    private fun createSection(title: String, items: List<Pair<Listing, String>>): TeamsMessage.Section {
        val listMarkdown = items.joinToString("\n\n") { toMarkdownLine(it) }
        return TeamsMessage.Section(activityTitle = title, text = listMarkdown, markdown = true)
    }

    private fun buildCards(groupedItems: List<Pair<String, List<Pair<Listing, String>>>>): List<TeamsMessage> {
        val allSections = mutableListOf<TeamsMessage.Section>()

        groupedItems.forEach { (title, items) ->
            if (items.isEmpty()) return@forEach
            val chunks = splitItems(items, MAX_SECTION_CHARS, MAX_ITEMS_PER_SECTION)
            chunks.forEachIndexed { index, chunk ->
                val sectionTitle = if (chunks.size == 1) title else "$title (${index + 1}/${chunks.size})"
                allSections.add(createSection(sectionTitle, chunk))
            }
        }

        if (allSections.isEmpty()) return emptyList()

        val cards = mutableListOf<TeamsMessage>()
        var currentSections = mutableListOf<TeamsMessage.Section>()
        var currentChars = 0

        allSections.forEach { section ->
            val sectionChars = section.activityTitle.length + section.text.length
            if (currentSections.isNotEmpty() && currentChars + sectionChars > MAX_CARD_CHARS) {
                cards.add(
                    TeamsMessage(
                        summary = "부동산 새 매물 리포트",
                        sections = currentSections.toList()
                    )
                )
                currentSections = mutableListOf()
                currentChars = 0
            }
            currentSections.add(section)
            currentChars += sectionChars
        }

        if (currentSections.isNotEmpty()) {
            cards.add(
                TeamsMessage(
                    summary = "부동산 새 매물 리포트",
                    sections = currentSections.toList()
                )
            )
        }
        return cards
    }

    private fun splitItems(
        items: List<Pair<Listing, String>>,
        maxChars: Int,
        maxItems: Int,
    ): List<List<Pair<Listing, String>>> {
        val chunks = mutableListOf<List<Pair<Listing, String>>>()
        var current = mutableListOf<Pair<Listing, String>>()
        var currentChars = 0

        items.forEach { item ->
            val lineLen = toMarkdownLine(item).length + 2
            val exceedByCount = current.size >= maxItems
            val exceedByChars = current.isNotEmpty() && currentChars + lineLen > maxChars
            if (exceedByCount || exceedByChars) {
                chunks.add(current.toList())
                current = mutableListOf()
                currentChars = 0
            }
            current.add(item)
            currentChars += lineLen
        }

        if (current.isNotEmpty()) {
            chunks.add(current.toList())
        }
        return chunks
    }

    private fun toMarkdownLine(item: Pair<Listing, String>): String {
        val (listing, type) = item
        val householdInfo = if (listing.hsehCnt > 0) ", ${listing.hsehCnt}세대" else ""
        return "**[$type]** ${listing.regionName} ${listing.title} **${listing.price}만** (${listing.pyeong}평$householdInfo, ${listing.floor}) - [상세보기](${listing.url})"
    }

    companion object {
        private const val MAX_ITEMS_PER_SECTION = 25
        private const val MAX_SECTION_CHARS = 4500
        private const val MAX_CARD_CHARS = 12000
    }
}
