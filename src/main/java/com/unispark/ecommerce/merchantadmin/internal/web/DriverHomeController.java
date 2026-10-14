package com.unispark.ecommerce.merchantadmin.internal.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Driver home. Assigned deliveries and COD collection arrive with delivery (M10). */
@Controller
class DriverHomeController {

    @GetMapping("/driver")
    String home() {
        return "driver/home";
    }
}
