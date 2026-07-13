package com.vamsi.smartroute.governance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/governance")
@Tag(name = "governance", description = "Per-tenant spend caps, enforced before tokens are spent")
public class GovernanceController {

    private final SpendLedger ledger;
    private final BudgetGuard guard;

    public GovernanceController(SpendLedger ledger, BudgetGuard guard) {
        this.ledger = ledger;
        this.guard = guard;
    }

    @Operation(summary = "Get a tenant's spend and cap")
    @GetMapping("/spend/{tenant}")
    public SpendResponse spend(@PathVariable String tenant) {
        return new SpendResponse(tenant, ledger.spent(tenant), guard.capFor(tenant));
    }

    @Operation(summary = "Set a tenant's budget cap")
    @PutMapping("/budget/{tenant}")
    public BudgetResponse setBudget(@PathVariable String tenant, @RequestParam double capUsd) {
        if (capUsd < 0) {
            throw new IllegalArgumentException("capUsd must not be negative");
        }
        guard.setCap(tenant, capUsd);
        return new BudgetResponse(tenant, capUsd);
    }

    public record SpendResponse(String tenant, double spentUsd, double capUsd) {}

    public record BudgetResponse(String tenant, double capUsd) {}
}
