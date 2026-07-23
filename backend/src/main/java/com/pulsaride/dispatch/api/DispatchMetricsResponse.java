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
        double failureRatePct,
        Double avgTtfaMs,
        Double p95TtfaMs,
        Double avgTtrMs,
        Double p95TtrMs,
        Double avgDegradedReassignmentMs,
        Double p95DegradedReassignmentMs,
        double giniFairness
) {
}
