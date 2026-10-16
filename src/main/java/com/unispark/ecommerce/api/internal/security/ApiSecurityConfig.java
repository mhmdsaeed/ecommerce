package com.unispark.ecommerce.api.internal.security;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unispark.ecommerce.shared.RateLimits;
import com.unispark.ecommerce.tenant.Site;
import com.unispark.ecommerce.tenant.Sites;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * The API's security chain (SPEC 10): {@code /api/**} on the main host, ahead of the web chains. Stateless: no
 * session, no cookies, so no CSRF. Bearer tokens are HS256 JWTs signed with {@code JWT_SECRET}; they must expire.
 * The catalog reads are open to the shopper app; everything else under {@code /api/v1} needs a token.
 * Errors are problem details (JSON), never the HTML error pages.
 */
@Configuration(proxyBeanMethods = false)
class ApiSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityConfig.class);

    /** HS256 needs a key of at least 256 bits. */
    static final int MIN_SECRET_BYTES = 32;

    @Bean
    @Order(0)
    SecurityFilterChain apiChain(HttpSecurity http, Sites sites, StoreLookup stores, JwtDecoder apiJwtDecoder,
            ObjectMapper json, RateLimits limits) throws Exception {
        var entryPoint = new BearerTokenAuthenticationEntryPoint();
        var deniedHandler = new BearerTokenAccessDeniedHandler();
        http.securityMatcher(new AndRequestMatcher(request -> sites.siteOf(request) == Site.MAIN,
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/stores/*", "/api/v1/products", "/api/v1/products/*")
                        .permitAll()
                        // springdoc serves the UI next to its configured path: /api/docs redirects there.
                        .requestMatchers(HttpMethod.GET, "/api/docs", "/api/docs/**", "/api/swagger-ui/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(apiJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) -> {
                            entryPoint.commence(request, response, exception);
                            Problems.write(json, request, response, HttpStatus.UNAUTHORIZED,
                                    "A valid bearer token is required.");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            deniedHandler.handle(request, response, exception);
                            Problems.write(json, request, response, HttpStatus.FORBIDDEN, "Not allowed.");
                        }))
                .addFilterBefore(new ApiRateLimitFilter(limits, json), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new ApiTenantFilter(stores, json), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Verifies HS256 tokens signed with {@code app.api.jwt-secret}. Without a secret (a deployment that does not
     * use merchant tokens yet) every token is refused; the open catalog reads still work.
     */
    @Bean
    JwtDecoder apiJwtDecoder(@Value("${app.api.jwt-secret:}") String secret) {
        if (secret.isBlank()) {
            log.warn("JWT_SECRET is not set: the API refuses every bearer token");
            return token -> {
                throw new BadJwtException("Bearer tokens are not accepted");
            };
        }
        var key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(),
                new JwtClaimValidator<>(JwtClaimNames.EXP, Objects::nonNull)));
        return decoder;
    }
}
