package com.unispark.ecommerce.merchantadmin.internal.web.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.unispark.ecommerce.merchantadmin.internal.web.FormErrors;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.StoreSettings.DetailsInvalid;
import com.unispark.ecommerce.tenant.StoreSettings.DetailsSaved;

/**
 * Store settings: the name shoppers see and how they reach the store (SPEC 9.3, the wizard's first step). The
 * address (slug) is fixed at signup. The numbers show in the storefront's footer.
 */
@Controller
@RequestMapping("/admin/settings/store")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class StoreDetailsController {

    static final String PAGE = "merchantadmin/settings/store";

    private final StoreSettings settings;

    StoreDetailsController(StoreSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    String page(Model model) {
        var current = settings.current();
        var form = new DetailsForm();
        form.setNameAr(current.name().ar());
        form.setNameEn(current.name().en());
        form.setContactPhone(current.contactPhone());
        form.setWhatsapp(current.whatsapp());
        model.addAttribute("form", form);
        return PAGE;
    }

    @PostMapping
    String save(@ModelAttribute("form") DetailsForm form, BindingResult binding) {
        return switch (settings.updateDetails(form.getNameAr(), form.getNameEn(), form.getContactPhone(),
                form.getWhatsapp())) {
            case DetailsSaved saved -> "redirect:/admin/settings/store?saved";
            case DetailsInvalid invalid -> {
                FormErrors.apply(binding, "settings.store", invalid.problems());
                yield PAGE;
            }
        };
    }

    /** The form as typed; numbers are checked and normalized by the tenant module. */
    static class DetailsForm {

        private String nameAr;
        private String nameEn;
        private String contactPhone;
        private String whatsapp;

        public String getNameAr() {
            return nameAr;
        }

        public void setNameAr(String nameAr) {
            this.nameAr = nameAr;
        }

        public String getNameEn() {
            return nameEn;
        }

        public void setNameEn(String nameEn) {
            this.nameEn = nameEn;
        }

        public String getContactPhone() {
            return contactPhone;
        }

        public void setContactPhone(String contactPhone) {
            this.contactPhone = contactPhone;
        }

        public String getWhatsapp() {
            return whatsapp;
        }

        public void setWhatsapp(String whatsapp) {
            this.whatsapp = whatsapp;
        }
    }
}
