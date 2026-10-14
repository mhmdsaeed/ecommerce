package com.unispark.ecommerce.catalog.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.catalog.Categories;
import com.unispark.ecommerce.catalog.internal.persistence.Category;
import com.unispark.ecommerce.catalog.internal.persistence.CategoryRepository;
import com.unispark.ecommerce.catalog.internal.persistence.ProductRepository;
import com.unispark.ecommerce.catalog.internal.persistence.ProductRepository.CategoryCount;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;

@Service
@Transactional
class CategoryService implements Categories {

    private static final Comparator<Category> DISPLAY_ORDER = Comparator.comparingInt(Category::getSortOrder)
            .thenComparing(category -> Texts.slugSource(category.getName()));

    private final CategoryRepository categories;
    private final ProductRepository products;

    CategoryService(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryView> list() {
        var all = categories.findAll();
        var productCounts = products.countByCategory().stream()
                .collect(Collectors.toMap(CategoryCount::getCategoryId, CategoryCount::getProducts));
        var children = all.stream().filter(category -> category.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId));
        var result = new ArrayList<CategoryView>();
        all.stream().filter(category -> category.getParentId() == null).sorted(DISPLAY_ORDER).forEach(parent -> {
            var own = children.getOrDefault(parent.getId(), List.of());
            result.add(view(parent, productCounts, own.size()));
            own.stream().sorted(DISPLAY_ORDER).forEach(child -> result.add(view(child, productCounts, 0)));
        });
        return List.copyOf(result);
    }

    @Override
    public CategoryResult create(CategoryDraft draft) {
        var problems = new ArrayList<FieldProblem>();
        var checked = check(draft, null, problems);
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        var category = categories.save(new Category(checked.name(), checked.slug(), draft.parentId(), checked.sortOrder()));
        return new Saved(category.getId());
    }

    @Override
    public CategoryResult update(UUID id, CategoryDraft draft) {
        var existing = categories.findById(id);
        if (existing.isEmpty()) {
            return new NotFound();
        }
        var problems = new ArrayList<FieldProblem>();
        var checked = check(draft, existing.get(), problems);
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        existing.get().update(checked.name(), checked.slug(), draft.parentId(), checked.sortOrder());
        return new Saved(id);
    }

    @Override
    public CategoryResult setActive(UUID id, boolean active) {
        return categories.findById(id).<CategoryResult>map(category -> {
            category.setActive(active);
            return new Saved(id);
        }).orElseGet(NotFound::new);
    }

    @Override
    public CategoryResult delete(UUID id) {
        var category = categories.findById(id);
        if (category.isEmpty()) {
            return new NotFound();
        }
        var productCount = products.countByCategoryId(id);
        var childCount = categories.countByParentId(id);
        if (productCount > 0 || childCount > 0) {
            return new InUse(productCount, childCount);
        }
        categories.delete(category.get());
        return new Saved(id);
    }

    private record Checked(LocalizedText name, String slug, int sortOrder) {
    }

    private Checked check(CategoryDraft draft, Category existing, List<FieldProblem> problems) {
        var name = Texts.localizedOrNull(draft.nameAr(), draft.nameEn());
        if (name == null) {
            problems.add(new FieldProblem("nameAr", "required"));
        }
        var sortOrder = draft.sortOrder() == null ? 0 : draft.sortOrder();
        if (sortOrder < 0 || sortOrder > 9999) {
            problems.add(new FieldProblem("sortOrder", "range"));
        }
        checkParent(draft.parentId(), existing, problems);

        var id = existing == null ? null : existing.getId();
        String slug = null;
        if (!Texts.isBlank(draft.slug())) {
            slug = Slugs.normalize(draft.slug());
            if (!Slugs.isValid(slug)) {
                problems.add(new FieldProblem("slug", "invalid"));
            } else if (id == null ? categories.existsBySlug(slug) : categories.existsBySlugAndIdNot(slug, id)) {
                problems.add(new FieldProblem("slug", "taken"));
            }
        } else if (existing != null) {
            slug = existing.getSlug();
        } else if (name != null) {
            slug = Slugs.unique(Slugs.fromName(Texts.slugSource(name), "category"), categories::existsBySlug);
        }
        return new Checked(name, slug, sortOrder);
    }

    private void checkParent(UUID parentId, Category existing, List<FieldProblem> problems) {
        if (parentId == null) {
            return;
        }
        if (existing != null && parentId.equals(existing.getId())) {
            problems.add(new FieldProblem("parentId", "invalid"));
            return;
        }
        var parent = categories.findById(parentId);
        if (parent.isEmpty()) {
            problems.add(new FieldProblem("parentId", "invalid"));
        } else if (parent.get().getParentId() != null) {
            problems.add(new FieldProblem("parentId", "too-deep"));
        } else if (existing != null && categories.countByParentId(existing.getId()) > 0) {
            problems.add(new FieldProblem("parentId", "has-children"));
        }
    }

    private static CategoryView view(Category category, Map<UUID, Long> productCounts, int childCount) {
        return new CategoryView(category.getId(), category.getName(), category.getSlug(), category.getParentId(),
                category.getSortOrder(), category.isActive(), productCounts.getOrDefault(category.getId(), 0L),
                childCount);
    }
}
