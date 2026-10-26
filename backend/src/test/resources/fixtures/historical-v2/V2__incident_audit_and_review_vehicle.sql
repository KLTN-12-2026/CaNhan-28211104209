-- V2__incident_audit_and_review_vehicle.sql
-- Add audit tracking columns to incident_events for L01/L09 compliance
ALTER TABLE incident_events ADD COLUMN previous_status VARCHAR(30) NULL;
ALTER TABLE incident_events ADD COLUMN next_status VARCHAR(30) NULL;
ALTER TABLE incident_events ADD COLUMN assessed_damage DECIMAL(12, 2) NULL;
ALTER TABLE incident_events ADD COLUMN third_party_coverage DECIMAL(12, 2) NULL;
ALTER TABLE incident_events ADD COLUMN requested_amount DECIMAL(12, 2) NULL;
ALTER TABLE incident_events ADD COLUMN responsibility_basis TEXT NULL;

-- Add vehicle_id to reviews for explicit review-vehicle association
ALTER TABLE reviews ADD COLUMN vehicle_id BIGINT NULL;

-- Backfill vehicle_id on existing reviews from bookings table
UPDATE reviews
SET vehicle_id = (SELECT b.vehicle_id FROM bookings b WHERE b.id = reviews.booking_id)
WHERE vehicle_id IS NULL;

-- Add index and foreign key
CREATE INDEX idx_reviews_vehicle ON reviews(vehicle_id);

ALTER TABLE reviews
    ADD CONSTRAINT fk_reviews_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id);
