package com.unispark.ecommerce.tenant;

import java.util.Locale;
import java.util.UUID;

import com.unispark.ecommerce.shared.LocalizedText;

/**
 * Creates stores. The caller chooses the store id so it can enter that tenant before the transaction starts and
 * create the store's first tenant rows (the owner) in the same transaction.
 */
public interface StoreRegistration {

    SlugCheck checkSlug(String slug);

    /**
     * Creates a TRIAL store. Joins the caller's transaction.
     *
     * @throws SlugUnavailableException if the slug is invalid, reserved or taken
     */
    StoreSummary register(NewStore store);

    /** Records who created the store, once that user exists. */
    void recordCreator(UUID storeId, UUID createdBy);

    record NewStore(UUID id, String slug, LocalizedText name, Locale defaultLocale, String contactPhone) {
    }

    enum SlugCheck {
        AVAILABLE,
        INVALID_FORMAT,
        RESERVED,
        TAKEN
    }

    final class SlugUnavailableException extends RuntimeException {

        private final SlugCheck reason;

        public SlugUnavailableException(String slug, SlugCheck reason) {
            super("Store slug '" + slug + "' is not available: " + reason);
            this.reason = reason;
        }

        public SlugCheck reason() {
            return reason;
        }
    }
}
