package com.unispark.ecommerce.identity.internal.security;

import java.util.Locale;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import com.unispark.ecommerce.identity.internal.LoginThrottle;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUser;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;

/** Platform admin login (email + password) with the same rate limits as staff login. */
final class PlatformAuthenticationProvider implements AuthenticationProvider {

    private final DaoAuthenticationProvider delegate;
    private final LoginThrottle throttle;

    PlatformAuthenticationProvider(PlatformUserRepository users, PasswordEncoder passwords, LoginThrottle throttle) {
        this.delegate = new DaoAuthenticationProvider(userDetails(users));
        this.delegate.setPasswordEncoder(passwords);
        this.throttle = throttle;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var address = authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress()
                : "unknown";
        var account = "platform/" + authentication.getName().strip().toLowerCase(Locale.ROOT);
        if (!throttle.tryLogin(address, account)) {
            throw new LoginThrottledException();
        }
        var result = delegate.authenticate(authentication);
        throttle.loginSucceeded(account);
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static UserDetailsService userDetails(PlatformUserRepository users) {
        return email -> users.findByEmail(email.strip().toLowerCase(Locale.ROOT))
                .map(user -> User.withUsername(user.getEmail())
                        .password(user.getPasswordHash())
                        .roles(PlatformUser.PLATFORM_ADMIN)
                        .disabled(!user.isActive())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown platform user"));
    }
}
