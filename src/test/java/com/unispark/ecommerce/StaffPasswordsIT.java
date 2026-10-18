package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.PASSWORD;
import static com.unispark.ecommerce.TestAccounts.fromNewAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Staff passwords after signup: owners and managers editing staff and setting new passwords, everyone changing
 * their own, and resetting a forgotten one through a link. Messages go to the log in tests (no mail server or SMS
 * provider), which is where the links are read from.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class StaffPasswordsIT {

    static final String NEW_PASSWORD = "brand-new-pass-9";
    static final Pattern LINK = Pattern.compile("/admin/password/reset\\?store=([a-z0-9-]+)&token=([A-Za-z0-9_-]{43})");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    String slug;
    UUID store;
    UUID clerkId;
    String ownerEmail;
    String clerkEmail;

    @BeforeEach
    void createStore() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        ownerEmail = "owner@" + slug + ".test";
        clerkEmail = "clerk@" + slug + ".test";
        TestAccounts.staff(staffUsers, passwords, store, "Owner One", ownerEmail, "+963944100200", StaffRole.OWNER);
        clerkId = TestAccounts.staff(staffUsers, passwords, store, "Clerk One", clerkEmail, null, StaffRole.STAFF);
    }

    @Test
    void ownerEditsAStaffMemberAndANewRoleEndsTheirSession() throws Exception {
        var owner = TestAccounts.logIn(mvc, slug, ownerEmail);
        var clerk = TestAccounts.logIn(mvc, slug, clerkEmail);

        mvc.perform(get(MAIN + "/admin/staff/" + clerkId).session(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Clerk One"), containsString(clerkEmail))));
        mvc.perform(post(MAIN + "/admin/staff/" + clerkId).session(owner).with(csrf())
                        .param("name", "Clerk Renamed").param("email", clerkEmail).param("phone", "0933 300 400")
                        .param("role", "MANAGER"))
                .andExpect(redirectedUrl("/admin/staff?updated"));

        mvc.perform(get(MAIN + "/admin/staff").session(owner))
                .andExpect(content().string(allOf(containsString("Clerk Renamed"), containsString("+963933300400"))));
        mvc.perform(get(MAIN + "/admin").session(clerk)).andExpect(redirectedUrl("/admin/login?expired"));
        // The new phone logs in.
        TestAccounts.logIn(mvc, slug, "0933300400");
    }

    @Test
    void refusesContactsAnotherMemberUses() throws Exception {
        var owner = TestAccounts.logIn(mvc, slug, ownerEmail);

        mvc.perform(post(MAIN + "/admin/staff/" + clerkId).session(owner).with(csrf())
                        .param("name", "Clerk One").param("email", ownerEmail).param("phone", "").param("role", "STAFF"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("هذا البريد مستخدم لموظف آخر")));
        // Keeping one's own email is fine.
        mvc.perform(post(MAIN + "/admin/staff/" + clerkId).session(owner).with(csrf())
                        .param("name", "Clerk One").param("email", clerkEmail).param("phone", "").param("role", "STAFF"))
                .andExpect(redirectedUrl("/admin/staff?updated"));
    }

    @Test
    void managersOnlyEditRolesBelowTheirsAndNeverThemselves() throws Exception {
        var managerId = TestAccounts.staff(staffUsers, passwords, store, "Manager One", "manager@" + slug + ".test",
                null, StaffRole.MANAGER);
        var manager = TestAccounts.logIn(mvc, slug, "manager@" + slug + ".test");
        var ownerId = TenantContext.callAs(store, () -> staffUsers.findByEmail(ownerEmail).orElseThrow().getId());

        mvc.perform(get(MAIN + "/admin/staff/" + ownerId).session(manager)).andExpect(status().isNotFound());
        mvc.perform(get(MAIN + "/admin/staff/" + managerId).session(manager)).andExpect(status().isNotFound());
        mvc.perform(post(MAIN + "/admin/staff/" + ownerId + "/password").session(manager).with(csrf())
                .param("newPassword", NEW_PASSWORD)).andExpect(status().isForbidden());
        // Not even a clerk can be promoted to a role the manager does not manage.
        mvc.perform(post(MAIN + "/admin/staff/" + clerkId).session(manager).with(csrf())
                        .param("name", "Clerk One").param("email", clerkEmail).param("role", "MANAGER"))
                .andExpect(status().isForbidden());

        var clerk = TestAccounts.logIn(mvc, slug, clerkEmail);
        mvc.perform(get(MAIN + "/admin/staff/" + clerkId).session(clerk)).andExpect(status().isForbidden());
    }

    @Test
    void ownerSetsANewPasswordAndTheOldOneStopsWorking() throws Exception {
        var owner = TestAccounts.logIn(mvc, slug, ownerEmail);
        var clerk = TestAccounts.logIn(mvc, slug, clerkEmail);

        mvc.perform(post(MAIN + "/admin/staff/" + clerkId + "/password").session(owner).with(csrf())
                        .param("newPassword", "short"))
                .andExpect(content().string(containsString("8")));
        mvc.perform(post(MAIN + "/admin/staff/" + clerkId + "/password").session(owner).with(csrf())
                        .param("newPassword", NEW_PASSWORD))
                .andExpect(redirectedUrl("/admin/staff?password-set"));

        mvc.perform(get(MAIN + "/admin").session(clerk)).andExpect(redirectedUrl("/admin/login?expired"));
        login(clerkEmail, PASSWORD).andExpect(redirectedUrlPattern("/admin/login?error*"));
        login(clerkEmail, NEW_PASSWORD).andExpect(redirectedUrl("/admin"));
    }

    @Test
    void staffChangeTheirOwnPasswordAndStayLoggedInHereOnly() throws Exception {
        var here = TestAccounts.logIn(mvc, slug, clerkEmail);
        var elsewhere = TestAccounts.logIn(mvc, slug, clerkEmail);

        mvc.perform(changeOwn(here, "wrong-password", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(content().string(containsString("هذه ليست كلمة مرورك الحالية")));
        mvc.perform(changeOwn(here, PASSWORD, NEW_PASSWORD, "something-else"))
                .andExpect(content().string(containsString("كلمتا المرور غير متطابقتين")));
        var changed = mvc.perform(changeOwn(here, PASSWORD, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(redirectedUrl("/admin/account?changed"))
                .andReturn();

        var session = changed.getRequest().getSession(false);
        mvc.perform(get(MAIN + "/admin").session((MockHttpSession) session))
                .andExpect(status().isOk());
        mvc.perform(get(MAIN + "/admin").session(elsewhere)).andExpect(redirectedUrl("/admin/login?expired"));
        login(clerkEmail, NEW_PASSWORD).andExpect(redirectedUrl("/admin"));
    }

    @Test
    void resetsAForgottenPasswordThroughAnEmailedLink(CapturedOutput output) throws Exception {
        var before = TestAccounts.logIn(mvc, slug, clerkEmail);
        // Locked out by failed attempts: a reset proves who they are, so they may log in again.
        for (var i = 0; i < 5; i++) {
            login(clerkEmail, "not-it-" + i);
        }
        login(clerkEmail, PASSWORD).andExpect(redirectedUrlPattern("/admin/login?locked*"));

        mvc.perform(get(MAIN + "/admin/password/forgot").param("store", slug))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("نسيت كلمة المرور"),
                        containsString("value=\"" + slug + "\""))));
        requestReset(clerkEmail).andExpect(redirectedUrl("/admin/password/forgot?sent"));
        mvc.perform(get(MAIN + "/admin/password/forgot?sent"))
                .andExpect(content().string(containsString("إن كان هذا الحساب موجوداً")));

        var token = linkSentTo(output, "Email to " + clerkEmail);
        mvc.perform(get(MAIN + "/admin/password/reset").param("store", slug).param("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(allOf(containsString("اختر كلمة مرور جديدة"),
                        not(containsString("لم يعد يعمل")))));
        mvc.perform(reset(token, NEW_PASSWORD, "typo-password"))
                .andExpect(content().string(containsString("كلمتا المرور غير متطابقتين")));
        mvc.perform(reset(token, NEW_PASSWORD, NEW_PASSWORD)).andExpect(redirectedUrl("/admin/login?reset"));

        login(clerkEmail, NEW_PASSWORD).andExpect(redirectedUrl("/admin"));
        mvc.perform(get(MAIN + "/admin").session(before)).andExpect(redirectedUrl("/admin/login?expired"));
        // Once only.
        mvc.perform(get(MAIN + "/admin/password/reset").param("store", slug).param("token", token))
                .andExpect(content().string(containsString("هذا الرابط لم يعد يعمل")));
        mvc.perform(reset(token, "another-pass-11", "another-pass-11"))
                .andExpect(content().string(containsString("هذا الرابط لم يعد يعمل")));
    }

    @Test
    void showsTheStoreBeingSignedInToOnTheShopSign(CapturedOutput output) throws Exception {
        var sign = "<span class=\"shop-sign__slug\" x-text=\"slug\">" + slug + "</span>";
        mvc.perform(get(MAIN + "/admin/login").param("store", slug))
                .andExpect(content().string(allOf(containsString("shop-sign--opening"), containsString(sign))));
        mvc.perform(get(MAIN + "/admin/password/forgot").param("store", slug))
                .andExpect(content().string(containsString(sign)));
        requestReset(clerkEmail).andExpect(flash().attribute("store", slug));

        var token = linkSentTo(output, "Email to " + clerkEmail);
        mvc.perform(get(MAIN + "/admin/password/reset").param("store", slug).param("token", token))
                .andExpect(content().string(allOf(containsString("class=\"shop-sign shop-sign--open\""),
                        containsString("<span class=\"shop-sign__slug\">" + slug + "</span>"))));
        // A link that no longer works: the shutter is down and no address shows.
        mvc.perform(get(MAIN + "/admin/password/reset").param("store", slug).param("token", "not-a-token"))
                .andExpect(content().string(allOf(containsString("shop-sign--closed"),
                        not(containsString("shop-sign__slug")))));
    }

    @Test
    void sendsTheLinkByTextToMembersWhoAskWithTheirPhone(CapturedOutput output) throws Exception {
        requestReset("0944 100 200").andExpect(redirectedUrl("/admin/password/forgot?sent"));

        var token = linkSentTo(output, "Message to +963944100200");
        mvc.perform(reset(token, NEW_PASSWORD, NEW_PASSWORD)).andExpect(redirectedUrl("/admin/login?reset"));
        login(ownerEmail, NEW_PASSWORD).andExpect(redirectedUrl("/admin"));
    }

    @Test
    void answersTheSameForUnknownAccountsAndSendsNothing(CapturedOutput output) throws Exception {
        requestReset("nobody@" + slug + ".test").andExpect(redirectedUrl("/admin/password/forgot?sent"));
        mvc.perform(post(MAIN + "/admin/password/forgot").with(csrf()).with(fromNewAddress())
                        .param("store", "no-such-store").param("username", clerkEmail))
                .andExpect(redirectedUrl("/admin/password/forgot?sent"));

        assertThat(output.getOut()).doesNotContain("/admin/password/reset?store=");
    }

    @Test
    void disabledMembersGetNoLink(CapturedOutput output) throws Exception {
        TenantContext.runAs(store, () -> jdbc.update("UPDATE staff_user SET active = false WHERE id = ?", clerkId));

        requestReset(clerkEmail).andExpect(redirectedUrl("/admin/password/forgot?sent"));

        assertThat(output.getOut()).doesNotContain("Email to " + clerkEmail);
    }

    @Test
    void refusesExpiredLinksAndLinksForAnotherStore(CapturedOutput output) throws Exception {
        requestReset(clerkEmail);
        var token = linkSentTo(output, "Email to " + clerkEmail);

        var otherSlug = TestStores.randomSlug();
        TestStores.create(jdbc, otherSlug, "ACTIVE");
        mvc.perform(get(MAIN + "/admin/password/reset").param("store", otherSlug).param("token", token))
                .andExpect(content().string(containsString("هذا الرابط لم يعد يعمل")));

        TenantContext.runAs(store, () -> assertThat(jdbc.update(
                "UPDATE password_reset SET expires_at = now() - interval '1 minute' WHERE staff_id = ?", clerkId))
                .isPositive());
        mvc.perform(get(MAIN + "/admin/password/reset").param("store", slug).param("token", token))
                .andExpect(content().string(containsString("هذا الرابط لم يعد يعمل")));
        mvc.perform(reset(token, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(content().string(containsString("هذا الرابط لم يعد يعمل")));
        login(clerkEmail, PASSWORD).andExpect(redirectedUrl("/admin"));
    }

    @Test
    void limitsResetRequestsPerAccount(CapturedOutput output) throws Exception {
        for (var i = 0; i < 4; i++) {
            requestReset(clerkEmail).andExpect(redirectedUrl("/admin/password/forgot?sent"));
        }

        assertThat(Pattern.compile("Email to " + Pattern.quote(clerkEmail)).matcher(output.getOut()).results().count())
                .isEqualTo(3);
    }

    @Test
    void keepsTheTokenOutOfTheDatabase(CapturedOutput output) throws Exception {
        requestReset(clerkEmail);
        var token = linkSentTo(output, "Email to " + clerkEmail);

        var stored = TenantContext.callAs(store, () -> jdbc.queryForList(
                "SELECT token_hash FROM password_reset WHERE staff_id = ?", String.class, clerkId));
        assertThat(stored).hasSize(1).doesNotContain(token);
        var logged = TenantContext.callAs(store, () -> jdbc.queryForList(
                "SELECT template || ' ' || recipient FROM notification_log WHERE template = 'password_reset'",
                String.class));
        assertThat(logged).containsExactly("password_reset " + clerkEmail);
    }

    private ResultActions requestReset(String username) throws Exception {
        return mvc.perform(post(MAIN + "/admin/password/forgot").with(csrf()).with(fromNewAddress())
                .param("store", slug).param("username", username));
    }

    private MockHttpServletRequestBuilder reset(String token,
            String password, String confirm) {
        return post(MAIN + "/admin/password/reset").with(csrf())
                .param("store", slug).param("token", token)
                .param("newPassword", password).param("confirmPassword", confirm);
    }

    private MockHttpServletRequestBuilder changeOwn(
            MockHttpSession session, String current, String password, String confirm) {
        return post(MAIN + "/admin/account/password").session(session).with(csrf())
                .param("currentPassword", current).param("newPassword", password).param("confirmPassword", confirm);
    }

    private ResultActions login(String username, String password)
            throws Exception {
        return mvc.perform(post(MAIN + "/admin/login").with(csrf()).with(sameAddress)
                .param("store", slug).param("username", username).param("password", password));
    }

    /** One address for the logins of a test, so failed attempts add up per account, not per address. */
    private final RequestPostProcessor sameAddress = fromNewAddress();

    /** The token of the last reset link in the log line that starts with {@code prefix}. */
    private String linkSentTo(CapturedOutput output, String prefix) {
        var all = output.getOut();
        var from = all.lastIndexOf(prefix);
        assertThat(from).as("a message %s", prefix).isNotNegative();
        var matcher = LINK.matcher(all.substring(from));
        assertThat(matcher.find()).as("a reset link after %s", prefix).isTrue();
        assertThat(matcher.group(1)).isEqualTo(slug);
        return matcher.group(2);
    }
}
