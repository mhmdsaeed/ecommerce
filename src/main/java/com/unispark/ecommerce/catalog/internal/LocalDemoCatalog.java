package com.unispark.ecommerce.catalog.internal;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.catalog.Categories;
import com.unispark.ecommerce.catalog.Categories.CategoryDraft;
import com.unispark.ecommerce.catalog.ProductImages;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.OptionDraft;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.VariantDraft;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Local seed data (SPEC 15): a small catalog for {@code demo1}, created through the same services as the admin.
 * Runs once, while the store has no products. Images are simple generated pictures; if MinIO is not running the
 * products are created without them.
 */
@Component
@Profile("local")
@Order(30)
class LocalDemoCatalog implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoCatalog.class);

    private final StoreLookup stores;
    private final Categories categories;
    private final Products products;
    private final ProductImages images;

    LocalDemoCatalog(StoreLookup stores, Categories categories, Products products, ProductImages images) {
        this.stores = stores;
        this.categories = categories;
        this.products = products;
        this.images = images;
    }

    @Override
    public void run(ApplicationArguments args) {
        var store = stores.findBySlug("demo1").orElseThrow(() -> new IllegalStateException("Demo store missing: demo1"));
        TenantContext.runAs(store.id(), () -> {
            if (products.search(null, null, 0).totalItems() > 0) {
                return;
            }
            var clothes = saved(categories.create(new CategoryDraft("ملابس", "Clothes", null, null, 1)));
            var kitchen = saved(categories.create(new CategoryDraft("مطبخ", "Kitchen", null, null, 2)));

            var shirt = created(products.create(new ProductDraft("قميص قطني", "Cotton shirt",
                    "قميص قطن مريح للصيف.", "A comfortable cotton shirt for summer.", null, clothes, Currency.SYP), "150000"));
            products.defineOptions(shirt, List.of(new OptionDraft("المقاس", "Size", "S, M, L", "S, M, L"),
                    new OptionDraft("اللون", "Colour", "أبيض, أزرق", "White, Blue")));
            var variants = products.find(shirt).orElseThrow().variants();
            products.updateVariants(shirt, variants.stream()
                    .map(variant -> new VariantDraft(variant.id(), null,
                            variant.optionValues().getFirst().equals("L") ? "165000" : "150000", null, true))
                    .toList());
            addImage(shirt, new Color(0x1E88E5), "SHIRT");
            products.changeStatus(shirt, ProductStatus.ACTIVE);

            var mug = created(products.create(new ProductDraft("كوب قهوة", "Coffee mug", null, null, null, kitchen,
                    Currency.USD), "4.50"));
            addImage(mug, new Color(0x8D6E63), "MUG");
            products.changeStatus(mug, ProductStatus.ACTIVE);

            created(products.create(new ProductDraft("إبريق شاي", "Teapot", null, null, null, kitchen, Currency.SYP),
                    "95000"));
            log.info("Seeded the demo1 catalog: 2 categories, 3 products");
        });
    }

    private void addImage(UUID product, Color color, String label) {
        var result = images.add(product, new ByteArrayInputStream(picture(color, label)));
        if (!(result instanceof ProductImages.Added)) {
            log.warn("Demo image for {} not stored ({}); is MinIO running?", label, result);
        }
    }

    private static byte[] picture(Color color, String label) {
        var image = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(color);
        graphics.fillRect(0, 0, 1200, 1200);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 160));
        var width = graphics.getFontMetrics().stringWidth(label);
        graphics.drawString(label, (1200 - width) / 2, 660);
        graphics.dispose();
        try {
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID saved(Categories.CategoryResult result) {
        if (result instanceof Categories.Saved(var id)) {
            return id;
        }
        throw new IllegalStateException("Demo category not created: " + result);
    }

    private static UUID created(Products.ProductResult result) {
        if (result instanceof Products.Saved(var id)) {
            return id;
        }
        throw new IllegalStateException("Demo product not created: " + result);
    }
}
