package com.unispark.ecommerce.catalog.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products.OptionView;
import com.unispark.ecommerce.catalog.StorefrontCatalog;
import com.unispark.ecommerce.catalog.internal.persistence.Category;
import com.unispark.ecommerce.catalog.internal.persistence.CategoryRepository;
import com.unispark.ecommerce.catalog.internal.persistence.Product;
import com.unispark.ecommerce.catalog.internal.persistence.ProductImage;
import com.unispark.ecommerce.catalog.internal.persistence.ProductOption;
import com.unispark.ecommerce.catalog.internal.persistence.ProductRepository;
import com.unispark.ecommerce.catalog.internal.persistence.ProductVariant;
import com.unispark.ecommerce.shared.LocalizedText;

/**
 * Shopper-facing catalog reads. Visibility is decided in one place ({@link #VISIBLE}); categories are few, so the
 * visible ones are worked out in Java and passed to the queries.
 */
@Service
@Transactional(readOnly = true)
class StorefrontCatalogService implements StorefrontCatalog {

    /** Published, at least one available variant, and either uncategorized or in a visible category. */
    static final String VISIBLE = """
            p.status = :active
            and exists (select 1 from ProductVariant v where v.product = p and v.active = true)
            and (p.categoryId is null or p.categoryId in :visibleCategories)
            """;

    /** The lowest available price, scaled to the display currency for comparison across currencies. Both factors
     * are parameters: a literal 1 would make Hibernate type the CASE as an integer. */
    static final String PRICE = """
            (select min(v.price) from ProductVariant v where v.product = p and v.active = true)
              * (case when p.priceCurrency = :foreign then :factor else :unscaled end)
            """;

    private final EntityManager entityManager;
    private final CategoryRepository categories;
    private final ProductRepository products;

    StorefrontCatalogService(EntityManager entityManager, CategoryRepository categories, ProductRepository products) {
        this.entityManager = entityManager;
        this.categories = categories;
        this.products = products;
    }

    @Override
    public List<CategoryLink> navigation() {
        return new Visible(categories.findAll()).tree();
    }

    @Override
    public List<ProductCard> newest(int limit) {
        var visible = new Visible(categories.findAll());
        return entityManager.createQuery("select p from Product p where " + VISIBLE + " order by p.createdAt desc",
                        Product.class)
                .setParameter("active", ProductStatus.ACTIVE)
                .setParameter("visibleCategories", visible.ids())
                .setMaxResults(limit)
                .getResultList().stream().map(StorefrontCatalogService::card).toList();
    }

    @Override
    public Optional<CategoryPage> category(String slug, Sort sort, PriceScale scale, int page) {
        var visible = new Visible(categories.findAll());
        var category = visible.bySlug(slug);
        if (category.isEmpty()) {
            return Optional.empty();
        }
        var ids = new ArrayList<UUID>();
        ids.add(category.get().getId());
        visible.children(category.get().getId()).forEach(child -> ids.add(child.getId()));
        var order = switch (sort) {
            case NEWEST -> "p.createdAt desc";
            case PRICE_ASC -> PRICE + " asc, p.createdAt desc";
            case PRICE_DESC -> PRICE + " desc, p.createdAt desc";
        };
        var where = VISIBLE + " and p.categoryId in :categoryIds";
        var query = entityManager.createQuery("select p from Product p where " + where + " order by " + order,
                        Product.class)
                .setParameter("active", ProductStatus.ACTIVE)
                .setParameter("visibleCategories", visible.ids())
                .setParameter("categoryIds", ids);
        if (sort != Sort.NEWEST) {
            query.setParameter("foreign", scale.foreign()).setParameter("factor", scale.factor())
                    .setParameter("unscaled", BigDecimal.ONE);
        }
        var count = entityManager.createQuery("select count(p) from Product p where " + where, Long.class)
                .setParameter("active", ProductStatus.ACTIVE)
                .setParameter("visibleCategories", visible.ids())
                .setParameter("categoryIds", ids)
                .getSingleResult();
        var results = page(query.setFirstResult(Math.max(page, 0) * PAGE_SIZE).setMaxResults(PAGE_SIZE)
                .getResultList(), page, count);
        var parent = Optional.ofNullable(category.get().getParentId()).flatMap(visible::byId)
                .map(found -> visible.link(found)).orElse(null);
        return Optional.of(new CategoryPage(visible.link(category.get()), parent, results));
    }

