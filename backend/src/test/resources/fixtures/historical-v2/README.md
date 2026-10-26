# Historical migration test fixtures

These files reproduce an old V1/V2 upgrade failure. V2 intentionally contains the historical incompatible review/vehicle foreign-key type. They are test inputs, not current schema instructions.

Do not copy them into src/main/resources/db/migration or run them against an application database. Current Flyway uses SQL V1–V8/V10 and Java V9 under classpath:db/migration.

The portable test runner pins SHA-256 for both files before use. This snapshot was retained from the existing migration-test evidence; it contains schema DDL, not a database dump or private credentials.
