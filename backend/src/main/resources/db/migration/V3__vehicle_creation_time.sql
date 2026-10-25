-- Legacy creation dates are unknown; preserve them as NULL.
ALTER TABLE vehicles ADD COLUMN created_at TIMESTAMP(6) NULL;
CREATE INDEX vehicle_creation_order ON vehicles(created_at, id);
