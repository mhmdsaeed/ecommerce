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
import com.unispark.ecommerce.identity.StaffManagement.StaffDetails;
import com.unispark.ecommerce.identity.StaffManagement.StaffResult;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.StaffRole;

/**
 * Drivers and staff (SPEC 9.3): list, add, edit, enable and disable, and set a new password for someone who forgot
 * theirs. Owners and managers only.
 */
@Controller
@RequestMapping("/admin/staff")
class StaffController {

    static final String VIEW = "merchantadmin/staff";
    static final String EDIT_VIEW = "merchantadmin/staff-edit";

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

    /** Details, role and a new password for one member. Members the actor may not manage are not found. */
    @GetMapping("/{id}")
    String edit(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal actor, Model model) {
        var member = staff.findManageable(id, actor)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", StaffEditForm.of(member));
        }
        if (!model.containsAttribute("passwordForm")) {
            model.addAttribute("passwordForm", new PasswordForm());
        }
        model.addAttribute("member", member);
        model.addAttribute("assignableRoles",
                Arrays.stream(StaffRole.values()).filter(actor.role()::canManage).toList());
        return EDIT_VIEW;
    }

    @PostMapping("/{id}")
    String update(@PathVariable UUID id, @Valid @ModelAttribute("form") StaffEditForm form, BindingResult binding,
            @AuthenticationPrincipal StaffPrincipal actor, Model model) {
        if (binding.hasErrors()) {
            return edit(id, actor, model);
        }
        var result = staff.update(id, new StaffDetails(form.getName(), form.getEmail(), form.getPhone(),
                form.getRole()), actor);
        if (result instanceof Rejected(var problems)) {
            problems.forEach(problem -> binding.rejectValue(
                    problem.field(), "staff." + problem.field() + "." + problem.code()));
            return edit(id, actor, model);
        }
        return redirectAfter(result, "updated");
    }

    /** For a member who forgot their password: the owner or manager sets one and tells them. */
    @PostMapping("/{id}/password")
    String setPassword(@PathVariable UUID id, @Valid @ModelAttribute("passwordForm") PasswordForm form,
            BindingResult binding, @AuthenticationPrincipal StaffPrincipal actor, Model model) {
        if (binding.hasErrors()) {
            return edit(id, actor, model);
        }
        var result = staff.setPassword(id, form.getNewPassword(), actor);
        if (result instanceof Rejected(var problems)) {
            problems.forEach(problem -> binding.rejectValue("newPassword", "staff.password." + problem.code()));
            return edit(id, actor, model);
        }
        return redirectAfter(result, "password-set");
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
