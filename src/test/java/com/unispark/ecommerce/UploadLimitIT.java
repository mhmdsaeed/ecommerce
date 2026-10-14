package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Uploads over the multipart request limit, through a real server: MockMvc never enforces Tomcat's limits.
 * {@code localhost} is the main site, so the merchant admin is reachable without a special Host header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Plain http: a Secure session cookie would never come back, as in local development.
        properties = "server.servlet.session.cookie.secure=false")
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class UploadLimitIT {

    private static final Pattern FORM_TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final Pattern HTMX_TOKEN = Pattern.compile("X-CSRF-TOKEN&quot;: &quot;([^&]+)&quot;");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    Products products;

    @Test
    void explainsUploadsOverTheRequestLimitInTheFormInsteadOfFailingSilently() throws Exception {
        var slug = TestStores.randomSlug();
        var store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "Owner", "owner@" + slug + ".test", null, StaffRole.OWNER);
        var product = TenantContext.callAs(store, () -> products.create(
                new ProductDraft("Rug", null, null, null, null, null, Currency.SYP), "1000"));
        var productId = ((Products.Saved) product).id();
        var http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        logIn(http, slug, "owner@" + slug + ".test");
        var page = http.send(get("/admin/products/" + productId), HttpResponse.BodyHandlers.ofString());
        var token = find(HTMX_TOKEN, page.body());

        var boundary = "----upload" + UUID.randomUUID();
        var upload = HttpRequest.newBuilder(uri("/admin/products/" + productId + "/images"))
                .header("HX-Request", "true")
                .header("X-CSRF-TOKEN", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, new byte[11 * 1024 * 1024])))
                .build();
        var response = http.send(upload, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("HX-Retarget")).contains("#upload-error");
        assertThat(response.headers().firstValue("HX-Reswap")).contains("innerHTML");
        assertThat(response.body()).contains("الملفات المختارة كبيرة جداً");
    }

    private void logIn(HttpClient http, String slug, String email) throws Exception {
        var form = http.send(get("/admin/login"), HttpResponse.BodyHandlers.ofString());
        var body = "_csrf=" + encode(find(FORM_TOKEN, form.body())) + "&store=" + encode(slug)
                + "&username=" + encode(email) + "&password=" + encode(TestAccounts.PASSWORD);
        var login = http.send(HttpRequest.newBuilder(uri("/admin/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/admin"));
    }

    private static byte[] multipart(String boundary, byte[] file) {
        var output = new ByteArrayOutputStream();
        var head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"huge.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n";
        output.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(file);
        output.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(uri(path)).GET().build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String find(Pattern pattern, String html) {
        var matcher = pattern.matcher(html);
        assertThat(matcher.find()).as("pattern %s in page", pattern).isTrue();
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
