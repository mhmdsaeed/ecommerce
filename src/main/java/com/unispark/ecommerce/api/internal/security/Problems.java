package com.unispark.ecommerce.api.internal.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes RFC 9457 problem details from filters, which run before the API's controller advice can. The detail is a
 * fixed text for developers, never an exception message.
 */
final class Problems {

    private Problems() {
    }

    static void write(ObjectMapper json, HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String detail) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), problem);
    }
}
