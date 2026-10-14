package com.unispark.ecommerce.merchantadmin.internal.web;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreSummary;

/** The logged-in staff member and their store, for the admin and driver layouts. Null on public pages. */
@ControllerAdvice(basePackageClasses = AdminModelAdvice.class)
class AdminModelAdvice {

    private final StoreLookup stores;

    AdminModelAdvice(StoreLookup stores) {
        this.stores = stores;
    }

    @ModelAttribute("currentStaff")
    StaffPrincipal currentStaff() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof StaffPrincipal staff ? staff : null;
    }

    @ModelAttribute("currentStore")
    StoreSummary currentStore() {
        return TenantContext.currentTenantId().flatMap(stores::findById).orElse(null);
    }
}
