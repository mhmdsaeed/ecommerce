package com.unispark.ecommerce.merchantadmin.internal.web.settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.Invalid;
import com.unispark.ecommerce.payment.PaymentMethods.MethodConfig;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;

/**
 * Store settings: payment methods (SPEC 6.2, 9.3). One card per method, each its own form with post/redirect/get.
 * A refused save shows that card again with what was typed and its errors. Owners and managers only.
 */
@Controller
@RequestMapping("/admin/settings/payments")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class PaymentSettingsController {

    static final String PAGE = "merchantadmin/settings/payments";

    private final PaymentMethods methods;

    PaymentSettingsController(PaymentMethods methods) {
        this.methods = methods;
    }

    /** One card: the method's values as shown in its form, and errors by field (message keys). */
    record Card(PaymentMethod method, boolean enabled, String displayNameAr, String displayNameEn, String accountId,
            String accountName, String instructionsAr, String instructionsEn, Map<String, String> errors) {

        static Card of(MethodConfig config) {
            return new Card(config.method(), config.enabled(),
                    config.displayName() == null ? null : config.displayName().ar(),
                    config.displayName() == null ? null : config.displayName().en(), config.accountId(),
                    config.accountName(), config.instructions() == null ? null : config.instructions().ar(),
                    config.instructions() == null ? null : config.instructions().en(), Map.of());
        }
    }

    @GetMapping
    String page(Model model) {
        model.addAttribute("cards", methods.all().stream().map(Card::of).toList());
        return PAGE;
    }

    @PostMapping("/{method}")
    String save(@PathVariable PaymentMethod method, @RequestParam(defaultValue = "false") boolean enabled,
            @RequestParam(required = false) String displayNameAr, @RequestParam(required = false) String displayNameEn,
            @RequestParam(required = false) String accountId, @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String instructionsAr,
            @RequestParam(required = false) String instructionsEn, Model model) {
        var draft = new MethodDraft(enabled, displayNameAr, displayNameEn, accountId, accountName, instructionsAr,
                instructionsEn);
        if (methods.save(method, draft) instanceof Invalid(var problems)) {
            var errors = new HashMap<String, String>();
            problems.forEach(problem -> errors.put(problem.field(),
                    "settings.payments." + problem.field() + "." + problem.code()));
            var typed = new Card(method, enabled, displayNameAr, displayNameEn, accountId, accountName,
                    instructionsAr, instructionsEn, Map.copyOf(errors));
            List<Card> cards = methods.all().stream()
                    .map(config -> config.method() == method ? typed : Card.of(config)).toList();
            model.addAttribute("cards", cards);
            model.addAttribute("failed", method);
            return PAGE;
        }
        return "redirect:/admin/settings/payments?saved=" + method;
    }
}
