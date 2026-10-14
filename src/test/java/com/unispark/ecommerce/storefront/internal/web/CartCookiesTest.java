package com.unispark.ecommerce.storefront.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;

class CartCookiesTest {

    @Test
    void keepsTheDeliveryDestinationAcrossRequests() {
        var response = new MockHttpServletResponse();
        var destination = Destination.of(Governorate.DAMASCUS, "دمشق", "المزة");

        new CartCookies(true).writeDestination(response, destination);

        var header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith(CartCookies.DESTINATION + "=").contains("HttpOnly", "Secure", "SameSite=Lax");
        var value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        assertThat(value).matches("[A-Za-z0-9_-]+");
        assertThat(CartCookies.parseDestination(value)).contains(destination);
    }

    @Test
    void keepsADestinationWithoutCityOrArea() {
        var response = new MockHttpServletResponse();
        new CartCookies(false).writeDestination(response, Destination.of(Governorate.HOMS, null, null));

        var header = response.getHeader("Set-Cookie");
        var value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        assertThat(CartCookies.parseDestination(value)).contains(Destination.of(Governorate.HOMS, null, null));
    }

    @Test
    void ignoresTamperedDestinations() {
        assertThat(CartCookies.parseDestination(null)).isEmpty();
        assertThat(CartCookies.parseDestination("")).isEmpty();
        assertThat(CartCookies.parseDestination("TkFSTklBCngKeQ")).isEmpty();
        assertThat(CartCookies.parseDestination("%%%garbage")).isEmpty();
        assertThat(CartCookies.parseDestination("_w")).isEmpty();
        assertThat(CartCookies.parseDestination("REFNQVNDVVM")).isEmpty();
        assertThat(CartCookies.parseDestination("A".repeat(2000))).isEmpty();
    }
}
