package com.unispark.ecommerce.api.internal.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/** Documents the store header on endpoints that read a store's data; ApiTenantFilter does the work. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(name = "X-Store-Id", in = ParameterIn.HEADER, required = true,
        description = "The store's id, from GET /api/v1/stores/{slug}. Not needed with a merchant token, which "
                + "names its store.")
@interface StoreHeader {
}
