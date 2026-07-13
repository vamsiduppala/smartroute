package com.vamsi.smartroute.routing;

/**
 * Thrown when a model call fails mid-escalation, after one or more EARLIER attempts in the
 * same request may have already succeeded and incurred real, billable cost.
 *
 * Before this existed, a failure here (network error, upstream 5xx, timeout, or even a
 * malformed-but-non-throwing response) propagated as a bare exception and any cost from prior
 * attempts in the same request was silently lost -- never booked to {@code SpendLedger}, never
 * recorded by {@code TelemetryService}, even though the tokens were genuinely spent. Callers
 * that care about that partial cost (currently {@code GatewayService} for the ledger,
 * {@code RouterTelemetryAspect} for telemetry) can catch this specifically, record
 * {@link #partialResult()}, and rethrow so the failure still propagates to the HTTP layer as
 * before.
 *
 * Note the distinction in {@link #partialResult()}: {@code attempts()} counts every loop
 * iteration INCLUDING the one that just failed (consistent with what a fully-successful
 * {@code RouteResult.attempts()} means elsewhere), while {@code attemptRecords()} only holds
 * the earlier attempts that actually got a usable response -- the failed one never gets a
 * record, since it produced no tokens/cost to record.
 */
public class PartialRouteException extends RuntimeException {

    private final RouteResult partialResult;

    public PartialRouteException(RouteResult partialResult, Throwable cause) {
        super("Attempt " + partialResult.attempts() + " failed; $" + partialResult.costUsd()
                + " already incurred from " + partialResult.attemptRecords().size()
                + " earlier successful attempt(s)", cause);
        this.partialResult = partialResult;
    }

    /** The cost/attempts/tokens/per-attempt records accumulated from earlier attempts before the one that failed. */
    public RouteResult partialResult() {
        return partialResult;
    }
}
