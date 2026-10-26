CREATE TABLE users (
 id VARCHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, full_name VARCHAR(100) NOT NULL,
 phone VARCHAR(20), role VARCHAR(10) NOT NULL, status VARCHAR(10) NOT NULL,
 token_version BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
 CHECK (role IN ('USER','STAFF','ADMIN')), CHECK (status IN ('ACTIVE','LOCKED'))
);
CREATE TABLE branches (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(120) NOT NULL, address VARCHAR(300) NOT NULL,
 status VARCHAR(10) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE TABLE staff_branches (
 user_id VARCHAR(36) NOT NULL, branch_id VARCHAR(36) NOT NULL,
 PRIMARY KEY (user_id, branch_id), FOREIGN KEY (user_id) REFERENCES users(id),
 FOREIGN KEY (branch_id) REFERENCES branches(id)
);
CREATE TABLE branch_routes (
 id VARCHAR(36) PRIMARY KEY, pickup_branch_id VARCHAR(36) NOT NULL,
 return_branch_id VARCHAR(36) NOT NULL, fee BIGINT NOT NULL, enabled BOOLEAN NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 UNIQUE (pickup_branch_id, return_branch_id), CHECK (fee >= 0),
 FOREIGN KEY (pickup_branch_id) REFERENCES branches(id), FOREIGN KEY (return_branch_id) REFERENCES branches(id)
);
CREATE TABLE vehicle_brands (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE);
CREATE TABLE vehicle_categories (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE);
CREATE TABLE vehicles (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(120) NOT NULL, plate VARCHAR(20) NOT NULL UNIQUE,
 brand_id VARCHAR(36) NOT NULL, category_id VARCHAR(36) NOT NULL,
 home_branch_id VARCHAR(36) NOT NULL, current_branch_id VARCHAR(36) NOT NULL,
 seats INTEGER NOT NULL, transmission VARCHAR(10) NOT NULL, daily_price BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL, description VARCHAR(2000), version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY (brand_id) REFERENCES vehicle_brands(id), FOREIGN KEY (category_id) REFERENCES vehicle_categories(id),
 FOREIGN KEY (home_branch_id) REFERENCES branches(id), FOREIGN KEY (current_branch_id) REFERENCES branches(id),
 CHECK (seats BETWEEN 2 AND 9), CHECK (transmission IN ('AUTO','MANUAL')),
 CHECK (daily_price > 0), CHECK (status IN ('ACTIVE','MAINTENANCE','INACTIVE'))
);
CREATE TABLE amenities (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE);
CREATE TABLE vehicle_amenities (
 vehicle_id VARCHAR(36) NOT NULL, amenity_id VARCHAR(36) NOT NULL,
 PRIMARY KEY (vehicle_id, amenity_id), FOREIGN KEY (vehicle_id) REFERENCES vehicles(id), FOREIGN KEY (amenity_id) REFERENCES amenities(id)
);
CREATE TABLE vehicle_images (
 id VARCHAR(36) PRIMARY KEY, vehicle_id VARCHAR(36) NOT NULL, url VARCHAR(500) NOT NULL,
 sort_order INTEGER NOT NULL, attribution VARCHAR(1000), UNIQUE(vehicle_id, sort_order), FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);
CREATE TABLE driver_licenses (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL UNIQUE,
 license_number VARCHAR(40) NOT NULL, scheme VARCHAR(20) NOT NULL, license_class VARCHAR(10) NOT NULL,
 expires_on DATE NOT NULL, automatic_only BOOLEAN NOT NULL, status VARCHAR(15) NOT NULL,
 checked_by VARCHAR(36), checked_at TIMESTAMP(6), version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY (user_id) REFERENCES users(id), FOREIGN KEY (checked_by) REFERENCES users(id),
 CHECK (scheme IN ('VN_PRE_2025','VN_2025')), CHECK(status IN ('PENDING','VERIFIED','REJECTED'))
);
CREATE TABLE bookings (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, vehicle_id VARCHAR(36) NOT NULL,
 pickup_branch_id VARCHAR(36) NOT NULL, return_branch_id VARCHAR(36) NOT NULL,
 actual_return_branch_id VARCHAR(36), start_at TIMESTAMP(6) NOT NULL, end_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 status VARCHAR(20) NOT NULL, daily_price BIGINT NOT NULL, rental_days INTEGER NOT NULL,
 rental_total BIGINT NOT NULL, one_way_fee BIGINT NOT NULL, total BIGINT NOT NULL,
 customer_name VARCHAR(100) NOT NULL, vehicle_name VARCHAR(120) NOT NULL,
 pickup_name VARCHAR(120) NOT NULL, return_name VARCHAR(120) NOT NULL,
 policy_version VARCHAR(30) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(vehicle_id) REFERENCES vehicles(id),
 FOREIGN KEY(pickup_branch_id) REFERENCES branches(id), FOREIGN KEY(return_branch_id) REFERENCES branches(id),
 FOREIGN KEY(actual_return_branch_id) REFERENCES branches(id),
 CHECK (start_at < end_at), CHECK(daily_price > 0), CHECK(rental_days > 0),
 CHECK (rental_total >= 0 AND one_way_fee >= 0 AND total >= 0),
 CHECK (status IN ('PENDING','CONFIRMED','IN_PROGRESS','RETURNED','COMPLETED','CANCELLED','REJECTED','EXPIRED'))
);
CREATE INDEX booking_vehicle_schedule ON bookings(vehicle_id, status, start_at, end_at);
CREATE INDEX booking_user_created ON bookings(user_id, created_at);
CREATE INDEX booking_expiry ON bookings(status, expires_at);
CREATE TABLE booking_status_history (
 id VARCHAR(36) PRIMARY KEY, booking_id VARCHAR(36) NOT NULL, previous_status VARCHAR(20),
 next_status VARCHAR(20) NOT NULL, actor_id VARCHAR(36), occurred_at TIMESTAMP(6) NOT NULL, reason VARCHAR(2000),
 FOREIGN KEY(booking_id) REFERENCES bookings(id), FOREIGN KEY(actor_id) REFERENCES users(id)
);
CREATE TABLE vehicle_handover_records (
 id VARCHAR(36) PRIMARY KEY, booking_id VARCHAR(36) NOT NULL, kind VARCHAR(10) NOT NULL,
 actor_id VARCHAR(36) NOT NULL, branch_id VARCHAR(36) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL,
 condition_note VARCHAR(2000) NOT NULL, license_snapshot TEXT, maintenance BOOLEAN NOT NULL,
 UNIQUE(booking_id, kind), FOREIGN KEY(booking_id) REFERENCES bookings(id),
 FOREIGN KEY(actor_id) REFERENCES users(id), FOREIGN KEY(branch_id) REFERENCES branches(id), CHECK(kind IN ('HANDOVER','RETURN'))
);
CREATE TABLE contracts (
 id VARCHAR(36) PRIMARY KEY, booking_id VARCHAR(36) NOT NULL UNIQUE, issued_at TIMESTAMP(6) NOT NULL,
 snapshot TEXT NOT NULL, terms_version VARCHAR(30) NOT NULL, FOREIGN KEY(booking_id) REFERENCES bookings(id)
);
CREATE TABLE invoices (
 id VARCHAR(36) PRIMARY KEY, booking_id VARCHAR(36) NOT NULL UNIQUE, issued_at TIMESTAMP(6) NOT NULL,
 total BIGINT NOT NULL, snapshot TEXT NOT NULL, FOREIGN KEY(booking_id) REFERENCES bookings(id), CHECK(total >= 0)
);
CREATE TABLE invoice_items (
 id VARCHAR(36) PRIMARY KEY, invoice_id VARCHAR(36) NOT NULL, name VARCHAR(200) NOT NULL,
 quantity INTEGER NOT NULL, unit_price BIGINT NOT NULL, amount BIGINT NOT NULL,
 FOREIGN KEY(invoice_id) REFERENCES invoices(id), CHECK(quantity > 0 AND unit_price >= 0 AND amount >= 0)
);
CREATE TABLE reviews (
 id VARCHAR(36) PRIMARY KEY, booking_id VARCHAR(36) NOT NULL UNIQUE, rating INTEGER NOT NULL,
 content VARCHAR(1000) NOT NULL, hidden BOOLEAN NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 moderated_by VARCHAR(36), moderated_at TIMESTAMP(6), version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(booking_id) REFERENCES bookings(id), FOREIGN KEY(moderated_by) REFERENCES users(id), CHECK(rating BETWEEN 1 AND 5)
);
CREATE TABLE incident_cases (
 id VARCHAR(36) PRIMARY KEY, booking_id VARCHAR(36) NOT NULL, reported_by VARCHAR(36) NOT NULL,
 kind VARCHAR(20) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL, reported_at TIMESTAMP(6) NOT NULL,
 description VARCHAR(2000) NOT NULL, evidence_reference VARCHAR(2000), status VARCHAR(20) NOT NULL,
 assessed_damage BIGINT NOT NULL DEFAULT 0, third_party_coverage BIGINT NOT NULL DEFAULT 0,
 requested_amount BIGINT NOT NULL DEFAULT 0, responsibility_basis VARCHAR(2000),
 version BIGINT NOT NULL DEFAULT 0, FOREIGN KEY(booking_id) REFERENCES bookings(id), FOREIGN KEY(reported_by) REFERENCES users(id),
 CHECK(kind IN ('ACCIDENT','DAMAGE','VIOLATION')), CHECK(status IN ('OPEN','ASSESSED','CLAIMED','DISPUTED','CLOSED')),
 CHECK(assessed_damage >= 0 AND third_party_coverage >= 0 AND requested_amount >= 0)
);
CREATE TABLE incident_events (
 id VARCHAR(36) PRIMARY KEY, case_id VARCHAR(36) NOT NULL, actor_id VARCHAR(36) NOT NULL,
 occurred_at TIMESTAMP(6) NOT NULL, action VARCHAR(30) NOT NULL, note VARCHAR(2000) NOT NULL,
 FOREIGN KEY(case_id) REFERENCES incident_cases(id), FOREIGN KEY(actor_id) REFERENCES users(id)
);
CREATE TABLE location_categories (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE);
CREATE TABLE locations (
 id VARCHAR(36) PRIMARY KEY, category_id VARCHAR(36) NOT NULL, name VARCHAR(200) NOT NULL,
 latitude DECIMAL(10,7) NOT NULL, longitude DECIMAL(10,7) NOT NULL, status VARCHAR(10) NOT NULL,
 FOREIGN KEY(category_id) REFERENCES location_categories(id), CHECK(latitude BETWEEN -90 AND 90), CHECK(longitude BETWEEN -180 AND 180)
);
CREATE TABLE itineraries (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, title VARCHAR(200) NOT NULL,
 criteria TEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL, FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE itinerary_days (
 id VARCHAR(36) PRIMARY KEY, itinerary_id VARCHAR(36) NOT NULL, trip_date DATE NOT NULL,
 UNIQUE(itinerary_id, trip_date), FOREIGN KEY(itinerary_id) REFERENCES itineraries(id)
);
CREATE TABLE itinerary_items (
 id VARCHAR(36) PRIMARY KEY, day_id VARCHAR(36) NOT NULL, location_id VARCHAR(36) NOT NULL,
 sort_order INTEGER NOT NULL, snapshot TEXT NOT NULL, UNIQUE(day_id, sort_order),
 FOREIGN KEY(day_id) REFERENCES itinerary_days(id), FOREIGN KEY(location_id) REFERENCES locations(id)
);
