package com.unispark.ecommerce.shared;

import java.util.Locale;
import java.util.Optional;

/**
 * The language to use when the visitor has not chosen one (no {@code lang} cookie), e.g. the store's default
 * language on its storefront (SPEC 11). Empty means "the platform default", Arabic.
 */
@FunctionalInterface
public interface DefaultLocaleSource {

    Optional<Locale> defaultLocale();
}
