package com.unispark.ecommerce;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * M8 done-when (SPEC 16): in a real browser, a shopper places a cash-on-delivery order, and the merchant sees it
 * in the admin. Hosts are the real ones ({@code <slug>.lvh.me}, {@code lvh.me}), mapped to this machine by a
 * browser flag, so no DNS is needed.
 *
 * <p>Uses the installed Microsoft Edge when there is one (no browser download), else Playwright's Chromium, which
 * CI installs with {@code playwright install --with-deps chromium}. {@code E2E_BROWSER_CHANNEL} overrides the
 * choice ({@code chromium}, {@code msedge}, {@code chrome}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Plain http: a Secure cookie would never come back.
        properties = "server.servlet.session.cookie.secure=false")
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutE2EIT {

    static final List<Path> EDGE = List.of(
            Path.of("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"),
            Path.of("C:/Program Files/Microsoft/Edge/Application/msedge.exe"),
            Path.of("/usr/bin/microsoft-edge"),
            Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Products products;

    @Autowired
    DeliveryZones zones;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    Playwright playwright;
    Browser browser;
    String slug;

    @BeforeAll
    void startBrowser() {
        var channel = System.getenv().getOrDefault("E2E_BROWSER_CHANNEL",
                EDGE.stream().anyMatch(Files::isExecutable) ? "msedge" : "chromium");
        var env = "chromium".equals(channel) ? Map.<String, String>of()
                : Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
        var options = new BrowserType.LaunchOptions()
                .setArgs(List.of("--host-resolver-rules=MAP lvh.me 127.0.0.1, MAP *.lvh.me 127.0.0.1"));
        if (!"chromium".equals(channel)) {
            options.setChannel(channel);
        }
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    void stopBrowser() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void createStore() {
        slug = TestStores.randomSlug();
        var store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "Owner", "owner@" + slug + ".test", null, StaffRole.OWNER);
        TenantContext.runAs(store, () -> {
            var id = ((Saved) products.create(new ProductDraft("وشاح صوف", "Wool scarf", null, null, "scarf", null,
                    Currency.SYP), "10000")).id();
            products.changeStatus(id, ProductStatus.ACTIVE);
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, null, null,
                    null));
        });
    }

    @Test
    void shopperPlacesACodOrderAndTheMerchantSeesIt() {
        try (var shopperContext = browser.newContext(); var merchantContext = browser.newContext()) {
            var shopper = shopperContext.newPage();
            shopper.navigate(store("/p/scarf"));
            shopper.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("أضف إلى السلة")).click();
            assertThat(shopper.getByText("تمت الإضافة إلى سلتك.")).isVisible();

            shopper.navigate(store("/cart"));
            shopper.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("إتمام الطلب")).click();
            assertThat(shopper).hasURL(Pattern.compile(".*/checkout$"));

            shopper.getByLabel("الاسم").fill("سارة أحمد");
            shopper.getByLabel("رقم الموبايل").fill("0933 123 456");
            shopper.locator("select[name=governorate]").selectOption("DAMASCUS");
            shopper.locator("input[name=city]").fill("دمشق");
            shopper.locator("input[name=area]").fill("المزة");
            shopper.locator("input[name=area]").dispatchEvent("change");
            shopper.getByLabel("أقرب معلم").fill("قرب صيدلية النور");
            // The summary refreshes over HTMX with the delivery fee and the total.
            assertThat(shopper.locator("#checkout-summary")).containsText("12,000 ل.س");
            shopper.getByLabel("الدفع عند الاستلام").check();
            shopper.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("تأكيد الطلب")).click();

            assertThat(shopper).hasURL(Pattern.compile(".*/orders/1001\\?t=.+"));
            assertThat(shopper.locator("h1")).hasText("طلب رقم 1001");
            assertThat(shopper.getByText("ادفع 12,000 ل.س نقداً عند استلام طلبك.")).isVisible();

            var merchant = merchantContext.newPage();
            merchant.navigate(main("/admin/login"));
            merchant.locator("input[name=store]").fill(slug);
            merchant.locator("input[name=username]").fill("owner@" + slug + ".test");
            merchant.locator("input[name=password]").fill(TestAccounts.PASSWORD);
            merchant.locator("form button[type=submit]").click();
            assertThat(merchant).hasURL(Pattern.compile(".*/admin$"));

            merchant.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("الطلبات")).click();
            var row = merchant.locator("tr", new Page.LocatorOptions().setHasText("#1001"));
            assertThat(row).containsText("سارة أحمد");
            assertThat(row).containsText("12,000 ل.س");
            assertThat(row).containsText("بانتظار التأكيد");
            row.getByRole(AriaRole.LINK).click();
            assertThat(merchant.locator("body")).containsText("قرب صيدلية النور");
        }
    }

    private String store(String path) {
        return "http://" + slug + ".lvh.me:" + port + path;
    }

    private String main(String path) {
        return "http://lvh.me:" + port + path;
    }
}
