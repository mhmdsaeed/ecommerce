package com.unispark.ecommerce.shared.internal.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

/**
 * Locale comes from the {@code lang} cookie, defaulting to Arabic (SPEC 11).
 * {@code ?lang=ar|en} switches it; the {@code /lang/{ar|en}} route arrives with the storefront in M5.
 */
@Configuration(proxyBeanMethods = false)
class LocaleConfig implements WebMvcConfigurer {

    static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("ar");
    static final List<Locale> SUPPORTED_LOCALES = List.of(DEFAULT_LOCALE, Locale.ENGLISH);
    static final String LANG_PARAM = "lang";

    /**
     * Declared explicitly because Boot only auto-configures a MessageSource when a base
     * {@code messages.properties} exists; we keep exactly two bundles, ar and en.
     */
    @Bean
    MessageSource messageSource() {
        var messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setDefaultLocale(DEFAULT_LOCALE);
        return messageSource;
    }

    @Bean
    LocaleResolver localeResolver() {
        var resolver = new CookieLocaleResolver("lang");
        resolver.setDefaultLocale(DEFAULT_LOCALE);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setCookieSameSite("Lax");
        resolver.setCookieHttpOnly(true);
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SupportedLocaleChangeInterceptor(localeResolver()));
    }

    /** Like Spring's {@code LocaleChangeInterceptor}, but ignores languages we have no bundle for. */
    record SupportedLocaleChangeInterceptor(LocaleResolver localeResolver) implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            var lang = request.getParameter(LANG_PARAM);
            if (lang != null) {
                SUPPORTED_LOCALES.stream()
                        .filter(locale -> locale.getLanguage().equals(lang))
                        .findFirst()
                        .ifPresent(locale -> localeResolver.setLocale(request, response, locale));
            }
            return true;
        }
    }
}
