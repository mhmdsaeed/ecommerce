package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.util.List;

import org.springframework.validation.BindingResult;

import com.unispark.ecommerce.shared.FieldProblem;

/**
 * Puts service problems on the form. The message key drops list indexes, so {@code variants[3].price/invalid}
 * shows on that row with the message {@code <prefix>.variants.price.invalid}.
 */
final class FormErrors {

    private FormErrors() {
    }

    static void apply(BindingResult binding, String prefix, List<FieldProblem> problems) {
        for (var problem : problems) {
            var key = prefix + "." + problem.field().replaceAll("\\[\\d+]", "") + "." + problem.code();
            if (binding.getTarget() != null && isProperty(binding, problem.field())) {
                binding.rejectValue(problem.field(), key);
            } else {
                binding.reject(key);
            }
        }
    }

    private static boolean isProperty(BindingResult binding, String field) {
        try {
            return binding.getFieldType(field) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
