package me.aptprice.util

import me.aptprice.model.Listing
import me.aptprice.model.MarketStatus

internal object RegionResultGuard {
    fun shouldAccept(regionName: String, oldData: Map<String, Listing>, listings: List<Listing>): Boolean {
        val filtered = listings.filter { it.pyeong in 20..39 }
        if (filtered.isNotEmpty()) {
            return true
        }

        val hadVisibleTrackedListings = oldData.values.any { listing ->
            listing.regionName == regionName &&
                listing.pyeong in 20..39 &&
                listing.status != MarketStatus.OFF_MARKET
        }

        // 이전에 추적 중인 매물이 있었는데 이번 결과가 완전히 비면,
        // 실제 0건이라기보다 응답/파싱 이상일 가능성이 높아서 성공으로 치지 않는다.
        return !hadVisibleTrackedListings
    }
}
