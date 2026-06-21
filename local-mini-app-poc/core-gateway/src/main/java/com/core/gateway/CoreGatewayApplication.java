package com.core.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import org.springframework.web.server.session.WebSessionIdResolver;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.http.server.reactive.ServerHttpResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@SpringBootApplication
public class CoreGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreGatewayApplication.class, args);
    }

    @Value("${CORE_BE_URI:http://core-be:8082}")
    private String coreBackendUri;

    @Value("${MINI_APP_BE_URI:http://mini-app-be:8081}")
    private String miniAppBackendUri;

    @Value("${KEYCLOAK_URI:http://keycloak:8080}")
    private String keycloakUri;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        // Core Backend route: validate token, propagate headers, STRIP raw JWT
        TokenPropagationFilter coreBeFilter = new TokenPropagationFilter(true);
        
        // Mini App Backend route: validate token, propagate headers, KEEP raw JWT for Keycloak exchanges
        TokenPropagationFilter miniAppBeFilter = new TokenPropagationFilter(false);

        return builder.routes()
                // Route all wallet API requests to core-be microservice
                .route("core-be-route", r -> r.path("/api/wallet/**")
                        .filters(f -> f.filter(coreBeFilter))
                        .uri(coreBackendUri))
                // Route all rewards API requests to mini-app-be microservice
                .route("mini-app-be-route", r -> r.path("/api/rewards/**")
                        .filters(f -> f.filter(miniAppBeFilter))
                        .uri(miniAppBackendUri))
                // Route Keycloak OAuth token requests through the gateway broker
                .route("keycloak-token-route", r -> r.path("/realms/production/protocol/openid-connect/token")
                        .uri(keycloakUri))
                .build();
    }
}

// 1. Gateway Security Configuration: Decoupled JWT Token Offloading & Cross-Origin Credentials
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((exchange, e) -> Mono.defer(() -> {
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    try {
                        response.getHeaders().set(org.springframework.http.HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    } catch (UnsupportedOperationException ex) {
                        // Gracefully ignore if headers are already read-only/committed
                    }
                    return response.setComplete();
                }))
                .accessDeniedHandler((exchange, denied) -> Mono.defer(() -> {
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return response.setComplete();
                }))
            )
            .authorizeExchange(exchanges -> exchanges
                // Restrict bootstrap and business endpoints to authenticated Keycloak tokens
                .pathMatchers("/api/gateway/bootstrap-session", "/api/wallet/**", "/api/rewards/claim").authenticated()
                .anyExchange().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }

    @Bean
    public org.springframework.web.cors.reactive.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cookie"));
        configuration.setAllowCredentials(true);
        
        org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource source = 
            new org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        return new CorsWebFilter(corsConfigurationSource());
    }

    @Bean
    public WebSessionIdResolver webSessionIdResolver() {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName("SESSION");
        resolver.addCookieInitializer(builder -> {
            builder.path("/");
            builder.sameSite("None");
            builder.secure(true);
        });
        return resolver;
    }
}

// 2. Gateway Controller: Mini App BFF session bootstrap endpoint
@RestController
@CrossOrigin(allowCredentials = "true", originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
class GatewayMiniAppBffController {

    @PostMapping("/api/gateway/bootstrap-session")
    public Mono<ResponseEntity<?>> bootstrapSession(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            JwtAuthenticationToken authentication,
            ServerWebExchange exchange) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "Missing Bearer token")));
        }

        String scopedJwt = authorizationHeader.substring(7).trim();
        if (scopedJwt.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "Empty Bearer token")));
        }

        return exchange.getSession().map(session -> {
            session.getAttributes().put("SCOPED_TOKEN", scopedJwt);

            String userId = authentication.getToken().getSubject();
            String clientId = authentication.getToken().getClaimAsString("azp");

            System.out.println("====== API GATEWAY BFF: SESSION BOOTSTRAPPED FOR USER " + userId
                    + " VIA CLIENT " + Objects.toString(clientId, "unknown") + " ======");

            return ResponseEntity.ok(Map.of(
                    "status", "session_bootstrapped",
                    "userId", Objects.toString(userId, ""),
                    "clientId", Objects.toString(clientId, "")
            ));
        });
    }
}

// 3. Gateway Pre-Security WebFilter: Resolves the Gateway-owned WebSession cookie
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TokenSwappingWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        // If Authorization header is missing, resolve the user's token directly from the Gateway's server-side WebSession.
        if (authHeader == null || authHeader.trim().isEmpty()) {
            if (exchange.getRequest().getCookies().containsKey("SESSION")) {
                return exchange.getSession().flatMap(session -> {
                    String cachedJwt = (String) session.getAttribute("SCOPED_TOKEN");
                    if (cachedJwt != null) {
                        System.out.println("====== API GATEWAY STATEFUL: Resolved token from WebSession! Injecting Bearer Header... ======");
                        ServerWebExchange mutatedExchange = exchange.mutate()
                            .request(r -> r.header("Authorization", "Bearer " + cachedJwt))
                            .build();
                        return chain.filter(mutatedExchange);
                    }
                    return chain.filter(exchange);
                }).switchIfEmpty(chain.filter(exchange));
            }
            return chain.filter(exchange);
        }
        
        return chain.filter(exchange);
    }
}

// 4. Gateway Filter: Secure User Identity Propagation Pattern
class TokenPropagationFilter implements GatewayFilter {

    private final boolean stripAuthorization;

    public TokenPropagationFilter(boolean stripAuthorization) {
        this.stripAuthorization = stripAuthorization;
    }

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
                    // RFC 8693: for token-exchange tokens the "aud" claim is the authorization
                    // signal (the token is minted FOR a specific resource). The "scope" claim is
                    // empty on legacy-exchange tokens because exchange can only narrow the subject's
                    // scopes, so we forward the audience for resource servers to authorize on.
                    List<String> audiences = jwt.getAudience();
                    String audience = (audiences != null) ? String.join(" ", audiences) : "";

                    // Mutate downstream request, propagating verified trusted headers
                    ServerWebExchange mutatedExchange = exchange.mutate()
                        .request(r -> {
                            r.header("X-User-Id", userId != null ? userId : "");
                            r.header("X-Client-Id", clientId != null ? clientId : "");
                            r.header("X-User-Scopes", scopes != null ? scopes : "");
                            r.header("X-Token-Audience", audience);
                            
                            if (stripAuthorization) {
                                // Strip JWT for core systems to prevent duplication
                                r.headers(headers -> headers.remove("Authorization"));
                            }
                        })
                        .build();
                    
                    return chain.filter(mutatedExchange);
                }
                return chain.filter(exchange);
            })
            .switchIfEmpty(chain.filter(exchange)); // Fallback if unauthenticated
    }
}
