package com.unispark.ecommerce.platformadmin.internal.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.PlatformDatabase;
import com.unispark.ecommerce.tenant.StoreStatus;

/** Every store across tenants, for the platform admin's lists. Reads the store table on the platform role. */
@Component
class StoreNames {

    private final PlatformDatabase platform;

    StoreNames(PlatformDatabase platform) {
        this.platform = platform;
    }

    /** Newest first; {@code status} null for all. */
    List<StoreRow> list(StoreStatus status) {
        return platform.jdbc().sql("""
                        SELECT id, slug, name ->> 'ar' AS name_ar, name ->> 'en' AS name_en, status, created_at
                        FROM store WHERE (CAST(:status AS TEXT) IS NULL OR status = :status)
                        ORDER BY created_at DESC
                        """)
                .param("status", status == null ? null : status.name())
                .query((row, index) -> new StoreRow(
                        row.getObject("id", UUID.class),
                        row.getString("slug"),
                        LocalizedText.of(row.getString("name_ar"), row.getString("name_en")),
                        StoreStatus.valueOf(row.getString("status")),
                        row.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** By id, in the order of {@link #list}. */
    Map<UUID, StoreRow> byId() {
        var rows = new LinkedHashMap<UUID, StoreRow>();
        list(null).forEach(row -> rows.put(row.id(), row));
        return rows;
    }

    record StoreRow(UUID id, String slug, LocalizedText name, StoreStatus status, Instant createdAt) {
    }
}
