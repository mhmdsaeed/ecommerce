package com.unispark.ecommerce.merchantadmin.internal.web.settings;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.unispark.ecommerce.identity.StaffManagement;
import com.unispark.ecommerce.identity.StaffManagement.StaffMember;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.pricing.CurrencyConversion;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.pricing.ExchangeRates.ExchangeRate;
import com.unispark.ecommerce.pricing.ExchangeRates.Invalid;
import com.unispark.ecommerce.pricing.PriceFormatter;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Htmx;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.NumberDigits;
import com.unispark.ecommerce.shared.RoundingRule;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Store settings: pricing (SPEC 7, 9.3). Currencies, rounding and digits are one form; the exchange rate has its
 * own HTMX section with history and a preview of how converted prices will look. Owners and managers only.
 */
@Controller
@RequestMapping("/admin/settings")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class PricingSettingsController {

    static final String PAGE = "merchantadmin/settings/pricing";
    static final String RATE_SECTION = PAGE + " :: rate";
    static final int HISTORY = 10;
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Damascus");
    static final List<Money> PREVIEW = List.of(
            Money.of(new BigDecimal("1"), Currency.USD), Money.of(new BigDecimal("4.50"), Currency.USD),
            Money.of(new BigDecimal("25"), Currency.USD), Money.of(new BigDecimal("1000"), Currency.SYP),
            Money.of(new BigDecimal("15000"), Currency.SYP));

    private final StoreSettings settings;
    private final ExchangeRates rates;
    private final PriceFormatter formatter;
    private final StaffManagement staff;

    PricingSettingsController(StoreSettings settings, ExchangeRates rates, PriceFormatter formatter,
            StaffManagement staff) {
        this.settings = settings;
        this.rates = rates;
        this.formatter = formatter;
        this.staff = staff;
    }

    /** One line of the rate history: the rate (pounds per dollar), when, and who set it. */
    record RateRow(String rate, String setAt, String setBy) {
    }

    /** A price as stored, and as shoppers of a store with the other display currency see it. */
    record Example(String stored, String shown) {
    }

    @GetMapping
    String index() {
        return "redirect:/admin/settings/pricing";
    }

    @GetMapping("/pricing")
    String page(Model model, Locale locale) {
        model.addAttribute("form", PricingForm.of(settings.current()));
        return render(model, locale);
    }

    @PostMapping("/pricing")
    String save(@Valid @ModelAttribute("form") PricingForm form, BindingResult binding, Model model, Locale locale) {
        if (binding.hasErrors()) {
            return render(model, locale);
        }
        settings.updatePricing(form.getBaseCurrency(), form.getDisplayCurrency(), form.getRoundingRule(),
                form.getNumberDigits());
        return "redirect:/admin/settings/pricing?saved";
    }

    @PostMapping("/pricing/rate")
    String setRate(@RequestParam(defaultValue = "") String rate, @AuthenticationPrincipal StaffPrincipal actor,
            Model model, Locale locale, HttpServletRequest request) {
        var result = rates.set(rate, actor.staffId());
        if (result instanceof Invalid(var problem)) {
            model.addAttribute("rateError", "settings.rate." + problem.code());
            model.addAttribute("rateInput", rate);
        } else if (!Htmx.isHtmx(request)) {
            return "redirect:/admin/settings/pricing?rate";
        } else {
            model.addAttribute("rateNotice", "settings.rate.saved");
        }
        if (Htmx.isHtmx(request)) {
            rateModel(model, locale);
            return RATE_SECTION;
        }
        model.addAttribute("form", PricingForm.of(settings.current()));
        return render(model, locale);
    }

    private String render(Model model, Locale locale) {
        model.addAttribute("currencies", Currency.values());
        model.addAttribute("roundingRules", RoundingRule.values());
        model.addAttribute("digitOptions", NumberDigits.values());
        rateModel(model, locale);
        return PAGE;
    }

    private void rateModel(Model model, Locale locale) {
        var names = staffNames();
        var dates = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale).withZone(DISPLAY_ZONE);
        var current = rates.current();
        model.addAttribute("currentRate", current.map(rate -> row(rate, names, dates, locale)).orElse(null));
        model.addAttribute("history", rates.history(HISTORY).stream().map(rate -> row(rate, names, dates, locale)).toList());
        model.addAttribute("examples", current.map(rate -> examples(rate, locale)).orElse(List.of()));
    }

    private RateRow row(ExchangeRate rate, Map<UUID, String> names, DateTimeFormatter dates, Locale locale) {
        return new RateRow(formatter.rate(rate.sypPerUsd(), locale), dates.format(rate.effectiveAt()),
                names.getOrDefault(rate.setBy(), ""));
    }

    private List<Example> examples(ExchangeRate rate, Locale locale) {
        var rounding = settings.current().roundingRule();
        var examples = new ArrayList<Example>();
        for (var price : PREVIEW) {
            var other = price.currency() == Currency.USD ? Currency.SYP : Currency.USD;
            var shown = CurrencyConversion.convert(price, other, rate.sypPerUsd(), rounding);
            examples.add(new Example(formatter.format(price, locale), formatter.format(shown, locale)));
        }
        return examples;
    }

    private Map<UUID, String> staffNames() {
        return staff.list().stream().collect(Collectors.toMap(StaffMember::id, StaffMember::name));
    }
}
