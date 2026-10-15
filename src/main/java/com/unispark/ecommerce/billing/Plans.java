package com.unispark.ecommerce.billing;

import java.util.List;
import java.util.Optional;

import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/** What stores can subscribe to (SPEC 5 {@code plan}). Prices are monthly, in dollars. */
public interface Plans {

    int MAX_NAME_LENGTH = 60;

    /** Active plans first, then by price. */
    List<PlanView> all();

    Optional<PlanView> find(String code);

    /** Creates the plan, or updates the one with this code. */
    SaveResult save(PlanDraft draft);

    /**
     * @param limits what a store on the plan may have; enforcing them comes later
     * @param active inactive plans are kept for the stores on them but offered to no one else
     */
    record PlanView(String code, LocalizedText name, Money monthlyPrice, Limits limits, boolean active) {
    }

    /** Each limit is null for "no limit". */
    record Limits(Integer products, Integer staff, Integer ordersPerMonth) {

        public static final Limits NONE = new Limits(null, null, null);
    }

    /** The plan form as submitted: numbers as typed, so they can be reported back. */
    record PlanDraft(String code, String nameAr, String nameEn, String monthlyPrice, String products, String staff,
            String ordersPerMonth, boolean active) {
    }

    sealed interface SaveResult {
    }

    record Saved(String code) implements SaveResult {
    }

    record Invalid(List<FieldProblem> problems) implements SaveResult {
    }
}
