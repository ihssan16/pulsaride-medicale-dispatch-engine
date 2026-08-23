package com.pulsaride.dispatch.api;

import java.time.OffsetDateTime;
import java.util.List;

public record AnalyticsSummaryResponse(
        OffsetDateTime generatedAt,
        DispatchMetricsResponse metrics,
        AvailabilitySummaryResponse availability,
        EventPublicationSummaryResponse events,
        List<ProfessionalLoadResponse> professionalLoads
) {
}