    @Override
    public Optional<ProductView> product(String slug) {
        var visible = new Visible(categories.findAll());
        return products.findBySlug(slug)
                .filter(product -> product.getStatus() == ProductStatus.ACTIVE)
                .filter(product -> product.getCategoryId() == null || visible.ids().contains(product.getCategoryId()))
                .filter(product -> product.getVariants().stream().anyMatch(ProductVariant::isActive))
                .map(product -> view(product, visible));
    }

    @Override
    public ResultPage search(String query, int page) {
        var normalized = ProductSearch.normalize(query);
        if (normalized.isEmpty()) {
            return new ResultPage(List.of(), 0, 0, 0);
        }
        var visible = new Visible(categories.findAll());
        var where = VISIBLE + " and p.searchText like :pattern escape '\\'";
        var found = entityManager.createQuery("select p from Product p where " + where
                                + " order by function('similarity', p.searchText, :query) desc, p.createdAt desc",
                        Product.class)
                .setParameter("active", ProductStatus.ACTIVE)
                .setParameter("visibleCategories", visible.ids())
                .setParameter("pattern", ProductSearch.containsPattern(normalized))
                .setParameter("query", normalized)
                .setFirstResult(Math.max(page, 0) * PAGE_SIZE)
                .setMaxResults(PAGE_SIZE)
                .getResultList();
        var count = entityManager.createQuery("select count(p) from Product p where " + where, Long.class)
                .setParameter("active", ProductStatus.ACTIVE)
                .setParameter("visibleCategories", visible.ids())
                .setParameter("pattern", ProductSearch.containsPattern(normalized))
                .getSingleResult();
        return page(found, page, count);
    }

    @Override
    public Map<UUID, Offer> offers(Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        var visible = new Visible(categories.findAll());
        var found = entityManager.createQuery("""
                        select v from ProductVariant v join fetch v.product p
                        where v.id in :ids and v.active = true and p.status = :active
                          and (p.categoryId is null or p.categoryId in :visibleCategories)
                        """, ProductVariant.class)
                .setParameter("ids", variantIds)
                .setParameter("active", ProductStatus.ACTIVE)
                .setParameter("visibleCategories", visible.ids())
                .getResultList();
        return found.stream().collect(Collectors.toMap(ProductVariant::getId, StorefrontCatalogService::offer));
    }

    private static Offer offer(ProductVariant variant) {
        var product = variant.getProduct();
        var choices = new ArrayList<Choice>();
        for (var option : product.getOptions().stream().sorted(Comparator.comparingInt(ProductOption::getPosition)).toList()) {
            var label = variant.getOptionValues().get(option.getPosition() - 1);
            var value = option.getChoices().stream().filter(choice -> label.equals(VariantMatrix.label(choice)))
                    .findFirst().orElse(LocalizedText.of(label, null));
            choices.add(new Choice(option.getName(), value));
        }
        var images = product.getImages().stream().sorted(Comparator.comparingInt(ProductImage::getSortOrder)).toList();
        var image = images.stream().filter(candidate -> variant.getId().equals(candidate.getVariantId())).findFirst()
                .or(() -> images.stream().findFirst()).map(CatalogViews::image).orElse(null);
        return new Offer(variant.getId(), product.getId(), product.getName(), product.getSlug(), List.copyOf(choices),
                variant.price(), image, variant.getSku());
    }

