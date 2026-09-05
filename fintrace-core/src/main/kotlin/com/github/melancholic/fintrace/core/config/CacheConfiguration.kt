package com.github.melancholic.fintrace.core.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
@EnableCaching
class CacheConfiguration {

    @Bean
    fun userIdBySubCache() = Caffeine.newBuilder()
        .expireAfterWrite(55, TimeUnit.MINUTES)

    @Bean
    fun cacheManager() = CaffeineCacheManager().apply {
        registerCustomCache(USERID_BY_SUB_CACHE, userIdBySubCache().build())
    }

    companion object {
        const val USERID_BY_SUB_CACHE = "userIdBySubCache"
    }

}