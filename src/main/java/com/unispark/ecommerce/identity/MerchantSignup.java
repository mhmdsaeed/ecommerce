package com.unispark.ecommerce.identity;

import java.util.List;
import java.util.Locale;

import com.unispark.ecommerce.shared.FieldProblem;

/**
 * Merchant signup (SPEC 1, 9.3): creates a TRIAL store and its owner in one transaction.
 * Callers validate field presence and length; this checks the business rules (slug, phone, email, password).
 */
public interface MerchantSignup {

    SignupResult signUp(SignupRequest request);

    /** {@code clientAddress} feeds the per-address signup rate limit. */
    record SignupRequest(
            String storeName, String slug, String ownerName, String email, String phone, String password,
            Locale locale, String clientAddress) {
    }

    sealed interface SignupResult {
    }

    record SignedUp(StaffPrincipal owner) implements SignupResult {
    }

    record Rejected(List<FieldProblem> problems) implements SignupResult {
    }

    record TooManyAttempts() implements SignupResult {
    }
}
