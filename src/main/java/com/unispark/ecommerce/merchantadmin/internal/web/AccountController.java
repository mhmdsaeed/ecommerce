package com.unispark.ecommerce.merchantadmin.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.unispark.ecommerce.identity.StaffPasswords;
import com.unispark.ecommerce.identity.StaffPasswords.Changed;
import com.unispark.ecommerce.identity.StaffPasswords.LinkUnusable;
import com.unispark.ecommerce.identity.StaffPasswords.Rejected;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.StaffSessions;

/**
 * The logged-in member's own account: changing their password. Any merchant admin role. Other sessions of the
 * account end; this one is logged in again with the new credentials.
 */
@Controller
@RequestMapping("/admin/account")
class AccountController {

    static final String VIEW = "merchantadmin/account";

    private final StaffPasswords passwords;
    private final StaffSessions sessions;

    AccountController(StaffPasswords passwords, StaffSessions sessions) {
        this.passwords = passwords;
        this.sessions = sessions;
    }

    @GetMapping
    String account(Model model) {
        if (!model.containsAttribute("passwordForm")) {
            model.addAttribute("passwordForm", new PasswordForm());
        }
        return VIEW;
    }

    @PostMapping("/password")
    String changePassword(@Valid @ModelAttribute("passwordForm") PasswordForm form, BindingResult binding,
            @AuthenticationPrincipal StaffPrincipal actor, HttpServletRequest request, HttpServletResponse response) {
        if (form.confirmationDiffers()) {
            binding.rejectValue("confirmPassword", "account.password.mismatch");
        }
        if (binding.hasErrors()) {
            return VIEW;
        }
        return switch (passwords.change(actor, form.getCurrentPassword(), form.getNewPassword())) {
            case Changed(var principal) -> {
                sessions.logIn(principal, request, response);
                yield "redirect:/admin/account?changed";
            }
            case Rejected(var problems) -> {
                problems.forEach(problem -> binding.rejectValue(
                        problem.field(), "account.password." + problem.code()));
                yield VIEW;
            }
            case LinkUnusable unusable -> throw new IllegalStateException("No link involved");
        };
    }
}
