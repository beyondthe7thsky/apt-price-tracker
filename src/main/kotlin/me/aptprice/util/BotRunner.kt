package me.aptprice.util

import me.aptprice.model.Listing
import me.aptprice.repository.FileDataRepository
import me.aptprice.service.AbuseBlockedException
import me.aptprice.service.NaverService
import me.aptprice.service.TeamsNotifierService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
@ConditionalOnProperty(name = ["bot.enabled"], havingValue = "true", matchIfMissing = true)
class BotRunner(
    private val naverService: NaverService, // 서비스 교체
    private val repository: FileDataRepository,
    private val notifier: TeamsNotifierService,
    @Value("\${bot.safe.max-regions-per-run:2}") private val maxRegionsPerRun: Int,
    @Value("\${bot.safe.region-delay-min-ms:20000}") private val regionDelayMinMs: Long,
    @Value("\${bot.safe.region-delay-max-ms:60000}") private val regionDelayMaxMs: Long,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = Random(System.currentTimeMillis())

    override fun run(vararg args: String) {
        val targetRegions = listOf(
            // 1) 수원시 (영통구, 광교, 화서)
            mapOf("name" to "수원_매탄동", "code" to "4111710100"),
            mapOf("name" to "수원_영통동", "code" to "4111710500"),
            mapOf("name" to "수원_망포동", "code" to "4111710700"),
            mapOf("name" to "수원_이의동", "code" to "4111710300"),
            mapOf("name" to "수원_하동", "code" to "4111710400"),
            mapOf("name" to "수원_화서동", "code" to "4111513800"),

            // 2) 용인시 수지구
            mapOf("name" to "용인수지_풍덕천동", "code" to "4146510100"),
            mapOf("name" to "용인수지_죽전동", "code" to "4146510200"),
            mapOf("name" to "용인수지_동천동", "code" to "4146510300"),
            mapOf("name" to "용인수지_신봉동", "code" to "4146510500"),
            mapOf("name" to "용인수지_성복동", "code" to "4146510600"),
            mapOf("name" to "용인수지_상현동", "code" to "4146510700"),

            // 3) 서울 마용성
            mapOf("name" to "서울마포_아현동", "code" to "1144010100"),
            mapOf("name" to "서울마포_공덕동", "code" to "1144010200"),
            mapOf("name" to "서울마포_상암동", "code" to "1144012700"),
            mapOf("name" to "서울마포_염리동", "code" to "1144010900"),
            mapOf("name" to "서울성동_옥수동", "code" to "1120011300"),
            mapOf("name" to "서울성동_금호동1가", "code" to "1120010900"),
            mapOf("name" to "서울성동_행당동", "code" to "1120010700"),
            mapOf("name" to "서울성동_성수동1가", "code" to "1120011400"),
            mapOf("name" to "서울용산_이촌동", "code" to "1117012900"),

            // 4) 서울 서남권
            mapOf("name" to "서울양천_목동", "code" to "1147010200"),
            mapOf("name" to "서울양천_신정동", "code" to "1147010100"),
            mapOf("name" to "서울강서_마곡동", "code" to "1150010500"),
            mapOf("name" to "서울강서_내발산동", "code" to "1150010600"),
            mapOf("name" to "서울강서_화곡동", "code" to "1150010300"),
            mapOf("name" to "서울영등포_신길동", "code" to "1156013200"),
            mapOf("name" to "서울영등포_문래동3가", "code" to "1156012100"),
            mapOf("name" to "서울영등포_여의도동", "code" to "1156011000"),

            // 5) 서울 남부
            mapOf("name" to "서울동작_흑석동", "code" to "1159010500"),
            mapOf("name" to "서울동작_상도동", "code" to "1159010200"),
            mapOf("name" to "서울동작_사당동", "code" to "1159010700"),
            mapOf("name" to "서울관악_봉천동", "code" to "1162010100"),
            mapOf("name" to "서울구로_신도림동", "code" to "1153010100"),
            mapOf("name" to "서울구로_구로동", "code" to "1153010200"),
            mapOf("name" to "서울구로_개봉동", "code" to "1153010700"),
            mapOf("name" to "서울금천_독산동", "code" to "1154510200"),

            // 6) 서울 동부/서북권
            mapOf("name" to "서울강동_고덕동", "code" to "1174010200"),
            mapOf("name" to "서울강동_명일동", "code" to "1174010100"),
            mapOf("name" to "서울강동_상일동", "code" to "1174010300"),
            mapOf("name" to "서울광진_광장동", "code" to "1121510400"),
            mapOf("name" to "서울광진_자양동", "code" to "1121510500"),
            mapOf("name" to "서울동대문_전농동", "code" to "1123010400"),
            mapOf("name" to "서울동대문_답십리동", "code" to "1123010500"),
            mapOf("name" to "서울성북_길음동", "code" to "1129013400"),
            mapOf("name" to "서울서대문_남가좌동", "code" to "1141012000"),
            mapOf("name" to "서울서대문_북가좌동", "code" to "1141011900"),
            mapOf("name" to "서울은평_진관동", "code" to "1138011400"),
            mapOf("name" to "서울은평_응암동", "code" to "1138010700")
        )

        val runRegions = targetRegions.take(maxRegionsPerRun.coerceAtLeast(1))
        log.info(
            "=== [네이버 모바일] 결혼 준비용 매물 감시 가동 (전체: {}개 동, 이번 실행: {}개 동) ===",
            targetRegions.size,
            runRegions.size
        )

        val oldData = repository.loadAll()
        val allNewListings = mutableListOf<Listing>()
        var blockedByAbuse = false

        for ((index, region) in runRegions.withIndex()) {
            val listings = try {
                naverService.fetchListings(region["name"]!!, region["code"]!!)
            } catch (e: AbuseBlockedException) {
                blockedByAbuse = true
                log.warn("{} 수집 중단: {}", region["name"], e.message)
                break
            }

            // 20~39평형만 필터링
            val filtered = listings.filter { it.pyeong in 20..39 }
            allNewListings.addAll(filtered)

            if (index < runRegions.lastIndex) {
                val delayRange = normalizedDelayRange(regionDelayMinMs, regionDelayMaxMs, 20_000L, 60_000L)
                val delayMillis = random.nextLong(delayRange.first, delayRange.second + 1)
                log.info("다음 지역 수집 전 {}ms 대기", delayMillis)
                Thread.sleep(delayMillis)
            }
        }

        if (allNewListings.isEmpty()) {
            if (blockedByAbuse) {
                log.warn("abuse 차단으로 신규 수집 결과가 없습니다. 기존 JSON 데이터는 유지합니다.")
            } else {
                log.warn("수집 결과가 없어 기존 JSON 데이터는 유지합니다.")
            }
            return
        }

        val notifyList = mutableListOf<Pair<Listing, String>>()
        val updatedListings = allNewListings.map { newListing ->
            val oldListing = oldData[newListing.articleNo]
            if (oldListing == null) {
                notifyList.add(Pair(newListing, "신규✨"))
            } else if (newListing.price < oldListing.price) {
                notifyList.add(Pair(newListing, "급매⬇️${oldListing.price - newListing.price}만"))
            }
            newListing
        }

        if (notifyList.isNotEmpty()) {
            notifier.sendGroupedNotification(notifyList)
        }

        repository.saveAll(updatedListings)
    }

    private fun normalizedDelayRange(minMs: Long, maxMs: Long, defaultMinMs: Long, defaultMaxMs: Long): Pair<Long, Long> {
        val min = if (minMs > 0) minMs else defaultMinMs
        val max = if (maxMs >= min) maxMs else defaultMaxMs.coerceAtLeast(min)
        return min to max
    }
}
