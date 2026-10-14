package com.unispark.ecommerce.storefront.internal.web;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;

/**
 * The cart cookies, per store host:
 * <ul>
 *   <li>{@value #TOKEN}: the cart token. HttpOnly; scripts never need it.
 *   <li>{@value #COUNT}: the number of items, read by the header badge (app.js). So pages stay the same for
 *       everyone and cacheable, and showing the badge costs no database query.
 *   <li>{@value #DESTINATION}: where the shopper last asked delivery to go, so the cart keeps showing the fee
 *       after every change (and checkout can start from it). Base64url, HttpOnly.
 * </ul>
 */
@Component
class CartCookies {

    static final String TOKEN = "cart";
    static final String COUNT = "cart_count";
    static final String DESTINATION = "delivery_to";
    private static final String SEPARATOR = "\n";
    private static final int MAX_DESTINATION_LENGTH = 1024;

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

    void writeDestination(HttpServletResponse response, Destination destination) {
        var value = String.join(SEPARATOR, destination.governorate().name(),
                destination.city() == null ? "" : destination.city(),
                destination.area() == null ? "" : destination.area());
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        add(response, DESTINATION, encoded, true);
    }

    /**
     * The cart token as sent, or null. Read raw like the destination: {@code @CookieValue} would URL-decode it and
     * answer a tampered value such as {@code %%%} with a server error, where an unknown token is just no cart.
     */
    static String token(HttpServletRequest request) {
        var cookie = WebUtils.getCookie(request, TOKEN);
        return cookie == null ? null : cookie.getValue();
    }

    /**
     * The destination in the request's cookie; empty when there is none or it was tampered with. Read from the
     * raw cookie, because {@code @CookieValue} URL-decodes values and fails on a malformed one.
     */
    static Optional<Destination> readDestination(HttpServletRequest request) {
        var cookie = WebUtils.getCookie(request, DESTINATION);
        return parseDestination(cookie == null ? null : cookie.getValue());
    }

    static Optional<Destination> parseDestination(String cookie) {
        if (cookie == null || cookie.isEmpty() || cookie.length() > MAX_DESTINATION_LENGTH) {
            return Optional.empty();
        }
        try {
            var decoded = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(cookie))).toString();
            var parts = decoded.split(SEPARATOR, -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(Destination.of(Governorate.valueOf(parts[0]), parts[1], parts[2]));
        } catch (IllegalArgumentException | CharacterCodingException e) {
            return Optional.empty();
        }
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
