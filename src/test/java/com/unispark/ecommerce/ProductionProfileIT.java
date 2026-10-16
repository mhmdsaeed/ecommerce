package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * M15: the prod profile on a real server, as a reverse proxy would call it. MockMvc cannot show this: Tomcat's
 * RemoteIpValve, which turns forwarded headers into the client address and https, runs before any filter.
 * Requests are written by hand because Java's HTTP clients refuse to set {@code Host}. Rate limits are on here,
 * unlike in the other integration tests, and each test uses client addresses of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limits.enabled=true")
@Import(TestcontainersConfig.class)
@ActiveProfiles({"test", "prod"})
class ProductionProfileIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    String slug;

    @BeforeEach
    void createStore() {
        slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "ACTIVE");
    }

    @Test
    void redirectsPlainHttpToHttps() throws IOException {
        var page = request("GET", slug + ".lvh.me", "/search?q=x", "");
        assertThat(page).startsWith("HTTP/1.1 308").contains("Location: https://" + slug + ".lvh.me/search?q=x");

        // 308, not 301: a form posted over http keeps its method and body.
        var form = request("POST", "lvh.me", "/signup", "");
        assertThat(form).startsWith("HTTP/1.1 308").contains("Location: https://lvh.me/signup");
    }

    @Test
    void keepsHealthChecksOnPlainHttp() throws IOException {
        assertThat(request("GET", "lvh.me", "/actuator/health/readiness", "")).startsWith("HTTP/1.1 200");
    }

    @Test
    void trustsHttpsFromTheProxyAndSendsHsts() throws IOException {
        var page = request("GET", slug + ".lvh.me", "/", viaProxy("203.0.113.1"));

        assertThat(page).startsWith("HTTP/1.1 200")
                .contains("Strict-Transport-Security: max-age=31536000 ; includeSubDomains")
                // The CSRF cookie is Secure, and canonical links use https.
                .containsPattern("Set-Cookie: XSRF-TOKEN=[^\\r\\n]*; Secure")
                .contains("<link rel=\"canonical\" href=\"https://" + slug + ".lvh.me/\">");
    }

    @Test
    void countsTheAddressTheProxySawNotOnesTheClientMadeUp() throws IOException {
        for (var i = 0; i < 10; i++) {
            request("POST", slug + ".lvh.me", "/checkout/code", viaProxy("203.0.113.30"));
        }

        // The proxy appends the address it saw; whatever the client put before it is not trusted.
        var spoofed = request("POST", slug + ".lvh.me", "/checkout/code", viaProxy("198.51.100.99, 203.0.113.30"));
        assertThat(spoofed).startsWith("HTTP/1.1 429");
    }

    @Test
    void limitsVerificationTextsPerClientAddress() throws IOException {
        for (var i = 0; i < 10; i++) {
            var response = request("POST", slug + ".lvh.me", "/checkout/code", viaProxy("203.0.113.10"));
            // No CSRF token: refused, but counted, since the limit comes first.
            assertThat(response).startsWith("HTTP/1.1 403");
        }

        var limited = request("POST", slug + ".lvh.me", "/checkout/code",
                viaProxy("203.0.113.10") + "Accept: text/html\r\n");
        assertThat(limited).startsWith("HTTP/1.1 429")
                .containsPattern("Retry-After: \\d+")
                .contains("لحظة من فضلك")
                .contains("noindex");

        var otherShopper = request("POST", slug + ".lvh.me", "/checkout/code", viaProxy("203.0.113.11"));
        assertThat(otherShopper).startsWith("HTTP/1.1 403");
        // Browsing is not limited.
        assertThat(request("GET", slug + ".lvh.me", "/", viaProxy("203.0.113.10"))).startsWith("HTTP/1.1 200");
    }

    @Test
    void limitsTheApiPerClientAddress() throws IOException {
        // 300 a minute, refilled continuously (5 a second), so a few more get through while these run.
        String limited = null;
        var served = 0;
        while (served < 400) {
            var response = request("GET", "lvh.me", "/api/v1/stores/" + slug, viaProxy("203.0.113.20"));
            if (response.startsWith("HTTP/1.1 429")) {
                limited = response;
                break;
            }
            assertThat(response).startsWith("HTTP/1.1 200");
            served++;
        }

        assertThat(served).isBetween(300, 399);
        assertThat(limited)
                .containsPattern("Retry-After: \\d+")
                .contains("application/problem+json")
                .contains("\"status\":429");
        assertThat(request("GET", "lvh.me", "/api/v1/stores/" + slug, viaProxy("203.0.113.21")))
                .startsWith("HTTP/1.1 200");
    }

    /** What a TLS-terminating proxy adds; the test client on loopback is a trusted proxy by default. */
    private static String viaProxy(String clientAddress) {
        return "X-Forwarded-For: " + clientAddress + "\r\nX-Forwarded-Proto: https\r\n";
    }

    private String request(String method, String host, String path, String extraHeaders) throws IOException {
        try (var socket = new Socket("localhost", port)) {
            var request = method + " " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + extraHeaders
                    + ("POST".equals(method) ? "Content-Length: 0\r\n" : "")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
