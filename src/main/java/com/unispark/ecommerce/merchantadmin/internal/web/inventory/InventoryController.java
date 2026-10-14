package com.unispark.ecommerce.merchantadmin.internal.web.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.VariantRow;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.inventory.Inventory.Rejected;
import com.unispark.ecommerce.inventory.Inventory.UnknownVariant;
import com.unispark.ecommerce.inventory.Inventory.Updated;
import com.unispark.ecommerce.inventory.StockLevels;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.shared.Htmx;

/**
 * Inventory (SPEC 9.3): every variant with its stock. Each row saves on its own over HTMX and swaps only its stock
 * form. An empty quantity means the variant is not tracked.
 */
@Controller
@RequestMapping("/admin/inventory")
class InventoryController {

    static final String PAGE = "merchantadmin/inventory";
    static final String RESULTS = PAGE + " :: results";
    static final String STOCK = PAGE + " :: stock";

    private final Products products;
    private final Inventory inventory;

    InventoryController(Products products, Inventory inventory) {
        this.products = products;
        this.inventory = inventory;
    }

    @GetMapping
    String list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, Model model,
            HttpServletRequest request) {
        var result = products.variants(q, page);
        model.addAttribute("q", q);
        model.addAttribute("result", result);
        model.addAttribute("levels", inventory.levels(result.items().stream().map(VariantRow::id).toList()));
        return Htmx.isHtmx(request) ? RESULTS : PAGE;
    }

    @PostMapping("/{variantId}")
    String save(@PathVariable UUID variantId, @RequestParam(defaultValue = "") String onHand, Model model,
            HttpServletRequest request) {
        model.addAttribute("variantId", variantId);
        model.addAttribute("value", onHand);
        var tracked = !onHand.isBlank();
        var quantity = tracked ? parse(onHand) : null;
        if (tracked && quantity == null) {
            return answer(request, model, current(variantId), "inventory.on-hand.invalid", null);
        }
        var result = inventory.setOnHand(variantId, quantity);
        return switch (result) {
            case Updated(var level) -> {
                model.addAttribute("value", level == null ? "" : Integer.toString(level.onHand()));
                model.addAttribute("saved", true);
                yield answer(request, model, level, null, null);
            }
            case Rejected(var code, var reserved) ->
                    answer(request, model, current(variantId), "inventory.on-hand." + code, reserved);
            case UnknownVariant() -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    /** A whole number as merchants type it (Arabic digits, grouping), or null when it is not one in range. */
    private static Integer parse(String input) {
        return Amounts.parse(input, 0)
                .filter(amount -> amount.compareTo(BigDecimal.valueOf(Inventory.MAX_ON_HAND)) <= 0)
                .map(BigDecimal::intValueExact)
                .orElse(null);
    }

    private StockLevels.Level current(UUID variantId) {
        return inventory.levels(List.of(variantId)).of(variantId).orElse(null);
    }

    private static String answer(HttpServletRequest request, Model model, StockLevels.Level level, String error,
            Integer errorArgument) {
        if (!Htmx.isHtmx(request)) {
            return "redirect:/admin/inventory" + (error == null ? "?saved" : "?invalid");
        }
        model.addAttribute("level", level);
        model.addAttribute("error", error);
        model.addAttribute("errorArgument", errorArgument);
        return STOCK;
    }
}
