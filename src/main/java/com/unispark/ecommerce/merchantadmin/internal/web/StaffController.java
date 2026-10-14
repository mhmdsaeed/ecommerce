package com.unispark.ecommerce.merchantadmin.internal.web;

import java.util.Arrays;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

import com.unispark.ecommerce.identity.StaffManagement;
import com.unispark.ecommerce.identity.StaffManagement.Done;
import com.unispark.ecommerce.identity.StaffManagement.NewStaffMember;
import com.unispark.ecommerce.identity.StaffManagement.NotAllowed;
import com.unispark.ecommerce.identity.StaffManagement.NotFound;
import com.unispark.ecommerce.identity.StaffManagement.Rejected;
import com.unispark.ecommerce.identity.StaffManagement.StaffResult;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.StaffRole;

/** Drivers and staff (SPEC 9.3): list, add, enable and disable. Owners and managers only. */
@Controller
@RequestMapping("/admin/staff")
class StaffController {

    static final String VIEW = "merchantadmin/staff";

    private final StaffManagement staff;

    StaffController(StaffManagement staff) {
        this.staff = staff;
    }

    @GetMapping
    String list(@AuthenticationPrincipal StaffPrincipal actor, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new StaffForm());
        }
        model.addAttribute("members", staff.list());
        model.addAttribute("assignableRoles",
                Arrays.stream(StaffRole.values()).filter(actor.role()::canManage).toList());
        return VIEW;
    }

    @PostMapping
    String add(@Valid @ModelAttribute("form") StaffForm form, BindingResult binding,
            @AuthenticationPrincipal StaffPrincipal actor, Model model) {
        if (binding.hasErrors()) {
            return list(actor, model);
        }
        var result = staff.add(new NewStaffMember(
                form.getName(), form.getEmail(), form.getPhone(), form.getRole(), form.getPassword()), actor);
        if (result instanceof Rejected(var problems)) {
            problems.forEach(problem -> binding.rejectValue(
                    problem.field(), "staff." + problem.field() + "." + problem.code()));
            return list(actor, model);
        }
        return redirectAfter(result, "added");
    }

    @PostMapping("/{id}/active")
    String setActive(@PathVariable UUID id, @RequestParam boolean active,
            @AuthenticationPrincipal StaffPrincipal actor) {
        return redirectAfter(staff.setActive(id, active, actor), active ? "enabled" : "disabled");
    }

    private static String redirectAfter(StaffResult result, String outcome) {
        return switch (result) {
            case Done done -> "redirect:/admin/staff?" + outcome;
            case NotAllowed notAllowed -> throw new AccessDeniedException("Not allowed to manage this staff member");
            case NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            case Rejected rejected -> throw new IllegalStateException("Unexpected validation result");
        };
    }
}
