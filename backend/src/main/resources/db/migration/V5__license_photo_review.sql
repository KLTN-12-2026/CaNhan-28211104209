ALTER TABLE driver_licenses ADD COLUMN front_photo VARCHAR(80);
ALTER TABLE driver_licenses ADD COLUMN back_photo VARCHAR(80);
ALTER TABLE driver_licenses ADD COLUMN photo_review_status VARCHAR(20) NOT NULL DEFAULT 'MISSING';
ALTER TABLE driver_licenses ADD COLUMN photo_review_reason VARCHAR(1000);
ALTER TABLE driver_licenses ADD COLUMN photo_reviewed_by VARCHAR(36);
ALTER TABLE driver_licenses ADD COLUMN photo_reviewed_at TIMESTAMP(6);
ALTER TABLE driver_licenses ADD CONSTRAINT fk_license_photo_reviewer FOREIGN KEY (photo_reviewed_by) REFERENCES users(id);
ALTER TABLE driver_licenses ADD CONSTRAINT ck_license_photo_review CHECK (photo_review_status IN ('MISSING','PENDING','APPROVED','NEEDS_CHANGES'));
