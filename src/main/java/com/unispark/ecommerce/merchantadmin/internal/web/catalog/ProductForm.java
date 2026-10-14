package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.unispark.ecommerce.catalog.Products.ProductDetails;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.shared.Currency;

/** Product details form. {@code price} is only used when creating: afterwards prices live on the variants. */
public class ProductForm {

    @Size(max = 200)
    private String nameAr;

    @Size(max = 200)
    private String nameEn;

    @Size(max = 5000)
    private String descriptionAr;

    @Size(max = 5000)
    private String descriptionEn;

    @Size(max = 80)
    private String slug;

    private UUID categoryId;

    @NotNull
    private Currency priceCurrency = Currency.SYP;

    @Size(max = 30)
    private String price;

    static ProductForm of(ProductDetails product) {
        var form = new ProductForm();
        form.nameAr = product.name().ar();
        form.nameEn = product.name().en();
        if (product.description() != null) {
            form.descriptionAr = product.description().ar();
            form.descriptionEn = product.description().en();
        }
        form.slug = product.slug();
        form.categoryId = product.categoryId();
        form.priceCurrency = product.priceCurrency();
        return form;
    }

    ProductDraft toDraft() {
        return new ProductDraft(nameAr, nameEn, descriptionAr, descriptionEn, slug, categoryId, priceCurrency);
    }

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

    public String getDescriptionAr() {
        return descriptionAr;
    }

    public void setDescriptionAr(String descriptionAr) {
        this.descriptionAr = descriptionAr;
    }

    public String getDescriptionEn() {
        return descriptionEn;
    }

    public void setDescriptionEn(String descriptionEn) {
        this.descriptionEn = descriptionEn;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public Currency getPriceCurrency() {
        return priceCurrency;
    }

    public void setPriceCurrency(Currency priceCurrency) {
        this.priceCurrency = priceCurrency;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }
}
