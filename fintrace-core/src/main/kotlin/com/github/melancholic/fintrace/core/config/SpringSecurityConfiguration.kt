package com.github.melancholic.fintrace.core.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SpringSecurityConfiguration {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        return http.authorizeHttpRequests { auth ->
            auth
                .requestMatchers(*PERMITTED_PATHS).permitAll()
                .requestMatchers("/admin/**").hasRole(ADMIN_ROLE)
                .anyRequest().authenticated()
        }.build()
    }

    companion object {
        val PERMITTED_PATHS = arrayOf(
            "/",
            "/api/public/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
        )
        const val ADMIN_ROLE = "ADMIN"

    }
}