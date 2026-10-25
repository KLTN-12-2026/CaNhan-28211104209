ALTER TABLE bookings ADD COLUMN deposit_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bookings ADD COLUMN deposit_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN deposit_policy VARCHAR(40);
ALTER TABLE bookings ADD COLUMN deposit_approved_at TIMESTAMP(6);
ALTER TABLE bookings ADD COLUMN deposit_approved_by VARCHAR(36);
ALTER TABLE bookings ADD COLUMN deposit_license_version BIGINT;
ALTER TABLE bookings ADD COLUMN deposit_txn_ref VARCHAR(32);
ALTER TABLE bookings ADD COLUMN cancellation_kind VARCHAR(30);
ALTER TABLE bookings ADD CONSTRAINT fk_deposit_approver FOREIGN KEY (deposit_approved_by) REFERENCES users(id);
ALTER TABLE bookings ADD CONSTRAINT ck_booking_deposit CHECK (deposit_amount >= 0);

CREATE TABLE payment_transactions (
 id VARCHAR(32) PRIMARY KEY, booking_id VARCHAR(36) NOT NULL,
 amount BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 provider_transaction VARCHAR(30) UNIQUE, provider_pay_date VARCHAR(14),
 applied BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY (booking_id) REFERENCES bookings(id),
 CHECK (amount > 0), CHECK (status IN ('PENDING','SUCCEEDED','FAILED','UNKNOWN'))
);
CREATE INDEX payment_pending ON payment_transactions(status,created_at);
CREATE INDEX payment_booking ON payment_transactions(booking_id,created_at);
CREATE TABLE payment_refunds (
 id VARCHAR(32) PRIMARY KEY, payment_id VARCHAR(32) NOT NULL UNIQUE,
 booking_id VARCHAR(36) NOT NULL, amount BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL, kind VARCHAR(30) NOT NULL, reason VARCHAR(1000) NOT NULL,
 approved_by VARCHAR(36), approved_at TIMESTAMP(6), created_at TIMESTAMP(6) NOT NULL,
 provider_transaction VARCHAR(30), request_date VARCHAR(14), version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY (payment_id) REFERENCES payment_transactions(id), FOREIGN KEY (booking_id) REFERENCES bookings(id),
 FOREIGN KEY (approved_by) REFERENCES users(id), CHECK (amount >= 0),
 CHECK (status IN ('REQUESTED','APPROVED','PROCESSING','SUCCEEDED','FAILED','UNKNOWN','DECLINED'))
);
CREATE INDEX refunds_work ON payment_refunds(status,created_at);
CREATE TABLE payment_events (
 id VARCHAR(36) PRIMARY KEY, booking_id VARCHAR(36) NOT NULL, payment_id VARCHAR(32),
 kind VARCHAR(40) NOT NULL, note VARCHAR(1000) NOT NULL, actor_id VARCHAR(36),
 created_at TIMESTAMP(6) NOT NULL, event_key VARCHAR(128) UNIQUE,
 FOREIGN KEY (booking_id) REFERENCES bookings(id), FOREIGN KEY (payment_id) REFERENCES payment_transactions(id),
 FOREIGN KEY (actor_id) REFERENCES users(id)
);
CREATE INDEX payment_event_booking ON payment_events(booking_id,created_at);
