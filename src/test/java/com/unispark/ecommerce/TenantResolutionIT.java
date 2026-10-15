package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.unispark.ecommerce.shared.TenantContext;

/** Host-based tenant resolution (SPEC 4.1). MockMvc does not render error pages; see {@link NotFoundPageIT}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class TenantResolutionIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void greetsShoppersWithTheStoreNameOnItsSubdomain() throws Exception {
        var slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "ACTIVE", "{\"ar\": \"متجر الورد\", \"en\": \"Rose Shop\"}");

        mvc.perform(get("http://" + slug + ".lvh.me:8080/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("أهلاً بك في متجر الورد")));
        mvc.perform(get("http://" + slug + ".lvh.me:8080/").param("lang", "en"))
                .andExpect(content().string(containsString("Welcome to Rose Shop")));
    }

    @Test
    void ignoresThePortAndLetterCaseOfTheHost() throws Exception {
        var slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "TRIAL", "{\"ar\": \"متجر التجربة\"}");

        mvc.perform(get("http://" + slug.toUpperCase() + ".LVH.ME:9999/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("متجر التجربة")));
    }

    @Test
    void showsSuspendedStoresAsTemporarilyUnavailable() throws Exception {
        var slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "SUSPENDED");

        mvc.perform(get("http://" + slug + ".lvh.me:8080/")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void answersNotFoundForAnUnknownStore() throws Exception {
        mvc.perform(get("http://nosuchstore.lvh.me:8080/")).andExpect(status().isNotFound());
    }

    @Test
    void answersNotFoundForAClosedStore() throws Exception {
        var slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "CLOSED");

        mvc.perform(get("http://" + slug + ".lvh.me:8080/")).andExpect(status().isNotFound());
    }

    @Test
    void answersNotFoundForHostsThatCannotBeAStore() throws Exception {
        mvc.perform(get("http://a.b.lvh.me:8080/")).andExpect(status().isNotFound());
        // Too short for a slug. (Hosts with "_" are covered in StoreHostsTest: java.net.URI cannot parse them.)
        mvc.perform(get("http://ab.lvh.me:8080/")).andExpect(status().isNotFound());
    }

    @Test
    void treatsTheBaseDomainWwwAndOtherHostsAsTheMainSite() throws Exception {
        for (var host : new String[] {"lvh.me:8080", "www.lvh.me:8080", "localhost"}) {
            mvc.perform(get("http://" + host + "/"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("متجرك على الإنترنت خلال دقائق")));
        }
    }

    @Test
    void sendsThePlatformAdminHostRootToThePlatformLogin() throws Exception {
        mvc.perform(get("http://admin.lvh.me:8080/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/platform/login"));
    }

    @Test
    void servesStaticAssetsWithoutAStoreLookup() throws Exception {
        mvc.perform(get("http://nosuchstore.lvh.me:8080/css/app.css")).andExpect(status().isOk());
    }

    @Test
    void clearsTheTenantAfterEveryRequest() throws Exception {
        var slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "ACTIVE");

        mvc.perform(get("http://" + slug + ".lvh.me:8080/test/probes")).andExpect(status().isOk());

        assertThat(TenantContext.currentTenantId()).isEmpty();
    }
}
