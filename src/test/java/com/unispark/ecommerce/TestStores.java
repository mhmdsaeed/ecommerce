package com.unispark.ecommerce;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.unispark.ecommerce.shared.Ids;

/** Creates stores (tenants) directly in the database; each call uses a fresh slug so tests never collide. */
public final class TestStores {

    private TestStores() {
    }

    public static String randomSlug() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static UUID create(JdbcTemplate jdbc, String slug, String status) {
        return create(jdbc, slug, status, "{\"ar\": \"متجر " + slug + "\", \"en\": \"Store " + slug + "\"}");
    }

    public static UUID create(JdbcTemplate jdbc, String slug, String status, String nameJson) {
        var id = Ids.newId();
        jdbc.update("""
                INSERT INTO store (id, slug, name, status, created_at, updated_at, version)
                VALUES (?, ?, ?::jsonb, ?, now(), now(), 0)
                """, id, slug, nameJson, status);
        return id;
    }
}
