package com.vamsi.smartroute.governance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
    public Map<String, Object> spend(@PathVariable String tenant) {
        return Map.of("tenant", tenant, "spentUsd", ledger.spent(tenant), "capUsd", guard.capFor(tenant));
    }

    @Operation(summary = "Set a tenant's budget cap")
    @PutMapping("/budget/{tenant}")
    public Map<String, Object> setBudget(@PathVariable String tenant, @RequestParam double capUsd) {
        guard.setCap(tenant, capUsd);
        return Map.of("tenant", tenant, "capUsd", capUsd);
    }
}
