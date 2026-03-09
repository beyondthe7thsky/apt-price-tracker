package me.aptprice.util

import me.aptprice.model.Listing
import me.aptprice.model.MarketStatus
import me.aptprice.repository.FileDataRepository
import me.aptprice.service.AbuseBlockedException
import me.aptprice.service.NaverService
import me.aptprice.service.RegionFetchFailedException
import me.aptprice.service.TeamsNotifierService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import kotlin.random.Random

@Component
@ConditionalOnProperty(name = ["bot.enabled"], havingValue = "true", matchIfMissing = true)
class BotRunner(
    private val naverService: NaverService, // 서비스 교체
    private val repository: FileDataRepository,
    private val notifier: TeamsNotifierService,
    @Value("\${bot.safe.max-regions-per-run:50}") private val maxRegionsPerRun: Int,
    @Value("\${bot.safe.region-delay-min-ms:3000}") private val regionDelayMinMs: Long,
    @Value("\${bot.safe.region-delay-max-ms:7000}") private val regionDelayMaxMs: Long,
    @Value("\${bot.market.off-market-confirm-miss-count:3}") private val offMarketConfirmMissCount: Int,
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
            mapOf("name" to "수원_천천동", "code" to "4111113300"),

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
        val successfulRegions = mutableSetOf<String>()
        var blockedByAbuse = false

        for ((index, region) in runRegions.withIndex()) {
            val regionName = region["name"]!!
            val listings = try {
                naverService.fetchListings(regionName, region["code"]!!)
            } catch (e: AbuseBlockedException) {
                blockedByAbuse = true
                log.warn("{} 수집 중단: {}", regionName, e.message)
                break
            } catch (e: RegionFetchFailedException) {
                log.warn("{} 수집 실패(기존 데이터 유지): {}", regionName, e.message)
                continue
            }
            successfulRegions.add(regionName)

            // 20~39평형만 필터링
            val filtered = listings.filter { it.pyeong in 20..39 }
            allNewListings.addAll(filtered)

            if (index < runRegions.lastIndex) {
                val delayRange = normalizedDelayRange(regionDelayMinMs, regionDelayMaxMs, 3_000L, 7_000L)
                val delayMillis = random.nextLong(delayRange.first, delayRange.second + 1)
                log.info("다음 지역 수집 전 {}ms 대기", delayMillis)
                Thread.sleep(delayMillis)
            }
        }

        if (successfulRegions.isEmpty()) {
            if (blockedByAbuse) {
                log.warn("abuse 차단으로 성공한 지역이 없어 기존 JSON 데이터를 유지합니다.")
            } else {
                log.warn("성공한 지역이 없어 기존 JSON 데이터를 유지합니다.")
            }
            return
        }

        val now = LocalDateTime.now().toString()
        val newByArticleNo = allNewListings
            .groupBy { it.articleNo }
            .mapValues { (_, items) -> items.maxByOrNull { it.updatedAt } ?: items.first() }

        val mergedByArticleNo = mutableMapOf<String, Listing>()
        val notifyList = mutableListOf<Pair<Listing, String>>()
        var offMarketCandidateChanged = 0
        var offMarketChanged = 0
        var relistedChanged = 0

        // 기존 매물 처리: 이번 실행에 성공한 지역에서 미노출이면 소진 후보/소진으로 상태 전환
        oldData.forEach { (articleNo, oldListing) ->
            if (newByArticleNo.containsKey(articleNo)) return@forEach

            if (oldListing.regionName !in successfulRegions) {
                mergedByArticleNo[articleNo] = oldListing
                return@forEach
            }

            val transitioned = transitionMissingListing(oldListing, now)
            mergedByArticleNo[articleNo] = transitioned

            if (transitioned.status != oldListing.status) {
                when (transitioned.status) {
                    MarketStatus.OFF_MARKET_CANDIDATE -> {
                        offMarketCandidateChanged += 1
                    }
                    MarketStatus.OFF_MARKET -> {
                        offMarketChanged += 1
                    }
                    else -> Unit
                }
            }
        }

        // 신규/재노출 매물 처리
        newByArticleNo.forEach { (articleNo, freshListing) ->
            val oldListing = oldData[articleNo]
            val normalizedListing = mergeSeenListing(oldListing, freshListing, now)
            mergedByArticleNo[articleNo] = normalizedListing

            if (oldListing == null) {
                notifyList.add(Pair(normalizedListing, "신규✨"))
            } else if (normalizedListing.status == MarketStatus.RELISTED) {
                relistedChanged += 1
                notifyList.add(Pair(normalizedListing, "재등록♻️"))
            } else if (freshListing.price < oldListing.price) {
                notifyList.add(Pair(normalizedListing, "급매⬇️${oldListing.price - freshListing.price}만"))
            }
        }

        val mergedListings = mergedByArticleNo.values
            .sortedWith(compareBy<Listing> { it.regionName }.thenBy { it.articleNo })

        if (notifyList.isNotEmpty()) {
            notifier.sendGroupedNotification(notifyList)
        }

        repository.saveAll(mergedListings)
        val activeCount = mergedListings.count { it.status == MarketStatus.ACTIVE || it.status == MarketStatus.RELISTED }
        val candidateCount = mergedListings.count { it.status == MarketStatus.OFF_MARKET_CANDIDATE }
        val offMarketCount = mergedListings.count { it.status == MarketStatus.OFF_MARKET }
        log.info(
            "저장 완료 - 성공 지역: {}개, 보존 지역: {}개, 이번 노출: {}건, 최종 저장: {}건",
            successfulRegions.size,
            (runRegions.size - successfulRegions.size).coerceAtLeast(0),
            newByArticleNo.size,
            mergedListings.size
        )
        log.info(
            "상태 요약 - ACTIVE/RELISTED: {}건, OFF_MARKET_CANDIDATE: {}건, OFF_MARKET: {}건",
            activeCount,
            candidateCount,
            offMarketCount
        )
        log.info(
            "상태 전환 - 소진후보 전환: {}건, 소진 전환: {}건, 재등록 전환: {}건",
            offMarketCandidateChanged,
            offMarketChanged,
            relistedChanged
        )
    }

    private fun normalizedDelayRange(minMs: Long, maxMs: Long, defaultMinMs: Long, defaultMaxMs: Long): Pair<Long, Long> {
        val min = if (minMs > 0) minMs else defaultMinMs
        val max = if (maxMs >= min) maxMs else defaultMaxMs.coerceAtLeast(min)
        return min to max
    }

    private fun mergeSeenListing(old: Listing?, fresh: Listing, now: String): Listing {
        if (old == null) {
            return fresh.copy(
                updatedAt = now,
                firstSeenAt = now,
                lastSeenAt = now,
                status = MarketStatus.ACTIVE,
                statusChangedAt = now,
                offMarketAt = null,
                missCount = 0
            )
        }

        val nextStatus = if (old.status == MarketStatus.OFF_MARKET) {
            MarketStatus.RELISTED
        } else {
            MarketStatus.ACTIVE
        }
        val statusChangedAt = if (nextStatus != old.status) now else old.statusChangedAt
        val normalizedFirstSeenAt = old.firstSeenAt.ifBlank { old.updatedAt }

        return fresh.copy(
            updatedAt = now,
            firstSeenAt = normalizedFirstSeenAt,
            lastSeenAt = now,
            status = nextStatus,
            statusChangedAt = statusChangedAt,
            offMarketAt = null,
            missCount = 0
        )
    }

    private fun transitionMissingListing(old: Listing, now: String): Listing {
        if (old.status == MarketStatus.OFF_MARKET) {
            return old
        }

        val newMissCount = old.missCount + 1
        val threshold = offMarketConfirmMissCount.coerceAtLeast(2)
        val nextStatus = if (newMissCount >= threshold) {
            MarketStatus.OFF_MARKET
        } else {
            MarketStatus.OFF_MARKET_CANDIDATE
        }
        val statusChangedAt = if (nextStatus != old.status) now else old.statusChangedAt

        return old.copy(
            status = nextStatus,
            statusChangedAt = statusChangedAt,
            offMarketAt = if (nextStatus == MarketStatus.OFF_MARKET) (old.offMarketAt ?: now) else old.offMarketAt,
            missCount = newMissCount
        )
    }
}
