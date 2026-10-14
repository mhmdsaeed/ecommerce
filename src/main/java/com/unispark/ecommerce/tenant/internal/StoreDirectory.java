package com.unispark.ecommerce.tenant.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreRegistration;
import com.unispark.ecommerce.tenant.StoreSlugs;
import com.unispark.ecommerce.tenant.StoreStatus;
import com.unispark.ecommerce.tenant.StoreSummary;
import com.unispark.ecommerce.tenant.internal.persistence.Store;
import com.unispark.ecommerce.tenant.internal.persistence.StoreRepository;

@Service
@Transactional(readOnly = true)
class StoreDirectory implements StoreLookup, StoreRegistration {

    private final StoreRepository stores;

    StoreDirectory(StoreRepository stores) {
        this.stores = stores;
    }

    @Override
    public Optional<StoreSummary> findBySlug(String slug) {
        return stores.findBySlug(slug).map(Store::toSummary);
    }

    @Override
    public Optional<StoreSummary> findById(UUID id) {
        return stores.findById(id).map(Store::toSummary);
    }

    @Override
    public SlugCheck checkSlug(String slug) {
        if (!StoreSlugs.hasValidFormat(slug)) {
            return SlugCheck.INVALID_FORMAT;
        }
        if (StoreSlugs.isReserved(slug)) {
            return SlugCheck.RESERVED;
        }
        return stores.existsBySlug(slug) ? SlugCheck.TAKEN : SlugCheck.AVAILABLE;
    }

    @Override
    @Transactional
    public StoreSummary register(NewStore request) {
        var check = checkSlug(request.slug());
        if (check != SlugCheck.AVAILABLE) {
            throw new SlugUnavailableException(request.slug(), check);
        }
        var store = new Store(request.id(), request.slug(), request.name(), StoreStatus.TRIAL, request.defaultLocale());
        store.setContactPhone(request.contactPhone());
        try {
            // Flush now so a concurrent signup that took the slug fails here, as a slug error.
            return stores.saveAndFlush(store).toSummary();
        } catch (DataIntegrityViolationException e) {
            throw new SlugUnavailableException(request.slug(), SlugCheck.TAKEN);
        }
    }

    @Override
    @Transactional
    public void recordCreator(UUID storeId, UUID createdBy) {
        stores.findById(storeId).orElseThrow().recordCreator(createdBy);
    }
}
