package com.unispark.ecommerce.identity.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.unispark.ecommerce.identity.ColorScheme;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** A store's staff member. Email is stored lowercase and phone in E.164; at least one of them is set. */
@Entity
@Table(name = "staff_user")
public class StaffUser extends TenantScopedEntity {

    @Column(nullable = false)
    private String name;

    private String email;

    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StaffRole role;

    @Column(nullable = false)
    private boolean active;

    /** Goes up with every password change; sessions from before it end (StaffSessionFilter). */
    @Column(name = "credentials_version", nullable = false)
    private int credentialsVersion;

    /** Light or dark admin screens. */
    @Enumerated(EnumType.STRING)
    @Column(name = "color_scheme", nullable = false)
    private ColorScheme colorScheme = ColorScheme.DEVICE;

    protected StaffUser() {
    }

    public StaffUser(String name, String email, String phone, String passwordHash, StaffRole role) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public StaffRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getCredentialsVersion() {
        return credentialsVersion;
    }

    public ColorScheme getColorScheme() {
        return colorScheme;
    }

    public void setColorScheme(ColorScheme colorScheme) {
        this.colorScheme = colorScheme;
    }

    /** {@code email} lowercase, {@code phone} in E.164; at least one of them. */
    public void updateDetails(String name, String email, String phone, StaffRole role) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.role = role;
    }

    /** A new password ends every session that logged in with the old one. */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.credentialsVersion++;
    }
}
