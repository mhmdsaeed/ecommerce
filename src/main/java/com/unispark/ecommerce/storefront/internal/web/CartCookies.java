package com.unispark.ecommerce.storefront.internal.web;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.cart.Carts;

/**
 * The two cart cookies, per store host:
 * <ul>
 *   <li>{@value #TOKEN}: the cart token. HttpOnly; scripts never need it.
 *   <li>{@value #COUNT}: the number of items, read by the header badge (app.js). So pages stay the same for
 *       everyone and cacheable, and showing the badge costs no database query.
 * </ul>
 */
@Component
class CartCookies {

    static final String TOKEN = "cart";
    static final String COUNT = "cart_count";

    private final boolean secure;

    CartCookies(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        this.secure = secure;
    }

    void write(HttpServletResponse response, String token, int count) {
        if (token != null) {
            add(response, TOKEN, token, true);
        }
        writeCount(response, count);
    }

    void writeCount(HttpServletResponse response, int count) {
        add(response, COUNT, Integer.toString(count), false);
    }

    private void add(HttpServletResponse response, String name, String value, boolean httpOnly) {
        var cookie = ResponseCookie.from(name, value)
                .path("/")
                .maxAge(Carts.LIFETIME)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
