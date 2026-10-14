package com.unispark.ecommerce.merchantadmin.internal.web;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.unispark.ecommerce.identity.MerchantSignup;
import com.unispark.ecommerce.identity.MerchantSignup.Rejected;
import com.unispark.ecommerce.identity.MerchantSignup.SignedUp;
import com.unispark.ecommerce.identity.MerchantSignup.SignupRequest;
import com.unispark.ecommerce.identity.MerchantSignup.TooManyAttempts;
import com.unispark.ecommerce.identity.StaffSessions;
import com.unispark.ecommerce.tenant.Sites;

/** Merchant signup on the main site. Success logs the owner in and opens the (empty) dashboard. */
@Controller
@RequestMapping("/signup")
class SignupController {

    static final String VIEW = "merchantadmin/signup";

    private final MerchantSignup signup;
    private final StaffSessions sessions;
    private final Sites sites;

    SignupController(MerchantSignup signup, StaffSessions sessions, Sites sites) {
        this.signup = signup;
        this.sessions = sessions;
        this.sites = sites;
    }

    @ModelAttribute("storeDomain")
    String storeDomain() {
        return "." + sites.baseHost();
    }

    @GetMapping
    String form(Model model) {
        model.addAttribute("form", new SignupForm());
        return VIEW;
    }

    @PostMapping
    String submit(@Valid @ModelAttribute("form") SignupForm form, BindingResult binding, Locale locale,
            HttpServletRequest request, HttpServletResponse response) {
        if (binding.hasErrors()) {
            return VIEW;
        }
        var result = signup.signUp(new SignupRequest(form.getStoreName(), form.getSlug(), form.getOwnerName(),
                form.getEmail(), form.getPhone(), form.getPassword(), locale, request.getRemoteAddr()));
        return switch (result) {
            case SignedUp(var owner) -> {
                sessions.logIn(owner, request, response);
                yield "redirect:/admin";
            }
            case Rejected(var problems) -> {
                problems.forEach(problem -> binding.rejectValue(
                        problem.field(), "signup." + problem.field() + "." + problem.code()));
                yield VIEW;
            }
            case TooManyAttempts tooMany -> {
                binding.reject("signup.too-many-attempts");
                yield VIEW;
            }
        };
    }
}
