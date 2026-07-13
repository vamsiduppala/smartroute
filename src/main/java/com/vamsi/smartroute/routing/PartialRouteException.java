package com.vamsi.smartroute.routing;

/**
 * Thrown when a model call fails mid-escalation, after one or more EARLIER attempts in the
 * same request may have already succeeded and incurred real, billable cost.
 *
 * Before this existed, a failure here (network error, upstream 5xx, timeout) propagated as a
 * bare exception and any cost from prior attempts in the same request was silently lost --
 * never booked to {@code SpendLedger}, never recorded by {@code TelemetryService}, even though
 * the tokens were genuinely spent. Callers that care about that partial cost (currently
 * {@code GatewayService} for the ledger, {@code RouterTelemetryAspect} for telemetry) can catch
 * this specifically, record {@link #partialResult()}, and rethrow so the failure still
 * propagates to the HTTP layer as before.
 */
public class PartialRouteException extends RuntimeException {

    private final RouteResult partialResult;

    public PartialRouteException(RouteResult partialResult, Throwable cause) {
        super("Model call failed after " + partialResult.attempts() + " attempt(s); $"
                + partialResult.costUsd() + " already incurred before the failure", cause);
        this.partialResult = partialResult;
    }

    /** The cost/attempts/tokens accumulated from earlier attempts before the one that failed. */
    public RouteResult partialResult() {
        return partialResult;
    }
}
