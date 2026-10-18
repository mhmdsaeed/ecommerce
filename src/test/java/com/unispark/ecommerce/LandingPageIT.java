package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The main site's landing page: where {@code /} leads, what it says, and the address it hands to signup. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class LandingPageIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void showsTheLandingPageAtTheMainSiteRoot() throws Exception {
        mvc.perform(get(MAIN + "/")).andExpect(forwardedUrl("/welcome"));
    }

    @Test
    void offersTheAddressFieldThePlansAndThePaymentMethods() throws Exception {
        mvc.perform(get(MAIN + "/welcome"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("افتح متجرك على الإنترنت خلال دقائق"),
                        // The sign follows the address field, which opens the signup form.
                        containsString("x-data=\"storeSign\" data-input=\"slug\""),
                        containsString("<form id=\"claim\" action=\"/signup\" method=\"get\""),
                        containsString("<span class=\"shop-sign__domain\">.lvh.me</span>"),
                        // Seeded plans (V15) and their limits.
                        containsString("الأساسية"), containsString("الاحترافية"), containsString(">300<"),
                        containsString("شام كاش"), containsString("الدفع عند الاستلام"),
                        not(containsString("??")))));
    }

    @Test
    void speaksEnglishWhenAsked() throws Exception {
        mvc.perform(get(MAIN + "/welcome").param("lang", "en"))
                .andExpect(content().string(allOf(
                        containsString("Open your online store in minutes"),
                        containsString("A 14-day free trial, no card needed."),
                        containsString("Basic"), containsString("Syriatel Cash"))));
    }

    @Test
    void storesDoNotShowTheLandingPage() throws Exception {
        var slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "ACTIVE");

        mvc.perform(get("http://" + slug + ".lvh.me:8080/welcome")).andExpect(status().isNotFound());
        mvc.perform(get("http://" + slug + ".lvh.me:8080/"))
                .andExpect(content().string(not(containsString("shop-sign--hero"))));
    }

    @Test
    void signupStartsWithTheChosenAddress() throws Exception {
        mvc.perform(get(MAIN + "/signup").param("slug", " My-Shop "))
                .andExpect(content().string(allOf(containsString("value=\"my-shop\""),
                        // The sign follows the name and address fields, starting from the chosen address.
                        containsString("data-input=\"slug\" data-name-input=\"storeName\""),
                        containsString("<span class=\"shop-sign__slug\" x-text=\"slug\">my-shop</span>"))));
    }
}
