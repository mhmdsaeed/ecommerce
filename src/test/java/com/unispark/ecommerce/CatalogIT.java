package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.shared.PlatformDatabase;
import com.unispark.ecommerce.shared.TenantContext;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** M3 done-when: a merchant creates a product with two variants and images (SPEC 17), through the admin UI. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class CatalogIT {

    /** The product page's shelf, sent out of band with a section's HTMX response (attributes in any order). */
    static final String SHELF_OOB = "<section(?=[^>]*id=\"shelf\")(?=[^>]*hx-swap-oob=\"true\")[^>]*>";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformDatabase platform;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    Products products;

    @Autowired
    ImageStore imageStore;

    @Autowired
    S3Client s3;

    String slug;
    UUID store;
    MockHttpSession owner;

    @BeforeEach
    void createStoreAndLogIn() throws Exception {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "Owner", "owner@" + slug + ".test", null, StaffRole.OWNER);
        owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
    }

    @Test
    void merchantCreatesAProductWithTwoVariantsAndImages() throws Exception {
        var id = createProduct(owner, "قميص قطني", "150,000");

        mvc.perform(get(MAIN + "/admin/products/{id}", id).session(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("قميص قطني"), containsString("value=\"150000\""))));

        mvc.perform(htmx(post(MAIN + "/admin/products/{id}/options", id))
                        .param("options[0].nameAr", "المقاس").param("options[0].nameEn", "Size")
                        .param("options[0].valuesAr", "S، M").param("options[0].valuesEn", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        not(containsString("<html")), containsString("تم حفظ الخيارات"),
                        containsString(">S<"), containsString(">M<"))));

        var variants = details(id).variants();
        assertThat(variants).extracting(Products.VariantView::label).containsExactly("S", "M");
        mvc.perform(htmx(post(MAIN + "/admin/products/{id}/variants", id))
                        .param("variants[0].id", variants.get(0).id().toString())
                        .param("variants[0].price", "150000").param("variants[0].active", "true")
                        .param("variants[1].id", variants.get(1).id().toString())
                        .param("variants[1].price", "١٧٥٬٠٠٠").param("variants[1].compareAtPrice", "200,000")
                        .param("variants[1].sku", "SHIRT-M").param("variants[1].active", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("تم حفظ الأسعار"),
                        // The shelf at the top of the page follows the prices: from the cheapest, no photo yet.
                        matchesPattern("(?s).*" + SHELF_OOB + ".*من</span>\\s*<bdi[^>]*>150,000.*"),
                        containsString("بلا صورة"))));

        mvc.perform(htmx(multipart(MAIN + "/admin/products/{id}/images", id)
                        .file(new MockMultipartFile("files", "front.jpg", "image/jpeg", TestImages.jpeg(1600, 1200)))
                        .file(new MockMultipartFile("files", "back.png", "image/png", TestImages.png(500, 500)))))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        not(containsString("<html")), containsString("front.jpg:"), containsString("back.png:"),
                        containsString("تمت الإضافة"), containsString("/640.webp"),
                        matchesPattern("(?s).*" + SHELF_OOB + ".*"), not(containsString("بلا صورة")))));

        mvc.perform(post(MAIN + "/admin/products/{id}/status", id).session(owner).with(csrf()).param("status", "ACTIVE"))
                .andExpect(redirectedUrl("/admin/products/" + id + "?status"));
        mvc.perform(get(MAIN + "/admin/products/{id}", id).session(owner))
                .andExpect(content().string(allOf(containsString("border-s-brand-600"),
                        containsString("href=\"//" + slug + "."), containsString("/p/قميص-قطني\""),
                        containsString("شاهده في متجرك"), not(containsString("hx-swap-oob")))));

        var product = details(id);
        assertThat(product.status().name()).isEqualTo("ACTIVE");
        assertThat(product.variants()).extracting(variant -> variant.price().amount())
                .containsExactly(new BigDecimal("150000.00"), new BigDecimal("175000.00"));
        assertThat(product.variants().get(1).compareAtPrice().amount()).isEqualTo(new BigDecimal("200000.00"));
        assertThat(product.variants().get(1).sku()).isEqualTo("SHIRT-M");
        assertThat(product.images()).hasSize(2);
        assertThat(product.images().getFirst().width()).isEqualTo(1600);

        mvc.perform(get(MAIN + "/admin/products").session(owner))
                .andExpect(content().string(allOf(containsString("قميص قطني"), containsString("منشور"),
                        containsString("150,000"), containsString("/320.webp"))));

        for (var image : product.images()) {
            assertThat(image.storageKey()).startsWith("public/" + store + "/");
            for (var size : ImageSize.values()) {
                assertThat(s3.headObject(head -> head.bucket(TestcontainersConfig.MEDIA_BUCKET)
                        .key(image.storageKey() + "/" + size.width() + ".webp")).contentType()).isEqualTo("image/webp");
            }
        }
        var publicUrl = imageStore.url(product.images().getFirst().storageKey(), ImageSize.MEDIUM);
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(publicUrl)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(anonymous.statusCode()).as("public-read rendition at %s", publicUrl).isEqualTo(200);
        assertThat(new String(anonymous.body(), 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
        assertThat(anonymous.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("immutable"));
    }

    @Test
    void addingAnOptionKeepsPricesOfVariantsThatStillExist() throws Exception {
        var id = createProduct(owner, "Mug", "5000");
        defineOptions(id, "Size", "S, M");
        var medium = details(id).variants().get(1);
        setPrice(id, details(id).variants(), 1, "7000");

        defineOptions(id, "Size", "S, M, L");

        var variants = details(id).variants();
        assertThat(variants).extracting(Products.VariantView::label).containsExactly("S", "M", "L");
        assertThat(variants.get(1).id()).isEqualTo(medium.id());
        assertThat(variants.get(1).price().amount()).isEqualTo(new BigDecimal("7000.00"));
        assertThat(variants.get(2).price().amount()).isEqualTo(new BigDecimal("5000.00"));
    }

    @Test
    void removingAllOptionsGoesBackToOneDefaultVariant() throws Exception {
        var id = createProduct(owner, "Cap", "3000");
        defineOptions(id, "Colour", "Red, Blue");

        mvc.perform(htmx(post(MAIN + "/admin/products/{id}/options", id))).andExpect(status().isOk());

        assertThat(details(id).variants()).singleElement().satisfies(variant -> {
            assertThat(variant.optionValues()).isEmpty();
            assertThat(variant.price().amount()).isEqualTo(new BigDecimal("3000.00"));
        });
    }

    @Test
    void optionErrorsKeepWhatWasTypedAndTheVariantTable() throws Exception {
        var id = createProduct(owner, "Scarf", "2500");

        mvc.perform(htmx(post(MAIN + "/admin/products/{id}/options", id))
                        .param("options[0].nameAr", "اللون").param("options[0].valuesAr", "أحمر, أزرق")
                        .param("options[0].valuesEn", "Red"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("عدد القيم بالإنجليزية يجب أن يساوي"),
                        containsString("value=\"أحمر, أزرق\""),
                        containsString("value=\"2500\""))));
        assertThat(details(id).options()).isEmpty();
    }

    @Test
    void variantErrorsShowOnTheirRowAndKeepTheOptions() throws Exception {
        var id = createProduct(owner, "Belt", "1000");
        defineOptions(id, "Size", "S, M");
        var variants = details(id).variants();

        mvc.perform(htmx(post(MAIN + "/admin/products/{id}/variants", id))
                        .param("variants[0].id", variants.get(0).id().toString()).param("variants[0].price", "12,50")
                        .param("variants[1].id", variants.get(1).id().toString()).param("variants[1].price", "900")
                        .param("variants[1].compareAtPrice", "800"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("سعر غير صالح"), containsString("يجب أن يكون أعلى من السعر"),
                        containsString("value=\"12,50\""), containsString("value=\"S, M\""),
                        containsString(">S<"))));
        assertThat(details(id).variants().get(1).price().amount()).isEqualTo(new BigDecimal("1000.00"));
    }

    @Test
    void rejectsUploadsThatAreNotImagesAndStoresNothing() throws Exception {
        var id = createProduct(owner, "Hat", "1000");

        mvc.perform(htmx(multipart(MAIN + "/admin/products/{id}/images", id)
                        .file(new MockMultipartFile("files", "photo.jpg", "image/jpeg",
                                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "anim.gif", "image/gif",
                                "GIF89a....".getBytes(StandardCharsets.US_ASCII)))))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("هذه ليست صورة JPEG أو PNG أو WebP"), not(containsString("تمت الإضافة")))));

        assertThat(details(id).images()).isEmpty();
    }

    @Test
    void escapesUploadedFileNames() throws Exception {
        var id = createProduct(owner, "Bag", "1000");

        mvc.perform(htmx(multipart(MAIN + "/admin/products/{id}/images", id)
                        .file(new MockMultipartFile("files", "<img src=x onerror=alert(1)>.jpg", "image/jpeg",
                                TestImages.jpeg(100, 100)))))
                .andExpect(content().string(allOf(
                        not(containsString("<img src=x")), containsString("&lt;img src=x onerror=alert(1)&gt;.jpg"))));
    }

    @Test
    void removingAnImageDeletesItsStoredRenditions() throws Exception {
        var id = createProduct(owner, "Lamp", "1000");
        mvc.perform(htmx(multipart(MAIN + "/admin/products/{id}/images", id)
                .file(new MockMultipartFile("files", "lamp.jpg", "image/jpeg", TestImages.jpeg(800, 600)))));
        var image = details(id).images().getFirst();
        var objectKey = image.storageKey() + "/320.webp";
        assertThat(s3.headObject(head -> head.bucket(TestcontainersConfig.MEDIA_BUCKET).key(objectKey))).isNotNull();

        mvc.perform(htmx(post(MAIN + "/admin/products/{id}/images/{imageId}/delete", id, image.id())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("لا توجد صور بعد")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThatThrownBy(() -> s3.headObject(
                        head -> head.bucket(TestcontainersConfig.MEDIA_BUCKET).key(objectKey)))
                        .isInstanceOf(S3Exception.class));
    }

    @Test
    void reordersImages() throws Exception {
        var id = createProduct(owner, "Vase", "1000");
        mvc.perform(htmx(multipart(MAIN + "/admin/products/{id}/images", id)
                .file(new MockMultipartFile("files", "a.jpg", "image/jpeg", TestImages.jpeg(300, 200)))
                .file(new MockMultipartFile("files", "b.png", "image/png", TestImages.png(200, 200)))));
        var first = details(id).images().get(0);
        var second = details(id).images().get(1);

        mvc.perform(htmx(post(MAIN + "/admin/products/{id}/images/{imageId}/move", id, second.id())).param("direction", "-1"))
                .andExpect(status().isOk());

        assertThat(details(id).images()).extracting(Products.ImageView::id).containsExactly(second.id(), first.id());
    }

    @Test
    void cannotPublishWithoutAPricedVariant() throws Exception {
        var id = createProduct(owner, "Free sample", "0");

        // The shelf says why: no price, publishing needs one, and the button waits for it.
        mvc.perform(get(MAIN + "/admin/products/{id}", id).session(owner))
                .andExpect(content().string(allOf(containsString("border-s-amber-400"), containsString("بلا سعر"),
                        containsString("مطلوب قبل النشر"),
                        matchesPattern("(?s).*<button type=\"submit\"[^>]*disabled=\"disabled\">نشر</button>.*"))));
        mvc.perform(post(MAIN + "/admin/products/{id}/status", id).session(owner).with(csrf()).param("status", "ACTIVE"))
                .andExpect(redirectedUrl("/admin/products/" + id))
                .andExpect(flash().attribute("error", "product.status.needs-price"));
        assertThat(details(id).status().name()).isEqualTo("DRAFT");
    }

    @Test
    void onlyDraftsCanBeDeleted() throws Exception {
        var id = createProduct(owner, "Pen", "500");
        mvc.perform(post(MAIN + "/admin/products/{id}/status", id).session(owner).with(csrf()).param("status", "ACTIVE"));

        mvc.perform(post(MAIN + "/admin/products/{id}/delete", id).session(owner).with(csrf()))
                .andExpect(flash().attribute("error", "product.status.not-draft"));

        mvc.perform(post(MAIN + "/admin/products/{id}/status", id).session(owner).with(csrf()).param("status", "DRAFT"));
        mvc.perform(post(MAIN + "/admin/products/{id}/delete", id).session(owner).with(csrf()))
                .andExpect(redirectedUrl("/admin/products?deleted"));
        assertThat(TenantContext.callAs(store, () -> products.find(id))).isEmpty();
    }

    @Test
    void generatesArabicSlugsAndKeepsThemUnique() throws Exception {
        var first = createProduct(owner, "قميص قطني", "1000");
        var second = createProduct(owner, "قَمِيص قُطني", "1000");

        assertThat(details(first).slug()).isEqualTo("قميص-قطني");
        assertThat(details(second).slug()).isEqualTo("قميص-قطني-2");
    }

    @Test
    void validatesTheNewProductForm() throws Exception {
        mvc.perform(post(MAIN + "/admin/products").session(owner).with(csrf())
                        .param("nameAr", "").param("nameEn", "").param("priceCurrency", "SYP").param("price", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("أدخل اسم المنتج بالعربية أو بالإنجليزية"),
                        containsString("اكتب السعر بالأرقام"))));
    }

    @Test
    void searchesProductsOverHtmx() throws Exception {
        createProduct(owner, "Blue Teapot", "1000");
        createProduct(owner, "Red Kettle", "1000");

        mvc.perform(htmx(get(MAIN + "/admin/products").param("q", "teap")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        not(containsString("<html")), containsString("Blue Teapot"), not(containsString("Red Kettle")))));
        mvc.perform(htmx(get(MAIN + "/admin/products").param("q", "100%_")))
                .andExpect(content().string(containsString("لا توجد منتجات تطابق البحث")));
    }

    @Test
    void listsProductsInStatusTabsWithCounts() throws Exception {
        var published = createProduct(owner, "Blue Teapot", "1000");
        createProduct(owner, "Blue Cup", "0");
        createProduct(owner, "Red Kettle", "1000");
        mvc.perform(post(MAIN + "/admin/products/{id}/status", published).session(owner).with(csrf()).param("status", "ACTIVE"));

        mvc.perform(get(MAIN + "/admin/products").session(owner))
                .andExpect(content().string(allOf(
                        matchesPattern("(?s).*<a href=\"/admin/products\"[^>]*aria-current=\"page\"[^>]*>\\s*<span>كل المنتجات</span>\\s*<span[^>]*>3</span>.*"),
                        matchesPattern("(?s).*status=ACTIVE\"[^>]*>\\s*<span>منشور</span>\\s*<span[^>]*>1</span>.*"),
                        matchesPattern("(?s).*status=DRAFT\"[^>]*>\\s*<span>مسودة</span>\\s*<span[^>]*>2</span>.*"),
                        matchesPattern("(?s).*status=ARCHIVED\"[^>]*>\\s*<span>مؤرشف</span>\\s*<span[^>]*>0</span>.*"),
                        containsString("بلا سعر"), not(containsString("name=\"status\" form=")))));

        // A tab keeps the search; the status rides along with the next search in the hidden field.
        mvc.perform(htmx(get(MAIN + "/admin/products").param("status", "DRAFT").param("q", "blue")))
                .andExpect(content().string(allOf(not(containsString("<html")),
                        containsString("Blue Cup"), not(containsString("Blue Teapot")), not(containsString("Red Kettle")),
                        containsString("<input type=\"hidden\" name=\"status\" form=\"product-filters\" value=\"DRAFT\">"),
                        containsString("href=\"/admin/products?q=blue\""),
                        matchesPattern("(?s).*<a href=\"/admin/products\\?status=DRAFT&amp;q=blue\"[^>]*aria-current=\"page\".*"))));
    }

    @Test
    void managesCategoriesAndRefusesToDeleteOnesInUse() throws Exception {
        mvc.perform(htmx(post(MAIN + "/admin/categories")).param("nameAr", "ملابس").param("nameEn", "Clothes"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        not(containsString("<html")), containsString("تمت إضافة القسم"), containsString("/c/ملابس"))));
        var parent = categoryId("ملابس");
        mvc.perform(htmx(post(MAIN + "/admin/categories")).param("nameAr", "قمصان").param("parentId", parent.toString()))
                .andExpect(content().string(containsString("/c/قمصان")));
        var child = categoryId("قمصان");
        mvc.perform(post(MAIN + "/admin/products").session(owner).with(csrf()).param("nameAr", "قميص")
                .param("priceCurrency", "SYP").param("price", "1000").param("categoryId", child.toString()));

        mvc.perform(htmx(post(MAIN + "/admin/categories/{id}/delete", parent)))
                .andExpect(content().string(containsString("لا يمكن حذف قسم فيه منتجات أو أقسام فرعية")));
        mvc.perform(htmx(post(MAIN + "/admin/categories/{id}/delete", child)))
                .andExpect(content().string(containsString("لا يمكن حذف قسم فيه منتجات أو أقسام فرعية")));
        mvc.perform(htmx(post(MAIN + "/admin/categories/{id}/active", child)).param("active", "false"))
                .andExpect(content().string(allOf(containsString("أصبح القسم مخفياً من المتجر"),
                        // The tree: the parent links to its page in the shop; the hidden child hangs off its line,
                        // greyed, with its address as plain text and its product counted.
                        matchesPattern("(?s).*<a href=\"//" + slug + "\\.[^\"]*/c/ملابس\".*"),
                        matchesPattern("(?s).*border-s-2[^\"]*\">\\s*<div class=\"opacity-60\">\\s*<div class=\"font-medium\">قمصان</div>"
                                + "\\s*<span dir=\"ltr\"[^>]*>/c/قمصان</span>.*منتج واحد.*"))));
        // A visible subcategory under a hidden parent is not in the shop either: no link, and it says why.
        mvc.perform(htmx(post(MAIN + "/admin/categories/{id}/active", child)).param("active", "true"));
        mvc.perform(htmx(post(MAIN + "/admin/categories/{id}/active", parent)).param("active", "false"))
                .andExpect(content().string(allOf(not(containsString("/c/قمصان\" target")),
                        matchesPattern("(?s).*border-s-2[^\"]*\">\\s*<div class=\"opacity-60\">\\s*<div class=\"font-medium\">قمصان</div>"
                                + ".*مخفي مع القسم الرئيسي.*"))));
        mvc.perform(htmx(post(MAIN + "/admin/categories")).param("nameAr", "أحذية").param("parentId", child.toString()))
                .andExpect(content().string(containsString("يمكن أن يكون القسم الفرعي داخل قسم رئيسي فقط")));
    }

    @Test
    void anotherStoreCannotSeeOrChangeTheseProducts() throws Exception {
        var id = createProduct(owner, "Secret product", "1000");
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, other, "Other", "owner@" + otherSlug + ".test", null, StaffRole.OWNER);
        var intruder = TestAccounts.logIn(mvc, otherSlug, "owner@" + otherSlug + ".test");

        mvc.perform(get(MAIN + "/admin/products").session(intruder))
                .andExpect(content().string(not(containsString("Secret product"))));
        mvc.perform(get(MAIN + "/admin/products/{id}", id).session(intruder)).andExpect(status().isNotFound());
        mvc.perform(post(MAIN + "/admin/products/{id}/status", id).session(intruder).with(csrf()).param("status", "ACTIVE"))
                .andExpect(status().isNotFound());
        mvc.perform(post(MAIN + "/admin/products/{id}/options", id).session(intruder).with(csrf())
                        .header("HX-Request", "true").param("options[0].nameAr", "x").param("options[0].valuesAr", "y"))
                .andExpect(status().isNotFound());
        mvc.perform(multipart(MAIN + "/admin/products/{id}/images", id)
                        .file(new MockMultipartFile("files", "x.jpg", "image/jpeg", TestImages.jpeg(50, 50)))
                        .session(intruder).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isNotFound());
        assertThat(details(id).status().name()).isEqualTo("DRAFT");
        assertThat(details(id).images()).isEmpty();
    }

    @Test
    void sendsHtmxRequestsWithAnExpiredSessionToTheLoginPage() throws Exception {
        mvc.perform(get(MAIN + "/admin/products").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/admin/login?expired"));
        // A new session has no CSRF token yet, which is what an expired session looks like to a posted form.
        mvc.perform(post(MAIN + "/admin/categories").header("HX-Request", "true").header("X-CSRF-TOKEN", "stale"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("HX-Redirect", "/admin/login?expired"));
        mvc.perform(get(MAIN + "/admin/products")).andExpect(redirectedUrlPattern("**/admin/login"));
    }

    private UUID createProduct(MockHttpSession session, String nameAr, String price) throws Exception {
        var result = mvc.perform(post(MAIN + "/admin/products").session(session).with(csrf())
                        .param("nameAr", nameAr).param("priceCurrency", "SYP").param("price", price))
                .andExpect(redirectedUrlPattern("/admin/products/*?created"))
                .andReturn();
        var location = result.getResponse().getRedirectedUrl();
        return UUID.fromString(location.substring("/admin/products/".length(), location.indexOf('?')));
    }

    private void defineOptions(UUID id, String name, String values) throws Exception {
        mvc.perform(htmx(post(MAIN + "/admin/products/{id}/options", id))
                        .param("options[0].nameAr", name).param("options[0].valuesAr", values))
                .andExpect(content().string(containsString("تم حفظ الخيارات")));
    }

    private void setPrice(UUID id, List<Products.VariantView> variants, int index, String price) throws Exception {
        var request = htmx(post(MAIN + "/admin/products/{id}/variants", id));
        for (int i = 0; i < variants.size(); i++) {
            request.param("variants[" + i + "].id", variants.get(i).id().toString())
                    .param("variants[" + i + "].price", i == index ? price : variants.get(i).price().amount().toPlainString())
                    .param("variants[" + i + "].active", "true");
        }
        mvc.perform(request).andExpect(content().string(containsString("تم حفظ الأسعار")));
    }

    private Products.ProductDetails details(UUID id) {
        return TenantContext.callAs(store, () -> products.find(id)).orElseThrow();
    }

    private UUID categoryId(String nameAr) {
        return platform.jdbc().sql("SELECT id FROM category WHERE tenant_id = ? AND name ->> 'ar' = ?")
                .params(store, nameAr).query(UUID.class).single();
    }

    private MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder request) {
        return request.session(owner).with(csrf()).header("HX-Request", "true");
    }
}
