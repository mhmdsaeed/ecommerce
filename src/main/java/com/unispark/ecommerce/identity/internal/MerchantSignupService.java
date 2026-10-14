package com.unispark.ecommerce.identity.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.unispark.ecommerce.identity.MerchantSignup;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.Ids;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.PhoneNumbers;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreRegistration;
import com.unispark.ecommerce.tenant.StoreRegistration.NewStore;
import com.unispark.ecommerce.tenant.StoreRegistration.SlugUnavailableException;
import com.unispark.ecommerce.tenant.StoreSlugs;

/**
 * Creates the store and its owner in one transaction. The store id is chosen first and entered as the tenant
 * before the transaction starts, so the owner row passes the new store's RLS policy on the same connection.
 */
@Service
class MerchantSignupService implements MerchantSignup {

    private final StoreRegistration stores;
    private final StaffUserRepository staff;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;
    private final LoginThrottle throttle;

    MerchantSignupService(StoreRegistration stores, StaffUserRepository staff, PasswordEncoder passwords,
            TransactionTemplate transaction, LoginThrottle throttle) {
        this.stores = stores;
        this.staff = staff;
        this.passwords = passwords;
        this.transaction = transaction;
        this.throttle = throttle;
    }

    @Override
    public SignupResult signUp(SignupRequest request) {
        if (!throttle.trySignup(request.clientAddress())) {
            return new TooManyAttempts();
        }
        var problems = new ArrayList<FieldProblem>();
        var slug = StoreSlugs.normalize(request.slug());
        switch (stores.checkSlug(slug)) {
            case AVAILABLE -> { }
            case INVALID_FORMAT -> problems.add(new FieldProblem("slug", "invalid"));
            case RESERVED -> problems.add(new FieldProblem("slug", "reserved"));
            case TAKEN -> problems.add(new FieldProblem("slug", "taken"));
        }
        var phone = PhoneNumbers.normalize(request.phone());
        if (phone.isEmpty()) {
            problems.add(new FieldProblem("phone", "invalid"));
        }
        String email = null;
        if (request.email() != null && !request.email().isBlank()) {
            var normalized = Credentials.normalizeEmail(request.email());
            if (normalized.isEmpty()) {
                problems.add(new FieldProblem("email", "invalid"));
            }
            email = normalized.orElse(null);
        }
        Credentials.passwordProblem(request.password())
                .ifPresent(code -> problems.add(new FieldProblem("password", code)));
        if (!problems.isEmpty()) {
            return new Rejected(List.copyOf(problems));
        }
        return create(request, slug, email, phone.get());
    }

    private SignupResult create(SignupRequest request, String slug, String email, String phone) {
        var storeId = Ids.newId();
        var storeName = localized(request.storeName().strip(), request.locale());
        var passwordHash = passwords.encode(request.password());
        try {
            return TenantContext.callAs(storeId, () -> transaction.execute(status -> {
                stores.register(new NewStore(storeId, slug, storeName, request.locale(), phone));
                var owner = staff.save(new StaffUser(
                        request.ownerName().strip(), email, phone, passwordHash, StaffRole.OWNER));
                stores.recordCreator(storeId, owner.getId());
                return new SignedUp(new StaffPrincipal(owner.getId(), storeId, slug, owner.getName(), StaffRole.OWNER));
            }));
        } catch (SlugUnavailableException e) {
            return new Rejected(List.of(new FieldProblem("slug", "taken")));
        }
    }

    /** The name is entered once, in the merchant's language; the other language falls back to it. */
    private static LocalizedText localized(String name, Locale locale) {
        return "en".equals(locale.getLanguage()) ? LocalizedText.of(null, name) : LocalizedText.of(name, null);
    }
}
