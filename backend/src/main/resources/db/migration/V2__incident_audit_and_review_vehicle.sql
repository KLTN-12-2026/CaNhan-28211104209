-- V2__incident_audit_and_review_vehicle.sql
-- Pre-release revision approved 2026-10-04: reviews use booking -> vehicle.
-- Only for a fresh database or a database with successful V1 and no V2 attempt.
-- Do not apply to a partially migrated database or repair its history blindly.
-- Historical events stay NULL: their former state/basis cannot be reconstructed.
-- Snapshot existing incident assessment fields; this does not fix L01 time bounds.
ALTER TABLE incident_events ADD COLUMN previous_status VARCHAR(30) NULL;
ALTER TABLE incident_events ADD COLUMN next_status VARCHAR(30) NULL;
ALTER TABLE incident_events ADD COLUMN assessed_damage BIGINT NULL;
ALTER TABLE incident_events ADD COLUMN third_party_coverage BIGINT NULL;
ALTER TABLE incident_events ADD COLUMN requested_amount BIGINT NULL;
ALTER TABLE incident_events ADD COLUMN responsibility_basis TEXT NULL;

