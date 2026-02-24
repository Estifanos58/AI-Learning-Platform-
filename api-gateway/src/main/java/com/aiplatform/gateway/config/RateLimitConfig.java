package com.aiplatform.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver clientRateLimitKeyResolver() {
        return exchange -> Mono.justOrEmpty(
                        exchange.getRequest().getHeaders().getFirst("X-User-Id")
                )
                .switchIfEmpty(Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                        .map(address -> address.getAddress().getHostAddress()))
                .defaultIfEmpty("anonymous");
    }
}
