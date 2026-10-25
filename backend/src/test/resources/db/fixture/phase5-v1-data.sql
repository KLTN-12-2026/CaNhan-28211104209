-- Isolated audit fixture only. Apply AFTER unmodified V1 in audit_upgrade.
INSERT INTO users(id,email,password_hash,full_name,role,status) VALUES ('u-audit','audit@example.test','not-a-login-hash','Audit','USER','ACTIVE');
INSERT INTO branches(id,name,address,status) VALUES ('b-audit','Audit','Audit','ACTIVE');
INSERT INTO vehicle_brands VALUES ('brand-audit','Audit');
INSERT INTO vehicle_categories VALUES ('cat-audit','Audit');
INSERT INTO vehicles(id,name,plate,brand_id,category_id,home_branch_id,current_branch_id,seats,transmission,daily_price,status) VALUES ('v-audit','Audit','AUDIT-ONLY','brand-audit','cat-audit','b-audit','b-audit',5,'AUTO',100000,'ACTIVE');
INSERT INTO bookings(id,user_id,vehicle_id,pickup_branch_id,return_branch_id,start_at,end_at,created_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,customer_name,vehicle_name,pickup_name,return_name,policy_version) VALUES ('booking-audit','u-audit','v-audit','b-audit','b-audit','2026-01-01','2026-01-02','2025-12-30','2025-12-30 02:00:00','COMPLETED',100000,1,100000,0,100000,'Audit','Audit','Audit','Audit','audit');
INSERT INTO reviews(id,booking_id,rating,content,hidden,created_at) VALUES ('review-audit','booking-audit',5,'Preserve this review',false,'2026-01-03');

INSERT INTO incident_cases(id,booking_id,reported_by,kind,occurred_at,reported_at,description,evidence_reference,status,assessed_damage,third_party_coverage,requested_amount,responsibility_basis) VALUES ('case-audit','booking-audit','u-audit','DAMAGE','2026-01-01 12:00:00','2026-01-03','Historical description','TEST-ONLY','ASSESSED',1000,100,900,'Original basis');
INSERT INTO incident_events(id,case_id,actor_id,occurred_at,action,note) VALUES ('event-audit','case-audit','u-audit','2026-01-03','ASSESSED','Preserve historical note');
INSERT INTO contracts(id,booking_id,issued_at,snapshot,terms_version) VALUES ('contract-audit','booking-audit','2025-12-31','Original contract snapshot','audit');
INSERT INTO invoices(id,booking_id,issued_at,total,snapshot) VALUES ('invoice-audit','booking-audit','2026-01-03',100000,'Original invoice snapshot');
INSERT INTO invoice_items(id,invoice_id,name,quantity,unit_price,amount) VALUES ('item-audit','invoice-audit','Rental',1,100000,100000);
