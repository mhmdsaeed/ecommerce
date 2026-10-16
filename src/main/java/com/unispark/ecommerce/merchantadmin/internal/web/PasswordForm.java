package com.unispark.ecommerce.merchantadmin.internal.web;

import jakarta.validation.constraints.Size;

/**
 * Every password form: an owner setting a staff member's password (only {@code newPassword}), staff changing their
 * own ({@code currentPassword} too), and a reset through a link ({@code store} and {@code token} too). Fields a form
 * does not show stay null. Length rules are the service's ({@code Credentials}); confirmation is checked here.
 */
public class PasswordForm {

    @Size(max = 200)
    private String currentPassword;

    @Size(max = 200)
    private String newPassword;

    @Size(max = 200)
    private String confirmPassword;

    @Size(max = 64)
    private String store;

    @Size(max = 64)
    private String token;

    /** True when a confirmation was asked for and differs. */
    boolean confirmationDiffers() {
        return confirmPassword != null && !confirmPassword.equals(newPassword);
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
