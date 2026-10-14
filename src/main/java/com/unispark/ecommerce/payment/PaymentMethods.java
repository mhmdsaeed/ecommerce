package com.unispark.ecommerce.payment;

import java.util.List;
import java.util.Optional;

import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;

/**
 * The current store's payment methods (SPEC 5, 9.3). Cash on delivery is on until the merchant turns it off; the
 * transfer methods are off until the merchant enables them with the account to pay into. At least one method stays
 * enabled.
 */
public interface PaymentMethods {

    int MAX_ACCOUNT_LENGTH = 100;
    int MAX_NAME_LENGTH = 60;
    int MAX_INSTRUCTIONS_LENGTH = 500;

    /** Every method, in {@link PaymentMethod} order, with its settings or the defaults. */
    List<MethodConfig> all();

    /** The enabled methods, in {@link PaymentMethod} order. */
    List<MethodConfig> enabled();

    Optional<MethodConfig> enabled(PaymentMethod method);

    MethodResult save(PaymentMethod method, MethodDraft draft);

    /**
     * @param displayName  the merchant's own name for the method, or null for the standard one
     * @param accountId    wallet number or IBAN, for transfers
     * @param instructions shown with the account after an order is placed, or null
     */
    record MethodConfig(PaymentMethod method, boolean enabled, LocalizedText displayName, String accountId,
            String accountName, LocalizedText instructions) {
    }

    record MethodDraft(boolean enabled, String displayNameAr, String displayNameEn, String accountId,
            String accountName, String instructionsAr, String instructionsEn) {
    }

    sealed interface MethodResult {
    }

    record Saved() implements MethodResult {
    }

    record Invalid(List<FieldProblem> problems) implements MethodResult {
    }
}
