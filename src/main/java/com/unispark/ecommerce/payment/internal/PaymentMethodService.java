package com.unispark.ecommerce.payment.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.media.ImageDiscarded;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.internal.persistence.PaymentMethodConfig;
import com.unispark.ecommerce.payment.internal.persistence.PaymentMethodConfigRepository;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantCache;

/** Payment methods. The enabled ones show on every product page, so they are cached per store and evicted on change. */
@Service
@Transactional
class PaymentMethodService implements PaymentMethods {

    private final PaymentMethodConfigRepository configs;
    private final ApplicationEventPublisher events;
    private final TenantCache<List<MethodConfig>> enabled = new TenantCache<>();

    PaymentMethodService(PaymentMethodConfigRepository configs, ApplicationEventPublisher events) {
        this.configs = configs;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MethodConfig> all() {
        var saved = configs.findAll().stream()
                .collect(Collectors.toMap(PaymentMethodConfig::getMethod, Function.identity()));
        return Arrays.stream(PaymentMethod.values()).map(method -> view(method, saved)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MethodConfig> enabled() {
        return enabled.get(() -> all().stream().filter(MethodConfig::enabled).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MethodConfig> enabled(PaymentMethod method) {
        return enabled().stream().filter(config -> config.method() == method).findFirst();
    }

    @Override
    public MethodResult save(PaymentMethod method, MethodDraft draft) {
        var problems = new ArrayList<FieldProblem>();
        var accountId = text(draft.accountId(), "accountId", MAX_ACCOUNT_LENGTH, problems);
        var accountName = text(draft.accountName(), "accountName", MAX_ACCOUNT_LENGTH, problems);
        var nameAr = text(draft.displayNameAr(), "displayNameAr", MAX_NAME_LENGTH, problems);
        var nameEn = text(draft.displayNameEn(), "displayNameEn", MAX_NAME_LENGTH, problems);
        var instructionsAr = text(draft.instructionsAr(), "instructionsAr", MAX_INSTRUCTIONS_LENGTH, problems);
        var instructionsEn = text(draft.instructionsEn(), "instructionsEn", MAX_INSTRUCTIONS_LENGTH, problems);
        if (draft.enabled() && method.isTransfer()) {
            if (accountId == null) {
                problems.add(new FieldProblem("accountId", "required"));
            }
            if (accountName == null) {
                problems.add(new FieldProblem("accountName", "required"));
            }
        }
        if (!draft.enabled() && enabled().stream().allMatch(config -> config.method() == method)) {
            problems.add(new FieldProblem("enabled", "last-method"));
        }
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        var config = configs.findByMethod(method).orElseGet(() -> new PaymentMethodConfig(method));
        config.update(draft.enabled(), localized(nameAr, nameEn), accountId, accountName,
                localized(instructionsAr, instructionsEn));
        configs.save(config);
        enabled.evict();
        return new Saved();
    }

    @Override
    public void setQr(PaymentMethod method, String imageKey) {
        var config = configs.findByMethod(method).orElseGet(() -> {
            var created = new PaymentMethodConfig(method);
            created.update(!method.isTransfer(), null, null, null, null);
            return created;
        });
        var previous = config.getQrImageKey();
        config.setQrImageKey(imageKey);
        configs.save(config);
        enabled.evict();
        if (previous != null && !previous.equals(imageKey)) {
            events.publishEvent(new ImageDiscarded(previous));
        }
    }

    /** Without a saved row, cash on delivery is on and transfers are off. */
    private static MethodConfig view(PaymentMethod method, Map<PaymentMethod, PaymentMethodConfig> saved) {
        var config = saved.get(method);
        if (config == null) {
            return new MethodConfig(method, !method.isTransfer(), null, null, null, null, null);
        }
        return new MethodConfig(method, config.isEnabled(), config.getDisplayName(), config.getAccountId(),
                config.getAccountName(), config.getInstructions(), config.getQrImageKey());
    }

    /** Free text kept as typed (instructions keep their line breaks), but trimmed; null when blank. */
    private static String text(String input, String field, int maxLength, List<FieldProblem> problems) {
        if (input == null || input.isBlank()) {
            return null;
        }
        var value = input.strip();
        if (value.length() > maxLength) {
            problems.add(new FieldProblem(field, "too-long"));
        }
        return value;
    }

    private static LocalizedText localized(String ar, String en) {
        return ar == null && en == null ? null : LocalizedText.of(ar, en);
    }
}
