package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.OptionDraft;
import com.unispark.ecommerce.catalog.Products.ProductDetails;
import com.unispark.ecommerce.catalog.Products.VariantDraft;
import com.unispark.ecommerce.shared.Money;

/**
 * The options and variants editor: always {@link Products#MAX_OPTIONS} option rows (blank rows mean "no
 * option") and one row per variant. Two forms on the page bind to it: options, then variants.
 */
public class VariantsForm {

    private List<OptionRow> options = new ArrayList<>();
    private List<VariantRow> variants = new ArrayList<>();

    static VariantsForm of(ProductDetails product) {
        var form = new VariantsForm();
        for (var option : product.options()) {
            var row = new OptionRow();
            row.nameAr = option.name().ar();
            row.nameEn = option.name().en();
            row.valuesAr = join(option.values().stream().map(value -> value.ar()).toList());
            row.valuesEn = join(option.values().stream().map(value -> value.en()).toList());
            form.options.add(row);
        }
        form.padOptions();
        for (var variant : product.variants()) {
            var row = new VariantRow();
            row.id = variant.id();
            row.label = variant.label();
            row.sku = variant.sku();
            row.price = plain(variant.price());
            row.compareAtPrice = variant.compareAtPrice() == null ? null : plain(variant.compareAtPrice());
            row.active = variant.active();
            form.variants.add(row);
        }
        return form;
    }

    /** Keeps the editor at three rows after binding a partial submission. */
    void padOptions() {
        while (options.size() < Products.MAX_OPTIONS) {
            options.add(new OptionRow());
        }
    }

    List<OptionDraft> optionDrafts() {
        return options.stream().map(row -> new OptionDraft(row.nameAr, row.nameEn, row.valuesAr, row.valuesEn)).toList();
    }

    List<VariantDraft> variantDrafts() {
        return variants.stream()
                .map(row -> new VariantDraft(row.id, row.sku, row.price, row.compareAtPrice, row.active))
                .toList();
    }

    /** Only when every value has that language; a partial list would misalign the values. */
    private static String join(List<String> values) {
        return values.stream().anyMatch(value -> value == null) ? null : String.join(", ", values);
    }

    /** 150000.00 becomes "150000", 12.50 stays "12.50": what a merchant would type. */
    static String plain(Money money) {
        var amount = money.amount();
        return amount.stripTrailingZeros().scale() <= 0 ? amount.toBigInteger().toString() : amount.toPlainString();
    }

    public List<OptionRow> getOptions() {
        return options;
    }

    public void setOptions(List<OptionRow> options) {
        this.options = options;
    }

    public List<VariantRow> getVariants() {
        return variants;
    }

    public void setVariants(List<VariantRow> variants) {
        this.variants = variants;
    }

    public static class OptionRow {

        private String nameAr;
        private String nameEn;
        private String valuesAr;
        private String valuesEn;

        public String getNameAr() {
            return nameAr;
        }

        public void setNameAr(String nameAr) {
            this.nameAr = nameAr;
        }

        public String getNameEn() {
            return nameEn;
        }

        public void setNameEn(String nameEn) {
            this.nameEn = nameEn;
        }

        public String getValuesAr() {
            return valuesAr;
        }

        public void setValuesAr(String valuesAr) {
            this.valuesAr = valuesAr;
        }

        public String getValuesEn() {
            return valuesEn;
        }

        public void setValuesEn(String valuesEn) {
            this.valuesEn = valuesEn;
        }
    }

    public static class VariantRow {

        private UUID id;
        private String label;
        private String sku;
        private String price;
        private String compareAtPrice;
        private boolean active;

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getSku() {
            return sku;
        }

        public void setSku(String sku) {
            this.sku = sku;
        }

        public String getPrice() {
            return price;
        }

        public void setPrice(String price) {
            this.price = price;
        }

        public String getCompareAtPrice() {
            return compareAtPrice;
        }

        public void setCompareAtPrice(String compareAtPrice) {
            this.compareAtPrice = compareAtPrice;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }
    }
}
