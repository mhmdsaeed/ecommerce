package com.unispark.ecommerce.merchantadmin.internal.web.orders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.identity.StaffDirectory;
import com.unispark.ecommerce.identity.StaffDirectory.StaffCard;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.order.CodSettlement.InvalidAmount;
import com.unispark.ecommerce.order.CodSettlement.MoreThanOutstanding;
import com.unispark.ecommerce.order.CodSettlement.NotADriver;
import com.unispark.ecommerce.order.CodSettlement.Remitted;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.ListParams;

/**
 * Cash-on-delivery settlement (SPEC 8, 9.3): per driver, what they collected, handed over and still hold; a driver's
 * statement; and recording the cash they hand over. Owners and managers only: this is the store's money.
 */
@Controller
@RequestMapping("/admin/settlement")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class SettlementController {

    private final CodSettlement settlement;
    private final StaffDirectory staff;

    SettlementController(CodSettlement settlement, StaffDirectory staff) {
        this.settlement = settlement;
        this.staff = staff;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("rows", SettlementViews.rows(settlement.balances(), names()));
        return "merchantadmin/settlement/list";
    }

    @GetMapping("/{driverId}")
    String statement(@PathVariable UUID driverId, @RequestParam(required = false) String done,
            @RequestParam(required = false) String error, Model model) {
        var driver = staff.find(driverId).filter(card -> card.role() == StaffRole.DRIVER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("driver", driver);
        var statement = settlement.statement(driverId);
        model.addAttribute("statement", statement);
        // The remit form starts on the first currency the driver still holds, empty when everything is handed over.
        model.addAttribute("due", statement.balances().stream()
                .filter(balance -> balance.outstanding().isPositive()).findFirst().orElse(null));
        model.addAttribute("lines", SettlementViews.lines(statement, names()));
        model.addAttribute("currencies", Currency.values());
        model.addAttribute("done", "remitted".equals(done) ? "settlement.done.remitted" : null);
        model.addAttribute("error", error != null && Set.of("amount", "too-much").contains(error)
                ? "settlement.error." + error : null);
        return "merchantadmin/settlement/statement";
    }

    @PostMapping("/{driverId}/remit")
    String remit(@PathVariable UUID driverId, @RequestParam(defaultValue = "") String amount,
            @RequestParam(name = "currency", required = false) String currency,
            @RequestParam(required = false) String note, @AuthenticationPrincipal StaffPrincipal actor) {
        var chosen = ListParams.constant(Currency.class, currency).orElse(null);
        return switch (settlement.remit(driverId, amount, chosen, note, actor.staffId())) {
            case Remitted remitted -> "redirect:/admin/settlement/" + driverId + "?done=remitted";
            case InvalidAmount invalid -> "redirect:/admin/settlement/" + driverId + "?error=amount";
            case MoreThanOutstanding tooMuch -> "redirect:/admin/settlement/" + driverId + "?error=too-much";
            case NotADriver notADriver -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    private Map<UUID, String> names() {
        return staff.all().stream().collect(Collectors.toMap(StaffCard::id, StaffCard::name));
    }
}
