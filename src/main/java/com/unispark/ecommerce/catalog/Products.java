package com.unispark.ecommerce.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * The current store's products, for the merchant admin.
 *
 * <p>Every product has at least one variant. Without options it has a single default variant; defining options
 * (up to 3, at most {@value #MAX_VARIANTS} combinations) replaces the variants with one per combination, keeping
 * existing variants whose combination still exists. Prices live on variants, in the product's currency.
 *
 * <p>Amounts are taken as typed ({@code 150,000}, Arabic digits) and validated here.
 */
public interface Products {

    int MAX_OPTIONS = 3;
    int MAX_VALUES_PER_OPTION = 20;
    int MAX_VARIANTS = 100;
    int PAGE_SIZE = 25;

    ProductPage search(String query, ProductStatus status, int page);

    Optional<ProductDetails> find(UUID id);

    /** Creates a DRAFT product with one default variant at {@code price}. */
    ProductResult create(ProductDraft draft, String price);

    ProductResult update(UUID id, ProductDraft draft);

    /** Activating needs at least one active variant with a price above zero. Only drafts may be deleted. */
    ProductResult changeStatus(UUID id, ProductStatus status);

    ProductResult delete(UUID id);

    ProductResult defineOptions(UUID id, List<OptionDraft> options);

    ProductResult updateVariants(UUID id, List<VariantDraft> variants);

    /** Blank slug: generated from the name. */
    record ProductDraft(String nameAr, String nameEn, String descriptionAr, String descriptionEn, String slug,
            UUID categoryId, Currency priceCurrency) {
    }

    /** Comma-separated values; the English list, when given, must have as many values as the Arabic one. */
    record OptionDraft(String nameAr, String nameEn, String valuesAr, String valuesEn) {
    }

    record VariantDraft(UUID id, String sku, String price, String compareAtPrice, boolean active) {
    }

    record ProductPage(List<ProductSummary> items, int page, int totalPages, long totalItems) {

        public boolean hasPrevious() {
            return page > 0;
        }

        public boolean hasNext() {
            return page + 1 < totalPages;
        }
    }

    record ProductSummary(UUID id, LocalizedText name, String slug, ProductStatus status, Money lowestPrice,
            int variantCount, String imageKey, Instant updatedAt) {
    }

    record ProductDetails(UUID id, LocalizedText name, LocalizedText description, String slug, UUID categoryId,
            ProductStatus status, Currency priceCurrency, List<OptionView> options, List<VariantView> variants,
            List<ImageView> images) {

        public boolean hasOptions() {
            return !options.isEmpty();
        }
    }

    record OptionView(int position, LocalizedText name, List<LocalizedText> values) {
    }

    record VariantView(UUID id, List<String> optionValues, String sku, Money price, Money compareAtPrice,
            boolean active) {

        /** E.g. "M / أحمر"; empty for the default variant of a product without options. */
        public String label() {
            return String.join(" / ", optionValues);
        }
    }

    record ImageView(UUID id, String storageKey, int width, int height, int sortOrder) {
    }

    sealed interface ProductResult {
    }

    record Saved(UUID id) implements ProductResult {
    }

    record Invalid(List<FieldProblem> problems) implements ProductResult {
    }

    record NotFound() implements ProductResult {
    }
}
