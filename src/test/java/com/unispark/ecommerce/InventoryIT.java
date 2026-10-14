package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.inventory.Inventory.Insufficient;
import com.unispark.ecommerce.inventory.Inventory.Rejected;
import com.unispark.ecommerce.inventory.Inventory.Reserved;
import com.unispark.ecommerce.inventory.Inventory.Shortage;
import com.unispark.ecommerce.inventory.Inventory.UnknownVariant;
import com.unispark.ecommerce.inventory.Inventory.Updated;
import com.unispark.ecommerce.inventory.StockLevels.Level;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/** M6 done-when, second half: stock cannot go negative under concurrency (SPEC 6.4, 17). */
@SpringBootTest
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class InventoryIT {

    @Autowired
    Inventory inventory;

    @Autowired
    Products products;

    @Autowired
    JdbcTemplate jdbc;

    UUID store;
    UUID mug;
    UUID plate;

    @BeforeEach
    void createVariants() {
        store = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");
        mug = variantOf("كوب", "mug");
        plate = variantOf("صحن", "plate");
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        as(() -> inventory.setOnHand(mug, 10));

        var results = concurrently(40, () -> inventory.reserve(Map.of(mug, 1)));

        assertThat(results.stream().filter(Reserved.class::isInstance)).hasSize(10);
        assertThat(results.stream().filter(Insufficient.class::isInstance)).hasSize(30);
        assertThat(level(mug)).isEqualTo(new Level(mug, 10, 10));
    }

    @Test
    void concurrentLargerReservationsLeaveTheRemainder() throws Exception {
        as(() -> inventory.setOnHand(mug, 10));

        var results = concurrently(12, () -> inventory.reserve(Map.of(mug, 3)));

        assertThat(results.stream().filter(Reserved.class::isInstance)).hasSize(3);
        assertThat(level(mug).available()).isEqualTo(1);
    }

    @Test
    void lowersStockSafelyWhileOrdersReserve() throws Exception {
        as(() -> inventory.setOnHand(mug, 20));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (var i = 0; i < 20; i++) {
            tasks.add(() -> inventory.reserve(Map.of(mug, 1)));
        }
        for (var i = 0; i < 5; i++) {
            tasks.add(() -> inventory.setOnHand(mug, 8));
        }
        var results = concurrently(tasks);

        var level = level(mug);
        var reservedCount = results.stream().filter(Reserved.class::isInstance).count();
        assertThat(level.reserved()).isEqualTo((int) reservedCount);
        assertThat(level.reserved()).isLessThanOrEqualTo(level.onHand());
        assertThat(level.available()).isNotNegative();
    }

    @Test
    void reservesAllOrNothing() {
        as(() -> {
            inventory.setOnHand(mug, 5);
            inventory.setOnHand(plate, 1);
        });

        var result = TenantContext.callAs(store, () -> inventory.reserve(Map.of(mug, 2, plate, 3)));

        assertThat(result).isEqualTo(new Insufficient(List.of(new Shortage(plate, 3, 1))));
        assertThat(level(mug).reserved()).isZero();
        assertThat(level(plate).reserved()).isZero();
    }

    @Test
    void untrackedVariantsAlwaysReserve() {
        as(() -> inventory.setOnHand(mug, 1));

        var result = TenantContext.callAs(store, () -> inventory.reserve(Map.of(mug, 1, plate, 500)));

        assertThat(result).isInstanceOf(Reserved.class);
        assertThat(TenantContext.callAs(store, () -> inventory.levels(List.of(plate)).of(plate))).isEmpty();
    }

    @Test
    void releasesAndDeductsReservedUnits() {
        as(() -> {
            inventory.setOnHand(mug, 10);
            inventory.reserve(Map.of(mug, 4));
            inventory.release(Map.of(mug, 1));
            inventory.deduct(Map.of(mug, 2));
        });

        assertThat(level(mug)).isEqualTo(new Level(mug, 8, 1));
    }

    @Test
    void refusesToReleaseMoreThanIsReserved() {
        as(() -> inventory.setOnHand(mug, 10));

        assertThatThrownBy(() -> as(() -> inventory.release(Map.of(mug, 1))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(level(mug)).isEqualTo(new Level(mug, 10, 0));
    }

    @Test
    void keepsOnHandAtOrAboveReserved() {
        as(() -> {
            inventory.setOnHand(mug, 10);
            inventory.reserve(Map.of(mug, 6));
        });

        assertThat(TenantContext.callAs(store, () -> inventory.setOnHand(mug, 5))).isEqualTo(new Rejected("below-reserved", 6));
        assertThat(TenantContext.callAs(store, () -> inventory.setOnHand(mug, null))).isEqualTo(new Rejected("reserved", 6));
        assertThat(TenantContext.callAs(store, () -> inventory.setOnHand(mug, 6))).isEqualTo(new Updated(new Level(mug, 6, 6)));
    }

    @Test
    void stopsTrackingAndRejectsBadInput() {
        as(() -> inventory.setOnHand(mug, 3));

        assertThat(TenantContext.callAs(store, () -> inventory.setOnHand(mug, null))).isEqualTo(new Updated(null));
        assertThat(TenantContext.callAs(store, () -> inventory.levels(List.of(mug)).byVariant())).isEmpty();
        assertThat(TenantContext.callAs(store, () -> inventory.setOnHand(mug, -1))).isEqualTo(new Rejected("invalid", 0));
        assertThat(TenantContext.callAs(store, () -> inventory.setOnHand(UUID.randomUUID(), 1)))
                .isInstanceOf(UnknownVariant.class);
    }

    @Test
    void doesNotSeeOrChangeAnotherStoresStock() {
        as(() -> inventory.setOnHand(mug, 10));
        var other = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");

        TenantContext.runAs(other, () -> {
            assertThat(inventory.levels(List.of(mug)).byVariant()).isEmpty();
            assertThat(inventory.setOnHand(mug, 99)).isInstanceOf(UnknownVariant.class);
            assertThat(inventory.reserve(Map.of(mug, 5))).isInstanceOf(Reserved.class);
        });

        assertThat(level(mug)).isEqualTo(new Level(mug, 10, 0));
    }

    private UUID variantOf(String name, String slug) {
        return TenantContext.callAs(store, () -> {
            var id = ((Saved) products.create(new ProductDraft(name, null, null, null, slug, null, Currency.SYP), "100"))
                    .id();
            return products.find(id).orElseThrow().variants().getFirst().id();
        });
    }

    private Level level(UUID variant) {
        return TenantContext.callAs(store, () -> inventory.levels(List.of(variant)).of(variant).orElseThrow());
    }

    private void as(Runnable action) {
        TenantContext.runAs(store, action);
    }

    private <T> List<T> concurrently(int times, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (var i = 0; i < times; i++) {
            tasks.add(task);
        }
        return concurrently(tasks);
    }

    /** Starts every task at once, each on its own thread in the store's tenant context. */
    private <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var futures = new ArrayList<Future<T>>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try (var scope = TenantContext.enter(store)) {
                        return task.call();
                    }
                }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
