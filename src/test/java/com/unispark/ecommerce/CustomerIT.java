package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.unispark.ecommerce.customer.Customers;
import com.unispark.ecommerce.customer.Customers.AddressDraft;
import com.unispark.ecommerce.customer.Customers.AddressSaved;
import com.unispark.ecommerce.customer.Customers.Invalid;
import com.unispark.ecommerce.customer.Customers.NotFound;
import com.unispark.ecommerce.customer.Customers.Registered;
import com.unispark.ecommerce.delivery.DeliveryQuotes;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Deliverable;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantContext;

/** Customers and their addresses (SPEC 5), and an address priced by the zones (SPEC 8). */
@SpringBootTest
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class CustomerIT {

    static final AddressDraft MAZZEH = new AddressDraft(Governorate.DAMASCUS, "دمشق", "المزة", "قرب صيدلية النور",
            "بناء 4، الطابق الثاني", null, null, null);

    @Autowired
    Customers customers;

    @Autowired
    DeliveryZones zones;

    @Autowired
    DeliveryQuotes quotes;

    @Autowired
    JdbcTemplate jdbc;

    UUID store;

    @BeforeEach
    void createStore() {
        store = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");
    }

    @Test
    void registersOneCustomerPerPhoneWhateverTheSpelling() {
        var first = registered("0933 123 456", "سارة");
        var again = registered("+963933123456", null);
        var arabicDigits = registered("٠٩٣٣١٢٣٤٥٦", "سارة أحمد");

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(arabicDigits.id()).isEqualTo(first.id());
        assertThat(first.phone()).isEqualTo("+963933123456");
        assertThat(again.name()).isEqualTo("سارة");
        assertThat(arabicDigits.name()).isEqualTo("سارة أحمد");
        assertThat(as(() -> customers.findByPhone("00963 933-123-456"))).hasValueSatisfying(customer -> {
            assertThat(customer.blocked()).isFalse();
            assertThat(customer.orderCount()).isZero();
        });
    }

    @Test
    void rejectsInvalidPhonesAndNames() {
        assertThat(as(() -> customers.register("12345", null)))
                .isEqualTo(new Invalid(List.of(new FieldProblem("phone", "invalid"))));
        assertThat(as(() -> customers.register(" ", "x".repeat(101))))
                .isEqualTo(new Invalid(List.of(new FieldProblem("phone", "required"), new FieldProblem("name", "too-long"))));
        assertThat(count("customer")).isZero();
    }

    @Test
    void concurrentRegistrationsOfOnePhoneShareTheCustomer() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<UUID>>();
            for (var i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try (var scope = TenantContext.enter(store)) {
                        return ((Registered) customers.register("0944 555 666", null)).customer().id();
                    }
                }));
            }
            start.countDown();
            var ids = new ArrayList<UUID>();
            for (var future : futures) {
                ids.add(future.get());
            }
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(count("customer")).isEqualTo(1);
    }

    @Test
    void savesUpdatesAndRemovesAddresses() {
        var customer = registered("0933 000 111", null).id();

        var saved = (AddressSaved) as(() -> customers.addAddress(customer, new AddressDraft(Governorate.DAMASCUS,
                "  دمشق ", " المزة  ", "قرب صيدلية النور", null, "0944 222 333", new BigDecimal("33.5021234"),
                new BigDecimal("36.2512"))));
        as(() -> customers.addAddress(customer, new AddressDraft(Governorate.RIF_DIMASHQ, "جرمانا", null, null,
                "شارع الخضر", null, null, null)));

        var addresses = as(() -> customers.addresses(customer));
        assertThat(addresses).hasSize(2);
        var first = addresses.getFirst();
        assertThat(first.id()).isEqualTo(saved.id());
        assertThat(first.city()).isEqualTo("دمشق");
        assertThat(first.area()).isEqualTo("المزة");
        assertThat(first.phone()).isEqualTo("+963944222333");
        assertThat(first.lat()).isEqualByComparingTo("33.502123");
        assertThat(first.lng()).isEqualByComparingTo("36.2512");

        assertThat(as(() -> customers.updateAddress(customer, first.id(), MAZZEH))).isEqualTo(saved);
        assertThat(as(() -> customers.addresses(customer)).getFirst().details()).isEqualTo("بناء 4، الطابق الثاني");

        assertThat(as(() -> customers.removeAddress(customer, first.id()))).isEqualTo(saved);
        assertThat(as(() -> customers.addresses(customer))).singleElement()
                .satisfies(address -> assertThat(address.city()).isEqualTo("جرمانا"));
        assertThat(as(() -> customers.removeAddress(customer, first.id()))).isEqualTo(new NotFound());
    }

    @Test
    void explainsInvalidAddresses() {
        var customer = registered("0933 000 222", null).id();

        var result = as(() -> customers.addAddress(customer, new AddressDraft(null, " ", "x".repeat(81), null, " ",
                "123", new BigDecimal("91"), null)));

        assertThat(result).isEqualTo(new Invalid(List.of(new FieldProblem("governorate", "required"),
                new FieldProblem("city", "required"), new FieldProblem("area", "too-long"),
                new FieldProblem("landmark", "required"), new FieldProblem("phone", "invalid"),
                new FieldProblem("location", "invalid"))));
        assertThat(as(() -> customers.addAddress(customer, new AddressDraft(Governorate.HOMS, "حمص", null, "x", null,
                null, new BigDecimal("34.7"), new BigDecimal("181")))))
                .isEqualTo(new Invalid(List.of(new FieldProblem("location", "invalid"))));
        assertThat(count("customer_address")).isZero();
    }

    @Test
    void limitsAddressesPerCustomer() {
        var customer = registered("0933 000 333", null).id();
        for (var i = 0; i < Customers.MAX_ADDRESSES; i++) {
            assertThat(as(() -> customers.addAddress(customer, MAZZEH))).isInstanceOf(AddressSaved.class);
        }

        assertThat(as(() -> customers.addAddress(customer, MAZZEH)))
                .isEqualTo(new Invalid(List.of(new FieldProblem("address", "limit"))));
    }

    @Test
    void addressesBelongToTheirCustomerAndStore() {
        var owner = registered("0933 000 444", null).id();
        var stranger = registered("0933 000 555", null).id();
        var address = ((AddressSaved) as(() -> customers.addAddress(owner, MAZZEH))).id();

        assertThat(as(() -> customers.updateAddress(stranger, address, MAZZEH))).isEqualTo(new NotFound());
        assertThat(as(() -> customers.removeAddress(stranger, address))).isEqualTo(new NotFound());
        assertThat(as(() -> customers.addAddress(UUID.randomUUID(), MAZZEH))).isEqualTo(new NotFound());

        var other = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");
        TenantContext.runAs(other, () -> {
            assertThat(customers.find(owner)).isEmpty();
            assertThat(customers.findByPhone("0933 000 444")).isEmpty();
            assertThat(customers.addresses(owner)).isEmpty();
            assertThat(customers.addAddress(owner, MAZZEH)).isEqualTo(new NotFound());
            // The same phone is a separate customer in another store.
            var there = ((Registered) customers.register("0933 000 444", null)).customer().id();
            assertThat(there).isNotEqualTo(owner);
        });
        // Composite foreign key: an address cannot point at another store's customer, even past the service.
        assertThatThrownBy(() -> TenantContext.runAs(other, () -> jdbc.update("""
                INSERT INTO customer_address (id, tenant_id, customer_id, governorate, city, landmark,
                                              created_at, updated_at, version)
                VALUES (?, ?, ?, 'DAMASCUS', 'دمشق', 'x', now(), now(), 0)
                """, UUID.randomUUID(), other, owner))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savedAddressIsPricedByTheMatchingZone() {
        var customer = registered("0933 000 666", null).id();
        as(() -> {
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, null, null, null));
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", "المزه", "1500", Currency.SYP, null, null, null, null));
            return customers.addAddress(customer, MAZZEH);
        });

        var address = as(() -> customers.addresses(customer)).getFirst();
        var quote = as(() -> quotes.quote(address.destination(), Money.of(new BigDecimal("20000"), Currency.SYP)));

        assertThat(quote).isInstanceOfSatisfying(Deliverable.class, deliverable -> {
            assertThat(deliverable.zone().area()).isEqualTo("المزه");
            assertThat(deliverable.total()).isEqualTo(Money.of(new BigDecimal("21500"), Currency.SYP));
        });
    }

    private Customers.CustomerView registered(String phone, String name) {
        return ((Registered) as(() -> customers.register(phone, name))).customer();
    }

    private long count(String table) {
        return TenantContext.callAs(store, () -> jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
    }

    private <T> T as(Supplier<T> action) {
        return TenantContext.callAs(store, action);
    }
}
