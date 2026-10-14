package com.unispark.ecommerce.identity.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.unispark.ecommerce.shared.BaseEntity;

/** A platform administrator. Platform entity: no tenant. Email is stored lowercase. */
@Entity
@Table(name = "platform_user")
public class PlatformUser extends BaseEntity {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    @Column(nullable = false, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private boolean active;

    protected PlatformUser() {
    }

    public PlatformUser(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = PLATFORM_ADMIN;
        this.active = true;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }
}
