package me.aptprice.repository

import me.aptprice.model.Listing
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Paths

@Repository
class FileDataRepository(private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val filePath = Paths.get("data/apt-listings.json")

    fun loadAll(): Map<String, Listing> {
        if (!Files.exists(filePath)) return emptyMap()
        val list: List<Listing> = objectMapper.readValue(filePath.toFile())
        return list.associateBy { it.articleNo }
    }

    fun saveAll(listings: List<Listing>) {
        if (!Files.exists(filePath.parent)) Files.createDirectories(filePath.parent)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), listings)
        log.info("JSON 저장 완료 ({}건)", listings.size)
    }
}