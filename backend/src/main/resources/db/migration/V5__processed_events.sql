CREATE TABLE processed_events (
    event_id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(120),
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome VARCHAR(40) NOT NULL
);

CREATE INDEX idx_processed_events_type_processed_at
    ON processed_events(event_type, processed_at);
