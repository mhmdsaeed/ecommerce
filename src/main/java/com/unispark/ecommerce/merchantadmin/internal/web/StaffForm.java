package com.unispark.ecommerce.merchantadmin.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.unispark.ecommerce.identity.StaffRole;

/** "Add staff member" form backing object. Email or phone is required; the service checks that. */
public class StaffForm {

    @NotBlank
    @Size(max = 80)
    private String name;

    @Size(max = 254)
    private String email;

    @Size(max = 32)
    private String phone;

    @NotNull
    private StaffRole role;

    @NotBlank
    @Size(max = 200)
    private String password;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public StaffRole getRole() {
        return role;
    }

    public void setRole(StaffRole role) {
        this.role = role;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
