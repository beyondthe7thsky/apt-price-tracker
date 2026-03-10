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
import java.util.concurrent.ConcurrentHashMap
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

    @Value("\${naver.safe.max-complexes-per-region:35}")
    private var maxComplexesPerRegion: Int = 35

    @Value("\${naver.safe.rotate-complexes-by-day:true}")
    private var rotateComplexesByDay: Boolean = true

    @Value("\${naver.safe.complex-delay-min-ms:1200}")
    private var complexDelayMinMs: Long = 1_200L

    @Value("\${naver.safe.complex-delay-max-ms:2600}")
    private var complexDelayMaxMs: Long = 2_600L

    @Value("\${naver.safe.page-delay-min-ms:600}")
    private var pageDelayMinMs: Long = 600L

    @Value("\${naver.safe.page-delay-max-ms:1500}")
    private var pageDelayMaxMs: Long = 1_500L

    @Value("\${naver.safe.request-timeout-ms:20000}")
    private var requestTimeoutMs: Long = 20_000L

    @Value("\${naver.safe.abuse-cooldown-minutes:30}")
    private var abuseCooldownMinutes: Long = 30L

    @Value("\${naver.safe.household-fallback-enabled:true}")
    private var householdFallbackEnabled: Boolean = true

    @Value("\${naver.safe.household-fallback-timeout-ms:7000}")
    private var householdFallbackTimeoutMs: Long = 7_000L

    @Value("\${naver.safe.household-fallback-delay-min-ms:250}")
    private var householdFallbackDelayMinMs: Long = 250L

    @Value("\${naver.safe.household-fallback-delay-max-ms:700}")
    private var householdFallbackDelayMaxMs: Long = 700L

    @Value("\${naver.safe.household-fallback-normal-sample-rate:1.0}")
    private var householdFallbackNormalSampleRate: Double = 1.0

    @Value("\${naver.safe.household-fallback-degraded-sample-rate:0.2}")
    private var householdFallbackDegradedSampleRate: Double = 0.2

    @Value("\${naver.safe.household-fallback-429-window-ms:300000}")
    private var householdFallback429WindowMs: Long = 300_000L

    @Value("\${naver.safe.household-fallback-429-threshold:5}")
    private var householdFallback429Threshold: Int = 5

    @Value("\${naver.safe.household-fallback-degraded-duration-ms:600000}")
    private var householdFallbackDegradedDurationMs: Long = 600_000L

    @Volatile
    private var blockedUntilEpochMillis: Long = 0

    private val householdFallbackLock = Any()
    private val householdFallback429Lock = Any()
    private val householdFallbackCache = ConcurrentHashMap<String, Int>()
    private val householdFallbackMisses = ConcurrentHashMap.newKeySet<String>()
    private val householdFallback429Timestamps = ArrayDeque<Long>()

    @Volatile
    private var lastHouseholdFallbackAtMillis: Long = 0L

    @Volatile
    private var householdFallbackDegradedUntilMillis: Long = 0L

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
        for ((index, complex) in runComplexes.withIndex()) {
            val articleResult = fetchComplexArticles(regionName, complex)
            listings.addAll(articleResult.listings)

            if (articleResult.blockedByAbuse) {
                log.warn("{} 지역 수집 중 abuse 차단이 감지되어 이번 지역 결과를 폐기하고 수집을 중단합니다.", regionName)
                throw AbuseBlockedException("${regionName} 지역 수집 중 abuse 차단 감지")
            }

            if (index < runComplexes.lastIndex) {
                Thread.sleep(randomDelayMs(complexDelayMinMs, complexDelayMaxMs, 4_000L, 9_000L))
            }
        }

        val deduped = listings
            .groupBy { it.articleNo }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.updatedAt } }

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
            val hscpNo = node.get("hscpNo")?.asText()?.trim().orEmpty()
            val hscpNm = node.get("hscpNm")?.asText()?.trim().orEmpty()
            val hscpTypeCd = node.get("hscpTypeCd")?.asText()?.trim().orEmpty()
            val householdCount = firstPositive(
                parseHouseholdCount(node.get("hsehCnt")),
                parseHouseholdCount(node.get("totHsehCnt")),
                parseHouseholdCount(node.get("totHhldCnt")),
                parseHouseholdCount(node.get("hhldCnt"))
            )
            if (hscpNo.isBlank() || hscpNm.isBlank()) return@mapNotNull null
            if (hscpTypeCd != "A01") return@mapNotNull null // 아파트만 수집
            ComplexInfo(hscpNo = hscpNo, hscpNm = hscpNm, hsehCnt = householdCount)
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

    private fun fetchComplexArticles(regionName: String, complex: ComplexInfo): ArticleFetchResult {
        val listings = mutableListOf<Listing>()
        var page = 1

        while (page <= maxComplexPages.coerceAtLeast(1)) {
            val url =
                "https://m.land.naver.com/complex/getComplexArticleList?hscpNo=${complex.hscpNo}&rletTpCd=A01&tradTpCd=A1&order=prc&page=$page"
            val response = requestBodyWithRetry(url, "https://m.land.naver.com/complex/info/${complex.hscpNo}")
            if (response.blockedByAbuse) {
                return ArticleFetchResult(listings = listings, blockedByAbuse = true)
            }
            if (response.timedOut) {
                log.warn("{} {} 페이지 {} 조회 타임아웃으로 단지 수집을 중단합니다.", regionName, complex.hscpNm, page)
                break
            }
            val body = response.body ?: break

            val root = runCatching { objectMapper.readTree(body) }.getOrElse {
                log.warn("{} {} 페이지 {} 파싱 실패: {}", regionName, complex.hscpNm, page, it.message)
                break
            }

            val result = root.get("result") ?: break
            val list = result.get("list") ?: break
            if (!list.isArray || list.isEmpty) break

            list.forEach { node ->
                mapArticleNode(regionName, complex, node)?.let { listings.add(it) }
            }

            val moreDataYn = result.get("moreDataYn")?.asText("").orEmpty()
            if (moreDataYn != "Y") break
            page += 1
            Thread.sleep(randomDelayMs(pageDelayMinMs, pageDelayMaxMs, 1_500L, 3_500L))
        }

        return ArticleFetchResult(listings = listings)
    }

    private fun mapArticleNode(regionName: String, complex: ComplexInfo, node: JsonNode): Listing? {
        val articleNo = node.get("atclNo")?.asText()?.trim().orEmpty()
        if (articleNo.isBlank()) return null

        val areaSqm = node.get("spc2")?.asText()?.toDoubleOrNull() ?: 0.0
        val priceText = node.get("prcInfo")?.asText("").orEmpty()
        val parsedPrice = parsePrice(priceText)
        if (parsedPrice <= 0L) return null
        val householdCount = firstPositive(
            parseHouseholdCount(node.get("hsehCnt")),
            parseHouseholdCount(node.get("totHsehCnt")),
            parseHouseholdCount(node.get("totHhldCnt")),
            parseHouseholdCount(node.get("hhldCnt")),
            complex.hsehCnt,
            resolveHouseholdCountWithFallback(complex.hscpNo)
        )

        val title = node.get("atclNm")?.asText()?.trim().orEmpty().ifBlank { complex.hscpNm }
        val floor = node.get("flrInfo")?.asText()?.trim().orEmpty()

        return Listing(
            articleNo = articleNo,
            hscpNo = complex.hscpNo,
            title = title,
            regionName = regionName,
            price = parsedPrice,
            floor = floor,
            areaSqm = areaSqm,
            pyeong = (areaSqm / 3.3058).roundToInt(),
            hsehCnt = householdCount,
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

    private fun parseHouseholdCount(node: JsonNode?): Int {
        if (node == null || node.isNull) return 0
        val value = when {
            node.isNumber -> node.asInt(0)
            node.isTextual -> node.asText("").replace(",", "").trim().toIntOrNull() ?: 0
            else -> 0
        }
        return value.coerceAtLeast(0)
    }

    private fun firstPositive(vararg values: Int): Int =
        values.firstOrNull { it > 0 } ?: 0

    private fun resolveHouseholdCountWithFallback(hscpNo: String): Int {
        if (!householdFallbackEnabled || hscpNo.isBlank()) return 0
        householdFallbackCache[hscpNo]?.let { return it }
        if (householdFallbackMisses.contains(hscpNo)) return 0

        synchronized(householdFallbackLock) {
            householdFallbackCache[hscpNo]?.let { return it }
            if (householdFallbackMisses.contains(hscpNo)) return 0

            val now = System.currentTimeMillis()
            val sinceLast = now - lastHouseholdFallbackAtMillis
            val minDelay = householdFallbackDelayMinMs.coerceAtLeast(0L)
            if (sinceLast in 0 until minDelay) {
                Thread.sleep(minDelay - sinceLast)
            }

            if (!shouldAttemptHouseholdFallbackNow()) {
                householdFallbackMisses.add(hscpNo)
                log.info("세대수 보강 스킵(429 완화 샘플링). hscpNo={}", hscpNo)
                return 0
            }

            val resolved = fetchHouseholdCountFromAltApis(hscpNo)
            lastHouseholdFallbackAtMillis = System.currentTimeMillis()

            if (resolved > 0) {
                householdFallbackCache[hscpNo] = resolved
                log.info("세대수 보강 성공. hscpNo={} hsehCnt={}", hscpNo, resolved)
                return resolved
            }

            householdFallbackMisses.add(hscpNo)
            return 0
        }
    }

    private fun fetchHouseholdCountFromAltApis(hscpNo: String): Int {
        val candidates = listOf(
            AltApiCandidate(
                url = "https://new.land.naver.com/api/complexes/$hscpNo",
                referer = "https://new.land.naver.com/complexes/$hscpNo"
            ),
            AltApiCandidate(
                url = "https://new.land.naver.com/api/complexes/overview/$hscpNo",
                referer = "https://new.land.naver.com/complexes/$hscpNo"
            ),
            AltApiCandidate(
                url = "https://fin.land.naver.com/front-api/v1/complexes/$hscpNo",
                referer = "https://fin.land.naver.com/complexes/$hscpNo"
            )
        )

        for ((index, candidate) in candidates.withIndex()) {
            val body = requestBodyOnce(
                url = candidate.url,
                referer = candidate.referer,
                userAgent = DESKTOP_USER_AGENT,
                timeoutMs = householdFallbackTimeoutMs
            )

            if (body.isNullOrBlank()) {
                if (index < candidates.lastIndex) {
                    Thread.sleep(randomDelayMs(householdFallbackDelayMinMs, householdFallbackDelayMaxMs, 250L, 700L))
                }
                continue
            }

            val root = runCatching { objectMapper.readTree(body) }.getOrNull() ?: continue
            val hsehCnt = extractHouseholdCountFromTree(root)
            if (hsehCnt > 0) {
                return hsehCnt
            }

            if (index < candidates.lastIndex) {
                Thread.sleep(randomDelayMs(householdFallbackDelayMinMs, householdFallbackDelayMaxMs, 250L, 700L))
            }
        }
        return 0
    }

    private fun requestBodyOnce(
        url: String,
        referer: String,
        userAgent: String,
        timeoutMs: Long,
    ): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(3_000L)))
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", referer)
                .header("User-Agent", userAgent)
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            if (status == 200) {
                return response.body().orEmpty()
            }
            if (status == 429) {
                recordHouseholdFallback429()
            }
            if (status in setOf(401, 403, 404, 429)) {
                log.info("세대수 보강 API 응답 상태({}). url={}", status, url)
            } else {
                log.debug("세대수 보강 API 응답 상태({}). url={}", status, url)
            }
            null
        } catch (e: Exception) {
            log.debug("세대수 보강 API 호출 실패. url={} message={}", url, e.message)
            null
        }
    }

    private fun shouldAttemptHouseholdFallbackNow(): Boolean {
        val now = System.currentTimeMillis()
        val inDegradedMode = now < householdFallbackDegradedUntilMillis
        val sampleRate = if (inDegradedMode) {
            householdFallbackDegradedSampleRate
        } else {
            householdFallbackNormalSampleRate
        }.coerceIn(0.0, 1.0)

        if (sampleRate >= 1.0) return true
        if (sampleRate <= 0.0) return false
        return random.nextDouble() < sampleRate
    }

    private fun recordHouseholdFallback429() {
        val now = System.currentTimeMillis()
        val windowMs = householdFallback429WindowMs.coerceAtLeast(60_000L)
        val threshold = householdFallback429Threshold.coerceAtLeast(1)
        val degradedDurationMs = householdFallbackDegradedDurationMs.coerceAtLeast(60_000L)

        synchronized(householdFallback429Lock) {
            while (householdFallback429Timestamps.isNotEmpty() && now - householdFallback429Timestamps.first() > windowMs) {
                householdFallback429Timestamps.removeFirst()
            }
            householdFallback429Timestamps.addLast(now)

            if (householdFallback429Timestamps.size >= threshold) {
                val previousUntil = householdFallbackDegradedUntilMillis
                val nextUntil = now + degradedDurationMs
                if (nextUntil > previousUntil) {
                    householdFallbackDegradedUntilMillis = nextUntil
                    if (now >= previousUntil) {
                        log.warn(
                            "세대수 보강 API 429 급증 감지 - {}분간 샘플링 축소({} -> {}).",
                            degradedDurationMs / 60_000L,
                            householdFallbackNormalSampleRate,
                            householdFallbackDegradedSampleRate
                        )
                    }
                }
            }
        }
    }

    private fun extractHouseholdCountFromTree(root: JsonNode): Int {
        val direct = firstPositive(
            parseHouseholdCount(root.get("hsehCnt")),
            parseHouseholdCount(root.get("totHsehCnt")),
            parseHouseholdCount(root.get("totHhldCnt")),
            parseHouseholdCount(root.get("hhldCnt")),
            parseHouseholdCount(root.get("householdCount")),
            parseHouseholdCount(root.get("totalHouseholdCount")),
            parseHouseholdCount(root.get("allHouseholdCount"))
        )
        if (direct > 0) return direct

        fun scan(node: JsonNode, depth: Int): Int {
            if (depth > 5) return 0
            if (node.isObject) {
                for ((rawKey, value) in node.properties()) {
                    val key = rawKey.lowercase()
                    if (isHouseholdLikeField(key)) {
                        val parsed = parseHouseholdCount(value)
                        if (parsed > 0) return parsed
                    }
                    val nested = scan(value, depth + 1)
                    if (nested > 0) return nested
                }
            } else if (node.isArray) {
                node.forEach { child ->
                    val nested = scan(child, depth + 1)
                    if (nested > 0) return nested
                }
            }
            return 0
        }

        return scan(root, 0)
    }

    private fun isHouseholdLikeField(key: String): Boolean {
        if (key in HOUSEHOLD_FIELD_NAMES) return true
        if (key.contains("household") && (key.contains("cnt") || key.contains("count"))) return true
        if (key.contains("hhld") && (key.contains("cnt") || key.contains("count"))) return true
        if (key.contains("hseh") && (key.contains("cnt") || key.contains("count"))) return true
        return false
    }

    private fun String.oneLineSnippet(maxLength: Int = 180): String {
        return replace("\n", " ").replace("\r", " ").trim().take(maxLength)
    }

    private data class ComplexInfo(
        val hscpNo: String,
        val hscpNm: String,
        val hsehCnt: Int = 0
    )

    private data class ArticleFetchResult(
        val listings: List<Listing>,
        val blockedByAbuse: Boolean = false
    )

    private data class RequestResult(
        val body: String?,
        val blockedByAbuse: Boolean = false,
        val timedOut: Boolean = false,
    )

    private data class AltApiCandidate(
        val url: String,
        val referer: String,
    )

    companion object {
        private val RETRYABLE_STATUS_CODES = setOf(401, 403, 429, 500, 502, 503, 504)
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private val HOUSEHOLD_FIELD_NAMES = setOf(
            "hsehcnt",
            "tothsehcnt",
            "tothhldcnt",
            "hhldcnt",
            "householdcount",
            "totalhouseholdcount",
            "allhouseholdcount",
            "householdtotalcount"
        )
    }
}
