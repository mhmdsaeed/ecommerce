package com.unispark.ecommerce.storefront.internal.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * M0 placeholder home page. Replaced by the tenant-resolved storefront home in M5.
 */
@Controller
class PlaceholderController {

    @GetMapping("/")
    String home() {
        return "storefront/placeholder";
    }
}
