package com.unispark.ecommerce.merchantadmin.internal.web;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.unispark.ecommerce.identity.StaffPasswords;
import com.unispark.ecommerce.identity.StaffPasswords.Changed;
import com.unispark.ecommerce.identity.StaffPasswords.LinkUnusable;
import com.unispark.ecommerce.identity.StaffPasswords.Rejected;
import com.unispark.ecommerce.tenant.Sites;

/**
 * Forgotten passwords, for people who are not logged in (the security chain lets {@code /admin/password/**}
 * through). The request form answers the same whether or not the account exists. The link from the message opens
 * the reset form, which works once and for {@link StaffPasswords#LINK_LIFETIME}; afterwards the member logs in.
 */
@Controller
@RequestMapping("/admin/password")
class PasswordResetController {

    static final String FORGOT_VIEW = "merchantadmin/password-forgot";
    static final String RESET_VIEW = "merchantadmin/password-reset";

    private final StaffPasswords passwords;
    private final Sites sites;

    PasswordResetController(StaffPasswords passwords, Sites sites) {
        this.passwords = passwords;
        this.sites = sites;
    }

    /** For the shop sign every page here shows: the store address is {@code <store><storeDomain>}. */
    @ModelAttribute("storeDomain")
    String storeDomain() {
        return "." + sites.baseHost();
    }

    @GetMapping("/forgot")
    String forgot(@RequestParam(required = false) String store, Model model) {
        if (!model.containsAttribute("store")) {
            model.addAttribute("store", store);
        }
        model.addAttribute("minutes", StaffPasswords.LINK_LIFETIME.toMinutes());
        return FORGOT_VIEW;
    }

    @PostMapping("/forgot")
    String requestReset(@RequestParam(defaultValue = "") String store,
            @RequestParam(defaultValue = "") String username, HttpServletRequest request, Locale locale,
            RedirectAttributes redirect) {
        if (store.length() <= 64 && username.length() <= 254) {
            passwords.requestReset(store, username, request.getRemoteAddr(), locale);
            // The "sent" page keeps the shop sign on the store that was typed.
            redirect.addFlashAttribute("store", store.strip());
        }
        return "redirect:/admin/password/forgot?sent";
    }

    @GetMapping("/reset")
    String resetForm(@RequestParam(defaultValue = "") String store, @RequestParam(defaultValue = "") String token,
            Model model, HttpServletResponse response) {
        noReferrer(response);
        if (!passwords.resetLinkUsable(store, token)) {
            return expired(model);
        }
        var form = new PasswordForm();
        form.setStore(store);
        form.setToken(token);
        model.addAttribute("passwordForm", form);
        return RESET_VIEW;
    }

    @PostMapping("/reset")
    String reset(@Valid @ModelAttribute("passwordForm") PasswordForm form, BindingResult binding, Model model,
            HttpServletResponse response) {
        noReferrer(response);
        if (form.confirmationDiffers()) {
            binding.rejectValue("confirmPassword", "account.password.mismatch");
        }
        if (binding.hasErrors()) {
            return RESET_VIEW;
        }
        return switch (passwords.reset(form.getStore(), form.getToken(), form.getNewPassword())) {
            case Changed changed -> "redirect:/admin/login?reset";
            case Rejected(var problems) -> {
                problems.forEach(problem -> binding.rejectValue(
                        problem.field(), "account.password." + problem.code()));
                yield RESET_VIEW;
            }
            case LinkUnusable unusable -> expired(model);
        };
    }

    private String expired(Model model) {
        model.addAttribute("expired", true);
        return RESET_VIEW;
    }

    /** The token is in the URL: no other site should see it in a Referer header. */
    private static void noReferrer(HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
