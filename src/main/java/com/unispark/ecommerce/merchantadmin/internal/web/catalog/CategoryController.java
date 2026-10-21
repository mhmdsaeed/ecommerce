package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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

import com.unispark.ecommerce.catalog.Categories;
import com.unispark.ecommerce.catalog.Categories.CategoryResult;
import com.unispark.ecommerce.catalog.Categories.InUse;
import com.unispark.ecommerce.catalog.Categories.Invalid;
import com.unispark.ecommerce.catalog.Categories.NotFound;
import com.unispark.ecommerce.catalog.Categories.Saved;
import com.unispark.ecommerce.merchantadmin.internal.web.FormErrors;
import com.unispark.ecommerce.shared.Htmx;

/**
 * Categories (SPEC 9.3). One page: the list and the add form, swapped together as the {@code section} fragment
 * for HTMX requests. Editing is a separate page with a classic post/redirect/get.
 */
@Controller
@RequestMapping("/admin/categories")
class CategoryController {

    static final String PAGE = "merchantadmin/catalog/categories";
    static final String SECTION = PAGE + " :: section";
    static final String EDIT_PAGE = "merchantadmin/catalog/category-edit";

    private final Categories categories;

    CategoryController(Categories categories) {
        this.categories = categories;
    }

    @GetMapping
    String page(Model model) {
        model.addAttribute("form", new CategoryForm());
        return list(model);
    }

    @PostMapping
    String create(@Valid @ModelAttribute("form") CategoryForm form, BindingResult binding, Model model,
            HttpServletRequest request) {
        if (!binding.hasErrors()) {
            var result = categories.create(form.toDraft());
            if (result instanceof Invalid(var problems)) {
                FormErrors.apply(binding, "category", problems);
            } else {
                if (!Htmx.isHtmx(request)) {
                    return "redirect:/admin/categories?created";
                }
                resetForm(model);
                model.addAttribute("notice", "category.created");
            }
        }
        list(model);
        return Htmx.isHtmx(request) ? SECTION : PAGE;
    }

    @GetMapping("/{id}/edit")
    String editPage(@PathVariable UUID id, Model model) {
        var category = categories.list().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("form", CategoryForm.of(category));
        return edit(id, model);
    }

    @PostMapping("/{id}")
    String update(@PathVariable UUID id, @Valid @ModelAttribute("form") CategoryForm form, BindingResult binding,
            Model model) {
        if (!binding.hasErrors()) {
            switch (categories.update(id, form.toDraft())) {
                case Saved saved -> {
                    return "redirect:/admin/categories?updated";
                }
                case Invalid(var problems) -> FormErrors.apply(binding, "category", problems);
                case NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                case InUse inUse -> throw new IllegalStateException("update never reports InUse");
            }
        }
        return edit(id, model);
    }

    @PostMapping("/{id}/active")
    String setActive(@PathVariable UUID id, @RequestParam boolean active, Model model, HttpServletRequest request) {
        return afterAction(categories.setActive(id, active), active ? "category.enabled" : "category.disabled",
                model, request);
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable UUID id, Model model, HttpServletRequest request) {
        return afterAction(categories.delete(id), "category.deleted", model, request);
    }

    private String afterAction(CategoryResult result, String successNotice, Model model, HttpServletRequest request) {
        switch (result) {
            case Saved saved -> model.addAttribute("notice", successNotice);
            case InUse inUse -> model.addAttribute("error", "category.in-use");
            case NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            case Invalid invalid -> throw new IllegalStateException("toggle and delete never report Invalid");
        }
        if (!Htmx.isHtmx(request)) {
            return "redirect:/admin/categories";
        }
        model.addAttribute("form", new CategoryForm());
        list(model);
        return SECTION;
    }

    private String list(Model model) {
        var all = categories.list();
        model.addAttribute("categories", all);
        model.addAttribute("parents", all.stream().filter(Categories.CategoryView::topLevel).toList());
        // What shoppers see: a visible category, under a visible parent when it has one (as the storefront shows them).
        var visible = all.stream().filter(Categories.CategoryView::active).map(Categories.CategoryView::id)
                .collect(Collectors.toSet());
        model.addAttribute("inShop", all.stream()
                .filter(category -> category.active() && (category.topLevel() || visible.contains(category.parentId())))
                .map(Categories.CategoryView::id).collect(Collectors.toSet()));
        return PAGE;
    }

    private String edit(UUID id, Model model) {
        model.addAttribute("categoryId", id);
        model.addAttribute("parents", categories.list().stream()
                .filter(category -> category.topLevel() && !category.id().equals(id)).toList());
        return EDIT_PAGE;
    }

    /** After a successful HTMX add, show an empty form again (and drop the old binding result with it). */
    private static void resetForm(Model model) {
        model.asMap().remove(BindingResult.MODEL_KEY_PREFIX + "form");
        model.addAttribute("form", new CategoryForm());
    }
}
