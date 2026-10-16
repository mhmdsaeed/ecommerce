package com.unispark.ecommerce.identity.internal.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.unispark.ecommerce.shared.TenantScopedEntity;

/** A password reset link sent to a staff member: its token's hash, when it expires, and whether it was used. */
@Entity
@Table(name = "password_reset")
public class PasswordReset extends TenantScopedEntity {

    @Column(name = "staff_id", nullable = false, updatable = false)
    private UUID staffId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected PasswordReset() {
    }

    public PasswordReset(UUID staffId, String tokenHash, Instant expiresAt) {
        this.staffId = staffId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getStaffId() {
        return staffId;
    }

    /** Not used yet, and not expired at {@code now}. */
    public boolean usableAt(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void use(Instant now) {
        usedAt = now;
    }
}
