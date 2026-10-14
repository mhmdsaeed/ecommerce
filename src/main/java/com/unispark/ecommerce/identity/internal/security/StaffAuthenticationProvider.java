package com.unispark.ecommerce.identity.internal.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.internal.Credentials.LoginIdentifier;
import com.unispark.ecommerce.identity.internal.LoginThrottle;
import com.unispark.ecommerce.identity.internal.StaffAccounts;
import com.unispark.ecommerce.identity.internal.StaffAccounts.StaffLogin;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreSlugs;
import com.unispark.ecommerce.tenant.StoreSummary;

/**
 * Staff login: store address + email or phone + password. The store is resolved first and the staff lookup runs
 * inside that store's tenant, so authentication never needs to see across tenants (and never bypasses RLS).
 *
 * <p>Unknown stores and unknown users still cost one BCrypt comparison, so response times do not reveal which
 * accounts exist.
 */
final class StaffAuthenticationProvider implements AuthenticationProvider {

    private final StoreLookup stores;
    private final StaffAccounts accounts;
    private final PasswordEncoder passwords;
    private final LoginThrottle throttle;
    private final String unknownUserHash;

    StaffAuthenticationProvider(StoreLookup stores, StaffAccounts accounts, PasswordEncoder passwords,
            LoginThrottle throttle) {
        this.stores = stores;
        this.accounts = accounts;
        this.passwords = passwords;
        this.throttle = throttle;
        this.unknownUserHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var details = authentication.getDetails() instanceof StaffLoginDetails given
                ? given
                : new StaffLoginDetails(null, "unknown");
        var slug = StoreSlugs.normalize(details.storeSlug());
        var identifier = LoginIdentifier.parse(authentication.getName());
        var password = String.valueOf(authentication.getCredentials());
        var account = slug + "/" + identifier.key();

        if (!throttle.tryLogin(details.remoteAddress(), account)) {
            throw new LoginThrottledException();
        }
        var store = stores.findBySlug(slug).filter(found -> found.status().hasStorefront());
        Optional<StaffLogin> staff = store.flatMap(
                found -> TenantContext.callAs(found.id(), () -> accounts.findForLogin(identifier)));
        if (staff.isEmpty()) {
            passwords.matches(password, unknownUserHash);
            throw new BadCredentialsException("Bad credentials");
        }
        var user = staff.get();
        if (!passwords.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("Bad credentials");
        }
        if (!user.active()) {
            throw new DisabledException("Account disabled");
        }
        throttle.loginSucceeded(account);
        return authenticated(user, store.get());
    }

    private static Authentication authenticated(StaffLogin user, StoreSummary store) {
        var principal = new StaffPrincipal(user.id(), store.id(), store.slug(), user.name(), user.role());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(user.role().authority())));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
