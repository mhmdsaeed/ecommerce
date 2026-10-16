package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.PLATFORM;
import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.unispark.ecommerce.catalog.Categories;
import com.unispark.ecommerce.catalog.Categories.CategoryDraft;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.OptionDraft;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.catalog.Products.VariantDraft;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * M14 done-when: the SPEC 13 endpoints return data, and the docs are at {@code /api/docs}. Also the API's security
 * chain: open catalog reads, bearer tokens, the store header, and tenant isolation through the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ApiIT {

    static final String API = MAIN + "/api/v1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Categories categories;

    @Autowired
    Products products;

    @Autowired
    ExchangeRates rates;

    @Autowired
    Inventory inventory;

    @Value("${app.api.jwt-secret}")
    String secret;

    String slug;
    UUID store;
    UUID shirt;
    UUID mug;
    UUID draft;
    UUID smallShirt;
    UUID largeShirt;

    @BeforeEach
    void createCatalog() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE", "{\"ar\": \"متجر الورد\", \"en\": \"Rose Shop\"}");
        TenantContext.runAs(store, () -> {
            rates.set("118.5", null);
            var clothes = saved(categories.create(new CategoryDraft("ملابس", "Clothes", "clothes", null, 1)));
            shirt = published(new ProductDraft("قميص", "Shirt", "قطن", "Cotton", "shirt", clothes, Currency.SYP),
                    "1000");
            products.defineOptions(shirt, List.of(new OptionDraft("المقاس", "Size", "S, L", "S, L")));
            var variants = products.find(shirt).orElseThrow().variants();
            smallShirt = variants.get(0).id();
            largeShirt = variants.get(1).id();
            products.updateVariants(shirt, List.of(
                    new VariantDraft(smallShirt, null, "1000", null, true),
                    new VariantDraft(largeShirt, null, "1200", "1500", true)));
            inventory.setOnHand(largeShirt, 0);
            mug = published(new ProductDraft("كوب", "Mug", null, null, "mug", null, Currency.USD), "4.50");
            draft = saved(products.create(new ProductDraft("مسودة", "Draft", null, null, "draft", clothes,
                    Currency.SYP), "100"));
        });
    }

    @Test
    void findsAStoreBySlug() throws Exception {
        mvc.perform(get(API + "/stores/" + slug))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.id").value(store.toString()))
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.name.ar").value("متجر الورد"))
                .andExpect(jsonPath("$.name.en").value("Rose Shop"))
                .andExpect(jsonPath("$.defaultLanguage").value("ar"))
                .andExpect(jsonPath("$.displayCurrency").value("SYP"))
                .andExpect(jsonPath("$.url").value("http://" + slug + ".lvh.me:8080/"));
    }

    @Test
    void hidesStoresShoppersCannotVisit() throws Exception {
        mvc.perform(get(API + "/stores/no-such-store"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No such store."));

        jdbc.update("UPDATE store SET launched_at = NULL WHERE id = ?", store);
        mvc.perform(get(API + "/stores/" + slug)).andExpect(status().isNotFound());
        mvc.perform(withStore(get(API + "/products"))).andExpect(status().isNotFound());

        jdbc.update("UPDATE store SET launched_at = now(), status = 'SUSPENDED' WHERE id = ?", store);
        mvc.perform(get(API + "/stores/" + slug)).andExpect(status().isServiceUnavailable());
        mvc.perform(withStore(get(API + "/products"))).andExpect(status().isServiceUnavailable());

        jdbc.update("UPDATE store SET status = 'CLOSED' WHERE id = ?", store);
        mvc.perform(get(API + "/stores/" + slug)).andExpect(status().isNotFound());
    }

    @Test
    void listsVisibleProductsWithShopperPrices() throws Exception {
        mvc.perform(withStore(get(API + "/products")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").value(1))
                // Newest first; the draft is not listed.
                .andExpect(jsonPath("$.items[*].slug", contains("mug", "shirt")))
                // $4.50 at 118.5 with the default NEAREST_5 rule, as on the storefront.
                .andExpect(jsonPath("$.items[0].price.amount").value("535.00"))
                .andExpect(jsonPath("$.items[0].price.currency").value("SYP"))
                .andExpect(jsonPath("$.items[0].name.en").value("Mug"))
                .andExpect(jsonPath("$.items[1].price.amount").value("1000.00"))
                .andExpect(jsonPath("$.items[1].fromPrice").value(true));

        mvc.perform(withStore(get(API + "/products").param("sort", "price_desc")))
                .andExpect(jsonPath("$.items[*].slug", contains("shirt", "mug")));
        mvc.perform(withStore(get(API + "/products").param("category", "clothes")))
                .andExpect(jsonPath("$.items[*].slug", contains("shirt")));
        mvc.perform(withStore(get(API + "/products").param("q", "قميص")))
                .andExpect(jsonPath("$.items[*].slug", contains("shirt")));
        mvc.perform(withStore(get(API + "/products").param("page", "1")))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void returnsAProductWithItsVariants() throws Exception {
        mvc.perform(withStore(get(API + "/products/" + shirt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("shirt"))
                .andExpect(jsonPath("$.description.en").value("Cotton"))
                .andExpect(jsonPath("$.category.slug").value("clothes"))
                .andExpect(jsonPath("$.options[0].name.en").value("Size"))
                .andExpect(jsonPath("$.options[0].values[*].en", contains("S", "L")))
                .andExpect(jsonPath("$.variants", hasSize(2)))
                .andExpect(jsonPath("$.variants[0].id").value(smallShirt.toString()))
                .andExpect(jsonPath("$.variants[0].price.amount").value("1000.00"))
                .andExpect(jsonPath("$.variants[0].inStock").value(true))
                .andExpect(jsonPath("$.variants[0].compareAtPrice").doesNotExist())
                .andExpect(jsonPath("$.variants[1].compareAtPrice.amount").value("1500.00"))
                .andExpect(jsonPath("$.variants[1].inStock").value(false))
                .andExpect(jsonPath("$.images", hasSize(0)));

        mvc.perform(withStore(get(API + "/products/" + draft)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such product."));
        mvc.perform(withStore(get(API + "/products/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(withStore(get(API + "/products/not-an-id"))).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void neverShowsAnotherStoresProducts() throws Exception {
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        var theirs = TenantContext.callAs(other, () -> published(
                new ProductDraft("سر", "Secret", null, null, "secret", null, Currency.SYP), "100"));

        mvc.perform(withStore(get(API + "/products/" + theirs))).andExpect(status().isNotFound());
        mvc.perform(withStore(get(API + "/products")))
                .andExpect(jsonPath("$.items[*].slug", not(contains("secret"))))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(withStore(get(API + "/products").param("q", "Secret")))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get(API + "/products/" + theirs).header("X-Store-Id", other.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsBadCatalogRequestsWithProblemDetails() throws Exception {
        mvc.perform(get(API + "/products"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The X-Store-Id header is required."));
        mvc.perform(get(API + "/products").header("X-Store-Id", "demo1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("X-Store-Id is not a store id."));
        mvc.perform(get(API + "/products").header("X-Store-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(withStore(get(API + "/products").param("sort", "cheapest")))
                .andExpect(status().isBadRequest());
        mvc.perform(withStore(get(API + "/products").param("page", "-1"))).andExpect(status().isBadRequest());
        mvc.perform(withStore(get(API + "/products").param("page", "2147483647"))).andExpect(status().isBadRequest());
        mvc.perform(withStore(get(API + "/products").param("page", "x"))).andExpect(status().isBadRequest());
        mvc.perform(withStore(get(API + "/products").param("q", "a").param("category", "clothes")))
                .andExpect(status().isBadRequest());
        mvc.perform(withStore(get(API + "/products").param("category", "nothing")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such category."));
    }

    @Test
    void acceptsMerchantTokensNamingTheirStore() throws Exception {
        var token = token(store, Instant.now().plusSeconds(300));

        mvc.perform(get(API + "/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(withStore(get(API + "/products")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // A token reaches its store before launch; shoppers do not.
        jdbc.update("UPDATE store SET launched_at = NULL WHERE id = ?", store);
        mvc.perform(get(API + "/products").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        mvc.perform(get(API + "/products").header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void refusesInvalidTokens() throws Exception {
        var expired = token(store, Instant.now().minusSeconds(300));
        var forged = sign(store, Instant.now().plusSeconds(300), "another-secret-of-at-least-32-bytes!!");
        var endless = sign(store, null, secret);

        for (var bad : List.of(expired, forged, endless, "not-a-jwt")) {
            mvc.perform(withStore(get(API + "/products")).header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("A valid bearer token is required."));
        }
    }

    @Test
    void requiresATokenBeyondTheCatalog() throws Exception {
        mvc.perform(get(API + "/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post(API + "/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheApiOnTheMainHostOnly() throws Exception {
        mvc.perform(get(storefront(slug) + "/api/v1/products")).andExpect(status().isNotFound());
        mvc.perform(get(storefront(slug) + "/api/v1/stores/" + slug)).andExpect(status().isNotFound());
        // The platform host sends anyone not signed in to its login.
        mvc.perform(get(PLATFORM + "/api/v1/stores/" + slug))
                .andExpect(header().string("Location", containsString("/platform/login")));
    }

    @Test
    void publishesOpenApiDocs() throws Exception {
        mvc.perform(get(MAIN + "/api/docs/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Store API"))
                .andExpect(jsonPath("$.paths['/api/v1/stores/{slug}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/products'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/products/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/products'].get.parameters[?(@.name == 'X-Store-Id')]").exists())
                .andExpect(jsonPath("$.paths['/admin']").doesNotExist())
                .andExpect(jsonPath("$.paths['/']").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.schemas.Price.properties.amount.type").value("string"));

        mvc.perform(get(MAIN + "/api/docs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/api/swagger-ui/index.html")));
        mvc.perform(get(MAIN + "/api/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui-bundle.js")));
        mvc.perform(get(MAIN + "/api/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/docs/openapi.json")));
    }

    private MockHttpServletRequestBuilder withStore(MockHttpServletRequestBuilder request) {
        return request.header("X-Store-Id", store.toString());
    }

    private String token(UUID storeId, Instant expiresAt) {
        return sign(storeId, expiresAt, secret);
    }

    private static String sign(UUID storeId, Instant expiresAt, String key) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        var claims = JwtClaimsSet.builder().subject("owner").claim("store_id", storeId.toString())
                .issuedAt(Instant.now().minusSeconds(600));
        if (expiresAt != null) {
            claims.expiresAt(expiresAt);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private UUID published(ProductDraft draft, String price) {
        var id = saved(products.create(draft, price));
        products.changeStatus(id, ProductStatus.ACTIVE);
        return id;
    }

    private static UUID saved(Products.ProductResult result) {
        return ((Saved) result).id();
    }

    private static UUID saved(Categories.CategoryResult result) {
        return ((Categories.Saved) result).id();
    }
}
