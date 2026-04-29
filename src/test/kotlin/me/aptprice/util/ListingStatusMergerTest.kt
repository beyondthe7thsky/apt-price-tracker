package me.aptprice.util

import me.aptprice.model.Listing
import me.aptprice.model.MarketStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListingStatusMergerTest {

    @Test
    fun `same unit with new article number is treated as same active listing`() {
        val oldListing = listing(
            articleNo = "old-1",
            sameAddrHash = "hash-1",
            price = 120000,
            firstSeenAt = "2026-04-01T09:00:00",
            lastSeenAt = "2026-04-10T09:00:00"
        )
        val freshListing = listing(
            articleNo = "new-1",
            sameAddrHash = "hash-1",
            price = 120000
        )

        val result = ListingStatusMerger.merge(
            oldData = mapOf(oldListing.articleNo to oldListing),
            allNewListings = listOf(freshListing),
            successfulRegions = setOf(oldListing.regionName),
            now = "2026-04-11T09:00:00",
            offMarketConfirmMissCount = 3
        )

        assertEquals(1, result.mergedListings.size)
        val merged = result.mergedListings.single()
        assertEquals("old-1", merged.normalizedEntityId())
        assertEquals("new-1", merged.articleNo)
        assertEquals(MarketStatus.ACTIVE, merged.status)
        assertEquals("2026-04-01T09:00:00", merged.firstSeenAt)
        assertEquals("2026-04-11T09:00:00", merged.lastSeenAt)
        assertTrue(result.notifyList.isEmpty())
        assertFalse(result.mergedListings.any { it.articleNo == "old-1" })
    }

    @Test
    fun `same unit reappearing after off market becomes relisted even if article number changed`() {
        val oldListing = listing(
            articleNo = "old-2",
            sameAddrHash = "hash-2",
            status = MarketStatus.OFF_MARKET,
            statusChangedAt = "2026-04-10T09:00:00",
            offMarketAt = "2026-04-10T09:00:00",
            missCount = 3
        )
        val freshListing = listing(
            articleNo = "new-2",
            sameAddrHash = "hash-2"
        )

        val result = ListingStatusMerger.merge(
            oldData = mapOf(oldListing.articleNo to oldListing),
            allNewListings = listOf(freshListing),
            successfulRegions = setOf(oldListing.regionName),
            now = "2026-04-12T09:00:00",
            offMarketConfirmMissCount = 3
        )

        val merged = result.mergedListings.single()
        assertEquals("old-2", merged.normalizedEntityId())
        assertEquals("new-2", merged.articleNo)
        assertEquals(MarketStatus.RELISTED, merged.status)
        assertEquals("2026-04-12T09:00:00", merged.statusChangedAt)
        assertNull(merged.offMarketAt)
        assertEquals(listOf("다시 등록된 매물♻️"), result.notifyList.map { it.second })
        assertEquals(1, result.relistedChanged)
    }

    private fun listing(
        articleNo: String,
        sameAddrHash: String,
        price: Long = 100000,
        firstSeenAt: String = "2026-04-01T09:00:00",
        lastSeenAt: String = "2026-04-01T09:00:00",
        status: MarketStatus = MarketStatus.ACTIVE,
        statusChangedAt: String = firstSeenAt,
        offMarketAt: String? = null,
        missCount: Int = 0,
    ): Listing = Listing(
        articleNo = articleNo,
        hscpNo = "100",
        sameAddrHash = sameAddrHash,
        buildingName = "101동",
        title = "테스트아파트",
        featureDesc = "남향",
        tagList = listOf("급매"),
        regionName = "서울동작_흑석동",
        price = price,
        floor = "10/20",
        areaSqm = 84.0,
        areaSupplySqm = 112.0,
        areaExclusiveSqm = 84.0,
        pyeong = 34,
        url = "https://example.com/$articleNo",
        updatedAt = firstSeenAt,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        status = status,
        statusChangedAt = statusChangedAt,
        offMarketAt = offMarketAt,
        missCount = missCount
    )
}