    private static ResultPage page(List<Product> found, int page, long count) {
        var totalPages = (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE);
        return new ResultPage(found.stream().map(StorefrontCatalogService::card).toList(), Math.max(page, 0),
                totalPages, count);
    }

    static ProductCard card(Product product) {
        var available = product.getVariants().stream().filter(ProductVariant::isActive).toList();
        var cheapest = available.stream().min(Comparator.comparing(ProductVariant::getPrice)).orElseThrow();
        var differentPrices = available.stream().map(variant -> variant.getPrice().stripTrailingZeros())
                .collect(Collectors.toSet()).size() > 1;
        var image = product.getImages().stream().min(Comparator.comparingInt(ProductImage::getSortOrder))
                .map(CatalogViews::image).orElse(null);
        return new ProductCard(product.getId(), product.getName(), product.getSlug(), cheapest.price(),
                cheapest.compareAtPrice(), differentPrices, image);
    }

    private static ProductView view(Product product, Visible visible) {
        var available = product.getVariants().stream().filter(ProductVariant::isActive).toList();
        var options = new ArrayList<OptionView>();
        for (var option : product.getOptions()) {
            var index = option.getPosition() - 1;
            var offered = available.stream().map(variant -> variant.getOptionValues().get(index))
                    .collect(Collectors.toSet());
            options.add(new OptionView(option.getPosition(), option.getName(), option.getChoices().stream()
                    .filter(choice -> offered.contains(VariantMatrix.label(choice))).toList()));
        }
        var category = Optional.ofNullable(product.getCategoryId()).flatMap(visible::byId).map(visible::link)
                .orElse(null);
        return new ProductView(product.getId(), product.getName(), product.getDescription(), product.getSlug(),
                category, List.copyOf(options),
                available.stream().map(variant -> new VariantOffer(variant.getId(), variant.getOptionValues(),
                        variant.price(), variant.compareAtPrice())).toList(),
                product.getImages().stream().sorted(Comparator.comparingInt(ProductImage::getSortOrder))
                        .map(CatalogViews::image).toList());
    }

    /** The store's categories that shoppers may see: active, under an active parent if any. */
    private static final class Visible {

        /** Never empty, so {@code in :visibleCategories} stays valid SQL; the nil UUID matches nothing. */
        private static final UUID NONE = new UUID(0, 0);

        private final Map<UUID, Category> byId;
        private final Set<UUID> ids = new LinkedHashSet<>();

        Visible(List<Category> all) {
            var everything = all.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
            for (var category : all) {
                var parent = category.getParentId() == null ? null : everything.get(category.getParentId());
                if (category.isActive() && (category.getParentId() == null || (parent != null && parent.isActive()))) {
                    ids.add(category.getId());
                }
            }
            byId = everything;
            ids.add(NONE);
        }

        Set<UUID> ids() {
            return ids;
        }

        Optional<Category> byId(UUID id) {
            return ids.contains(id) ? Optional.ofNullable(byId.get(id)) : Optional.empty();
        }

        Optional<Category> bySlug(String slug) {
            return byId.values().stream().filter(category -> ids.contains(category.getId()))
                    .filter(category -> category.getSlug().equals(slug)).findFirst();
        }

        List<Category> children(UUID parentId) {
            return byId.values().stream().filter(category -> ids.contains(category.getId()))
                    .filter(category -> parentId.equals(category.getParentId()))
                    .sorted(Comparator.comparingInt(Category::getSortOrder))
                    .toList();
        }

        CategoryLink link(Category category) {
            return new CategoryLink(category.getId(), category.getName(), category.getSlug(),
                    children(category.getId()).stream()
                            .map(child -> new CategoryLink(child.getId(), child.getName(), child.getSlug(), List.of()))
                            .toList());
        }

        List<CategoryLink> tree() {
            return byId.values().stream()
                    .filter(category -> ids.contains(category.getId()) && category.getParentId() == null)
                    .sorted(Comparator.comparingInt(Category::getSortOrder))
                    .map(this::link)
                    .toList();
        }
    }
}
