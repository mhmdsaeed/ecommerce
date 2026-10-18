package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The localized error pages (404, 503, 4xx and 5xx) through a real server, because MockMvc skips Spring Boot's
 * error dispatch. Requests are written by hand: Java's HTTP clients refuse to set the {@code Host} header that
 * store resolution depends on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, ErrorPagesIT.Failing.class})
@ActiveProfiles("test")
class ErrorPagesIT {

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
                // The door sign says it in two words; the way back is a button home.
                .contains(">ليس هنا</p>").contains("<a href=\"/\" class=\"mt-8")
                .contains("<meta name=\"robots\" content=\"noindex\">")
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

    @Test
    void rendersOpeningSoonForAStoreNotLaunchedYet() throws IOException {
        var slug = TestStores.randomSlug();
        var store = TestStores.create(jdbc, slug, "TRIAL");
        jdbc.update("UPDATE store SET launched_at = NULL WHERE id = ?", store);

        var response = get(slug + ".lvh.me", "/", "");

        assertThat(response).startsWith("HTTP/1.1 503")
                .contains("هذا المتجر سيفتح قريباً")
                .contains("noindex")
                .doesNotContain("غير متاح مؤقتاً")
                // The store's sign, its shutter down.
                .contains("<p class=\"shop-sign__name\" dir=\"auto\">متجر " + slug + "</p>")
                .contains("<div class=\"shop-sign__shutter\" style=\"height: 100%\"></div>");
    }

    @Test
    void rendersAFriendlyPageForAServerErrorWithoutDetails() throws IOException {
        var response = get("lvh.me", "/test/failure", "");

        assertThat(response).startsWith("HTTP/1.1 500")
                .contains("حدث خطأ من جهتنا")
                .contains(">معطّل مؤقتاً</p>").contains(">500</p>")
                .contains("<meta name=\"robots\" content=\"noindex\">")
                .doesNotContain("secret detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.unispark");
    }

    @Test
    void rendersAFriendlyPageForOtherClientErrors() throws IOException {
        var response = get("lvh.me", "/test/number?n=abc", "");

        assertThat(response).startsWith("HTTP/1.1 400")
                .contains("لم نتمكن من تنفيذ هذا الطلب")
                .contains(">لم نفهم</p>").contains(">400</p>")
                .contains("<meta name=\"robots\" content=\"noindex\">");
    }

    /** A page that always fails, to see what visitors get. Registered as a member of this configuration. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Failing {

        @RestController
        static class FailingController {

            @GetMapping("/test/failure")
            String fail() {
                throw new IllegalStateException("secret detail");
            }

            @GetMapping("/test/number")
            String number(@RequestParam int n) {
                return String.valueOf(n);
            }
        }
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
