CREATE TABLE outbox_events (
    event_id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    producer VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events(published, occurred_at);

CREATE INDEX idx_outbox_events_type_aggregate
    ON outbox_events(event_type, aggregate_id);
