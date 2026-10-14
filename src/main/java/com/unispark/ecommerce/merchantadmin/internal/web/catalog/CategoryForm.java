package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.unispark.ecommerce.catalog.Categories.CategoryDraft;
import com.unispark.ecommerce.catalog.Categories.CategoryView;

/** Category form backing object. One of the two names is required; the service checks that. */
public class CategoryForm {

    @Size(max = 100)
    private String nameAr;

    @Size(max = 100)
    private String nameEn;

    @Size(max = 80)
    private String slug;

    private UUID parentId;

    @Min(0)
    @Max(9999)
    private Integer sortOrder;

    static CategoryForm of(CategoryView category) {
        var form = new CategoryForm();
        form.nameAr = category.name().ar();
        form.nameEn = category.name().en();
        form.slug = category.slug();
        form.parentId = category.parentId();
        form.sortOrder = category.sortOrder();
        return form;
    }

    CategoryDraft toDraft() {
        return new CategoryDraft(nameAr, nameEn, slug, parentId, sortOrder);
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

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
