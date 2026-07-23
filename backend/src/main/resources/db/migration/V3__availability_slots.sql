CREATE TABLE availability_slots (
    id VARCHAR(64) PRIMARY KEY,
    professional_id VARCHAR(64) NOT NULL UNIQUE,
    specialty_tag VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reserved_request_id VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_availability_slot_professional
        FOREIGN KEY (professional_id) REFERENCES professionals(id)
);

ALTER TABLE dispatch_requests
    ADD COLUMN assigned_slot_id VARCHAR(64);

ALTER TABLE dispatch_requests
    ADD CONSTRAINT fk_dispatch_request_availability_slot
        FOREIGN KEY (assigned_slot_id) REFERENCES availability_slots(id);

CREATE INDEX idx_availability_slots_status_specialty ON availability_slots(status, specialty_tag);
CREATE INDEX idx_dispatch_requests_assigned_slot ON dispatch_requests(assigned_slot_id);

INSERT INTO availability_slots (id, professional_id, specialty_tag, status, updated_at)
SELECT CONCAT('slot_', id), id, specialty_tag,
       CASE
           WHEN status = 'PROPOSED' THEN 'RESERVED'
           ELSE status
       END,
       CURRENT_TIMESTAMP
FROM professionals;
