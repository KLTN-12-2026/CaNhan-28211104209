CREATE TABLE booking_return_settlements (
 id VARCHAR(36) PRIMARY KEY,
 booking_id VARCHAR(36) NOT NULL,
 kind VARCHAR(12) NOT NULL,
 amount BIGINT NOT NULL,
 actor_id VARCHAR(36) NOT NULL,
 actor_name VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 method VARCHAR(20),
 reference_code VARCHAR(120),
 reason VARCHAR(1000) NOT NULL,
 reverses_id VARCHAR(36) UNIQUE,
 idempotency_key VARCHAR(80) NOT NULL,
 demo BOOLEAN NOT NULL,
 UNIQUE (booking_id, idempotency_key),
 FOREIGN KEY (booking_id) REFERENCES bookings(id),
 FOREIGN KEY (actor_id) REFERENCES users(id),
 FOREIGN KEY (reverses_id) REFERENCES booking_return_settlements(id),
 CHECK (kind IN ('RECEIPT','WAIVER','REVERSAL')),
 CHECK (amount > 0),
 CHECK ((kind='REVERSAL' AND reverses_id IS NOT NULL) OR (kind<>'REVERSAL' AND reverses_id IS NULL)),
 CHECK ((kind='RECEIPT' AND method IN ('CASH','BANK_TRANSFER') AND reference_code IS NOT NULL) OR (kind<>'RECEIPT' AND method IS NULL))
);
CREATE INDEX ix_return_settlement_booking ON booking_return_settlements(booking_id, created_at);
