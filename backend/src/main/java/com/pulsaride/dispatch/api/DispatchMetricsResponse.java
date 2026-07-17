package com.pulsaride.dispatch.api;

public record DispatchMetricsResponse(
        long totalRequests,
        long pendingRequests,
        long proposedRequests,
        long acceptedRequests,
        long closedRequests,
        long failedRequests,
        long totalAssignments,
        long refusedAssignments,
        long timedOutAssignments,
        double serviceRatePct,
        double refusalRatePct,
        double timeoutRatePct,
        Double avgTtfaMs,
        Double avgTtrMs,
        double giniFairness
) {
}
