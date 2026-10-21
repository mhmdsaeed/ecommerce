package com.unispark.ecommerce.merchantadmin.internal.web;

import java.net.URI;
import java.net.URISyntaxException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.unispark.ecommerce.identity.ColorScheme;
import com.unispark.ecommerce.identity.ColorSchemes;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.shared.ListParams;

/**
 * The Light / Dark / Device switch in the admin and driver menus: remembers the choice on the member's account and
 * goes back to the page they were on (same area only).
 */
@Controller
class ColorSchemeController {

    private final ColorSchemes schemes;

    ColorSchemeController(ColorSchemes schemes) {
        this.schemes = schemes;
    }

    @PostMapping({"/admin/scheme", "/driver/scheme"})
    String choose(@RequestParam(required = false) String scheme, @AuthenticationPrincipal StaffPrincipal actor,
            HttpServletRequest request) {
        var area = request.getRequestURI().startsWith(request.getContextPath() + "/driver") ? "/driver" : "/admin";
        ListParams.constant(ColorScheme.class, scheme).ifPresent(chosen -> schemes.setForStaff(actor.staffId(), chosen));
        return "redirect:" + backTo(request, area);
    }

    /** The page the switch was on, when it is in the same area; else the area's start page. */
    static String backTo(HttpServletRequest request, String area) {
        var referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null) {
            return area;
        }
        try {
            var uri = new URI(referer);
            var path = uri.getRawPath();
            if (path == null || !(path.equals(area) || path.startsWith(area + "/"))
                    || uri.getHost() != null && !uri.getHost().equalsIgnoreCase(request.getServerName())) {
                return area;
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (URISyntaxException e) {
            return area;
        }
    }
}
