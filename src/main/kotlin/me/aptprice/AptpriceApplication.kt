package me.aptprice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AptpriceApplication

fun main(args: Array<String>) {
    runApplication<AptpriceApplication>(*args)
}
