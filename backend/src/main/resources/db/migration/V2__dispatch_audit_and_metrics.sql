CREATE TABLE assignments (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    professional_id VARCHAR(64) NOT NULL,
    strategy VARCHAR(16) NOT NULL,
    proposed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    accepted_at TIMESTAMP WITH TIME ZONE,
    refused_at TIMESTAMP WITH TIME ZONE,
    timed_out_at TIMESTAMP WITH TIME ZONE,
    outcome VARCHAR(32) NOT NULL DEFAULT 'PROPOSED',
    CONSTRAINT fk_assignments_request
        FOREIGN KEY (request_id) REFERENCES dispatch_requests(id),
    CONSTRAINT fk_assignments_professional
        FOREIGN KEY (professional_id) REFERENCES professionals(id)
);

CREATE TABLE state_transitions (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    reason VARCHAR(255),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_state_transitions_request
        FOREIGN KEY (request_id) REFERENCES dispatch_requests(id)
);

CREATE TABLE simulation_runs (
    id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    seed INTEGER NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE metrics_results (
    id BIGSERIAL PRIMARY KEY,
    simulation_run_id VARCHAR(80),
    strategy VARCHAR(16),
    metric_name VARCHAR(80) NOT NULL,
    metric_value DOUBLE PRECISION NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_metrics_results_simulation_run
        FOREIGN KEY (simulation_run_id) REFERENCES simulation_runs(id)
);

CREATE TABLE professional_embeddings (
    professional_id VARCHAR(64) PRIMARY KEY,
    embedding_json TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_professional_embeddings_professional
        FOREIGN KEY (professional_id) REFERENCES professionals(id)
);
