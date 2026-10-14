package com.unispark.ecommerce.cart.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.cart.internal.persistence.Cart;
import com.unispark.ecommerce.cart.internal.persistence.CartItem;
import com.unispark.ecommerce.cart.internal.persistence.CartRepository;
import com.unispark.ecommerce.catalog.StorefrontCatalog;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.pricing.Prices;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Carts. Changes lock the cart row first, so two tabs changing one cart apply one after the other. What may be
 * bought comes from the storefront catalog (one visibility rule for pages and cart), and how many from inventory.
 */
@Service
@Transactional
class CartService implements Carts {

    private final CartRepository carts;
    private final StorefrontCatalog catalog;
    private final Inventory inventory;
    private final Prices prices;
    private final JdbcClient jdbc;
    private final Clock clock;

    CartService(CartRepository carts, StorefrontCatalog catalog, Inventory inventory, Prices prices, JdbcClient jdbc,
            Clock clock) {
        this.carts = carts;
        this.catalog = catalog;
        this.inventory = inventory;
        this.prices = prices;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CartView view(String token) {
        return live(token, false).map(this::view).orElse(CartView.EMPTY);
    }

    @Override
    @Transactional(readOnly = true)
    public int count(String token) {
        if (!CartTokens.isWellFormed(token)) {
            return 0;
        }
        return (int) carts.countItems(CartTokens.sessionKey(token), clock.instant());
    }

    @Override
    public AddOutcome add(String token, UUID variantId, int quantity) {
        var existing = live(token, true);
        var currentToken = existing.isPresent() ? token : null;
        var currentCount = existing.map(Cart::itemCount).orElse(0);
        if (!catalog.offers(Set.of(variantId)).containsKey(variantId)) {
            return new AddOutcome(currentToken, currentCount, Notice.UNAVAILABLE);
        }
        var inCart = existing.flatMap(cart -> cart.itemFor(variantId)).map(CartItem::getQuantity).orElse(0);
        if (inCart == 0 && existing.map(cart -> cart.getItems().size()).orElse(0) >= MAX_LINES) {
            return new AddOutcome(currentToken, currentCount, Notice.CART_FULL);
        }
        var available = inventory.levels(Set.of(variantId)).available(variantId);
        var cap = Math.min(MAX_QUANTITY, available.orElse(MAX_QUANTITY));
        if (cap <= inCart) {
            var notice = inCart == 0 ? Notice.SOLD_OUT : Notice.LIMITED;
            return new AddOutcome(currentToken, currentCount, notice);
        }
        var wanted = inCart + Math.max(quantity, 1);
        var kept = Math.min(wanted, cap);

        var newToken = currentToken == null ? CartTokens.newToken() : currentToken;
        var cart = existing.orElseGet(() -> new Cart(CartTokens.sessionKey(newToken), expiry()));
        cart.put(variantId, kept);
        cart.touch(expiry());
        carts.save(cart);
        return new AddOutcome(newToken, cart.itemCount(), kept < wanted ? Notice.LIMITED : null);
    }

    @Override
    public Change update(String token, UUID itemId, int quantity) {
        var cart = live(token, true);
        if (cart.isEmpty()) {
            return new Change(CartView.EMPTY, null);
        }
        var item = cart.get().item(itemId);
        if (item.isEmpty()) {
            return new Change(view(cart.get()), null);
        }
        if (quantity <= 0) {
            return remove(cart.get(), item.get());
        }
        var wanted = Math.min(quantity, MAX_QUANTITY);
        Notice notice = wanted < quantity ? Notice.LIMITED : null;
        var available = inventory.levels(Set.of(item.get().getVariantId())).available(item.get().getVariantId());
        if (available.isPresent() && wanted > available.getAsInt() && wanted > item.get().getQuantity()) {
            // Raising past the stock: go up to what is left, if anything. Lowering is always allowed.
            if (available.getAsInt() <= item.get().getQuantity()) {
                return new Change(view(cart.get()), Notice.LIMITED);
            }
            wanted = available.getAsInt();
            notice = Notice.LIMITED;
        }
        cart.get().put(item.get().getVariantId(), wanted);
        cart.get().touch(expiry());
        return new Change(view(cart.get()), notice);
    }

    @Override
    public Change remove(String token, UUID itemId) {
        var cart = live(token, true);
        if (cart.isEmpty()) {
            return new Change(CartView.EMPTY, null);
        }
        return cart.get().item(itemId).map(item -> remove(cart.get(), item))
                .orElseGet(() -> new Change(view(cart.get()), null));
    }

    @Override
    public int deleteExpired() {
        // Items go with their cart (ON DELETE CASCADE).
        return jdbc.sql("DELETE FROM cart WHERE tenant_id = :tenant AND expires_at <= :now")
                .param("tenant", TenantContext.requireTenantId())
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    private Change remove(Cart cart, CartItem item) {
        cart.remove(item);
        cart.touch(expiry());
        return new Change(view(cart), null);
    }

    private Optional<Cart> live(String token, boolean forUpdate) {
        if (!CartTokens.isWellFormed(token)) {
            return Optional.empty();
        }
        var key = CartTokens.sessionKey(token);
        return forUpdate ? carts.findLiveForUpdate(key, clock.instant()) : carts.findLive(key, clock.instant());
    }

    private Instant expiry() {
        return clock.instant().plus(LIFETIME);
    }

    private CartView view(Cart cart) {
        var variantIds = cart.getItems().stream().map(CartItem::getVariantId).toList();
        var offers = catalog.offers(variantIds);
        var stock = inventory.levels(variantIds);
        var lines = new ArrayList<Line>();
        var totals = new LinkedHashMap<Currency, BigDecimal>();
        for (var item : cart.getItems()) {
            var offer = offers.get(item.getVariantId());
            if (offer == null) {
                lines.add(new Line(item.getId(), item.getVariantId(), null, null, List.of(), null, item.getQuantity(),
                        null, null, null, Problem.UNAVAILABLE));
                continue;
            }
            var unit = prices.display(offer.price()).amount();
            var lineTotal = Money.of(unit.amount().multiply(BigDecimal.valueOf(item.getQuantity())), unit.currency());
            totals.merge(unit.currency(), lineTotal.amount(), BigDecimal::add);
            var available = stock.available(item.getVariantId());
            var left = available.isPresent() ? Integer.valueOf(available.getAsInt()) : null;
            var problem = left != null && item.getQuantity() > left ? Problem.NOT_ENOUGH_STOCK : null;
            lines.add(new Line(item.getId(), item.getVariantId(), offer.productName(), offer.productSlug(),
                    offer.choices(), offer.image(), item.getQuantity(), unit, lineTotal, left, problem));
        }
        return new CartView(List.copyOf(lines),
                totals.entrySet().stream().map(total -> Money.of(total.getValue(), total.getKey())).toList(),
                cart.itemCount());
    }
}
