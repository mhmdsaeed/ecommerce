package com.unispark.ecommerce.shared.internal.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import com.unispark.ecommerce.shared.DefaultLocaleSource;
import com.unispark.ecommerce.shared.LocaleAdjuster;

/**
 * Locale comes from the {@code lang} cookie, else a {@link DefaultLocaleSource} (the store's language), else Arabic
 * (SPEC 11); then {@link LocaleAdjuster}s refine it (the store's digits). {@code ?lang=ar|en} switches it, and so
 * does the storefront's {@code /lang/{ar|en}} route.
 */
@Configuration(proxyBeanMethods = false)
class LocaleConfig {

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
    LocaleResolver localeResolver(ObjectProvider<LocaleAdjuster> adjusters,
            ObjectProvider<DefaultLocaleSource> defaults) {
        var cookie = new CookieLocaleResolver("lang");
        cookie.setDefaultLocale(DEFAULT_LOCALE);
        cookie.setDefaultLocaleFunction(request -> defaults.orderedStream()
                .map(DefaultLocaleSource::defaultLocale)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(DEFAULT_LOCALE));
        cookie.setCookieMaxAge(Duration.ofDays(365));
        cookie.setCookieSameSite("Lax");
        cookie.setCookieHttpOnly(true);
        return new AdjustingLocaleResolver(cookie, adjusters);
    }

    @Bean
    WebMvcConfigurer localeChangeInterceptor(LocaleResolver localeResolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new SupportedLocaleChangeInterceptor(localeResolver));
            }
        };
    }

    /** The cookie keeps only the language; adjustments (such as digits) are applied on every resolve. */
    record AdjustingLocaleResolver(LocaleResolver delegate, ObjectProvider<LocaleAdjuster> adjusters)
            implements LocaleResolver {

        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            var locale = delegate.resolveLocale(request);
            for (var adjuster : adjusters) {
                locale = adjuster.adjust(locale);
            }
            return locale;
        }

        @Override
        public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
            delegate.setLocale(request, response, locale == null ? null : Locale.forLanguageTag(locale.getLanguage()));
        }
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
