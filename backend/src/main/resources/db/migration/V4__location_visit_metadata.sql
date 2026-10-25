-- Existing locations have unknown visit information until an administrator verifies it.
ALTER TABLE locations ADD COLUMN visit_metadata TEXT NULL;
