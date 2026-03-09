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
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.HttpStatusCodeException
import java.net.URI

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

        val pyeong20s = notifyItems.filter { it.first.pyeong in 20..29 }
        val pyeong30s = notifyItems.filter { it.first.pyeong in 30..39 }

        val groupedItems = listOf(
            "🏠 20평대" to pyeong20s,
            "🏢 30평대" to pyeong30s
        )

        val requestBody: Any = if (isPowerAutomateUrl(webhookUrl)) {
            buildPowerAutomatePayload(groupedItems, notifyItems.size)
        } else {
            buildSingleCard(groupedItems, notifyItems.size) ?: run {
                log.info("전송 가능한 Teams 카드가 없어 알림을 스킵합니다.")
                return
            }
        }

        if (requestBody is TeamsMessage && requestBody.sections.isEmpty()) {
            log.info("전송 가능한 Teams 카드가 없어 알림을 스킵합니다.")
            return
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (webhookAuthorization.isNotBlank()) {
                set(HttpHeaders.AUTHORIZATION, webhookAuthorization.trim())
            }
        }

        try {
            restTemplate.exchange(
                webhookUrl,
                HttpMethod.POST,
                HttpEntity(requestBody, headers),
                String::class.java
            )
            log.info("팀즈 알림 전송 완료 (1/1)")
        } catch (e: HttpStatusCodeException) {
            val status = e.statusCode.value()
            log.error(
                "팀즈 알림 전송 HTTP 오류: status={}, url={}, body={}",
                status,
                maskedWebhookUrl(webhookUrl),
                sanitizeBody(e.responseBodyAsString),
                e
            )
            if (status == 401 || status == 403) {
                log.error("인증 오류(401/403)입니다. webhook URL/서명(sig) 값을 확인하세요.")
            }
        } catch (e: Exception) {
            log.error(
                "팀즈 알림 전송 중 오류 발생, url={}",
                maskedWebhookUrl(webhookUrl),
                e
            )
        }
        log.info("팀즈 알림 전송 요약 - 전체: 1건")
    }

    private fun buildSingleCard(
        groupedItems: List<Pair<String, List<Pair<Listing, String>>>>,
        totalNotifyCount: Int,
    ): TeamsMessage? {
        val sections = mutableListOf<TeamsMessage.Section>()

        sections.add(
            TeamsMessage.Section(
                activityTitle = "부동산 새 매물 리포트",
                text = "총 ${totalNotifyCount}건 (${groupedItems.sumOf { it.second.size }}건 분류됨)",
                markdown = true
            )
        )

        groupedItems.forEach { (title, items) ->
            if (items.isEmpty()) return@forEach
            val (text, shownCount) = buildSectionText(items)
            val extraText = if (items.size > shownCount) "\n\n... 외 ${items.size - shownCount}건" else ""
            sections.add(
                TeamsMessage.Section(
                    activityTitle = "$title (총 ${items.size}건)",
                    text = text + extraText,
                    markdown = true
                )
            )
        }

        if (sections.size <= 1) return null
        return TeamsMessage(
            summary = "부동산 새 매물 리포트",
            sections = sections
        )
    }

    private fun buildSectionText(items: List<Pair<Listing, String>>): Pair<String, Int> {
        val builder = StringBuilder()
        var shownCount = 0

        for (item in items) {
            val line = toMarkdownLine(item)
            if (shownCount >= MAX_ITEMS_PER_GROUP) break
            if (builder.isNotEmpty() && builder.length + line.length + 2 > MAX_GROUP_TEXT_CHARS) break
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(line)
            shownCount += 1
        }
        return builder.toString() to shownCount
    }

    private fun buildPowerAutomatePayload(
        groupedItems: List<Pair<String, List<Pair<Listing, String>>>>,
        totalNotifyCount: Int,
    ): Map<String, Any?> {
        val adaptiveBody = mutableListOf<Map<String, Any>>()
        adaptiveBody.add(
            mapOf(
                "type" to "TextBlock",
                "text" to "부동산 새 매물 리포트",
                "weight" to "Bolder",
                "size" to "Medium",
                "wrap" to true
            )
        )
        adaptiveBody.add(
            mapOf(
                "type" to "TextBlock",
                "text" to "총 ${totalNotifyCount}건 (${groupedItems.sumOf { it.second.size }}건 분류됨)",
                "wrap" to true,
                "spacing" to "Small"
            )
        )

        groupedItems.forEach { (title, items) ->
            if (items.isEmpty()) return@forEach
            val (text, shownCount) = buildSectionText(items)
            val extraText = if (items.size > shownCount) "\n\n... 외 ${items.size - shownCount}건" else ""
            adaptiveBody.add(
                mapOf(
                    "type" to "TextBlock",
                    "text" to "$title (총 ${items.size}건)",
                    "weight" to "Bolder",
                    "wrap" to true,
                    "spacing" to "Medium"
                )
            )
            adaptiveBody.add(
                mapOf(
                    "type" to "TextBlock",
                    "text" to (text + extraText),
                    "wrap" to true,
                    "spacing" to "Small"
                )
            )
        }

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
                        "body" to adaptiveBody
                    )
                )
            )
        )
    }

    private fun toMarkdownLine(item: Pair<Listing, String>): String {
        val (listing, type) = item
        val householdInfo = if (listing.hsehCnt > 0) ", ${listing.hsehCnt}세대" else ""
        return "**[$type]** ${listing.regionName} ${listing.title} **${listing.price}만** (${listing.pyeong}평$householdInfo, ${listing.floor}) - [상세보기](${listing.url})"
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
        private const val MAX_ITEMS_PER_GROUP = 60
        private const val MAX_GROUP_TEXT_CHARS = 7000
    }
}
