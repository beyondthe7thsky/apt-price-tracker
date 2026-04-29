package me.aptprice.util

import me.aptprice.model.Listing
import me.aptprice.model.MarketStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionResultGuardTest {

    @Test
    fun `rejects empty result when region previously had visible tracked listings`() {
        val oldData = mapOf(
            "1" to listing(regionName = "용인수지_성복동", status = MarketStatus.ACTIVE, pyeong = 34)
        )

        val accepted = RegionResultGuard.shouldAccept(
            regionName = "용인수지_성복동",
            oldData = oldData,
            listings = emptyList()
        )

        assertFalse(accepted)
    }

    @Test
    fun `accepts empty result when region had no visible tracked listings before`() {
        val oldData = mapOf(
            "1" to listing(regionName = "용인수지_성복동", status = MarketStatus.OFF_MARKET, pyeong = 34)
        )

        val accepted = RegionResultGuard.shouldAccept(
            regionName = "용인수지_성복동",
            oldData = oldData,
            listings = emptyList()
        )

        assertTrue(accepted)
    }

    private fun listing(
        regionName: String,
        status: MarketStatus,
        pyeong: Int,
    ): Listing = Listing(
        articleNo = "a1",
        hscpNo = "100",
        sameAddrHash = "hash",
        buildingName = "101동",
        title = "테스트아파트",
        featureDesc = "",
        tagList = emptyList(),
        regionName = regionName,
        price = 100000,
        floor = "10/20",
        areaSqm = 84.0,
        areaSupplySqm = 112.0,
        areaExclusiveSqm = 84.0,
        pyeong = pyeong,
        url = "https://example.com/a1",
        updatedAt = "2026-04-01T09:00:00",
        firstSeenAt = "2026-04-01T09:00:00",
        lastSeenAt = "2026-04-01T09:00:00",
        status = status,
        statusChangedAt = "2026-04-01T09:00:00",
        offMarketAt = null,
        missCount = 0
    )
}
