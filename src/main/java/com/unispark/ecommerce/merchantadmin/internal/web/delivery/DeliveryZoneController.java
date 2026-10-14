package com.unispark.ecommerce.merchantadmin.internal.web.delivery;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.Invalid;
import com.unispark.ecommerce.delivery.DeliveryZones.NotFound;
import com.unispark.ecommerce.delivery.DeliveryZones.Saved;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneResult;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.merchantadmin.internal.web.FormErrors;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Htmx;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Delivery zones (SPEC 8, 9.3). One page with the list and the add form, swapped together as the {@code section}
 * fragment for HTMX requests; editing is a separate page with post/redirect/get. Fees are money decisions, so owners
 * and managers only.
 */
@Controller
@RequestMapping("/admin/delivery")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class DeliveryZoneController {

    static final String PAGE = "merchantadmin/delivery/zones";
    static final String SECTION = PAGE + " :: section";
    static final String EDIT_PAGE = "merchantadmin/delivery/zone-edit";

    private final DeliveryZones zones;
    private final StoreSettings settings;

    DeliveryZoneController(DeliveryZones zones, StoreSettings settings) {
        this.zones = zones;
        this.settings = settings;
    }

    @ModelAttribute
    void options(Model model) {
        model.addAttribute("governorates", Governorate.values());
        model.addAttribute("currencies", Currency.values());
    }

    @GetMapping
    String page(Model model) {
        model.addAttribute("form", newForm());
        return list(model);
    }

    @PostMapping
    String create(@Valid @ModelAttribute("form") ZoneForm form, BindingResult binding, Model model,
            HttpServletRequest request) {
        if (!binding.hasErrors()) {
            var result = zones.create(form.toDraft());
            if (result instanceof Invalid(var problems)) {
                FormErrors.apply(binding, "zone", problems);
            } else {
                if (!Htmx.isHtmx(request)) {
                    return "redirect:/admin/delivery?created";
                }
                model.asMap().remove(BindingResult.MODEL_KEY_PREFIX + "form");
                model.addAttribute("form", newForm());
                model.addAttribute("notice", "zone.created");
            }
        }
        list(model);
        return Htmx.isHtmx(request) ? SECTION : PAGE;
    }

    @GetMapping("/{id}/edit")
    String editPage(@PathVariable UUID id, Model model) {
        var zone = zones.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("form", ZoneForm.of(zone));
        model.addAttribute("zoneId", id);
        return EDIT_PAGE;
    }

    @PostMapping("/{id}")
    String update(@PathVariable UUID id, @Valid @ModelAttribute("form") ZoneForm form, BindingResult binding,
            Model model) {
        if (!binding.hasErrors()) {
            switch (zones.update(id, form.toDraft())) {
                case Saved saved -> {
                    return "redirect:/admin/delivery?updated";
                }
                case Invalid(var problems) -> FormErrors.apply(binding, "zone", problems);
                case NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        model.addAttribute("zoneId", id);
        return EDIT_PAGE;
    }

    @PostMapping("/{id}/active")
    String setActive(@PathVariable UUID id, @RequestParam boolean active, Model model, HttpServletRequest request) {
        return afterAction(zones.setActive(id, active), active ? "zone.enabled" : "zone.disabled", model, request);
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable UUID id, Model model, HttpServletRequest request) {
        return afterAction(zones.delete(id), "zone.deleted", model, request);
    }

    private String afterAction(ZoneResult result, String notice, Model model, HttpServletRequest request) {
        if (result instanceof NotFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!Htmx.isHtmx(request)) {
            return "redirect:/admin/delivery";
        }
        model.addAttribute("notice", notice);
        model.addAttribute("form", newForm());
        list(model);
        return SECTION;
    }

    private String list(Model model) {
        model.addAttribute("zones", zones.list());
        return PAGE;
    }

    private ZoneForm newForm() {
        return ZoneForm.empty(settings.current().baseCurrency());
    }
}
