package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.unispark.ecommerce.delivery.DeliveryQuotes;
import com.unispark.ecommerce.delivery.DeliveryQuotes.BelowMinimum;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Deliverable;
import com.unispark.ecommerce.delivery.DeliveryQuotes.NotDelivered;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.Invalid;
import com.unispark.ecommerce.delivery.DeliveryZones.Saved;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantContext;

/** Delivery zones (SPEC 8, 9.3): the admin page, matching through the database, and quotes with conversion. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class DeliveryZonesIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    DeliveryZones zones;

    @Autowired
    DeliveryQuotes quotes;

    @Autowired
    ExchangeRates rates;

    String slug;
    UUID store;
    MockHttpSession manager;

    @BeforeEach
    void logInManager() throws Exception {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "Manager", "manager@" + slug + ".test", null, StaffRole.MANAGER);
        manager = TestAccounts.logIn(mvc, slug, "manager@" + slug + ".test");
    }

    @Test
    void managerAddsZonesOverHtmx() throws Exception {
        mvc.perform(get(MAIN + "/admin/delivery").session(manager))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("مناطق التوصيل"), containsString("لا توجد مناطق توصيل بعد"),
                        containsString("ريف دمشق"), containsString("دير الزور"))));

        htmx(post(MAIN + "/admin/delivery").param("governorate", "DAMASCUS").param("city", "دمشق")
                .param("area", "المزة").param("fee", "١٬٥٠٠").param("currency", "SYP").param("freeOver", "50,000")
                .param("etaAr", "في اليوم نفسه"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(not(containsString("<html")), containsString("تمت إضافة المنطقة."),
                        containsString("دمشق"), containsString("المزة"), containsString("حي واحد"),
                        containsString("1,500 ل.س"), containsString("مجاني للطلبات من 50,000 ل.س"),
                        containsString("في اليوم نفسه"))));
        htmx(post(MAIN + "/admin/delivery").param("governorate", "RIF_DIMASHQ").param("fee", "0")
                .param("currency", "SYP").param("minOrder", "10000"))
                .andExpect(content().string(allOf(containsString("كل المحافظة"), containsString("مجاني"),
                        containsString("الحد الأدنى للطلب 10,000 ل.س"))));

        var all = as(zones::list);
        assertThat(all).extracting(DeliveryZones.Zone::governorate)
                .containsExactly(Governorate.DAMASCUS, Governorate.RIF_DIMASHQ);
        assertThat(all.getFirst().fee()).isEqualTo(syp("1500"));
        assertThat(all.getFirst().freeOver()).isEqualTo(syp("50000"));
        assertThat(all.getFirst().eta().ar()).isEqualTo("في اليوم نفسه");
        assertThat(all.getFirst().eta().en()).isNull();
    }

    @Test
    void explainsInvalidZonesAndKeepsWhatWasTyped() throws Exception {
        htmx(post(MAIN + "/admin/delivery").param("governorate", "").param("area", "المزة").param("fee", "12,50")
                .param("currency", "SYP").param("minOrder", "0"))
                .andExpect(content().string(allOf(containsString("اختر المحافظة."),
                        containsString("اكتب المدينة التي يقع فيها هذا الحي."), containsString("أدخل مبلغاً صحيحاً"),
                        containsString("أدخل مبلغاً أكبر من صفر"), containsString("value=\"12,50\""),
                        containsString("aria-invalid=\"true\""))));

        as(() -> zones.create(draft(Governorate.DAMASCUS, "دمشق", null, "2000")));
        // The same city in another spelling is the same destination.
        htmx(post(MAIN + "/admin/delivery").param("governorate", "DAMASCUS").param("city", " دِمَشق ")
                .param("fee", "3000").param("currency", "SYP"))
                .andExpect(content().string(containsString("توجد منطقة توصيل لهذا المكان بالفعل.")));
        // A crafted governorate is a plain type mismatch.
        htmx(post(MAIN + "/admin/delivery").param("governorate", "NARNIA").param("fee", "1").param("currency", "SYP"))
                .andExpect(content().string(containsString("القيمة غير صالحة.")));
        assertThat(as(zones::list)).hasSize(1);
    }

    @Test
    void editsPausesAndDeletesZones() throws Exception {
        var id = created(draft(Governorate.HOMS, "حمص", null, "2500"));

        mvc.perform(get(MAIN + "/admin/delivery/" + id + "/edit").session(manager))
                .andExpect(content().string(allOf(containsString("value=\"2500\""), containsString("value=\"حمص\""))));
        mvc.perform(post(MAIN + "/admin/delivery/" + id).session(manager).with(csrf())
                        .param("governorate", "HOMS").param("city", "حمص").param("area", "الوعر")
                        .param("fee", "4.5").param("currency", "USD"))
                .andExpect(redirectedUrl("/admin/delivery?updated"));
        var zone = as(() -> zones.find(id)).orElseThrow();
        assertThat(zone.area()).isEqualTo("الوعر");
        assertThat(zone.fee()).isEqualTo(Money.of(new BigDecimal("4.50"), Currency.USD));

        htmx(post(MAIN + "/admin/delivery/" + id + "/active").param("active", "false"))
                .andExpect(content().string(allOf(containsString("تم إيقاف التوصيل إلى هذه المنطقة مؤقتاً."),
                        containsString("موقوفة"))));
        assertThat(as(() -> zones.match(Destination.of(Governorate.HOMS, "حمص", "الوعر")))).isEmpty();

        htmx(post(MAIN + "/admin/delivery/" + id + "/delete"))
                .andExpect(content().string(containsString("تم حذف المنطقة.")));
        assertThat(as(zones::list)).isEmpty();
        htmx(post(MAIN + "/admin/delivery/" + id + "/delete")).andExpect(status().isNotFound());
    }

    @Test
    void onlyOwnersAndManagersManageZones() throws Exception {
        TestAccounts.staff(staffUsers, passwords, store, "Staff", "staff@" + slug + ".test", null, StaffRole.STAFF);
        var staff = TestAccounts.logIn(mvc, slug, "staff@" + slug + ".test");

        mvc.perform(get(MAIN + "/admin/delivery").session(staff)).andExpect(status().isForbidden());
        mvc.perform(post(MAIN + "/admin/delivery").session(staff).with(csrf()).param("governorate", "HOMS")
                .param("fee", "1").param("currency", "SYP")).andExpect(status().isForbidden());
        mvc.perform(get(MAIN + "/admin").session(staff))
                .andExpect(content().string(not(containsString("href=\"/admin/delivery\""))));
        mvc.perform(get(MAIN + "/admin").session(manager))
                .andExpect(content().string(containsString("href=\"/admin/delivery\"")));
    }

    @Test
    void anotherStoreCannotSeeOrChangeZones() throws Exception {
        var id = created(draft(Governorate.DAMASCUS, null, null, "2000"));
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, other, "Owner", "owner@" + otherSlug + ".test", null, StaffRole.OWNER);
        var otherOwner = TestAccounts.logIn(mvc, otherSlug, "owner@" + otherSlug + ".test");

        mvc.perform(get(MAIN + "/admin/delivery/" + id + "/edit").session(otherOwner)).andExpect(status().isNotFound());
        mvc.perform(post(MAIN + "/admin/delivery/" + id + "/delete").session(otherOwner).with(csrf()))
                .andExpect(status().isNotFound());
        TenantContext.runAs(other, () -> {
            assertThat(zones.list()).isEmpty();
            assertThat(zones.match(Destination.of(Governorate.DAMASCUS, "دمشق", null))).isEmpty();
            // The same destination is free to use in another store.
            assertThat(zones.create(draft(Governorate.DAMASCUS, null, null, "100"))).isInstanceOf(Saved.class);
        });
        assertThat(as(zones::list)).singleElement().extracting(DeliveryZones.Zone::fee).isEqualTo(syp("2000"));
    }

    @Test
    void quotesTheMostSpecificZoneInTheDisplayCurrency() {
        as(() -> {
            zones.create(new ZoneDraft(Governorate.DAMASCUS, null, null, "3000", Currency.SYP, null, null, null, null));
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, "50000", null, null));
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", "المزة", "1500", Currency.SYP, null, null, null, null));
            zones.create(new ZoneDraft(Governorate.ALEPPO, "حلب", null, "5", Currency.USD, "20", null, null, null));
            return null;
        });
        assertThat(quote(Governorate.DAMASCUS, "دمشق", "المزه", "10000"))
                .isInstanceOfSatisfying(Deliverable.class, quote -> {
                    assertThat(quote.fee()).isEqualTo(syp("1500"));
                    assertThat(quote.total()).isEqualTo(syp("11500"));
                });
        assertThat(quote(Governorate.DAMASCUS, "دمشق", "المالكي", "60000"))
                .isInstanceOfSatisfying(Deliverable.class, quote -> assertThat(quote.free()).isTrue());
        assertThat(quote(Governorate.DAMASCUS, "قدسيا", null, "10000"))
                .isInstanceOfSatisfying(Deliverable.class, quote -> assertThat(quote.fee()).isEqualTo(syp("3000")));
        assertThat(quote(Governorate.HOMS, "حمص", null, "10000")).isInstanceOf(NotDelivered.class);

        // No rate yet: the dollar fee stays in dollars and is charged as is.
        assertThat(quote(Governorate.ALEPPO, "حلب", null, "1000"))
                .isInstanceOfSatisfying(Deliverable.class, quote -> {
                    assertThat(quote.fee()).isEqualTo(Money.of(new BigDecimal("5"), Currency.USD));
                    assertThat(quote.total()).isNull();
                });
        // With 118.5 pounds per dollar: $5 -> 595, minimum $20 -> 2,370.
        as(() -> rates.set("118.5", null));
        assertThat(quote(Governorate.ALEPPO, "حلب", null, "1000"))
                .isInstanceOfSatisfying(BelowMinimum.class, quote -> {
                    assertThat(quote.minimum()).isEqualTo(syp("2370"));
                    assertThat(quote.shortBy()).isEqualTo(syp("1370"));
                });
        assertThat(quote(Governorate.ALEPPO, "حلب", null, "3000"))
                .isInstanceOfSatisfying(Deliverable.class, quote -> assertThat(quote.total()).isEqualTo(syp("3595")));
    }

    @Test
    void coverageListsWhereActiveZonesDeliver() {
        as(() -> {
            zones.create(draft(Governorate.DAMASCUS, "دمشق", "المزة", "1"));
            zones.create(draft(Governorate.DAMASCUS, "دمشق", "كفرسوسة", "1"));
            zones.create(draft(Governorate.DAMASCUS, "داريا", null, "1"));
            zones.create(draft(Governorate.RIF_DIMASHQ, null, null, "1"));
            var paused = ((Saved) zones.create(draft(Governorate.HOMS, null, null, "1"))).id();
            zones.setActive(paused, false);
            return null;
        });

        var coverage = as(zones::coverage);
        assertThat(coverage.governorates()).extracting(DeliveryZones.CoveredGovernorate::governorate)
                .containsExactly(Governorate.DAMASCUS, Governorate.RIF_DIMASHQ);
        assertThat(coverage.of(Governorate.DAMASCUS).orElseThrow().everywhere()).isFalse();
        assertThat(coverage.of(Governorate.RIF_DIMASHQ).orElseThrow().everywhere()).isTrue();
        assertThat(coverage.cities(Governorate.DAMASCUS)).containsExactlyInAnyOrder("دمشق", "داريا");
        assertThat(coverage.areas(Governorate.DAMASCUS, "دِمشق")).containsExactly("المزة", "كفرسوسة");
    }

    @Test
    void databaseAllowsOneZonePerDestination() {
        as(() -> zones.create(draft(Governorate.TARTUS, null, null, "1")));

        // NULLS NOT DISTINCT: a second whole-governorate zone is refused even past the service.
        assertThatThrownBy(() -> TenantContext.runAs(store, () -> jdbc.update("""
                INSERT INTO delivery_zone (id, tenant_id, governorate, fee, currency, created_at, updated_at, version)
                VALUES (?, ?, 'TARTUS', 5, 'SYP', now(), now(), 0)
                """, UUID.randomUUID(), store))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(as(() -> zones.create(draft(Governorate.TARTUS, null, null, "2"))))
                .isEqualTo(new Invalid(List.of(new FieldProblem("destination", "taken"))));
    }

    private DeliveryQuotes.Quote quote(Governorate governorate, String city, String area, String subtotal) {
        return as(() -> quotes.quote(Destination.of(governorate, city, area), syp(subtotal)));
    }

    private UUID created(ZoneDraft draft) {
        return ((Saved) as(() -> zones.create(draft))).id();
    }

    private <T> T as(Supplier<T> action) {
        return TenantContext.callAs(store, action);
    }

    private ResultActions htmx(MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.header("HX-Request", "true").session(manager).with(csrf()));
    }

    private static ZoneDraft draft(Governorate governorate, String city, String area, String fee) {
        return new ZoneDraft(governorate, city, area, fee, Currency.SYP, null, null, null, null);
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }
}
