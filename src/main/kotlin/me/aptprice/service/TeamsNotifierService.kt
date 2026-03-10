package me.aptprice.service

import me.aptprice.model.Listing
import me.aptprice.model.TeamsMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate
import java.net.URI
import kotlin.math.round

@Component
class TeamsNotifierService(
    @Value("\${TEAMS_WEBHOOK_URL:}") webhookUrl: String,
    @Value("\${TEAMS_WEBHOOK_AUTHORIZATION:}") private val webhookAuthorization: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restTemplate = RestTemplate()
    private val webhookUrl = webhookUrl.trim().trim('"').trim('\'')

    fun sendGroupedNotification(notifyItems: List<Pair<Listing, String>>) {
        if (webhookUrl.isBlank()) {
            log.warn("팀즈 웹후크 URL이 없어 알림을 스킵합니다. (JSON 파일만 업데이트됩니다.)")
            return
        }
        if (isPowerAutomateUrl(webhookUrl) && !hasPowerAutomateSignature(webhookUrl) && webhookAuthorization.isBlank()) {
            log.error(
                "Power Automate URL에 인증 쿼리(sig 등)가 없습니다. Teams 알림 전송을 스킵합니다. url={}",
                maskedWebhookUrl(webhookUrl)
            )
            return
        }
        if (notifyItems.isEmpty()) {
            log.debug("전송할 알림 항목이 없습니다.")
            return
        }

        val groupedItems = buildCombinedGroups(notifyItems)
        if (groupedItems.isEmpty()) {
            log.info("조건에 맞는 전송 대상이 없어 알림을 스킵합니다.")
            return
        }

        val requestBodies: List<Any> = if (isPowerAutomateUrl(webhookUrl)) {
            buildPowerAutomateCards(groupedItems, notifyItems.size)
        } else {
            buildMessageCards(groupedItems, notifyItems.size)
        }
        if (requestBodies.isEmpty()) {
            log.info("전송 가능한 Teams 카드가 없어 알림을 스킵합니다.")
            return
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (webhookAuthorization.isNotBlank()) {
                set(HttpHeaders.AUTHORIZATION, webhookAuthorization.trim())
            }
        }

        var successCount = 0
        for ((index, requestBody) in requestBodies.withIndex()) {
            try {
                restTemplate.exchange(
                    webhookUrl,
                    HttpMethod.POST,
                    HttpEntity(requestBody, headers),
                    String::class.java
                )
                successCount += 1
                log.info("팀즈 알림 전송 완료 ({}/{})", index + 1, requestBodies.size)
            } catch (e: HttpStatusCodeException) {
                val status = e.statusCode.value()
                log.error(
                    "팀즈 알림 전송 HTTP 오류 ({}/{}): status={}, url={}, body={}",
                    index + 1,
                    requestBodies.size,
                    status,
                    maskedWebhookUrl(webhookUrl),
                    sanitizeBody(e.responseBodyAsString),
                    e
                )
                if (status == 401 || status == 403) {
                    log.error("인증 오류(401/403)입니다. webhook URL/서명(sig) 값을 확인하세요.")
                    break
                }
            } catch (e: Exception) {
                log.error(
                    "팀즈 알림 전송 중 오류 발생 ({}/{}), url={}",
                    index + 1,
                    requestBodies.size,
                    maskedWebhookUrl(webhookUrl),
                    e
                )
            }
        }
        log.info("팀즈 알림 전송 요약 - 성공: {}건, 전체: {}건", successCount, requestBodies.size)
    }

    private fun buildMessageCards(
        groupedItems: List<Pair<String, List<Pair<Listing, String>>>>,
        totalNotifyCount: Int,
    ): List<TeamsMessage> {
        val cards = mutableListOf<TeamsMessage>()
        groupedItems.forEach { (groupTitle, items) ->
            val chunks = splitItems(items)
            chunks.forEachIndexed { index, chunk ->
                val title = if (chunks.size == 1) {
                    "$groupTitle (총 ${items.size}건)"
                } else {
                    "$groupTitle (총 ${items.size}건, ${index + 1}/${chunks.size})"
                }
                val text = chunk.joinToString("\n\n") { toMarkdownLine(it) }
                cards.add(
                    TeamsMessage(
                        summary = "부동산 새 매물 리포트",
                        sections = listOf(
                            TeamsMessage.Section(
                                activityTitle = "부동산 새 매물 리포트",
                                text = "총 ${totalNotifyCount}건",
                                markdown = true
                            ),
                            TeamsMessage.Section(
                                activityTitle = title,
                                text = text,
                                markdown = true
                            )
                        )
                    )
                )
            }
        }
        return cards
    }

    private fun buildPowerAutomateCards(
        groupedItems: List<Pair<String, List<Pair<Listing, String>>>>,
        totalNotifyCount: Int,
    ): List<Map<String, Any?>> {
        val cards = mutableListOf<Map<String, Any?>>()
        groupedItems.forEach { (groupTitle, items) ->
            val chunks = splitItems(items)
            chunks.forEachIndexed { index, chunk ->
                val title = if (chunks.size == 1) {
                    "$groupTitle (총 ${items.size}건)"
                } else {
                    "$groupTitle (총 ${items.size}건, ${index + 1}/${chunks.size})"
                }
                val text = chunk.joinToString("\n\n") { toMarkdownLine(it) }
                cards.add(buildPowerAutomateCardPayload(title, text, totalNotifyCount))
            }
        }
        return cards
    }

    private fun buildPowerAutomateCardPayload(
        title: String,
        text: String,
        totalNotifyCount: Int,
    ): Map<String, Any?> {
        return mapOf(
            "type" to "message",
            "attachments" to listOf(
                mapOf(
                    "contentType" to "application/vnd.microsoft.card.adaptive",
                    "contentUrl" to null,
                    "content" to mapOf(
                        "\$schema" to "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type" to "AdaptiveCard",
                        "version" to "1.4",
                        "body" to listOf(
                            mapOf(
                                "type" to "TextBlock",
                                "text" to "부동산 새 매물 리포트",
                                "weight" to "Bolder",
                                "size" to "Medium",
                                "wrap" to true
                            ),
                            mapOf(
                                "type" to "TextBlock",
                                "text" to "총 ${totalNotifyCount}건",
                                "wrap" to true,
                                "spacing" to "Small"
                            ),
                            mapOf(
                                "type" to "TextBlock",
                                "text" to title,
                                "weight" to "Bolder",
                                "wrap" to true,
                                "spacing" to "Medium"
                            ),
                            mapOf(
                                "type" to "TextBlock",
                                "text" to text,
                                "wrap" to true,
                                "spacing" to "Small"
                            )
                        )
                    )
                )
            )
        )
    }

    private fun splitItems(items: List<Pair<Listing, String>>): List<List<Pair<Listing, String>>> {
        val chunks = mutableListOf<List<Pair<Listing, String>>>()
        var current = mutableListOf<Pair<Listing, String>>()
        var currentChars = 0

        for (item in items) {
            val lineLen = toMarkdownLine(item).length + 2
            val exceedByCount = current.size >= MAX_ITEMS_PER_CARD
            val exceedByChars = current.isNotEmpty() && currentChars + lineLen > MAX_GROUP_TEXT_CHARS
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

    private fun buildCombinedGroups(items: List<Pair<Listing, String>>): List<Pair<String, List<Pair<Listing, String>>>> {
        val sorted = items.sortedByDescending { it.first.price }
        val pyeongBands = listOf(
            "20평대" to (20..29),
            "30평대" to (30..39)
        )
        val priceBands = listOf(
            PriceBand("8억 미만", 0L, 80_000L),
            PriceBand("8~9억대", 80_000L, 90_000L),
            PriceBand("9~10억대", 90_000L, 100_000L),
            PriceBand("10~12억대", 100_000L, 120_000L),
            PriceBand("12억 이상", 120_000L, null)
        )

        val groups = mutableListOf<Pair<String, List<Pair<Listing, String>>>>()
        pyeongBands.forEach { (pyeongTitle, range) ->
            val pyeongItems = sorted.filter { it.first.pyeong in range }
            priceBands.forEach { band ->
                val bandItems = pyeongItems.filter { band.contains(it.first.price) }
                if (bandItems.isNotEmpty()) {
                    groups.add("🏷️ $pyeongTitle · ${band.title}" to bandItems)
                }
            }
        }
        return groups
    }

    private fun toMarkdownLine(item: Pair<Listing, String>): String {
        val (listing, type) = item
        val supplySqm = listing.areaSupplySqm
        val exclusiveSqm = if (listing.areaExclusiveSqm > 0) listing.areaExclusiveSqm else listing.areaSqm
        val detailParts = mutableListOf<String>()

        if (listing.hsehCnt > 0) {
            detailParts.add("${listing.hsehCnt}세대")
        }
        if (supplySqm > 0 || exclusiveSqm > 0) {
            detailParts.add("공급/전용 ${formatArea(supplySqm)}/${formatArea(exclusiveSqm)}㎡")
        }
        if (listing.floor.isNotBlank()) {
            detailParts.add(listing.floor)
        }

        val detailText = if (detailParts.isEmpty()) "" else " (${detailParts.joinToString(", ")})"
        return "**[$type]** ${listing.regionName} ${listing.title} **${formatPrice(listing.price)}**$detailText - [상세보기](${listing.url})"
    }

    private fun formatPrice(priceMan: Long): String {
        if (priceMan < 10_000) return "${priceMan}만"
        val eok = priceMan / 10_000
        val man = priceMan % 10_000
        return if (man == 0L) "${eok}억" else "${eok}억 ${man}만"
    }

    private fun formatArea(areaSqm: Double): String {
        if (areaSqm <= 0.0) return "-"
        val rounded2 = round(areaSqm * 100.0) / 100.0
        val text = if (rounded2 % 1.0 == 0.0) {
            rounded2.toInt().toString()
        } else {
            rounded2.toString()
        }
        return text
    }

    private fun isPowerAutomateUrl(url: String): Boolean =
        url.contains("powerautomate", ignoreCase = true) || url.contains("environment.api.powerplatform.com", ignoreCase = true)

    private fun hasPowerAutomateSignature(url: String): Boolean {
        return try {
            val query = URI(url).rawQuery ?: return false
            query.contains("sig=") && query.contains("api-version=")
        } catch (_: Exception) {
            false
        }
    }

    private fun maskedWebhookUrl(url: String): String {
        return try {
            val uri = URI(url)
            val path = uri.rawPath ?: ""
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            val queryPart = if (uri.rawQuery.isNullOrBlank()) "" else "?***"
            "${uri.scheme}://${uri.host}$portPart$path$queryPart"
        } catch (_: Exception) {
            "<invalid-webhook-url>"
        }
    }

    private fun sanitizeBody(body: String?): String {
        if (body.isNullOrBlank()) return "<empty>"
        return body.replace(Regex("\\s+"), " ").take(300)
    }

    companion object {
        private const val MAX_ITEMS_PER_CARD = 25
        private const val MAX_GROUP_TEXT_CHARS = 3500
    }

    private data class PriceBand(
        val title: String,
        val minInclusive: Long,
        val maxExclusive: Long?,
    ) {
        fun contains(price: Long): Boolean =
            if (maxExclusive == null) price >= minInclusive else price >= minInclusive && price < maxExclusive
    }
}
