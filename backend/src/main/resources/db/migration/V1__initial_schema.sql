CREATE TABLE professionals (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    specialty_tag VARCHAR(80) NOT NULL,
    experience_years INTEGER NOT NULL,
    profile_text TEXT NOT NULL,
    quota_max_per_hour INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    consultations_today INTEGER NOT NULL DEFAULT 0,
    load DOUBLE PRECISION NOT NULL DEFAULT 0
);

CREATE TABLE dispatch_requests (
    id VARCHAR(64) PRIMARY KEY,
    patient_id VARCHAR(80) NOT NULL,
    patient_text TEXT NOT NULL,
    specialty_hint VARCHAR(80) NOT NULL,
    urgency_score INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    assigned_professional_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    proposed_at TIMESTAMP WITH TIME ZONE,
    accepted_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    ttfa_ms BIGINT,
    ttr_ms BIGINT,
    failure_reason VARCHAR(255),
    CONSTRAINT fk_dispatch_request_professional
        FOREIGN KEY (assigned_professional_id) REFERENCES professionals(id)
);

CREATE INDEX idx_professionals_status_specialty ON professionals(status, specialty_tag);
CREATE INDEX idx_dispatch_requests_status ON dispatch_requests(status);
