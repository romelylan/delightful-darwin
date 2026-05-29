package com.core.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class CoreGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreGatewayApplication.class, args);
    }

    @Value("${CORE_BE_URI:http://localhost:8082}")
    private String coreBackendUri;

    @Value("${KEYCLOAK_URI:http://keycloak:8080}")
    private String keycloakUri;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder, TokenPropagationFilter tokenFilter) {
        return builder.routes()
                // Route all wallet API requests to core-be microservice
                .route("core-be-route", r -> r.path("/api/wallet/**")
                        .filters(f -> f.filter(tokenFilter))
                        .uri(coreBackendUri))
                // Route Keycloak OAuth token requests through the gateway broker
                .route("keycloak-token-route", r -> r.path("/realms/production/protocol/openid-connect/token")
                        .uri(keycloakUri))
                .build();
    }
}

// 1. Gateway Security Configuration: Decoupled JWT Token Offloading
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeExchange(exchanges -> exchanges
                // Restrict all wallet APIs to valid Keycloak tokens
                .pathMatchers("/api/wallet/**").authenticated()
                .anyExchange().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}

// 2. Gateway Filter: Secure Header Propagation Pattern
@Configuration
class TokenPropagationFilter implements GatewayFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .flatMap(authentication -> {
                if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
                    Jwt jwt = (Jwt) authentication.getPrincipal();
                    
                    // Extract claims statefully from the Keycloak JWT
                    String userId = jwt.getSubject(); // sub
                    String clientId = jwt.getClaimAsString("azp"); // Authorized party (Client ID)
                    String scopes = jwt.getClaimAsString("scope");

                    // Mutate the downstream request, propagating secure trusted headers
                    ServerWebExchange mutatedExchange = exchange.mutate()
                        .request(r -> r
                            .header("X-User-Id", userId != null ? userId : "")
                            .header("X-Client-Id", clientId != null ? clientId : "")
                            .header("X-User-Scopes", scopes != null ? scopes : "")
                            // Strip raw JWT to prevent downstream duplication and reduce packet size
                            .headers(headers -> headers.remove("Authorization"))
                        )
                        .build();
                    
                    return chain.filter(mutatedExchange);
                }
                return chain.filter(exchange);
            })
            .switchIfEmpty(chain.filter(exchange)); // Fallback if unauthenticated
    }
}
