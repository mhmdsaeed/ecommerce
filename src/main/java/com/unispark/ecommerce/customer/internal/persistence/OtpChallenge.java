package com.unispark.ecommerce.customer.internal.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.unispark.ecommerce.shared.TenantScopedEntity;

/** One code sent to one phone. The code itself is never stored, only a hash salted with this row's id. */
@Entity
@Table(name = "otp_challenge")
public class OtpChallenge extends TenantScopedEntity {

    @Column(nullable = false, updatable = false)
    private String phone;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verification_hash")
    private String verificationHash;

    protected OtpChallenge() {
    }

    public OtpChallenge(String phone, Instant expiresAt) {
        this.phone = phone;
        this.expiresAt = expiresAt;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public boolean isLive(Instant now, int maxAttempts) {
        return verifiedAt == null && now.isBefore(expiresAt) && attempts < maxAttempts;
    }

    public void failedAttempt() {
        attempts++;
    }

    public void verified(Instant at, String verificationHash) {
        this.verifiedAt = at;
        this.verificationHash = verificationHash;
    }

    public String getPhone() {
        return phone;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
