package com.unispark.ecommerce.merchantadmin.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Signup form backing object. A JavaBean, because Thymeleaf's th:field binds through getters and setters. */
public class SignupForm {

    @NotBlank
    @Size(max = 80)
    private String storeName;

    @NotBlank
    @Size(max = 32)
    private String slug;

    @NotBlank
    @Size(max = 80)
    private String ownerName;

    @Size(max = 254)
    private String email;

    @NotBlank
    @Size(max = 32)
    private String phone;

    @NotBlank
    @Size(max = 200)
    private String password;

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
