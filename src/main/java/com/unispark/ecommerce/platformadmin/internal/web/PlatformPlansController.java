package com.unispark.ecommerce.platformadmin.internal.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.billing.Plans;
import com.unispark.ecommerce.billing.Plans.Invalid;
import com.unispark.ecommerce.billing.Plans.PlanDraft;
import com.unispark.ecommerce.billing.Plans.PlanView;
import com.unispark.ecommerce.billing.Plans.Saved;

/** Plans (SPEC 9.4): prices and limits. A plan's code is fixed once created; stores refer to it. */
@Controller
@RequestMapping("/platform/plans")
class PlatformPlansController {

    private final Plans plans;

    PlatformPlansController(Plans plans) {
        this.plans = plans;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("plans", plans.all());
        return "platformadmin/plans/list";
    }

    @GetMapping("/new")
    String create(Model model) {
        return form(new PlanForm(), true, model);
    }

    @GetMapping("/{code}")
    String edit(@PathVariable String code, Model model) {
        var plan = plans.find(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return form(PlanForm.of(plan), false, model);
    }

    @PostMapping
    String save(@ModelAttribute("form") PlanForm form, Model model) {
        var creating = plans.find(form.getCode()).isEmpty();
        return switch (plans.save(form.toDraft())) {
            case Saved saved -> "redirect:/platform/plans";
            case Invalid invalid -> {
                var errors = new LinkedHashMap<String, String>();
                invalid.problems().forEach(problem ->
                        errors.put(problem.field(), "platform.plan." + problem.field() + "." + problem.code()));
                model.addAttribute("errors", errors);
                yield form(form, creating, model);
            }
        };
    }

    private static String form(PlanForm form, boolean creating, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("creating", creating);
        model.asMap().putIfAbsent("errors", Map.of());
        return "platformadmin/plans/form";
    }

    /** The plan form; numbers stay text so typed values come back as typed. */
    static class PlanForm {

        private String code;
        private String nameAr;
        private String nameEn;
        private String monthlyPrice;
        private String products;
        private String staff;
        private String ordersPerMonth;
        private boolean active = true;

        static PlanForm of(PlanView plan) {
            var form = new PlanForm();
            form.code = plan.code();
            form.nameAr = plan.name().ar();
            form.nameEn = plan.name().en();
            form.monthlyPrice = plan.monthlyPrice().amount().stripTrailingZeros().toPlainString();
            form.products = text(plan.limits().products());
            form.staff = text(plan.limits().staff());
            form.ordersPerMonth = text(plan.limits().ordersPerMonth());
            form.active = plan.active();
            return form;
        }

        PlanDraft toDraft() {
            return new PlanDraft(code, nameAr, nameEn, monthlyPrice, products, staff, ordersPerMonth, active);
        }

        private static String text(Integer value) {
            return value == null ? "" : value.toString();
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getNameAr() {
            return nameAr;
        }

        public void setNameAr(String nameAr) {
            this.nameAr = nameAr;
        }

        public String getNameEn() {
            return nameEn;
        }

        public void setNameEn(String nameEn) {
            this.nameEn = nameEn;
        }

        public String getMonthlyPrice() {
            return monthlyPrice;
        }

        public void setMonthlyPrice(String monthlyPrice) {
            this.monthlyPrice = monthlyPrice;
        }

        public String getProducts() {
            return products;
        }

        public void setProducts(String products) {
            this.products = products;
        }

        public String getStaff() {
            return staff;
        }

        public void setStaff(String staff) {
            this.staff = staff;
        }

        public String getOrdersPerMonth() {
            return ordersPerMonth;
        }

        public void setOrdersPerMonth(String ordersPerMonth) {
            this.ordersPerMonth = ordersPerMonth;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }
    }
}
