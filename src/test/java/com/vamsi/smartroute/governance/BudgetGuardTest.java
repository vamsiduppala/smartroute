package com.vamsi.smartroute.governance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BudgetGuardTest {

    private BudgetGuard guardWith(double cap, double preSpent) {
        SpendLedger ledger = new SpendLedger();
        if (preSpent > 0) ledger.add("t", preSpent);
        return new BudgetGuard(ledger, cap);
    }

    @Test
    void allowsWhenComfortablyUnderCap() {
        assertEquals(BudgetGuard.Decision.ALLOW, guardWith(10.0, 0).evaluate("t", 1.0));
    }

    @Test
    void downgradesWhenCallWouldExceedCap() {
        assertEquals(BudgetGuard.Decision.DOWNGRADE, guardWith(10.0, 9.5).evaluate("t", 1.0));
    }

    @Test
    void rejectsWhenAtOrOverCap() {
        assertEquals(BudgetGuard.Decision.REJECT, guardWith(10.0, 10.0).evaluate("t", 0.5));
    }

    @Test
    void perTenantCapOverridesDefault() {
        BudgetGuard g = guardWith(10.0, 0);
        g.setCap("vip", 100.0);
        assertEquals(100.0, g.capFor("vip"), 1e-9);
        assertEquals(10.0, g.capFor("someone-else"), 1e-9);
    }
}
