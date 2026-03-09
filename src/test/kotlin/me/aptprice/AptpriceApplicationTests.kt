package me.aptprice

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["bot.enabled=false"])
class AptpriceApplicationTests {

    @Test
    fun contextLoads() {
    }

}
