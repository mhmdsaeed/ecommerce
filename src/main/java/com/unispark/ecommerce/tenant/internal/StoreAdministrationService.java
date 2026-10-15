package com.unispark.ecommerce.tenant.internal;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.tenant.StoreActivated;
import com.unispark.ecommerce.tenant.StoreAdministration;
import com.unispark.ecommerce.tenant.StoreStatus;
import com.unispark.ecommerce.tenant.SuspensionKind;
import com.unispark.ecommerce.tenant.internal.persistence.Store;
import com.unispark.ecommerce.tenant.internal.persistence.StoreRepository;

@Service
@Transactional
class StoreAdministrationService implements StoreAdministration {

    private static final Logger log = LoggerFactory.getLogger(StoreAdministrationService.class);
    static final int MAX_TEXT_LENGTH = 1000;

    private final StoreRepository stores;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    StoreAdministrationService(StoreRepository stores, Clock clock, ApplicationEventPublisher events) {
        this.stores = stores;
        this.clock = clock;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreRecord> find(UUID storeId) {
        return stores.findById(storeId).map(StoreAdministrationService::record);
    }

    @Override
    public StatusChange suspend(UUID storeId, String reason) {
        var text = clean(reason);
        if (text == null) {
            return new ReasonRequired();
        }
        var store = stores.findByIdForUpdate(storeId);
        if (store.isEmpty() || store.get().getStatus() == StoreStatus.CLOSED
                || store.get().getSuspensionKind() == SuspensionKind.MANUAL) {
            return new NotApplicable();
        }
        // A billing suspension becomes the admin's own, so paying no longer lifts it.
        store.get().suspend(SuspensionKind.MANUAL, text);
        log.info("Store {} suspended by the platform admin", store.get().getSlug());
        return new Changed(StoreStatus.SUSPENDED);
    }

    @Override
    public StatusChange activate(UUID storeId, boolean screened, String notes, String by) {
        var found = stores.findByIdForUpdate(storeId);
        if (found.isEmpty() || found.get().getStatus() == StoreStatus.ACTIVE
                || found.get().getStatus() == StoreStatus.CLOSED) {
            return new NotApplicable();
        }
        var store = found.get();
        if (store.getScreenedAt() == null) {
            if (!screened) {
                return new ScreeningRequired();
            }
            store.screened(clock.instant(), by, clean(notes));
        }
        store.reopen();
        // Billing resumes a subscription it had suspended; the store is open again either way.
        events.publishEvent(new StoreActivated(storeId));
        log.info("Store {} activated by {}", store.getSlug(), by);
        return new Changed(store.getStatus());
    }

    @Override
    public boolean suspendForNonPayment(UUID storeId, String reason) {
        var store = stores.findByIdForUpdate(storeId)
                .filter(found -> found.getStatus() == StoreStatus.TRIAL || found.getStatus() == StoreStatus.ACTIVE);
        store.ifPresent(found -> {
            found.suspend(SuspensionKind.BILLING, reason);
            log.info("Store {} suspended for non-payment", found.getSlug());
        });
        return store.isPresent();
    }

    @Override
    public boolean resumeAfterPayment(UUID storeId) {
        var store = stores.findByIdForUpdate(storeId)
                .filter(found -> found.getSuspensionKind() == SuspensionKind.BILLING);
        store.ifPresent(found -> {
            found.reopen();
            log.info("Store {} reopened after payment", found.getSlug());
        });
        return store.isPresent();
    }

    private static StoreRecord record(Store store) {
        return new StoreRecord(store.getId(), store.getSlug(), store.getName(), store.getStatus(),
                store.getContactPhone(), store.getCreatedAt(), store.getSuspensionKind(), store.getSuspensionReason(),
                store.getScreenedAt(), store.getScreenedBy(), store.getScreeningNotes());
    }

    private static String clean(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var stripped = text.strip();
        return stripped.length() <= MAX_TEXT_LENGTH ? stripped : stripped.substring(0, MAX_TEXT_LENGTH);
    }
}
