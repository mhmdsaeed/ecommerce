package com.unispark.ecommerce;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.Checkout;
import com.unispark.ecommerce.order.Checkout.Placed;
import com.unispark.ecommerce.order.Checkout.Request;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;
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

    @Autowired
    PaymentMethods methods;

    @Autowired
    Carts carts;

    @Autowired
    Checkout checkout;

    @Autowired
    Orders orders;

    Playwright playwright;
    Browser browser;
    String slug;
    UUID storeId;
    UUID scarf;

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
        storeId = TestStores.create(jdbc, slug, "ACTIVE");
        var store = storeId;
        TestAccounts.staff(staffUsers, passwords, store, "Owner", "owner@" + slug + ".test", null, StaffRole.OWNER);
        TenantContext.runAs(store, () -> {
            var id = ((Saved) products.create(new ProductDraft("وشاح صوف", "Wool scarf", null, null, "scarf", null,
                    Currency.SYP), "10000")).id();
            products.changeStatus(id, ProductStatus.ACTIVE);
            scarf = products.find(id).orElseThrow().variants().getFirst().id();
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

    /**
     * M9 done-when in a browser: a wallet order paid with a screenshot (a real multipart form with the storefront's
     * CSRF cookie), which the merchant checks through its signed link, approves, and takes to completed.
     */
    @Test
    void merchantApprovesAWalletPaymentAndCompletesTheOrder() throws Exception {
        TenantContext.runAs(storeId, () -> methods.save(PaymentMethod.SHAM_CASH,
                new MethodDraft(true, null, null, "0944 123 456", "Jasmine", null, null)));
        var receipt = Files.createTempFile("receipt", ".png");
        Files.write(receipt, TestImages.png(600, 900));
        try (var shopperContext = browser.newContext(); var merchantContext = browser.newContext()) {
            var shopper = shopperContext.newPage();
            shopper.navigate(store("/p/scarf"));
            shopper.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("أضف إلى السلة")).click();
            assertThat(shopper.getByText("تمت الإضافة إلى سلتك.")).isVisible();
            shopper.navigate(store("/checkout"));
            shopper.getByLabel("الاسم").fill("عمر");
            shopper.getByLabel("رقم الموبايل").fill("0944 555 666");
            shopper.locator("select[name=governorate]").selectOption("DAMASCUS");
            shopper.locator("input[name=city]").fill("دمشق");
            shopper.getByLabel("أقرب معلم").fill("قرب الحديقة");
            shopper.getByLabel("شام كاش").check();
            shopper.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("تأكيد الطلب")).click();
            assertThat(shopper).hasURL(Pattern.compile(".*/orders/1001\\?t=.+"));
            assertThat(shopper.getByText("0944 123 456")).isVisible();
            shopper.locator("input[name=screenshot]").setInputFiles(receipt);
            shopper.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("إرسال")).click();
            assertThat(shopper.getByText("وصلنا إثبات الدفع")).isVisible();
            assertThat(shopper.getByText("نتحقق من دفعتك.")).isVisible();

            var merchant = merchantContext.newPage();
            logIn(merchant);
            merchant.navigate(main("/admin/orders/1001"));
            var screenshot = merchant.locator("img[src*='X-Amz-Signature']");
            assertThat(screenshot).isVisible();
            // The signed link really serves the image to the browser.
            merchant.waitForFunction("img => img.complete && img.naturalWidth > 0", screenshot.elementHandle());
            merchant.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("وصلني المبلغ (12,000 ل.س)")).click();
            assertThat(merchant.getByText("تم قبول الدفع")).isVisible();
            var steps = new LinkedHashMap<String, String>();
            steps.put("تأكيد الطلب", "تم تأكيد الطلب.");
            steps.put("بدء التجهيز", "الطلب قيد التجهيز.");
            steps.put("خرج للتوصيل", "خرج الطلب للتوصيل.");
            steps.put("تم التسليم", "تم تسليم الطلب");
            steps.put("إغلاق الطلب كمكتمل", "اكتمل الطلب.");
            for (var step : steps.entrySet()) {
                merchant.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(step.getKey()).setExact(true))
                        .click();
                assertThat(merchant.getByText(step.getValue())).isVisible();
            }
            assertThat(merchant.locator("#status-heading")).containsText("مكتمل");

            shopper.reload();
            assertThat(shopper.locator("body")).containsText("مكتمل");
            assertThat(shopper.locator("body")).containsText("مدفوع");
        } finally {
            Files.deleteIfExists(receipt);
        }
    }

    /**
     * M10 done-when in a browser: on a phone-sized screen, the driver takes the order out, delivers it and collects
     * the cash; the owner's settlement page shows what the driver holds and records the hand-over.
     */
    @Test
    void driverDeliversAndTheOwnerSettlesTheCash() {
        var driverId = TestAccounts.staff(staffUsers, passwords, storeId, "أبو سمير", null, "+963933900001",
                StaffRole.DRIVER);
        var placed = TenantContext.callAs(storeId, () -> {
            var cart = carts.add(null, scarf, 2).token();
            var order = (Placed) checkout.place(new Request(cart, UUID.randomUUID(), "سارة", "0933 111 001", null,
                    Governorate.DAMASCUS, "دمشق", null, "قرب الجامع", null, PaymentMethod.COD, null, null));
            orders.changeStatus(order.orderId(), OrderStatus.CONFIRMED, Actor.system());
            orders.changeStatus(order.orderId(), OrderStatus.PREPARING, Actor.system());
            orders.assignDriver(order.orderId(), driverId, Actor.system());
            return order;
        });
        var phone = new Browser.NewContextOptions().setViewportSize(390, 844).setIsMobile(true).setHasTouch(true);
        try (var driverContext = browser.newContext(phone); var ownerContext = browser.newContext()) {
            var driver = driverContext.newPage();
            driver.navigate(main("/driver/login"));
            driver.locator("input[name=store]").fill(slug);
            driver.locator("input[name=username]").fill("0933 900 001");
            driver.locator("input[name=password]").fill(TestAccounts.PASSWORD);
            driver.locator("form button[type=submit]").click();
            assertThat(driver).hasURL(Pattern.compile(".*/driver$"));
            driver.getByText("#" + placed.number() + " · سارة").click();
            assertThat(driver.getByText("حصّل 22,000 ل.س نقداً")).isVisible();
            driver.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("استلمت الطلب، أنا في الطريق")).click();
            assertThat(driver.getByText("أنت في الطريق")).isVisible();
            assertThat(driver.locator("input[name=collected]")).hasValue("22000");
            driver.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("تم التسليم وتحصيل المبلغ")).click();
            assertThat(driver.getByText("تم تسجيل التسليم.")).isVisible();
            assertThat(driver.locator("#cash-heading + p")).hasText("22,000 ل.س");
            // No sideways scrolling on a phone.
            org.assertj.core.api.Assertions.assertThat(
                    driver.evaluate("document.documentElement.scrollWidth <= window.innerWidth")).isEqualTo(true);

            var owner = ownerContext.newPage();
            logIn(owner);
            owner.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("التحصيل")).click();
            owner.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("أبو سمير")).click();
            assertThat(owner.locator("input[name=amount]")).hasValue("22000");
            owner.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("تسجيل").setExact(true)).click();
            assertThat(owner.getByText("تم تسجيل المبلغ.")).isVisible();
            assertThat(owner.locator("main")).containsText("0 ل.س");
        }
    }

    private void logIn(Page merchant) {
        merchant.navigate(main("/admin/login"));
        merchant.locator("input[name=store]").fill(slug);
        merchant.locator("input[name=username]").fill("owner@" + slug + ".test");
        merchant.locator("input[name=password]").fill(TestAccounts.PASSWORD);
        merchant.locator("form button[type=submit]").click();
        assertThat(merchant).hasURL(Pattern.compile(".*/admin$"));
    }

    private String store(String path) {
        return "http://" + slug + ".lvh.me:" + port + path;
    }

    private String main(String path) {
        return "http://lvh.me:" + port + path;
    }
}
