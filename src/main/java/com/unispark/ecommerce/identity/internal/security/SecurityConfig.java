package com.unispark.ecommerce.identity.internal.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.LoginThrottle;
import com.unispark.ecommerce.identity.internal.StaffAccounts;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;
import com.unispark.ecommerce.tenant.Site;
import com.unispark.ecommerce.tenant.Sites;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * One security filter chain per area (SPEC 10), each with its own login and authentication manager:
 * <ol>
 *   <li>platform admin: every request on {@code admin.<base>}; platform admins only, under {@code /platform};
 *   <li>merchant admin: {@code /admin/**} on the main site; owners, managers and staff;
 *   <li>driver: {@code /driver/**} on the main site; drivers;
 *   <li>site: everything else (storefronts, signup, the main site's public pages); open, but an area's paths
 *       answer 404 on the wrong host.
 * </ol>
 * Sessions are per host (no cookie domain), and roles are checked per chain, so a session from one area grants
 * nothing in another even if its cookie is replayed there. CSRF protection is on in every chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    static final String[] PUBLIC_ASSETS = {"/css/**", "/js/**", "/images/**", "/favicon.ico", "/error"};

    private static final AuthenticationEntryPoint NOT_FOUND_ENTRY =
            (request, response, exception) -> response.sendError(HttpServletResponse.SC_NOT_FOUND);
    /** Wrong-host areas look like missing pages; CSRF failures keep their 403 so a stale form is not a 404. */
    private static final AccessDeniedHandler NOT_FOUND_DENIED = new DelegatingAccessDeniedHandler(
            new LinkedHashMap<>(Map.of(CsrfException.class, new AccessDeniedHandlerImpl())),
            (request, response, exception) -> response.sendError(HttpServletResponse.SC_NOT_FOUND));

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Order(1)
    SecurityFilterChain platformAdminChain(HttpSecurity http, Sites sites, PlatformUserRepository users,
            PasswordEncoder passwords, LoginThrottle throttle) throws Exception {
        http.securityMatcher(request -> sites.siteOf(request) == Site.PLATFORM_ADMIN)
                .authenticationManager(new ProviderManager(
                        new PlatformAuthenticationProvider(users, passwords, throttle)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_ASSETS).permitAll()
                        .requestMatchers("/platform/login").permitAll()
                        .requestMatchers("/", "/platform", "/platform/**").hasRole("PLATFORM_ADMIN")
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        .loginPage("/platform/login")
                        .defaultSuccessUrl("/platform", true)
                        .failureHandler(new LoginFailureHandler("/platform/login")))
                .logout(logout -> logout
                        .logoutUrl("/platform/logout")
                        .logoutSuccessUrl("/platform/login?logout"))
                .addFilterAfter(new RedirectRoot("/platform"), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain merchantAdminChain(HttpSecurity http, Sites sites, StoreLookup stores,
            StaffAccounts accounts, PasswordEncoder passwords, LoginThrottle throttle,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) throws Exception {
        return staffChain(http, sites, "/admin", stores, accounts, passwords, throttle, secureCookies,
                StaffRole.OWNER, StaffRole.MANAGER, StaffRole.STAFF);
    }

    @Bean
    @Order(3)
    SecurityFilterChain driverChain(HttpSecurity http, Sites sites, StoreLookup stores,
            StaffAccounts accounts, PasswordEncoder passwords, LoginThrottle throttle,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) throws Exception {
        return staffChain(http, sites, "/driver", stores, accounts, passwords, throttle, secureCookies,
                StaffRole.DRIVER);
    }

    @Bean
    @Order(4)
    SecurityFilterChain siteChain(HttpSecurity http, Sites sites) throws Exception {
        RequestMatcher storefront = request -> sites.siteOf(request) == Site.STOREFRONT;
        http.authorizeHttpRequests(authorize -> authorize
                        // Reaching this chain means the wrong host for these areas.
                        .requestMatchers("/admin/**", "/driver/**", "/platform/**").denyAll()
                        .requestMatchers(new AndRequestMatcher(storefront, path("/signup/**"))).denyAll()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(NOT_FOUND_ENTRY)
                        .accessDeniedHandler(NOT_FOUND_DENIED));
        return http.build();
    }

    private static SecurityFilterChain staffChain(HttpSecurity http, Sites sites, String area, StoreLookup stores,
            StaffAccounts accounts, PasswordEncoder passwords, LoginThrottle throttle, boolean secureCookies,
            StaffRole... roles) throws Exception {
        var loginUrl = area + "/login";
        var roleNames = Arrays.stream(roles).map(Enum::name).toArray(String[]::new);
        http.securityMatcher(new AndRequestMatcher(
                        request -> sites.siteOf(request) == Site.MAIN, path(area + "/**")))
                .authenticationManager(new ProviderManager(
                        new StaffAuthenticationProvider(stores, accounts, passwords, throttle)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(loginUrl).permitAll()
                        .anyRequest().hasAnyRole(roleNames))
                .formLogin(form -> form
                        .loginPage(loginUrl)
                        .authenticationDetailsSource(StaffLoginDetails.SOURCE)
                        .successHandler(new StaffLoginSuccessHandler(secureCookies))
                        .failureHandler(new LoginFailureHandler(loginUrl)))
                .logout(logout -> logout
                        .logoutUrl(area + "/logout")
                        .logoutSuccessUrl(loginUrl + "?logout"))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(HtmxAware.entryPoint(loginUrl))
                        .accessDeniedHandler(HtmxAware.accessDenied(loginUrl)))
                .addFilterAfter(new StaffSessionFilter(accounts, stores, loginUrl), AuthorizationFilter.class);
        return http.build();
    }

    private static RequestMatcher path(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(pattern);
    }

    /** {@code admin.<base>/} has no page of its own; send platform admins to their dashboard. */
    private static final class RedirectRoot extends OncePerRequestFilter {

        private final String target;

        RedirectRoot(String target) {
            this.target = target;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ("/".equals(request.getRequestURI().substring(request.getContextPath().length()))) {
                response.sendRedirect(request.getContextPath() + target);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
