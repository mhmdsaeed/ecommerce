package com.unispark.ecommerce.identity.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped like every staff query. */
public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {

    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /** Links still open for a staff member, to close them once the password has changed. */
    List<PasswordReset> findByStaffIdAndUsedAtIsNullAndExpiresAtAfter(UUID staffId, Instant now);
}
