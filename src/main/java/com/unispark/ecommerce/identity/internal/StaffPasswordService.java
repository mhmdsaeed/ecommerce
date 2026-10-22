package com.unispark.ecommerce.identity.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.unispark.ecommerce.identity.ResetLinkSender.ResetLink;
import com.unispark.ecommerce.identity.StaffPasswords;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.internal.Credentials.Email;
import com.unispark.ecommerce.identity.internal.Credentials.LoginIdentifier;
import com.unispark.ecommerce.identity.internal.Credentials.Phone;
import com.unispark.ecommerce.identity.internal.Credentials.Unusable;
import com.unispark.ecommerce.identity.internal.persistence.PasswordReset;
import com.unispark.ecommerce.identity.internal.persistence.PasswordResetRepository;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.SecretTokens;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreSlugs;
import com.unispark.ecommerce.tenant.StoreSummary;

/**
 * Own password changes and reset links. Reset requests come from people who are not logged in, so there is no
 * tenant yet: each one finds the store by its slug and enters it before its transaction starts, like login, so the
 * connection is bound to that store and row-level security applies.
 */
@Service
class StaffPasswordService implements StaffPasswords {

    private final StoreLookup stores;
    private final StaffUserRepository staff;
    private final PasswordResetRepository resets;
    private final PasswordEncoder passwords;
    private final PasswordChanges passwordChanges;
    private final PasswordMessages messages;
    private final LoginThrottle throttle;
    private final TransactionTemplate transaction;
    private final Clock clock;

    StaffPasswordService(StoreLookup stores, StaffUserRepository staff, PasswordResetRepository resets,
            PasswordEncoder passwords, PasswordChanges passwordChanges, PasswordMessages messages,
            LoginThrottle throttle, TransactionTemplate transaction, Clock clock) {
        this.stores = stores;
        this.staff = staff;
        this.resets = resets;
        this.passwords = passwords;
        this.passwordChanges = passwordChanges;
        this.messages = messages;
        this.throttle = throttle;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Runs in the actor's store, which the session filter has entered. */
    @Override
    public PasswordResult change(StaffPrincipal actor, String currentPassword, String newPassword) {
        if (!actor.tenantId().equals(TenantContext.requireTenantId())) {
            throw new IllegalStateException("Staff actor does not belong to the current tenant");
        }
        return transaction.execute(status -> {
            var user = staff.findById(actor.staffId()).orElseThrow();
            var problems = new ArrayList<FieldProblem>();
            if (currentPassword == null || !passwords.matches(currentPassword, user.getPasswordHash())) {
                problems.add(new FieldProblem("currentPassword", "wrong"));
            }
            Credentials.passwordProblem(newPassword)
                    .ifPresent(code -> problems.add(new FieldProblem("newPassword", code)));
            if (!problems.isEmpty()) {
                return new Rejected(List.copyOf(problems));
            }
            passwordChanges.change(user, newPassword);
            return new Changed(principal(user, actor.tenantId(), actor.storeSlug()));
        });
    }

    @Override
    public void requestReset(String storeSlug, String emailOrPhone, String clientAddress, Locale locale) {
        var slug = StoreSlugs.normalize(storeSlug);
        var identifier = LoginIdentifier.parse(emailOrPhone);
        if (identifier instanceof Unusable || !throttle.tryResetRequest(clientAddress, slug + "/" + identifier.key())) {
            return;
        }
        // Everything after the throttle runs on another thread: the answer takes as long whether or not the store and
        // the account exist, and whether or not a link is made and sent.
        messages.inBackground(() -> createLink(slug, identifier, locale));
    }

    private void createLink(String slug, LoginIdentifier identifier, Locale locale) {
        var store = openStore(slug);
        if (store.isEmpty()) {
            return;
        }
        TenantContext.runAs(store.get().id(), () -> {
            var link = transaction.execute(status -> {
                var user = switch (identifier) {
                    case Email(var email) -> staff.findByEmail(email);
                    case Phone(var phone) -> staff.findByPhone(phone);
                    case Unusable unusable -> Optional.<StaffUser>empty();
                };
                if (user.isEmpty() || !user.get().isActive()) {
                    return null;
                }
                // Only the newest link works: asking again closes the ones before it. The member's row is locked first,
                // so two requests at once take turns instead of both closing the same link.
                staff.lockById(user.get().getId());
                passwordChanges.closeLinks(user.get().getId());
                var token = SecretTokens.newToken();
                resets.save(new PasswordReset(user.get().getId(), SecretTokens.hash(token),
                        clock.instant().plus(LINK_LIFETIME)));
                var name = store.get().name().in(locale);
                return identifier instanceof Email(var email)
                        ? new ResetLink(email, null, slug, token, name, locale)
                        : new ResetLink(null, ((Phone) identifier).value(), slug, token, name, locale);
            });
            // After the commit, so the link works by the time it arrives.
            if (link != null) {
                messages.resetLink(link);
            }
        });
    }

    @Override
    public boolean resetLinkUsable(String storeSlug, String token) {
        var store = openStore(StoreSlugs.normalize(storeSlug));
        if (store.isEmpty() || !SecretTokens.isWellFormed(token)) {
            return false;
        }
        return Boolean.TRUE.equals(TenantContext.callAs(store.get().id(),
                () -> transaction.execute(status -> usableReset(token).isPresent())));
    }

    @Override
    public PasswordResult reset(String storeSlug, String token, String newPassword) {
        var slug = StoreSlugs.normalize(storeSlug);
        var store = openStore(slug);
        if (store.isEmpty() || !SecretTokens.isWellFormed(token)) {
            return new LinkUnusable();
        }
        var outcome = TenantContext.callAs(store.get().id(), () -> transaction.execute(status -> {
            var reset = usableReset(token);
            if (reset.isEmpty()) {
                return new Outcome(new LinkUnusable(), List.of());
            }
            var problem = Credentials.passwordProblem(newPassword);
            if (problem.isPresent()) {
                return new Outcome(new Rejected(List.of(new FieldProblem("newPassword", problem.get()))), List.of());
            }
            var user = staff.findById(reset.get().getStaffId()).orElseThrow();
            // Also closes this link, like every other one still open for the member.
            passwordChanges.change(user, newPassword);
            var accounts = Stream.of(user.getEmail(), user.getPhone()).filter(Objects::nonNull)
                    .map(contact -> slug + "/" + contact).toList();
            return new Outcome(new Changed(principal(user, store.get().id(), slug)), accounts);
        }));
        // Whoever proved they own the email or phone may log in again at once, even after failed attempts.
        outcome.loginAccounts().forEach(throttle::loginSucceeded);
        return outcome.result();
    }

    /** @param loginAccounts the member's login throttle keys, to clear after a reset */
    private record Outcome(PasswordResult result, List<String> loginAccounts) {
    }

    /** A link that is unused, unexpired, and for a member who is still active. */
    private Optional<PasswordReset> usableReset(String token) {
        var now = clock.instant();
        return resets.findByTokenHash(SecretTokens.hash(token))
                .filter(reset -> reset.usableAt(now))
                .filter(reset -> staff.findById(reset.getStaffId()).filter(StaffUser::isActive).isPresent());
    }

    /** Closed stores have no admin to log in to, so no resets either. */
    private Optional<StoreSummary> openStore(String slug) {
        return stores.findBySlug(slug).filter(found -> found.status().hasStorefront());
    }

    private static StaffPrincipal principal(StaffUser user, UUID storeId, String slug) {
        return new StaffPrincipal(user.getId(), storeId, slug, user.getName(), user.getRole(),
                user.getCredentialsVersion());
    }
}
