package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.util.HtmlUtils;

import com.unispark.ecommerce.shared.Htmx;

/**
 * Uploads over the multipart limit fail before any controller runs, so there is no section to re-render. HTMX
 * requests get the message swapped into the form's {@code #upload-error} slot instead (a 4xx would not be
 * swapped at all), leaving the images on the page untouched.
 */
@ControllerAdvice
class UploadLimitAdvice {

    static final String ERROR_SLOT = "#upload-error";

    private final MessageSource messages;

    UploadLimitAdvice(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<String> tooLarge(HttpServletRequest request, Locale locale) {
        var message = HtmlUtils.htmlEscape(messages.getMessage("image.request-too-large", null, locale));
        if (!Htmx.isHtmx(request)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).contentType(MediaType.TEXT_PLAIN).body(message);
        }
        return ResponseEntity.ok()
                .header("HX-Retarget", ERROR_SLOT)
                .header("HX-Reswap", "innerHTML")
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(message);
    }
}
