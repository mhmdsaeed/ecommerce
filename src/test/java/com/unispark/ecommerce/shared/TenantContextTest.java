package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TenantContextTest {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();

    @Test
    void hasNoTenantByDefault() {
        assertThat(TenantContext.currentTenantId()).isEmpty();
        assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void makesTheTenantCurrentInsideTheScopeOnly() {
        try (var scope = TenantContext.enter(A)) {
            assertThat(TenantContext.requireTenantId()).isEqualTo(A);
        }
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void restoresTheOuterTenantAfterANestedScope() {
        TenantContext.runAs(A, () -> {
            assertThat(TenantContext.callAs(B, TenantContext::requireTenantId)).isEqualTo(B);
            assertThat(TenantContext.requireTenantId()).isEqualTo(A);
        });
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void putsTheTenantInTheLoggingContext() {
        TenantContext.runAs(A, () -> {
            assertThat(MDC.get(TenantContext.LOG_KEY)).isEqualTo(A.toString());
            TenantContext.runAs(B, () -> assertThat(MDC.get(TenantContext.LOG_KEY)).isEqualTo(B.toString()));
            assertThat(MDC.get(TenantContext.LOG_KEY)).isEqualTo(A.toString());
        });
        assertThat(MDC.get(TenantContext.LOG_KEY)).isNull();
    }

    @Test
    void clearsTheTenantWhenTheActionThrows() {
        assertThatThrownBy(() -> TenantContext.runAs(A, () -> {
            throw new IllegalArgumentException("boom");
        })).hasMessage("boom");

        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void closingAScopeTwiceDoesNotClobberTheOuterTenant() {
        try (var outer = TenantContext.enter(A)) {
            var inner = TenantContext.enter(B);
            inner.close();
            inner.close();
            assertThat(TenantContext.requireTenantId()).isEqualTo(A);
        }
    }

    @Test
    void doesNotLeakTheTenantToOtherThreads() throws Exception {
        try (var scope = TenantContext.enter(A); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var seenElsewhere = executor.submit(TenantContext::currentTenantId).get();

            assertThat(seenElsewhere).isEmpty();
        }
    }

    @Test
    void rejectsANullTenant() {
        assertThatThrownBy(() -> TenantContext.enter(null)).isInstanceOf(NullPointerException.class);
    }
}
