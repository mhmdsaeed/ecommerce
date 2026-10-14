package com.unispark.ecommerce.catalog;

import java.util.List;
import java.util.UUID;

import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;

/**
 * The current store's categories. Two levels at most: a category may have a parent, but then it cannot have
 * children of its own. Categories with products or subcategories cannot be deleted.
 */
public interface Categories {

    /** Top-level categories in sort order, each followed by its children in sort order. */
    List<CategoryView> list();

    CategoryResult create(CategoryDraft draft);

    CategoryResult update(UUID id, CategoryDraft draft);

    CategoryResult setActive(UUID id, boolean active);

    CategoryResult delete(UUID id);

    /** Blank slug: generated from the name. No sort order: 0. */
    record CategoryDraft(String nameAr, String nameEn, String slug, UUID parentId, Integer sortOrder) {
    }

    record CategoryView(UUID id, LocalizedText name, String slug, UUID parentId, int sortOrder, boolean active,
            long productCount, int childCount) {

        public boolean topLevel() {
            return parentId == null;
        }
    }

    sealed interface CategoryResult {
    }

    record Saved(UUID id) implements CategoryResult {
    }

    record Invalid(List<FieldProblem> problems) implements CategoryResult {
    }

    record NotFound() implements CategoryResult {
    }

    /** Still has products or subcategories. */
    record InUse(long products, int children) implements CategoryResult {
    }
}
