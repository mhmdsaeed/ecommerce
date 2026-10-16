package com.unispark.ecommerce.merchantadmin.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.unispark.ecommerce.identity.StaffManagement.StaffMember;
import com.unispark.ecommerce.identity.StaffRole;

/** "Edit staff member" form: details and role. Email or phone is required; the service checks that. */
public class StaffEditForm {

    @NotBlank
    @Size(max = 80)
    private String name;

    @Size(max = 254)
    private String email;

    @Size(max = 32)
    private String phone;

    @NotNull
    private StaffRole role;

    static StaffEditForm of(StaffMember member) {
        var form = new StaffEditForm();
        form.setName(member.name());
        form.setEmail(member.email());
        form.setPhone(member.phone());
        form.setRole(member.role());
        return form;
    }

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
}
