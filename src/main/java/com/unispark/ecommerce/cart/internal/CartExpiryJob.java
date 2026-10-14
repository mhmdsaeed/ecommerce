package com.unispark.ecommerce.cart.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Deletes abandoned carts every hour (SPEC 14), store by store inside each store's tenant context, so row-level
 * security applies as usual. One failing store does not stop the others. Moves to JobRunr with the other jobs (M11).
 */
@Component
class CartExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CartExpiryJob.class);

    private final StoreLookup stores;
    private final Carts carts;

    CartExpiryJob(StoreLookup stores, Carts carts) {
        this.stores = stores;
        this.carts = carts;
    }

    @Scheduled(cron = "${app.cart.expiry-cron:0 17 * * * *}")
    void deleteExpiredCarts() {
        var deleted = 0;
        for (var store : stores.allIds()) {
            try {
                deleted += TenantContext.callAs(store, carts::deleteExpired);
            } catch (RuntimeException e) {
                log.warn("Could not delete expired carts of store {}", store, e);
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} expired carts", deleted);
        }
    }
}
