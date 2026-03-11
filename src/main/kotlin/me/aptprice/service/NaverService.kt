package me.aptprice.service

import me.aptprice.model.Listing
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class AbuseBlockedException(message: String) : RuntimeException(message)
class RegionFetchFailedException(message: String) : RuntimeException(message)

@Service
class NaverService(private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = Random(System.currentTimeMillis())
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .version(HttpClient.Version.HTTP_1_1)
        .build()
    @Value("\${naver.safe.max-attempts:2}")
    private var maxAttempts: Int = 2

    @Value("\${naver.safe.base-backoff-ms:2000}")
    private var baseBackoffMs: Long = 2_000L

    @Value("\${naver.safe.backoff-jitter-ms:1500}")
    private var backoffJitterMs: Long = 1_500L

    @Value("\${naver.safe.max-complex-pages:1}")
    private var maxComplexPages: Int = 1

    @Value("\${naver.safe.max-complex-pages-on-overflow:3}")
    private var maxComplexPagesOnOverflow: Int = 3

    @Value("\${naver.safe.max-overflow-complexes-per-region:8}")
    private var maxOverflowComplexesPerRegion: Int = 8

    @Value("\${naver.safe.overflow-batch-size:2}")
    private var overflowBatchSize: Int = 2

    @Value("\${naver.safe.overflow-batch-cooldown-min-ms:20000}")
    private var overflowBatchCooldownMinMs: Long = 20_000L

    @Value("\${naver.safe.overflow-batch-cooldown-max-ms:35000}")
    private var overflowBatchCooldownMaxMs: Long = 35_000L

    @Value("\${naver.safe.article-order:prc,date}")
    private var articleOrder: String = "prc,date"

    @Value("\${naver.safe.max-complexes-per-region:35}")
    private var maxComplexesPerRegion: Int = 35

    @Value("\${naver.safe.rotate-complexes-by-day:true}")
    private var rotateComplexesByDay: Boolean = true

    @Value("\${naver.safe.complex-delay-min-ms:1200}")
    private var complexDelayMinMs: Long = 1_200L

    @Value("\${naver.safe.complex-delay-max-ms:2600}")
    private var complexDelayMaxMs: Long = 2_600L

    @Value("\${naver.safe.complex-batch-size:6}")
    private var complexBatchSize: Int = 6

    @Value("\${naver.safe.batch-cooldown-min-ms:8000}")
    private var batchCooldownMinMs: Long = 8_000L

    @Value("\${naver.safe.batch-cooldown-max-ms:15000}")
    private var batchCooldownMaxMs: Long = 15_000L

    @Value("\${naver.safe.overflow-complex-delay-min-ms:2600}")
    private var overflowComplexDelayMinMs: Long = 2_600L

    @Value("\${naver.safe.overflow-complex-delay-max-ms:5200}")
    private var overflowComplexDelayMaxMs: Long = 5_200L

    @Value("\${naver.safe.page-delay-min-ms:600}")
    private var pageDelayMinMs: Long = 600L

    @Value("\${naver.safe.page-delay-max-ms:1500}")
    private var pageDelayMaxMs: Long = 1_500L

    @Value("\${naver.safe.request-timeout-ms:20000}")
    private var requestTimeoutMs: Long = 20_000L

    @Value("\${naver.safe.abuse-cooldown-minutes:30}")
    private var abuseCooldownMinutes: Long = 30L

    @Volatile
    private var blockedUntilEpochMillis: Long = 0

    fun abuseCooldownRemainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (blockedUntilEpochMillis - nowMillis).coerceAtLeast(0L)

    fun fetchListings(regionName: String, cortarNo: String): List<Listing> {
        val now = System.currentTimeMillis()
        if (now < blockedUntilEpochMillis) {
            val remainSec = ((blockedUntilEpochMillis - now) / 1000).coerceAtLeast(1)
            throw AbuseBlockedException("abuse 차단 쿨다운 중 (${remainSec}초 남음)")
        }

        log.info("{} 수집 시작 (m.land API)", regionName)

        val complexes = fetchComplexes(regionName, cortarNo)
        if (complexes.isEmpty()) {
            log.warn("{} 단지 목록 없음", regionName)
            return emptyList()
        }

        val runComplexes = selectRunComplexes(regionName, complexes)
        val listings = mutableListOf<Listing>()
        var overflowExpandedComplexes = 0
        var overflowCapLogged = false
        for ((index, complex) in runComplexes.withIndex()) {
            val allowOverflowExpansion = maxOverflowComplexesPerRegion <= 0 ||
                overflowExpandedComplexes < maxOverflowComplexesPerRegion
            if (!allowOverflowExpansion && !overflowCapLogged) {
                overflowCapLogged = true
                log.info(
                    "{} 지역은 과도한 요청 방지를 위해 페이지 확장 단지 상한({})을 적용합니다.",
                    regionName,
                    maxOverflowComplexesPerRegion
                )
            }

            val articleResult = fetchComplexArticles(regionName, complex, allowOverflowExpansion)
            listings.addAll(articleResult.listings)
            if (articleResult.overflowExpanded) {
                overflowExpandedComplexes += 1
            }

            if (articleResult.blockedByAbuse) {
                log.warn("{} 지역 수집 중 abuse 차단이 감지되어 이번 지역 결과를 폐기하고 수집을 중단합니다.", regionName)
                throw AbuseBlockedException("${regionName} 지역 수집 중 abuse 차단 감지")
            }

            if (index < runComplexes.lastIndex) {
                val delayMillis = if (articleResult.overflowExpanded) {
                    randomDelayMs(overflowComplexDelayMinMs, overflowComplexDelayMaxMs, 2_600L, 5_200L)
                } else {
                    randomDelayMs(complexDelayMinMs, complexDelayMaxMs, 4_000L, 9_000L)
                }
                Thread.sleep(delayMillis)

                if (complexBatchSize > 0 && (index + 1) % complexBatchSize == 0) {
                    val batchDelay = randomDelayMs(batchCooldownMinMs, batchCooldownMaxMs, 8_000L, 15_000L)
                    log.info(
                        "{} 지역 배치 쿨다운 {}ms ({}개 단지 처리)",
                        regionName,
                        batchDelay,
                        index + 1
                    )
                    Thread.sleep(batchDelay)
                }

                if (articleResult.overflowExpanded &&
                    overflowBatchSize > 0 &&
                    overflowExpandedComplexes > 0 &&
                    overflowExpandedComplexes % overflowBatchSize == 0
                ) {
                    val overflowBatchDelay = randomDelayMs(
                        overflowBatchCooldownMinMs,
                        overflowBatchCooldownMaxMs,
                        20_000L,
                        35_000L
                    )
                    log.info(
                        "{} 지역 overflow 쿨다운 {}ms (확장 단지 {}개 처리)",
                        regionName,
                        overflowBatchDelay,
                        overflowExpandedComplexes
                    )
                    Thread.sleep(overflowBatchDelay)
                }
            }
        }

        val deduped = listings
            .groupBy { dedupKey(it) }
            .mapNotNull { (_, items) ->
                items.minWithOrNull(
                    compareBy<Listing> { it.price }
                        .thenByDescending { it.updatedAt }
                        .thenBy { it.articleNo }
                )
            }

        log.info(
            "{} 수집 성공 - 단지 전체: {}개, 수집 대상 단지: {}개, 매물: {}건",
            regionName,
            complexes.size,
            runComplexes.size,
            deduped.size
        )
        return deduped
    }

    private fun fetchComplexes(regionName: String, cortarNo: String): List<ComplexInfo> {
        val url = "https://m.land.naver.com/complex/ajax/complexListByCortarNo?cortarNo=$cortarNo"
        val response = requestBodyWithRetry(url, "https://m.land.naver.com/")
        if (response.blockedByAbuse) {
            throw AbuseBlockedException("${regionName} 단지 목록 조회가 abuse 차단으로 중단됨")
        }
        if (response.timedOut) {
            throw RegionFetchFailedException("${regionName} 단지 목록 조회 타임아웃")
        }
        val body = response.body ?: throw RegionFetchFailedException("${regionName} 단지 목록 응답 없음")

        val root = runCatching { objectMapper.readTree(body) }.getOrElse {
            throw RegionFetchFailedException("${regionName} 단지 목록 파싱 실패: ${it.message}")
        }

        val result = root.get("result") ?: throw RegionFetchFailedException("${regionName} 단지 목록 result 필드 없음")
        return result.mapNotNull { node ->
            val hscpNo = node.get("hscpNo").textOrEmpty().trim()
            val hscpNm = node.get("hscpNm").textOrEmpty().trim()
            val hscpTypeCd = node.get("hscpTypeCd").textOrEmpty().trim()
            if (hscpNo.isBlank() || hscpNm.isBlank()) return@mapNotNull null
            if (hscpTypeCd != "A01") return@mapNotNull null // 아파트만 수집
            ComplexInfo(hscpNo = hscpNo, hscpNm = hscpNm)
        }
    }

    private fun selectRunComplexes(regionName: String, complexes: List<ComplexInfo>): List<ComplexInfo> {
        if (complexes.isEmpty()) return emptyList()

        val limit = maxComplexesPerRegion
        if (limit <= 0 || limit >= complexes.size) {
            return complexes
        }

        if (!rotateComplexesByDay) {
            return complexes.take(limit)
        }

        val dayOffset = LocalDate.now().dayOfYear
        val regionOffset = regionName.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        val start = (dayOffset + regionOffset) % complexes.size
        val rotated = complexes.drop(start) + complexes.take(start)
        return rotated.take(limit)
    }

    private fun fetchComplexArticles(
        regionName: String,
        complex: ComplexInfo,
        allowOverflowExpansion: Boolean,
    ): ArticleFetchResult {
        val listings = mutableListOf<Listing>()
        val orders = parsedArticleOrders()
        val configuredMaxPages = maxComplexPages.coerceAtLeast(1)
        var effectiveMaxPages = configuredMaxPages

        val firstOrder = orders.first()
        val firstPage = fetchArticlePage(regionName, complex, firstOrder, 1)
        if (firstPage.blockedByAbuse) {
            return ArticleFetchResult(listings = listings, blockedByAbuse = true)
        }
        if (firstPage.timedOut) {
            return ArticleFetchResult(listings = listings)
        }
        if (!firstPage.hasData) {
            return ArticleFetchResult(listings = listings)
        }
        firstPage.nodes.forEach { node ->
            mapArticleNode(regionName, complex, node)?.let { listings.add(it) }
        }

        if (allowOverflowExpansion && configuredMaxPages == 1 && firstPage.moreDataYn == "Y" && firstPage.nodes.size >= 20) {
            val overflowMaxPages = maxComplexPagesOnOverflow.coerceAtLeast(1)
            if (overflowMaxPages > effectiveMaxPages) {
                effectiveMaxPages = overflowMaxPages
                log.info(
                    "{} {} 매물량 많음(1페이지 {}건) -> 수집 페이지를 {}까지 확장",
                    regionName,
                    complex.hscpNm,
                    firstPage.nodes.size,
                    effectiveMaxPages
                )
            }
        }

        val pageBudgets = allocatePageBudgets(effectiveMaxPages, orders.size)

        // 1) 첫 정렬(보통 prc) 페이지 2..N
        var nextPage = 2
        var moreDataYn = firstPage.moreDataYn
        while (nextPage <= pageBudgets[0] && moreDataYn == "Y") {
            Thread.sleep(randomDelayMs(pageDelayMinMs, pageDelayMaxMs, 1_500L, 3_500L))
            val pageResult = fetchArticlePage(regionName, complex, firstOrder, nextPage)
            if (pageResult.blockedByAbuse) {
                return ArticleFetchResult(listings = listings, blockedByAbuse = true)
            }
            if (pageResult.timedOut || !pageResult.hasData) break
            pageResult.nodes.forEach { node ->
                mapArticleNode(regionName, complex, node)?.let { listings.add(it) }
            }
            moreDataYn = pageResult.moreDataYn
            nextPage += 1
        }

        // 2) 나머지 정렬(date 등) 페이지 1..N (총 페이지 예산은 유지)
        for (orderIndex in 1 until orders.size) {
            val order = orders[orderIndex]
            val budget = pageBudgets[orderIndex]
            if (budget <= 0) continue

            var page = 1
            var orderMoreDataYn = "Y"
            while (page <= budget && orderMoreDataYn == "Y") {
                Thread.sleep(randomDelayMs(pageDelayMinMs, pageDelayMaxMs, 1_500L, 3_500L))
                val pageResult = fetchArticlePage(regionName, complex, order, page)
                if (pageResult.blockedByAbuse) {
                    return ArticleFetchResult(listings = listings, blockedByAbuse = true)
                }
                if (pageResult.timedOut || !pageResult.hasData) break
                pageResult.nodes.forEach { node ->
                    mapArticleNode(regionName, complex, node)?.let { listings.add(it) }
                }
                orderMoreDataYn = pageResult.moreDataYn
                page += 1
            }
        }

        return ArticleFetchResult(
            listings = listings,
            overflowExpanded = effectiveMaxPages > configuredMaxPages
        )
    }

    private fun fetchArticlePage(
        regionName: String,
        complex: ComplexInfo,
        order: String,
        page: Int,
    ): ArticlePageResult {
        val url =
            "https://m.land.naver.com/complex/getComplexArticleList?hscpNo=${complex.hscpNo}&rletTpCd=A01&tradTpCd=A1&order=$order&page=$page"
        val response = requestBodyWithRetry(url, "https://m.land.naver.com/complex/info/${complex.hscpNo}")
        if (response.blockedByAbuse) {
            return ArticlePageResult(blockedByAbuse = true)
        }
        if (response.timedOut) {
            log.warn("{} {} [{}] 페이지 {} 조회 타임아웃으로 단지 수집을 중단합니다.", regionName, complex.hscpNm, order, page)
            return ArticlePageResult(timedOut = true)
        }
        val body = response.body ?: return ArticlePageResult(hasData = false)

        val root = runCatching { objectMapper.readTree(body) }.getOrElse {
            log.warn("{} {} [{}] 페이지 {} 파싱 실패: {}", regionName, complex.hscpNm, order, page, it.message)
            return ArticlePageResult(hasData = false)
        }

        val result = root.get("result") ?: return ArticlePageResult(hasData = false)
        val list = result.get("list") ?: return ArticlePageResult(hasData = false)
        if (!list.isArray || list.isEmpty) return ArticlePageResult(hasData = false)

        return ArticlePageResult(
            nodes = list.toList(),
            moreDataYn = result.get("moreDataYn").textOrEmpty(),
            hasData = true
        )
    }

    private fun parsedArticleOrders(): List<String> {
        val parsed = articleOrder
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        return parsed.ifEmpty { listOf("prc") }
    }

    private fun allocatePageBudgets(totalPages: Int, orderCount: Int): IntArray {
        if (orderCount <= 1) {
            return intArrayOf(totalPages.coerceAtLeast(1))
        }
        val safeTotal = totalPages.coerceAtLeast(1)
        val base = safeTotal / orderCount
        val remainder = safeTotal % orderCount
        val budgets = IntArray(orderCount) { index ->
            base + if (index < remainder) 1 else 0
        }
        if (budgets[0] <= 0) budgets[0] = 1
        return budgets
    }

    private fun mapArticleNode(regionName: String, complex: ComplexInfo, node: JsonNode): Listing? {
        val articleNo = node.get("atclNo").textOrEmpty().trim()
        if (articleNo.isBlank()) return null

        val areaSupplySqm = parseAreaSqm(node.get("spc1"))
        val areaExclusiveSqm = parseAreaSqm(node.get("spc2"))
        val areaSqm = firstPositiveArea(areaExclusiveSqm, areaSupplySqm)
        val pyeongBaseSqm = firstPositiveArea(areaSupplySqm, areaExclusiveSqm)
        val priceText = node.get("prcInfo").textOrEmpty()
        val parsedPrice = parsePrice(priceText)
        if (parsedPrice <= 0L) return null

        val title = node.get("atclNm").textOrEmpty().trim().ifBlank { complex.hscpNm }
        val featureDesc = node.get("atclFetrDesc").textOrEmpty().trim()
        val tagList = parseTagList(node.get("tagList"))
        val buildingName = node.get("bildNm").textOrEmpty().trim()
        val floor = node.get("flrInfo").textOrEmpty().trim()
        val sameAddrHash = node.get("sameAddrHash").textOrEmpty().trim()

        return Listing(
            articleNo = articleNo,
            hscpNo = complex.hscpNo,
            sameAddrHash = sameAddrHash,
            buildingName = buildingName,
            title = title,
            featureDesc = featureDesc,
            tagList = tagList,
            regionName = regionName,
            price = parsedPrice,
            floor = floor,
            areaSqm = areaSqm,
            areaSupplySqm = areaSupplySqm,
            areaExclusiveSqm = areaExclusiveSqm,
            pyeong = if (pyeongBaseSqm > 0.0) (pyeongBaseSqm / 3.3058).roundToInt() else 0,
            url = "https://fin.land.naver.com/articles/$articleNo"
        )
    }

    private fun requestBodyWithRetry(url: String, referer: String): RequestResult {
        var lastBodySnippet = ""
        var sawTimeout = false

        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(requestTimeoutMs.coerceAtLeast(5_000L)))
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", referer)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                val status = response.statusCode()
                val body = response.body().orEmpty()
                lastBodySnippet = body.oneLineSnippet()
                val location = response.headers().firstValue("location").orElse("")

                if (status == 200) {
                    return RequestResult(body = body)
                }

                if (status == 302 && location.contains("/error/abuse")) {
                    val cooldownMs = abuseCooldownMinutes.coerceAtLeast(1L) * 60_000L
                    blockedUntilEpochMillis = maxOf(
                        blockedUntilEpochMillis,
                        System.currentTimeMillis() + cooldownMs
                    )
                    log.warn("요청 차단(302 abuse). url={} location={} -> 쿨다운 {}분", url, location, abuseCooldownMinutes)
                    return RequestResult(body = null, blockedByAbuse = true)
                }

                if (status in RETRYABLE_STATUS_CODES) {
                    val backoff = backoffMillis(attempt)
                    log.warn(
                        "요청 재시도 상태코드({}) - {}ms 대기. url={} location={} body={}",
                        status,
                        backoff,
                        url,
                        location,
                        lastBodySnippet
                    )
                    Thread.sleep(backoff)
                    continue
                }

                log.error("요청 실패 상태코드({}). url={} location={} body={}", status, url, location, lastBodySnippet)
                return RequestResult(body = null)
            } catch (e: HttpTimeoutException) {
                sawTimeout = true
                val backoff = backoffMillis(attempt)
                log.warn("요청 타임아웃(시도 {}/{}): {} - {}ms 대기. url={}", attempt, maxAttempts, e.message, backoff, url)
                Thread.sleep(backoff)
            } catch (e: Exception) {
                val backoff = backoffMillis(attempt)
                log.warn("요청 오류(시도 {}/{}): {} - {}ms 대기. url={}", attempt, maxAttempts, e.message, backoff, url)
                Thread.sleep(backoff)
            }
        }

        if (sawTimeout) {
            log.warn("최대 재시도 소진(타임아웃). url={} lastBody={}", url, lastBodySnippet)
            return RequestResult(body = null, timedOut = true)
        }

        log.warn("최대 재시도 소진(응답 없음/오류). url={} lastBody={}", url, lastBodySnippet)
        return RequestResult(body = null)
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = baseBackoffMs.coerceAtLeast(500L) * (1L shl (attempt - 1))
        val jitter = backoffJitterMs.coerceAtLeast(0L)
        return base + random.nextLong(jitter + 1)
    }

    private fun randomDelayMs(minMs: Long, maxMs: Long, defaultMinMs: Long, defaultMaxMs: Long): Long {
        if (minMs <= 0L && maxMs <= 0L) return 0L
        val min = if (minMs > 0) minMs else defaultMinMs
        val max = if (maxMs >= min) maxMs else defaultMaxMs.coerceAtLeast(min)
        return random.nextLong(min, max + 1)
    }

    private fun parsePrice(raw: String): Long {
        val normalized = raw
            .substringBefore("~")
            .replace("만원", "")
            .trim()

        val clean = normalized.replace(",", "").replace(" ", "")
        return if (clean.contains("억")) {
            val s = clean.split("억")
            val uk = s[0].toLongOrNull() ?: 0L
            val man = if (s.size > 1 && s[1].isNotEmpty()) s[1].toLongOrNull() ?: 0L else 0L
            uk * 10000 + man
        } else clean.toLongOrNull() ?: 0L
    }

    private fun JsonNode?.textOrEmpty(): String {
        if (this == null || this.isNull) return ""
        return this.asString("")
    }

    private fun parseAreaSqm(node: JsonNode?): Double {
        if (node == null || node.isNull) return 0.0
        return when {
            node.isNumber -> node.asDouble(0.0)
            else -> node.textOrEmpty()
                .replace(",", "")
                .replace("㎡", "")
                .trim()
                .toDoubleOrNull() ?: 0.0
        }.coerceAtLeast(0.0)
    }

    private fun parseTagList(node: JsonNode?): List<String> {
        if (node == null || node.isNull || !node.isArray) return emptyList()
        return node.mapNotNull { tagNode ->
            val text = tagNode.textOrEmpty().trim()
            if (text.isBlank()) null else text
        }.distinct()
    }

    private fun firstPositiveArea(vararg values: Double): Double =
        values.firstOrNull { it > 0.0 } ?: 0.0

    private fun dedupKey(listing: Listing): String {
        if (listing.sameAddrHash.isNotBlank()) {
            return "HASH:${listing.hscpNo}:${listing.sameAddrHash}"
        }
        val buildingKey = normalizeBuildingKey(listing.buildingName)
        val floorKey = normalizeFloorKey(listing.floor)
        val areaKey = normalizedAreaKey(firstPositiveArea(listing.areaExclusiveSqm, listing.areaSupplySqm, listing.areaSqm))
        val titleKey = normalizeTitleKey(listing.title)
        if (buildingKey.isNotBlank() && floorKey.isNotBlank() && areaKey > 0.0 && titleKey.isNotBlank()) {
            return "UNIT:${listing.hscpNo}:$titleKey:$buildingKey:$floorKey:${"%.2f".format(Locale.US, areaKey)}"
        }
        return "ATCL:${listing.articleNo}"
    }

    private fun normalizeFloorKey(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(" ", "")
            .replace("저", "L")
            .replace("중", "M")
            .replace("고", "H")
            .uppercase()
    }

    private fun normalizeTitleKey(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^0-9a-z가-힣동]"), "")
    }

    private fun normalizeBuildingKey(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(" ", "")
            .replace(Regex("[^0-9a-zA-Z가-힣동]"), "")
            .uppercase()
    }

    private fun normalizedAreaKey(area: Double): Double =
        if (area > 0.0) kotlin.math.round(area * 100.0) / 100.0 else 0.0

    private fun String.oneLineSnippet(maxLength: Int = 180): String {
        return replace("\n", " ").replace("\r", " ").trim().take(maxLength)
    }

    private data class ComplexInfo(
        val hscpNo: String,
        val hscpNm: String
    )

    private data class ArticleFetchResult(
        val listings: List<Listing>,
        val blockedByAbuse: Boolean = false,
        val overflowExpanded: Boolean = false,
    )

    private data class ArticlePageResult(
        val nodes: List<JsonNode> = emptyList(),
        val moreDataYn: String = "N",
        val hasData: Boolean = false,
        val blockedByAbuse: Boolean = false,
        val timedOut: Boolean = false,
    )

    private data class RequestResult(
        val body: String?,
        val blockedByAbuse: Boolean = false,
        val timedOut: Boolean = false,
    )

    companion object {
        private val RETRYABLE_STATUS_CODES = setOf(401, 403, 429, 500, 502, 503, 504)
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }
}
