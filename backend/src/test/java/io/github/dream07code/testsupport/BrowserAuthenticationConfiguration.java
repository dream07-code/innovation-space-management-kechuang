package io.github.dream07code.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Dedicated test-only probe chain, never part of production component scanning or JAR. */
@TestConfiguration(proxyBeanMethods = false)
public class BrowserAuthenticationConfiguration {
    @Bean
    @Order(0)
    public SecurityFilterChain browserProbeChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/test-probe/**")
                .csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
}
