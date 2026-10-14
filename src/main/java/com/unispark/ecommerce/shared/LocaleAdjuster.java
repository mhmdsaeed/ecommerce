package com.unispark.ecommerce.shared;

import java.util.Locale;

/**
 * Lets other modules refine the locale resolved for a request, e.g. the tenant module adds the store's
 * {@link NumberDigits}. Implementations are called on every request and must be cheap.
 */
@FunctionalInterface
public interface LocaleAdjuster {

    Locale adjust(Locale locale);
}
