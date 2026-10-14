package com.unispark.ecommerce.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Local seed data (SPEC 15): staff for the demo stores. Runs after {@code LocalDemoStores}. Idempotent.
 * All demo passwords are {@value #PASSWORD}. Log in at {@code http://lvh.me:8080/admin/login}.
 */
@Component
@Profile("local")
@Order(20)
class LocalDemoUsers implements ApplicationRunner {

    static final String PASSWORD = "demo-pass-123";

    private static final Logger log = LoggerFactory.getLogger(LocalDemoUsers.class);

    private final StoreLookup stores;
    private final StaffUserRepository staff;
    private final PasswordEncoder passwords;

    LocalDemoUsers(StoreLookup stores, StaffUserRepository staff, PasswordEncoder passwords) {
        this.stores = stores;
        this.staff = staff;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("demo1", "سامر الحلبي", "owner@demo1.test", "+963933000001", StaffRole.OWNER);
        seed("demo1", "ليلى", "staff@demo1.test", null, StaffRole.STAFF);
        seed("demo1", "أبو خالد", null, "+963933000002", StaffRole.DRIVER);
        seed("demo2", "ريم", "owner@demo2.test", "+963944000001", StaffRole.OWNER);
    }

    private void seed(String slug, String name, String email, String phone, StaffRole role) {
        var store = stores.findBySlug(slug).orElseThrow(() -> new IllegalStateException("Demo store missing: " + slug));
        TenantContext.runAs(store.id(), () -> {
            var exists = email != null ? staff.existsByEmail(email) : staff.existsByPhone(phone);
            if (!exists) {
                staff.save(new StaffUser(name, email, phone, passwords.encode(PASSWORD), role));
                log.info("Seeded {} {} for {}", role, email != null ? email : phone, slug);
            }
        });
    }
}
