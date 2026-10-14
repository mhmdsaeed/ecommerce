package com.unispark.ecommerce.tenant.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreSummary;
import com.unispark.ecommerce.tenant.internal.persistence.Store;
import com.unispark.ecommerce.tenant.internal.persistence.StoreRepository;

@Service
@Transactional(readOnly = true)
class StoreDirectory implements StoreLookup {

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
}
