package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.util.UUID;

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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.unispark.ecommerce.catalog.Categories;
import com.unispark.ecommerce.catalog.ProductImages;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.Invalid;
import com.unispark.ecommerce.catalog.Products.NotFound;
import com.unispark.ecommerce.catalog.Products.ProductDetails;
import com.unispark.ecommerce.catalog.Products.ProductResult;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.merchantadmin.internal.web.FormErrors;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Htmx;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Products (SPEC 9.3): list with live search, create, and the edit page. The edit page has independent sections;
 * details and status use post/redirect/get, the options and variants editor swaps its own fragment over HTMX.
 */
@Controller
@RequestMapping("/admin/products")
class ProductController {

    static final String LIST_PAGE = "merchantadmin/catalog/products";
    static final String LIST_RESULTS = LIST_PAGE + " :: results";
    static final String NEW_PAGE = "merchantadmin/catalog/product-new";
    static final String EDIT_PAGE = "merchantadmin/catalog/product-edit";
    static final String VARIANTS_SECTION = EDIT_PAGE + " :: variants";

    private final Products products;
    private final Categories categories;
    private final StoreSettings settings;

    ProductController(Products products, Categories categories, StoreSettings settings) {
        this.products = products;
        this.categories = categories;
        this.settings = settings;
    }

    @GetMapping
    String list(@RequestParam(required = false) String q, @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "0") int page, Model model, HttpServletRequest request) {
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("result", products.search(q, status, page));
        return Htmx.isHtmx(request) ? LIST_RESULTS : LIST_PAGE;
    }

    @GetMapping("/new")
    String newPage(Model model) {
        var form = new ProductForm();
        form.setPriceCurrency(settings.current().baseCurrency());
        model.addAttribute("form", form);
        return newForm(model);
    }

    @PostMapping
    String create(@Valid @ModelAttribute("form") ProductForm form, BindingResult binding, Model model) {
        if (!binding.hasErrors()) {
            var result = products.create(form.toDraft(), form.getPrice());
            if (result instanceof Saved(var id)) {
                return "redirect:/admin/products/" + id + "?created";
            }
            FormErrors.apply(binding, "product", ((Invalid) result).problems());
        }
        return newForm(model);
    }

    @GetMapping("/{id}")
    String editPage(@PathVariable UUID id, Model model) {
        var product = load(id);
        model.addAttribute("form", ProductForm.of(product));
        model.addAttribute("variantsForm", VariantsForm.of(product));
        return edit(product, model);
    }

    @PostMapping("/{id}")
    String update(@PathVariable UUID id, @Valid @ModelAttribute("form") ProductForm form, BindingResult binding,
            Model model) {
        if (!binding.hasErrors()) {
            var result = products.update(id, form.toDraft());
            if (result instanceof Saved) {
                return "redirect:/admin/products/" + id + "?saved";
            }
            applyOrNotFound(result, binding);
        }
        var product = load(id);
        model.addAttribute("variantsForm", VariantsForm.of(product));
        return edit(product, model);
    }

    @PostMapping("/{id}/status")
    String changeStatus(@PathVariable UUID id, @RequestParam ProductStatus status, RedirectAttributes redirect) {
        var result = products.changeStatus(id, status);
        if (result instanceof NotFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (result instanceof Invalid(var problems)) {
            redirect.addFlashAttribute("error", "product." + problems.getFirst().field() + "." + problems.getFirst().code());
            return "redirect:/admin/products/" + id;
        }
        return "redirect:/admin/products/" + id + "?status";
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        var result = products.delete(id);
        if (result instanceof NotFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (result instanceof Invalid(var problems)) {
            redirect.addFlashAttribute("error", "product." + problems.getFirst().field() + "." + problems.getFirst().code());
            return "redirect:/admin/products/" + id;
        }
        return "redirect:/admin/products?deleted";
    }

    @PostMapping("/{id}/options")
    String defineOptions(@PathVariable UUID id, @ModelAttribute("variantsForm") VariantsForm form,
            BindingResult binding, Model model, HttpServletRequest request) {
        var result = products.defineOptions(id, form.optionDrafts());
        if (result instanceof Saved) {
            return variantsSection(id, "product.options.saved", model, request);
        }
        applyOrNotFound(result, binding);
        var stored = load(id);
        form.setVariants(VariantsForm.of(stored).getVariants());
        return variantsSectionWithErrors(stored, form, model, request);
    }

    @PostMapping("/{id}/variants")
    String updateVariants(@PathVariable UUID id, @ModelAttribute("variantsForm") VariantsForm form,
            BindingResult binding, Model model, HttpServletRequest request) {
        var result = products.updateVariants(id, form.variantDrafts());
        if (result instanceof Saved) {
            return variantsSection(id, "product.variants.saved", model, request);
        }
        applyOrNotFound(result, binding);
        var stored = load(id);
        form.setOptions(VariantsForm.of(stored).getOptions());
        restoreLabels(form, stored);
        return variantsSectionWithErrors(stored, form, model, request);
    }

    private String variantsSection(UUID id, String notice, Model model, HttpServletRequest request) {
        if (!Htmx.isHtmx(request)) {
            return "redirect:/admin/products/" + id;
        }
        var product = load(id);
        model.asMap().remove(BindingResult.MODEL_KEY_PREFIX + "variantsForm");
        model.addAttribute("variantsForm", VariantsForm.of(product));
        model.addAttribute("variantsNotice", notice);
        model.addAttribute("product", product);
        return VARIANTS_SECTION;
    }

    /**
     * The options form and the variants form share one backing object but post separately. On errors, the half
     * that was posted keeps what was typed; the caller refilled the other half from the product.
     */
    private String variantsSectionWithErrors(ProductDetails product, VariantsForm form, Model model,
            HttpServletRequest request) {
        form.padOptions();
        model.addAttribute("product", product);
        if (Htmx.isHtmx(request)) {
            return VARIANTS_SECTION;
        }
        model.addAttribute("form", ProductForm.of(product));
        return edit(product, model);
    }

    /** Variant labels are shown, not submitted. */
    private static void restoreLabels(VariantsForm form, ProductDetails product) {
        for (var row : form.getVariants()) {
            product.variants().stream().filter(variant -> variant.id().equals(row.getId())).findFirst()
                    .ifPresent(variant -> row.setLabel(variant.label()));
        }
    }

    private ProductDetails load(UUID id) {
        return products.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String newForm(Model model) {
        model.addAttribute("categories", categories.list());
        model.addAttribute("currencies", Currency.values());
        return NEW_PAGE;
    }

    private String edit(ProductDetails product, Model model) {
        model.addAttribute("product", product);
        model.addAttribute("categories", categories.list());
        model.addAttribute("currencies", Currency.values());
        model.addAttribute("maxImages", ProductImages.MAX_IMAGES);
        return EDIT_PAGE;
    }

    private static void applyOrNotFound(ProductResult result, BindingResult binding) {
        switch (result) {
            case Invalid(var problems) -> FormErrors.apply(binding, "product", problems);
            case NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            case Saved saved -> { }
        }
    }
}
