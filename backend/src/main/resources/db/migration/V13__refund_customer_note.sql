-- V13 (QR-D4): customer-facing refund message, separate from the internal reason.
-- Nullable; existing refunds keep an empty note (the internal reason is never copied to customers).
ALTER TABLE payment_refunds ADD COLUMN customer_note VARCHAR(500);
