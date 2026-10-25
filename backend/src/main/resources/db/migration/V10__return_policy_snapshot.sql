ALTER TABLE bookings ADD COLUMN return_policy_version VARCHAR(40);
ALTER TABLE bookings ADD COLUMN return_policy_snapshot TEXT;
ALTER TABLE bookings ADD COLUMN return_policy_accepted_at TIMESTAMP(6);
ALTER TABLE bookings ADD CONSTRAINT ck_booking_return_policy CHECK (
 (return_policy_version IS NULL AND return_policy_snapshot IS NULL AND return_policy_accepted_at IS NULL)
 OR (return_policy_version IS NOT NULL AND return_policy_snapshot IS NOT NULL AND return_policy_accepted_at IS NOT NULL)
);
