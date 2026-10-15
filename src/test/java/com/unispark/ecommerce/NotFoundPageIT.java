package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The localized 404 page through a real server, because MockMvc skips Spring Boot's error dispatch. Requests are
 * written by hand: Java's HTTP clients refuse to set the {@code Host} header that store resolution depends on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class NotFoundPageIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void rendersAnArabicPageForAnUnknownStore() throws IOException {
        var response = get("nosuchstore.lvh.me", "/", "");

        assertThat(response).startsWith("HTTP/1.1 404")
                .contains("dir=\"rtl\"")
                .contains("لم نجد ما تبحث عنه")
                .doesNotContain("??");
    }

    @Test
    void rendersThePageInTheCookieLanguage() throws IOException {
        var response = get("nosuchstore.lvh.me", "/", "Cookie: lang=en\r\n");

        assertThat(response).startsWith("HTTP/1.1 404")
                .contains("dir=\"ltr\"")
                .contains("We could not find that page");
    }

    @Test
    void rendersTheSamePageForAnUnknownPathInsteadOfJson() throws IOException {
        var response = get("lvh.me", "/no-such-page", "");

        assertThat(response).startsWith("HTTP/1.1 404")
                .contains("text/html")
                .contains("لم نجد ما تبحث عنه");
    }

    @Test
    void rendersTemporarilyUnavailableForASuspendedStore() throws IOException {
        var slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "SUSPENDED");

        var response = get(slug + ".lvh.me", "/", "");

        assertThat(response).startsWith("HTTP/1.1 503")
                .contains("dir=\"rtl\"")
                .contains("هذا المتجر غير متاح مؤقتاً")
                .doesNotContain("??");
    }

    private String get(String host, String path, String extraHeaders) throws IOException {
        try (var socket = new Socket("localhost", port)) {
            var request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Accept: text/html\r\n"
                    + extraHeaders
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
