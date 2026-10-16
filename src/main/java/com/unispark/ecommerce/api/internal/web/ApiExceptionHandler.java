package com.unispark.ecommerce.api.internal.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Problem details (RFC 9457) for the API's errors, while pages keep the localized error templates (problem details
 * are off globally). Spring's own exceptions (bad parameter types, wrong method…) are handled by the base class;
 * anything unexpected is a 500 without its message.
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        log.error("API request failed", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side.");
    }
}
