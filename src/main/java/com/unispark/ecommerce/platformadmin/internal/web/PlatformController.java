package com.unispark.ecommerce.platformadmin.internal.web;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.PlatformDatabase;

/** Platform admin login and dashboard. The dashboard lists the newest stores across all tenants. */
@Controller
class PlatformController {

    static final int RECENT_STORES = 50;
    /** Dates are shown in Syrian time (SPEC 18). */
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Damascus");

    private final PlatformDatabase platform;

    PlatformController(PlatformDatabase platform) {
        this.platform = platform;
    }

    @GetMapping("/platform/login")
    String login() {
        return "platformadmin/login";
    }

    @GetMapping("/platform")
    String dashboard(Model model) {
        var stores = platform.jdbc().sql("""
                        SELECT slug, name ->> 'ar' AS name_ar, name ->> 'en' AS name_en, status, created_at
                        FROM store ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("limit", RECENT_STORES)
                .query((row, index) -> new StoreRow(
                        row.getString("slug"),
                        LocalizedText.of(row.getString("name_ar"), row.getString("name_en")),
                        row.getString("status"),
                        row.getTimestamp("created_at").toInstant().atZone(DISPLAY_ZONE).toLocalDate()))
                .list();
        model.addAttribute("stores", stores);
        model.addAttribute("storeCount", platform.jdbc().sql("SELECT count(*) FROM store").query(Long.class).single());
        return "platformadmin/dashboard";
    }

    record StoreRow(String slug, LocalizedText name, String status, LocalDate createdOn) {
    }
}
