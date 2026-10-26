ALTER TABLE payment_refunds ADD COLUMN business_key VARCHAR(96);
CREATE UNIQUE INDEX uq_refund_business_key ON payment_refunds(business_key);
ALTER TABLE payment_transactions ADD CONSTRAINT ck_payment_applied_limit CHECK (applied_amount <= amount);
