package me.aptprice.util

import me.aptprice.model.Listing
import me.aptprice.model.MarketStatus
import java.util.Locale

internal object ListingStatusMerger {
    fun merge(
        oldData: Map<String, Listing>,
        allNewListings: List<Listing>,
        successfulRegions: Set<String>,
        now: String,
        offMarketConfirmMissCount: Int,
    ): MergeResult {
        val newByArticleNo = allNewListings
            .groupBy { it.articleNo }
            .mapValues { (_, items) -> items.maxByOrNull { it.updatedAt } ?: items.first() }

        val mergedByArticleNo = mutableMapOf<String, Listing>()
        val notifyList = mutableListOf<Pair<Listing, String>>()
        val matchedOldArticleNos = mutableSetOf<String>()
        val oldByIdentityKey = buildOldByIdentityKey(oldData.values)
        var offMarketCandidateChanged = 0
        var offMarketChanged = 0
        var relistedChanged = 0

        newByArticleNo.forEach { (articleNo, freshListing) ->
            val oldListing = oldData[articleNo] ?: matchOldListingByIdentity(freshListing, oldByIdentityKey)
            if (oldListing != null) {
                matchedOldArticleNos += oldListing.articleNo
            }
            val normalizedListing = mergeSeenListing(oldListing, freshListing, now)
            mergedByArticleNo[articleNo] = normalizedListing

            if (oldListing == null) {
                notifyList.add(normalizedListing to "신규✨")
            } else if (normalizedListing.status == MarketStatus.RELISTED) {
                relistedChanged += 1
                notifyList.add(normalizedListing to "다시 등록된 매물♻️")
            } else if (freshListing.price < oldListing.price) {
                notifyList.add(normalizedListing to "급매⬇️${oldListing.price - freshListing.price}만")
            }
        }

        oldData.forEach { (articleNo, oldListing) ->
            if (articleNo in matchedOldArticleNos) return@forEach
            if (newByArticleNo.containsKey(articleNo)) return@forEach

            if (oldListing.regionName !in successfulRegions) {
                mergedByArticleNo[articleNo] = oldListing
                return@forEach
            }

            val transitioned = transitionMissingListing(oldListing, now, offMarketConfirmMissCount)
            mergedByArticleNo[articleNo] = transitioned

            if (transitioned.status != oldListing.status) {
                when (transitioned.status) {
                    MarketStatus.OFF_MARKET_CANDIDATE -> offMarketCandidateChanged += 1
                    MarketStatus.OFF_MARKET -> offMarketChanged += 1
                    else -> Unit
                }
            }
        }

        val mergedListings = mergedByArticleNo.values
            .sortedWith(compareBy<Listing> { it.regionName }.thenBy { it.articleNo })

        return MergeResult(
            mergedListings = mergedListings,
            notifyList = notifyList,
            newVisibleCount = newByArticleNo.size,
            offMarketCandidateChanged = offMarketCandidateChanged,
            offMarketChanged = offMarketChanged,
            relistedChanged = relistedChanged
        )
    }

    private fun buildOldByIdentityKey(oldListings: Collection<Listing>): MutableMap<String, ArrayDeque<Listing>> {
        val byIdentity = mutableMapOf<String, ArrayDeque<Listing>>()
        oldListings.forEach { listing ->
            val identityKey = listingIdentityKey(listing) ?: return@forEach
            byIdentity.getOrPut(identityKey) { ArrayDeque() }.addLast(listing)
        }
        return byIdentity
    }

    private fun matchOldListingByIdentity(
        freshListing: Listing,
        oldByIdentityKey: MutableMap<String, ArrayDeque<Listing>>,
    ): Listing? {
        val identityKey = listingIdentityKey(freshListing) ?: return null
        val candidates = oldByIdentityKey[identityKey] ?: return null
        val matched = candidates.removeFirstOrNull() ?: return null
        if (candidates.isEmpty()) {
            oldByIdentityKey.remove(identityKey)
        }
        return matched
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
        val normalizedBuildingName = fresh.buildingName.ifBlank { old.buildingName }
        val normalizedFeatureDesc = fresh.featureDesc.ifBlank { old.featureDesc }
        val normalizedTagList = fresh.tagList.ifEmpty { old.tagList }

        return fresh.copy(
            updatedAt = now,
            firstSeenAt = normalizedFirstSeenAt,
            lastSeenAt = now,
            buildingName = normalizedBuildingName,
            featureDesc = normalizedFeatureDesc,
            tagList = normalizedTagList,
            status = nextStatus,
            statusChangedAt = statusChangedAt,
            offMarketAt = null,
            missCount = 0
        )
    }

    private fun transitionMissingListing(old: Listing, now: String, offMarketConfirmMissCount: Int): Listing {
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

    private fun listingIdentityKey(listing: Listing): String? {
        if (listing.sameAddrHash.isNotBlank()) {
            return "HASH:${listing.hscpNo}:${listing.sameAddrHash}"
        }
        val buildingKey = normalizeBuildingKey(listing.buildingName)
        val floorKey = normalizeFloorKey(listing.floor)
        val areaKey = normalizedAreaKey(
            sequenceOf(listing.areaExclusiveSqm, listing.areaSupplySqm, listing.areaSqm)
                .firstOrNull { it > 0.0 } ?: 0.0
        )
        val titleKey = normalizeTitleKey(listing.title)
        if (buildingKey.isBlank() || floorKey.isBlank() || areaKey <= 0.0 || titleKey.isBlank()) {
            return null
        }
        return "UNIT:${listing.hscpNo}:$titleKey:$buildingKey:$floorKey:${"%.2f".format(Locale.US, areaKey)}"
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
}

internal data class MergeResult(
    val mergedListings: List<Listing>,
    val notifyList: List<Pair<Listing, String>>,
    val newVisibleCount: Int,
    val offMarketCandidateChanged: Int,
    val offMarketChanged: Int,
    val relistedChanged: Int
)
