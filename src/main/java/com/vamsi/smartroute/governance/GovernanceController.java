package com.vamsi.smartroute.governance;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/governance")
public class GovernanceController {

    private final SpendLedger ledger;
    private final BudgetGuard guard;

    public GovernanceController(SpendLedger ledger, BudgetGuard guard) {
        this.ledger = ledger;
        this.guard = guard;
    }

    @GetMapping("/spend/{tenant}")
    public Map<String, Object> spend(@PathVariable String tenant) {
        return Map.of("tenant", tenant, "spentUsd", ledger.spent(tenant), "capUsd", guard.capFor(tenant));
    }

    @PutMapping("/budget/{tenant}")
    public Map<String, Object> setBudget(@PathVariable String tenant, @RequestParam double capUsd) {
        guard.setCap(tenant, capUsd);
        return Map.of("tenant", tenant, "capUsd", capUsd);
    }
}
