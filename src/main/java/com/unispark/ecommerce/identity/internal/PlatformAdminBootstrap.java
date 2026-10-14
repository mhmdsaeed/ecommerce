package com.unispark.ecommerce.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.internal.persistence.PlatformUser;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;

/**
 * Creates the first platform admin from {@code PLATFORM_ADMIN_EMAIL} and {@code PLATFORM_ADMIN_PASSWORD} when no
 * platform user exists yet. Does nothing once one exists, so the variables can be removed after first start.
 */
@Component
@Order(5)
class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final PlatformUserRepository users;
    private final PasswordEncoder passwords;
    private final String email;
    private final String password;

    PlatformAdminBootstrap(PlatformUserRepository users, PasswordEncoder passwords,
            @Value("${app.platform-admin.email:}") String email,
            @Value("${app.platform-admin.password:}") String password) {
        this.users = users;
        this.passwords = passwords;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank() || users.count() > 0) {
            return;
        }
        var normalized = Credentials.normalizeEmail(email)
                .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN_EMAIL is not a valid email address"));
        Credentials.passwordProblem(password).ifPresent(problem -> {
            throw new IllegalStateException("PLATFORM_ADMIN_PASSWORD is " + problem);
        });
        users.save(new PlatformUser(normalized, passwords.encode(password)));
        log.info("Created the first platform admin {}", normalized);
    }
}
