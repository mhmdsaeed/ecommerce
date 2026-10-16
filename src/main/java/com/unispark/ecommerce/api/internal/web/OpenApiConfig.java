package com.unispark.ecommerce.api.internal.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** The OpenAPI document behind {@code /api/docs}. Only the API package is scanned (application.yml). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String BEARER = "bearer";

    @Bean
    OpenAPI apiDocument() {
        return new OpenAPI()
                .info(new Info().title("Store API").version("v1").description("""
                        Read the catalog of a store open to shoppers. Look the store up by its slug, then send its \
                        id in the X-Store-Id header. Texts come in Arabic and English; prices are in the store's \
                        display currency, as amounts in strings. Errors are problem details (RFC 9457).
                        Merchant endpoints come with the mobile app and use bearer tokens (JWT, HS256) whose \
                        store_id claim names the store."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
