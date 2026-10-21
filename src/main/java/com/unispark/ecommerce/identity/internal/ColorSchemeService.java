package com.unispark.ecommerce.identity.internal;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.ColorScheme;
import com.unispark.ecommerce.identity.ColorSchemes;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUser;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;

/** Staff are found in the store the request is in (row-level security); platform admins by their email. */
@Service
@Transactional
class ColorSchemeService implements ColorSchemes {

    private final StaffUserRepository staff;
    private final PlatformUserRepository platformUsers;

    ColorSchemeService(StaffUserRepository staff, PlatformUserRepository platformUsers) {
        this.staff = staff;
        this.platformUsers = platformUsers;
    }

    @Override
    @Transactional(readOnly = true)
    public ColorScheme ofStaff(UUID staffId) {
        return staff.findById(staffId).map(StaffUser::getColorScheme).orElse(ColorScheme.DEVICE);
    }

    @Override
    public void setForStaff(UUID staffId, ColorScheme scheme) {
        staff.findById(staffId).ifPresent(user -> user.setColorScheme(Objects.requireNonNull(scheme)));
    }

    @Override
    @Transactional(readOnly = true)
    public ColorScheme ofPlatformUser(String email) {
        return platformUsers.findByEmail(normalize(email)).map(PlatformUser::getColorScheme)
                .orElse(ColorScheme.DEVICE);
    }

    @Override
    public void setForPlatformUser(String email, ColorScheme scheme) {
        platformUsers.findByEmail(normalize(email))
                .ifPresent(user -> user.setColorScheme(Objects.requireNonNull(scheme)));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
