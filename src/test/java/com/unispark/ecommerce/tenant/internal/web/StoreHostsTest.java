package com.unispark.ecommerce.tenant.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import com.unispark.ecommerce.tenant.Site;
import com.unispark.ecommerce.tenant.internal.web.StoreHosts.InvalidStoreHost;
import com.unispark.ecommerce.tenant.internal.web.StoreHosts.MainHost;
import com.unispark.ecommerce.tenant.internal.web.StoreHosts.PlatformAdminHost;
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

    @Test
    void matchesThePlatformAdminHost() {
        assertThat(hosts.match("admin.lvh.me")).isInstanceOf(PlatformAdminHost.class);
        assertThat(hosts.match("ADMIN.lvh.me:8080")).isInstanceOf(PlatformAdminHost.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lvh.me", "lvh.me:8080", "www.lvh.me", "localhost", "127.0.0.1", "[::1]",
            "evil-lvh.me", "demo1.example.com"})
    void treatsTheBaseDomainWwwAndForeignHostsAsTheMainSite(String host) {
        assertThat(hosts.match(host)).isInstanceOf(MainHost.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b.lvh.me", "x_y.lvh.me", "ab.lvh.me", "-demo.lvh.me", "demo-.lvh.me",
            ".lvh.me", "a234567890123456789012345678901234.lvh.me", "api.lvh.me", "platform.lvh.me"})
    void rejectsSubdomainsThatCannotBeAStore(String host) {
        assertThat(hosts.match(host)).isInstanceOf(InvalidStoreHost.class);
    }

    @Test
    void treatsAMissingHostAsTheMainSite() {
        assertThat(hosts.match(null)).isInstanceOf(MainHost.class);
    }

    @Test
    void classifiesRequestsBySite() {
        assertThat(hosts.siteOf(requestTo("lvh.me"))).isEqualTo(Site.MAIN);
        assertThat(hosts.siteOf(requestTo("admin.lvh.me"))).isEqualTo(Site.PLATFORM_ADMIN);
        assertThat(hosts.siteOf(requestTo("demo1.lvh.me"))).isEqualTo(Site.STOREFRONT);
    }

    @Test
    void buildsStoreHostsWithTheConfiguredPort() {
        assertThat(hosts.storeHost("demo1")).isEqualTo("demo1.lvh.me:8080");
        assertThat(hosts.baseHost()).isEqualTo("lvh.me");
    }

    @Test
    void requiresABaseDomain() {
        assertThatThrownBy(() -> new StoreHosts(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest requestTo(String host) {
        var request = new MockHttpServletRequest();
        request.setServerName(host);
        return request;
    }
}
