package com.unispark.ecommerce.platformadmin.internal.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.unispark.ecommerce.identity.ColorScheme;
import com.unispark.ecommerce.identity.ColorSchemes;
import com.unispark.ecommerce.shared.ListParams;

/** The Light / Dark / Device switch in the platform admin menu; goes back to the page it was on. */
@Controller
class PlatformColorSchemeController {

    private final ColorSchemes schemes;

    PlatformColorSchemeController(ColorSchemes schemes) {
        this.schemes = schemes;
    }

    @PostMapping("/platform/scheme")
    String choose(@RequestParam(required = false) String scheme, Principal principal, HttpServletRequest request) {
        ListParams.constant(ColorScheme.class, scheme)
                .ifPresent(chosen -> schemes.setForPlatformUser(principal.getName(), chosen));
        return "redirect:" + backTo(request.getHeader(HttpHeaders.REFERER), request.getServerName());
    }

    /** The page the switch was on, when it is a platform admin page on this host; else the dashboard. */
    static String backTo(String referer, String host) {
        if (referer == null) {
            return "/platform";
        }
        try {
            var uri = new URI(referer);
            var path = uri.getRawPath();
            if (path == null || !(path.equals("/platform") || path.startsWith("/platform/"))
                    || uri.getHost() != null && !uri.getHost().equalsIgnoreCase(host)) {
                return "/platform";
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (URISyntaxException e) {
            return "/platform";
        }
    }
}
