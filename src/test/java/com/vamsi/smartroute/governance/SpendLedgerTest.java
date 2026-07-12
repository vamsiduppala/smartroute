package com.vamsi.smartroute.governance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpendLedgerTest {

    @Test
    void accumulatesPerTenant() {
        SpendLedger l = new SpendLedger();
        l.add("a", 1.5);
        l.add("a", 2.0);
        assertEquals(3.5, l.spent("a"), 1e-9);
        assertEquals(0.0, l.spent("b"), 1e-9);
    }
}
