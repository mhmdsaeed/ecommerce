package com.unispark.ecommerce.tenant.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.unispark.ecommerce.tenant.internal.web.StoreHosts.InvalidStoreHost;
import com.unispark.ecommerce.tenant.internal.web.StoreHosts.PlatformHost;
import com.unispark.ecommerce.tenant.internal.web.StoreHosts.StoreHost;

class StoreHostsTest {

    final StoreHosts hosts = new StoreHosts("lvh.me:8080");

    @Test
    void matchesAStoreSubdomain() {
        assertThat(hosts.match("demo1.lvh.me")).isEqualTo(new StoreHost("demo1"));
    }

    @Test
    void ignoresPortCaseAndTrailingDot() {
        assertThat(hosts.match("Demo1.LVH.me:8080")).isEqualTo(new StoreHost("demo1"));
        assertThat(hosts.match("demo1.lvh.me.")).isEqualTo(new StoreHost("demo1"));
        assertThat(new StoreHosts("Example.com").match("shop-1.example.com:443")).isEqualTo(new StoreHost("shop-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lvh.me", "lvh.me:8080", "admin.lvh.me", "www.lvh.me", "localhost", "127.0.0.1",
            "[::1]", "evil-lvh.me", "demo1.example.com"})
    void treatsTheBaseDomainReservedSubdomainsAndForeignHostsAsPlatform(String host) {
        assertThat(hosts.match(host)).isInstanceOf(PlatformHost.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b.lvh.me", "x_y.lvh.me", "ab.lvh.me", "-demo.lvh.me", "demo-.lvh.me",
            ".lvh.me", "a234567890123456789012345678901234.lvh.me"})
    void rejectsSubdomainsThatCannotBeAStoreSlug(String host) {
        assertThat(hosts.match(host)).isInstanceOf(InvalidStoreHost.class);
    }

    @Test
    void treatsAMissingHostAsPlatform() {
        assertThat(hosts.match(null)).isInstanceOf(PlatformHost.class);
    }

    @Test
    void requiresABaseDomain() {
        assertThatThrownBy(() -> new StoreHosts(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
