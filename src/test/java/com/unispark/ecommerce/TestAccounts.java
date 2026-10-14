package com.unispark.ecommerce;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.security.crypto.password.PasswordEncoder;
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
