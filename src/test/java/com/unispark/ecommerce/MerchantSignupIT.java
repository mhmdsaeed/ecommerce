package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.from;
import static com.unispark.ecommerce.TestAccounts.fromNewAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.unispark.ecommerce.shared.PlatformDatabase;

/** M2 done-when: a merchant signs up and reaches the empty dashboard (SPEC 17). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class MerchantSignupIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformDatabase platform;

    @Test
    void showsTheSignupFormInArabic() throws Exception {
        mvc.perform(get(MAIN + "/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("افتح متجرك على الإنترنت"),
                        containsString("name=\"_csrf\""),
                        containsString(".lvh.me"),
                        not(containsString("??")))));
    }

    @Test
    void signsUpAndLandsOnTheEmptyDashboard() throws Exception {
        var slug = TestStores.randomSlug();

        var result = mvc.perform(signup(slug, "0933 222 333", "owner@example.com", "long-enough-pw"))
                .andExpect(redirectedUrl("/admin"))
                .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);

        mvc.perform(get(MAIN + "/admin").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("أهلاً سامر"),
                        containsString("متجر الياسمين"),
                        containsString(slug + ".lvh.me:8080"),
                        containsString("تجريبي"),
                        containsString("طلبات اليوم"))));
    }

    @Test
    void createsATrialStoreAndItsOwnerInTheNewTenant() throws Exception {
        var slug = TestStores.randomSlug();

        mvc.perform(signup(slug, "+963 933 222 444", " Owner@Example.COM ", "long-enough-pw"))
                .andExpect(redirectedUrl("/admin"));

        var store = platform.jdbc().sql("""
                        SELECT id, status, name ->> 'ar' AS name_ar, default_locale, contact_phone, created_by
                        FROM store WHERE slug = ?
                        """)
                .param(slug).query().singleRow();
        var owner = platform.jdbc().sql("""
                        SELECT id, tenant_id, role, email, phone, password_hash, active FROM staff_user WHERE tenant_id = ?
                        """)
                .param(store.get("id")).query().singleRow();

        assertThat(store).containsEntry("status", "TRIAL").containsEntry("name_ar", "متجر الياسمين")
                .containsEntry("default_locale", "ar").containsEntry("contact_phone", "+963933222444");
        assertThat(owner).containsEntry("role", "OWNER").containsEntry("email", "owner@example.com")
                .containsEntry("phone", "+963933222444").containsEntry("active", true);
        assertThat(store.get("created_by")).isEqualTo(owner.get("id"));
        assertThat((String) owner.get("password_hash")).startsWith("{bcrypt}");
    }

    @Test
    void storesTheNameInEnglishWhenSigningUpInEnglish() throws Exception {
        var slug = TestStores.randomSlug();

        mvc.perform(signup(slug, "0933 222 555", "", "long-enough-pw").param("lang", "en"))
                .andExpect(redirectedUrl("/admin"));

        var store = platform.jdbc().sql("SELECT name::text AS name, default_locale FROM store WHERE slug = ?")
                .param(slug).query().singleRow();
        assertThat(store.get("default_locale")).isEqualTo("en");
        assertThat((String) store.get("name")).contains("\"en\": \"متجر الياسمين\"").doesNotContain("\"ar\"");
    }

    @Test
    void theNewOwnerCanLogInWithTheirPhone() throws Exception {
        var slug = TestStores.randomSlug();
        mvc.perform(signup(slug, "0933 222 666", "", "long-enough-pw")).andExpect(redirectedUrl("/admin"));

        mvc.perform(post(MAIN + "/admin/login").with(csrf()).with(fromNewAddress())
                        .param("store", slug).param("username", "+963933222666").param("password", "long-enough-pw"))
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    void rejectsTakenReservedAndMalformedStoreAddresses() throws Exception {
        var taken = TestStores.randomSlug();
        TestStores.create(jdbc, taken, "ACTIVE");

        expectFieldError(signup(taken, "0933000111", "", "long-enough-pw"), "هذا العنوان مستخدم");
        expectFieldError(signup("admin", "0933000111", "", "long-enough-pw"), "هذا العنوان محجوز");
        expectFieldError(signup("api", "0933000111", "", "long-enough-pw"), "هذا العنوان محجوز");
        expectFieldError(signup("my store", "0933000111", "", "long-enough-pw"), "استخدم من 3 إلى 32 حرفاً");
        expectFieldError(signup("-shop", "0933000111", "", "long-enough-pw"), "استخدم من 3 إلى 32 حرفاً");
    }

    @Test
    void rejectsBadPhoneEmailAndPasswordTogether() throws Exception {
        mvc.perform(signup(TestStores.randomSlug(), "12345", "not-an-email", "short"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("رقم الهاتف غير صالح"),
                        containsString("البريد الإلكتروني غير صالح"),
                        containsString("كلمة المرور قصيرة"))));
    }

    @Test
    void requiresTheMandatoryFields() throws Exception {
        mvc.perform(post(MAIN + "/signup").with(csrf()).with(fromNewAddress()).param("slug", TestStores.randomSlug()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("هذا الحقل مطلوب")));
    }

    @Test
    void keepsWhatWasTypedExceptThePassword() throws Exception {
        mvc.perform(signup("admin", "0933000111", "kept@example.com", "secret-password-1"))
                .andExpect(content().string(allOf(
                        containsString("value=\"kept@example.com\""),
                        containsString("value=\"0933000111\""),
                        not(containsString("secret-password-1")))));
    }

    @Test
    void createsNothingWhenTheSignupIsRejected() throws Exception {
        var slug = TestStores.randomSlug();

        mvc.perform(signup(slug, "bad phone", "", "long-enough-pw")).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM store WHERE slug = ?", Long.class, slug)).isZero();
    }

    @Test
    void requiresTheCsrfToken() throws Exception {
        mvc.perform(post(MAIN + "/signup").with(fromNewAddress())
                        .param("storeName", "x").param("slug", TestStores.randomSlug()).param("ownerName", "x")
                        .param("phone", "0933000111").param("password", "long-enough-pw"))
                .andExpect(status().isForbidden());
    }

    @Test
    void limitsSignupsPerClientAddress() throws Exception {
        var address = from("198.51.100." + (1 + Math.abs(UUID.randomUUID().hashCode()) % 250));
        for (int signup = 0; signup < 5; signup++) {
            mvc.perform(signup(TestStores.randomSlug(), "0933 222 7" + signup + "0", "", "long-enough-pw").with(address))
                    .andExpect(redirectedUrl("/admin"));
        }

        mvc.perform(signup(TestStores.randomSlug(), "0933 222 799", "", "long-enough-pw").with(address))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("محاولات تسجيل كثيرة")));
    }

    private MockHttpServletRequestBuilder signup(String slug, String phone, String email, String password) {
        return post(MAIN + "/signup").with(csrf()).with(fromNewAddress())
                .param("storeName", "متجر الياسمين")
                .param("slug", slug)
                .param("ownerName", "سامر")
                .param("phone", phone)
                .param("email", email)
                .param("password", password);
    }

    private void expectFieldError(MockHttpServletRequestBuilder request, String message) throws Exception {
        mvc.perform(request).andExpect(status().isOk()).andExpect(content().string(containsString(message)));
    }
}
