package com.unispark.ecommerce.catalog.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.internal.persistence.CategoryRepository;
import com.unispark.ecommerce.catalog.internal.persistence.Product;
import com.unispark.ecommerce.catalog.internal.persistence.ProductOption;
import com.unispark.ecommerce.catalog.internal.persistence.ProductRepository;
import com.unispark.ecommerce.catalog.internal.persistence.ProductVariant;
import com.unispark.ecommerce.catalog.internal.persistence.ProductVariantRepository;
import com.unispark.ecommerce.media.ImageDiscarded;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;

@Service
@Transactional
class ProductService implements Products {

    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_DESCRIPTION_LENGTH = 5000;
    static final int MAX_SKU_LENGTH = 64;

    private static final Pattern SKU = Pattern.compile("\\S+");

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final ApplicationEventPublisher events;

    ProductService(ProductRepository products, ProductVariantRepository variants, CategoryRepository categories,
            ApplicationEventPublisher events) {
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductPage search(String query, ProductStatus status, int page) {
        var pattern = Texts.isBlank(query) ? null : "%" + escapeLike(query.strip().toLowerCase(Locale.ROOT)) + "%";
        var result = products.search(status, pattern,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new ProductPage(result.getContent().stream().map(CatalogViews::summary).toList(),
                result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductDetails> find(UUID id) {
        return products.findById(id).map(CatalogViews::details);
    }

    @Override
    public ProductResult create(ProductDraft draft, String price) {
        var problems = new ArrayList<FieldProblem>();
        var checked = check(draft, null, problems);
        var amount = Amounts.parse(price);
        if (amount.isEmpty()) {
            problems.add(new FieldProblem("price", "invalid"));
        }
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        var product = new Product(checked.name(), checked.description(), checked.slug(), draft.categoryId(),
                draft.priceCurrency());
        product.getVariants().add(new ProductVariant(product, 0, List.of(), amount.get()));
        return new Saved(products.save(product).getId());
    }

    @Override
    public ProductResult update(UUID id, ProductDraft draft) {
        var existing = products.findById(id);
        if (existing.isEmpty()) {
            return new NotFound();
        }
        var problems = new ArrayList<FieldProblem>();
        var checked = check(draft, existing.get(), problems);
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        existing.get().update(checked.name(), checked.description(), checked.slug(), draft.categoryId(),
                draft.priceCurrency());
        return new Saved(id);
    }

    @Override
    public ProductResult changeStatus(UUID id, ProductStatus status) {
        var existing = products.findById(id);
        if (existing.isEmpty()) {
            return new NotFound();
        }
        var product = existing.get();
        if (status == ProductStatus.ACTIVE && !sellable(product.getVariants())) {
            return new Invalid(List.of(new FieldProblem("status", "needs-price")));
        }
        product.setStatus(status);
        return new Saved(id);
    }

    @Override
    public ProductResult delete(UUID id) {
        var existing = products.findById(id);
        if (existing.isEmpty()) {
            return new NotFound();
        }
        var product = existing.get();
        if (product.getStatus() != ProductStatus.DRAFT) {
            return new Invalid(List.of(new FieldProblem("status", "not-draft")));
        }
        product.getImages().forEach(image -> events.publishEvent(new ImageDiscarded(image.getStorageKey())));
        products.delete(product);
        return new Saved(id);
    }

    @Override
    public ProductResult defineOptions(UUID id, List<OptionDraft> drafts) {
        var existing = products.findById(id);
        if (existing.isEmpty()) {
            return new NotFound();
        }
        var parsed = VariantMatrix.parse(drafts);
        if (!parsed.problems().isEmpty()) {
            return new Invalid(parsed.problems());
        }
        var product = existing.get();
        replaceOptions(product, parsed.options());
        replaceVariants(product, VariantMatrix.combinations(parsed.options()));
        return new Saved(id);
    }

    /** Updated in place by position: Hibernate inserts before it deletes, which would clash on (product, position). */
    private static void replaceOptions(Product product, List<VariantMatrix.ParsedOption> parsed) {
        var options = product.getOptions();
        options.removeIf(option -> option.getPosition() > parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            var position = i + 1;
            var definition = parsed.get(i);
            options.stream().filter(option -> option.getPosition() == position).findFirst().ifPresentOrElse(
                    option -> option.update(definition.name(), definition.choices()),
                    () -> options.add(new ProductOption(product, position, definition.name(), definition.choices())));
        }
    }

    /** Keeps variants whose combination still exists; new ones start at the price of the current first variant. */
    private static void replaceVariants(Product product, List<List<String>> combinations) {
        var current = product.getVariants();
        var startingPrice = current.stream().min(Comparator.comparingInt(ProductVariant::getPosition))
                .map(ProductVariant::getPrice).orElse(BigDecimal.ZERO);
        var byCombination = new HashMap<List<String>, ProductVariant>();
        current.forEach(variant -> byCombination.put(variant.getOptionValues(), variant));
        var kept = new HashSet<ProductVariant>();
        var added = new ArrayList<ProductVariant>();
        for (int position = 0; position < combinations.size(); position++) {
            var combination = combinations.get(position);
            var variant = byCombination.get(combination);
            if (variant == null) {
                added.add(new ProductVariant(product, position, combination, startingPrice));
            } else {
                variant.setPosition(position);
                kept.add(variant);
            }
        }
        current.removeIf(variant -> !kept.contains(variant));
        current.addAll(added);
    }

    @Override
    public ProductResult updateVariants(UUID id, List<VariantDraft> drafts) {
        var existing = products.findById(id);
        if (existing.isEmpty()) {
            return new NotFound();
        }
        var product = existing.get();
        var byId = new HashMap<UUID, ProductVariant>();
        product.getVariants().forEach(variant -> byId.put(variant.getId(), variant));
        var problems = new ArrayList<FieldProblem>();
        var changes = new ArrayList<Runnable>();
        var skus = new HashSet<String>();
        var anySellable = false;
        for (int i = 0; i < drafts.size(); i++) {
            var draft = drafts.get(i);
            var field = "variants[" + i + "].";
            var variant = draft.id() == null ? null : byId.get(draft.id());
            if (variant == null) {
                return new NotFound();
            }
            var sku = Texts.isBlank(draft.sku()) ? null : draft.sku().strip();
            if (sku != null) {
                if (sku.length() > MAX_SKU_LENGTH || !SKU.matcher(sku).matches()) {
                    problems.add(new FieldProblem(field + "sku", "invalid"));
                } else if (!skus.add(sku) || variants.existsBySkuAndIdNot(sku, variant.getId())) {
                    problems.add(new FieldProblem(field + "sku", "taken"));
                }
            }
            var price = Amounts.parse(draft.price());
            if (price.isEmpty()) {
                problems.add(new FieldProblem(field + "price", "invalid"));
            }
            BigDecimal compareAt = null;
            if (!Texts.isBlank(draft.compareAtPrice())) {
                var parsed = Amounts.parse(draft.compareAtPrice());
                if (parsed.isEmpty()) {
                    problems.add(new FieldProblem(field + "compareAtPrice", "invalid"));
                } else if (price.isPresent() && parsed.get().compareTo(price.get()) <= 0) {
                    problems.add(new FieldProblem(field + "compareAtPrice", "not-higher"));
                } else {
                    compareAt = parsed.get();
                }
            }
            if (draft.active() && price.isPresent() && price.get().signum() > 0) {
                anySellable = true;
            }
            if (price.isPresent()) {
                var newPrice = price.get();
                var newCompareAt = compareAt;
                changes.add(() -> variant.update(sku, newPrice, newCompareAt, draft.active()));
            }
        }
        if (product.getStatus() == ProductStatus.ACTIVE && !anySellable) {
            problems.add(new FieldProblem("variants", "none-sellable"));
        }
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        changes.forEach(Runnable::run);
        return new Saved(id);
    }

    private record Checked(LocalizedText name, LocalizedText description, String slug) {
    }

    private Checked check(ProductDraft draft, Product existing, List<FieldProblem> problems) {
        var name = Texts.localizedOrNull(draft.nameAr(), draft.nameEn());
        if (name == null) {
            problems.add(new FieldProblem("nameAr", "required"));
        } else if (length(draft.nameAr()) > MAX_NAME_LENGTH || length(draft.nameEn()) > MAX_NAME_LENGTH) {
            problems.add(new FieldProblem("nameAr", "too-long"));
        }
        var description = Texts.localizedOrNull(draft.descriptionAr(), draft.descriptionEn());
        if (length(draft.descriptionAr()) > MAX_DESCRIPTION_LENGTH || length(draft.descriptionEn()) > MAX_DESCRIPTION_LENGTH) {
            problems.add(new FieldProblem("descriptionAr", "too-long"));
        }
        if (draft.priceCurrency() == null) {
            problems.add(new FieldProblem("priceCurrency", "required"));
        }
        if (draft.categoryId() != null && !categories.existsById(draft.categoryId())) {
            problems.add(new FieldProblem("categoryId", "invalid"));
        }
        var id = existing == null ? null : existing.getId();
        String slug = null;
        if (!Texts.isBlank(draft.slug())) {
            slug = Slugs.normalize(draft.slug());
            if (!Slugs.isValid(slug)) {
                problems.add(new FieldProblem("slug", "invalid"));
            } else if (id == null ? products.existsBySlug(slug) : products.existsBySlugAndIdNot(slug, id)) {
                problems.add(new FieldProblem("slug", "taken"));
            }
        } else if (existing != null) {
            slug = existing.getSlug();
        } else if (name != null) {
            slug = Slugs.unique(Slugs.fromName(Texts.slugSource(name), "product"), products::existsBySlug);
        }
        return new Checked(name, description, slug);
    }

    private static boolean sellable(List<ProductVariant> variants) {
        return variants.stream().anyMatch(variant -> variant.isActive() && variant.getPrice().signum() > 0);
    }

    private static int length(String value) {
        return value == null ? 0 : value.strip().length();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
