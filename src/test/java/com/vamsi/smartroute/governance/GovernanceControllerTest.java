package com.vamsi.smartroute.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test for /governance/*. Uses REAL SpendLedger/BudgetGuard (pure in-memory, no
 * external deps) rather than mocks, since the interesting behavior lives in their wiring.
 */
@WebMvcTest(GovernanceController.class)
@Import({SpendLedger.class, BudgetGuard.class})
class GovernanceControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SpendLedger ledger;

    // Distinct tenant per test: the Spring context (and its SpendLedger/BudgetGuard singletons)
    // is cached and reused across test methods in this class, so shared tenant ids would leak
    // state between tests.

    @Test
    void spendReflectsDefaultCapWhenUnset() throws Exception {
        mvc.perform(get("/governance/spend/tenant-defaultcap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value("tenant-defaultcap"))
                .andExpect(jsonPath("$.spentUsd").value(0.0))
                .andExpect(jsonPath("$.capUsd").value(10.0));
    }

    @Test
    void settingBudgetChangesSubsequentCap() throws Exception {
        mvc.perform(put("/governance/budget/tenant-setcap").param("capUsd", "25.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capUsd").value(25.5));

        mvc.perform(get("/governance/spend/tenant-setcap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capUsd").value(25.5));
    }

    @Test
    void negativeCapIsRejected() throws Exception {
        mvc.perform(put("/governance/budget/tenant-negativecap").param("capUsd", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("capUsd must not be negative"));
    }

    @Test
    void spendEndpointReflectsLedgerBooking() throws Exception {
        ledger.add("tenant-spend", 3.25);

        mvc.perform(get("/governance/spend/tenant-spend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentUsd").value(3.25));
    }

    @Test
    void nonNumericCapIsRejectedAsBadRequestNotServerError() throws Exception {
        mvc.perform(put("/governance/budget/tenant-badcap").param("capUsd", "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingCapParamIsRejectedAsBadRequestNotServerError() throws Exception {
        mvc.perform(put("/governance/budget/tenant-nocap"))
                .andExpect(status().isBadRequest());
    }
}
