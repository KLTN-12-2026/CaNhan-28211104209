-- V7: QR Manual Deposit fields
-- Compatible with both MySQL and H2

-- Bookings table: QR DEMO fields
ALTER TABLE bookings ADD COLUMN deposit_method VARCHAR(20);
ALTER TABLE bookings ADD COLUMN deposit_mode VARCHAR(20);
ALTER TABLE bookings ADD COLUMN original_hold_until TIMESTAMP(6);
ALTER TABLE bookings ADD COLUMN payment_due_at TIMESTAMP(6);
ALTER TABLE bookings ADD COLUMN review_due_at TIMESTAMP(6);
ALTER TABLE bookings ADD COLUMN reported_at TIMESTAMP(6);
ALTER TABLE bookings ADD COLUMN deposit_receiver_snapshot TEXT;
ALTER TABLE bookings ADD CONSTRAINT ck_booking_deposit_method CHECK (deposit_method IN ('VNPAY_SANDBOX','DEMO_QR_MANUAL'));
ALTER TABLE bookings ADD CONSTRAINT ck_booking_deposit_mode CHECK (deposit_mode IN ('FULL','PARTIAL','EXCESS'));

-- Payment transactions: manual verification fields
ALTER TABLE payment_transactions ADD COLUMN verifier_id VARCHAR(36);
ALTER TABLE payment_transactions ADD COLUMN verified_at TIMESTAMP(6);
ALTER TABLE payment_transactions ADD COLUMN transfer_time TIMESTAMP(6);
ALTER TABLE payment_transactions ADD COLUMN transfer_note VARCHAR(500);
ALTER TABLE payment_transactions ADD COLUMN source_account VARCHAR(100);
ALTER TABLE payment_transactions ADD COLUMN applied_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_transactions ADD COLUMN idempotency_key VARCHAR(64);
ALTER TABLE payment_transactions ADD CONSTRAINT fk_payment_verifier FOREIGN KEY (verifier_id) REFERENCES users(id);
ALTER TABLE payment_transactions ADD CONSTRAINT ck_payment_applied_amount CHECK (applied_amount >= 0);

-- Unique index for idempotency (H2 doesn't support WHERE clause in CREATE INDEX)
CREATE UNIQUE INDEX uq_payment_receiver_ref ON payment_transactions (booking_id, idempotency_key);

-- Backfill deposit_method for existing VNPAY bookings
UPDATE bookings SET deposit_method = 'VNPAY_SANDBOX' WHERE deposit_required = true AND deposit_method IS NULL;

-- Backfill applied_amount for existing VNPAY payments: if applied=true then applied_amount = amount else 0
UPDATE payment_transactions SET applied_amount = CASE WHEN applied = true THEN amount ELSE 0 END WHERE applied_amount = 0;

-- Unique legacy refund constraint is removed by the following metadata-aware Flyway migration.
-- The generated constraint name differs between MySQL and H2; never guess its name.
