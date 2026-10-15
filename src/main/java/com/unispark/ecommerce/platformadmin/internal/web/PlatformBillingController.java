package com.unispark.ecommerce.platformadmin.internal.web;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.billing.InvoicePaymentMethod;
import com.unispark.ecommerce.billing.InvoiceStatus;
import com.unispark.ecommerce.billing.Invoices;
import com.unispark.ecommerce.billing.Invoices.Created;
import com.unispark.ecommerce.billing.Invoices.InvoiceDraft;
import com.unispark.ecommerce.billing.Invoices.Invalid;
import com.unispark.ecommerce.billing.Invoices.NotOpen;
import com.unispark.ecommerce.billing.Invoices.PaymentDraft;
import com.unispark.ecommerce.billing.Invoices.PaymentRecorded;
import com.unispark.ecommerce.billing.Invoices.ReasonRequired;
import com.unispark.ecommerce.billing.Invoices.Voided;
import com.unispark.ecommerce.billing.SubscriptionStatus;
import com.unispark.ecommerce.billing.Subscriptions;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.ListParams;

/**
 * Subscriptions and invoices across stores (SPEC 9.4): writing an invoice by hand, recording the money a store
 * sent, and voiding an invoice that should not be paid.
 */
@Controller
class PlatformBillingController {

    private static final Set<String> DONE = Set.of("created", "payment", "paid", "voided");

    private final Subscriptions subscriptions;
    private final Invoices invoices;
    private final StoreNames storeNames;

    PlatformBillingController(Subscriptions subscriptions, Invoices invoices, StoreNames storeNames) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.storeNames = storeNames;
    }

    @GetMapping("/platform/subscriptions")
    String subscriptions(@RequestParam(required = false) String status, Model model) {
        var filter = ListParams.constant(SubscriptionStatus.class, status).orElse(null);
        model.addAttribute("status", filter);
        model.addAttribute("statuses", SubscriptionStatus.values());
        model.addAttribute("subscriptions", subscriptions.list(filter));
        model.addAttribute("stores", storeNames.byId());
        return "platformadmin/subscriptions";
    }

    @GetMapping("/platform/invoices")
    String invoices(@RequestParam(required = false) String status, @RequestParam(required = false) String page,
            Model model) {
        var filter = ListParams.constant(InvoiceStatus.class, status).orElse(null);
        model.addAttribute("status", filter);
        model.addAttribute("statuses", InvoiceStatus.values());
        model.addAttribute("invoices", invoices.list(filter, ListParams.page(page)));
        model.addAttribute("stores", storeNames.byId());
        return "platformadmin/invoices/list";
    }

    @GetMapping("/platform/invoices/new")
    String newInvoice(@RequestParam(required = false) UUID store, Model model) {
        return invoiceForm(store, "", "", Currency.USD.name(), "", Map.of(), model);
    }

    @PostMapping("/platform/invoices")
    String createInvoice(@RequestParam(required = false) UUID store,
            @RequestParam(defaultValue = "") String description, @RequestParam(defaultValue = "") String amount,
            @RequestParam(defaultValue = "") String currency, @RequestParam(defaultValue = "") String dueDate,
            Model model) {
        var due = date(dueDate);
        var chosen = ListParams.constant(Currency.class, currency).orElse(null);
        return switch (invoices.create(new InvoiceDraft(store, description, amount, chosen, due))) {
            case Created created -> "redirect:/platform/invoices/" + created.invoiceId() + "?done=created";
            case Invalid invalid -> invoiceForm(store, description, amount, currency, dueDate,
                    errors("platform.invoice.", invalid), model);
        };
    }

    @GetMapping("/platform/invoices/{id}")
    String invoice(@PathVariable UUID id, @RequestParam(required = false) String done, Model model) {
        return invoicePage(id, done, Map.of(), null, model);
    }

    @PostMapping("/platform/invoices/{id}/payments")
    String recordPayment(@PathVariable UUID id, @RequestParam(required = false) String method,
            @RequestParam(defaultValue = "") String amount, @RequestParam(required = false) String reference,
            Principal admin, Model model) {
        var chosen = ListParams.constant(InvoicePaymentMethod.class, method).orElse(null);
        return switch (invoices.recordPayment(id, new PaymentDraft(chosen, amount, reference), admin.getName())) {
            case PaymentRecorded recorded ->
                    "redirect:/platform/invoices/" + id + "?done=" + (recorded.settled() ? "paid" : "payment");
            case Invalid invalid -> invoicePage(id, null, errors("platform.payment.", invalid), amount, model);
            case NotOpen notOpen -> invoicePage(id, null, Map.of("invoice", "platform.invoice.not-open"), amount, model);
        };
    }

    @PostMapping("/platform/invoices/{id}/void")
    String voidInvoice(@PathVariable UUID id, @RequestParam(required = false) String reason, Model model) {
        return switch (invoices.voidInvoice(id, reason)) {
            case Voided voided -> "redirect:/platform/invoices/" + id + "?done=voided";
            case ReasonRequired required ->
                    invoicePage(id, null, Map.of("reason", "platform.invoice.void.reason.required"), null, model);
            case NotOpen notOpen -> invoicePage(id, null, Map.of("invoice", "platform.invoice.not-open"), null, model);
        };
    }

    private String invoiceForm(UUID store, String description, String amount, String currency, String dueDate,
            Map<String, String> errors, Model model) {
        model.addAttribute("store", store);
        model.addAttribute("description", description);
        model.addAttribute("amount", amount);
        model.addAttribute("currency", currency);
        model.addAttribute("dueDate", dueDate);
        model.addAttribute("currencies", Currency.values());
        model.addAttribute("stores", storeNames.list(null));
        model.addAttribute("errors", errors);
        return "platformadmin/invoices/new";
    }

    private String invoicePage(UUID id, String done, Map<String, String> errors, String amount, Model model) {
        var invoice = invoices.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("invoice", invoice);
        model.addAttribute("store", storeNames.byId().get(invoice.storeId()));
        model.addAttribute("methods", InvoicePaymentMethod.values());
        model.addAttribute("amount", amount != null ? amount
                : invoice.due().amount().stripTrailingZeros().toPlainString());
        model.addAttribute("done", done != null && DONE.contains(done) ? "platform.invoice.done." + done : null);
        model.addAttribute("errors", errors);
        return "platformadmin/invoices/detail";
    }

    private static Map<String, String> errors(String prefix, Invalid invalid) {
        var errors = new LinkedHashMap<String, String>();
        for (FieldProblem problem : invalid.problems()) {
            errors.putIfAbsent(problem.field(), prefix + problem.field() + "." + problem.code());
        }
        return errors;
    }

    private static LocalDate date(String text) {
        try {
            return text == null || text.isBlank() ? null : LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
