package com.unispark.ecommerce;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUser;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.TenantContext;

/** Creates staff and platform users for tests. Every account gets {@link #PASSWORD}. */
public final class TestAccounts {

    public static final String PASSWORD = "correct-horse-7";
    public static final String MAIN = "http://lvh.me:8080";
    public static final String PLATFORM = "http://admin.lvh.me:8080";

    private TestAccounts() {
    }

    public static UUID staff(StaffUserRepository repository, PasswordEncoder passwords, UUID storeId, String name,
            String email, String phone, StaffRole role) {
        return TenantContext.callAs(storeId, () -> repository.save(
                new StaffUser(name, email, phone, passwords.encode(PASSWORD), role)).getId());
    }

    public static String platformAdmin(PlatformUserRepository repository, PasswordEncoder passwords) {
        var email = "ops-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
        repository.save(new PlatformUser(email, passwords.encode(PASSWORD)));
        return email;
    }

    /** Logs a staff member in through the real login form and returns the session. */
    public static MockHttpSession logIn(MockMvc mvc, String storeSlug, String username) throws Exception {
        var result = mvc.perform(post(MAIN + "/admin/login").with(csrf()).with(fromNewAddress())
                        .param("store", storeSlug).param("username", username).param("password", PASSWORD))
                .andReturn();
        var target = result.getResponse().getRedirectedUrl();
        if (target == null || target.contains("login")) {
            throw new IllegalStateException("Login failed for " + username + " at " + storeSlug + ": " + target);
        }
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    public static String storefront(String slug) {
        return "http://" + slug + ".lvh.me:8080";
    }

    /** Rate limits are per client address; giving each test its own keeps them independent. */
    public static RequestPostProcessor fromNewAddress() {
        var random = ThreadLocalRandom.current();
        var address = "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    public static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
