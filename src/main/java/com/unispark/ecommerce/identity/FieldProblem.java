package com.unispark.ecommerce.identity;

/**
 * A business-rule failure on one form field, e.g. {@code ("slug", "taken")}. Web controllers turn it into the
 * message {@code <form>.<field>.<code>}.
 */
public record FieldProblem(String field, String code) {
}
