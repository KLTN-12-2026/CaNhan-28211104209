package com.carrental;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, real MySQL only. Creates new named schemas; never drops/cleans/repairs. */
@EnabledIfEnvironmentVariable(named="KDRIVE_MIGRATION_MYSQL_URL", matches="jdbc:mysql:.*")
class MySqlMigrationTest {
 private final String base=System.getenv("KDRIVE_MIGRATION_MYSQL_URL");
 private final String user=System.getenv("KDRIVE_MIGRATION_MYSQL_USER");
 private final String password=System.getenv("KDRIVE_MIGRATION_MYSQL_PASSWORD");
 @BeforeEach void verifyFixtureBeforeDdl() {
  String fixture=System.getenv("KDRIVE_TEST_DB_URL");
  verifyMigrationTarget(base,fixture,()->CancelBoundaryTest.verifySafeIsolatedDatabase(fixture,
   System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),user,password));
 }
 static void verifyMigrationTarget(String adminUrl,String fixtureUrl,Runnable verifyMarker) {
  if(adminUrl==null || fixtureUrl==null || !fixtureUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/[A-Za-z0-9_]+\\?.*"))
   throw new IllegalStateException("[MIGRATION GUARD] Explicit isolated fixture required before DDL");
  var uri=java.net.URI.create(fixtureUrl.substring(5));
  if(uri.getPort()==3306 || uri.getPort()==33385 || !adminUrl.equals(fixtureUrl.replace(uri.getPath()+"?","/mysql?")))
   throw new IllegalStateException("[MIGRATION GUARD] Admin connection must match isolated fixture endpoint");
  verifyMarker.run();
 }
 private JdbcTemplate jdbc(String schema){return new JdbcTemplate(new DriverManagerDataSource(base.replace("/mysql?","/"+schema+"?"),user,password));}
 private Flyway flyway(String schema,String target){var config=Flyway.configure().dataSource(jdbc(schema).getDataSource()).locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false);if(target!=null)config.target(target);return config.load();}
 private List<Map<String,Object>> data(JdbcTemplate j){var result=new ArrayList<Map<String,Object>>();for(String table:j.queryForList("select table_name from information_schema.tables where table_schema=database() and table_name <> 'flyway_schema_history' order by table_name",String.class)){for(var row:j.queryForList("select * from `"+table+"` order by 1")){var copy=new TreeMap<String,Object>(row);copy.put("_table",table);result.add(copy);}}return result;}
 private List<Map<String,Object>> structure(JdbcTemplate j){var result=new ArrayList<Map<String,Object>>();for(String sql:List.of(
  "select table_name,column_name,column_type,is_nullable,column_default,extra from information_schema.columns where table_schema=database() and table_name <> 'flyway_schema_history' order by table_name,ordinal_position",
  "select table_name,index_name,non_unique,seq_in_index,column_name from information_schema.statistics where table_schema=database() and table_name <> 'flyway_schema_history' order by table_name,index_name,seq_in_index",
  "select table_name,constraint_name,column_name,referenced_table_name,referenced_column_name from information_schema.key_column_usage where table_schema=database() and table_name <> 'flyway_schema_history' order by table_name,constraint_name,ordinal_position",
  "select constraint_name,check_clause from information_schema.check_constraints where constraint_schema=database() order by constraint_name"))result.addAll(j.queryForList(sql));return result;}
 @Test void partialHistoricalV2BlocksRetryWithoutRepair()throws Exception{
  String archive=System.getenv("KDRIVE_HISTORICAL_MIGRATION_DIR");
  org.junit.jupiter.api.Assumptions.assumeTrue(archive!=null,"Historical migration snapshot is required for this probe");
  assertTrue(base.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/mysql\\?.*"));
  String schema="phase5_p_"+UUID.randomUUID().toString().replace("-","");jdbc("mysql").execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_0900_ai_ci");
  var old=Flyway.configure().dataSource(jdbc(schema).getDataSource()).locations("filesystem:"+Path.of(archive).toAbsolutePath().toString().replace('\\','/')).cleanDisabled(true).target("1").load();old.migrate();
  var j=jdbc(schema);try(var connection=j.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/fixture/phase5-v1-data.sql"));}
  var oldV2=Flyway.configure().dataSource(j.getDataSource()).locations("filesystem:"+Path.of(archive).toAbsolutePath().toString().replace('\\','/')).cleanDisabled(true).load();
  assertThrows(org.flywaydb.core.api.FlywayException.class,oldV2::migrate);
  assertEquals(1,j.queryForObject("select count(*) from flyway_schema_history where version='2' and success=0",Integer.class));
  assertEquals(6,j.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='incident_events' and column_name in ('previous_status','next_status','assessed_damage','third_party_coverage','requested_amount','responsibility_basis')",Integer.class));
  assertEquals("bigint",j.queryForObject("select data_type from information_schema.columns where table_schema=database() and table_name='reviews' and column_name='vehicle_id'",String.class));
  var before=data(j);assertThrows(org.flywaydb.core.api.FlywayException.class,()->flyway(schema,null).migrate());assertEquals(before,data(j));
  assertEquals("Preserve this review",j.queryForObject("select content from reviews where id='review-audit'",String.class));
  String output=System.getenv("KDRIVE_MIGRATION_ARTIFACT_DIR");if(output!=null)Files.writeString(Path.of(output).resolve("partial-history.txt"),"schema="+schema+"\n"+j.queryForList("select * from flyway_schema_history order by installed_rank")+"\n"+structure(j));
 }
 @Test void freshAndV1UpgradePreserveDataAndMatchSchema()throws Exception{
  assertTrue(base.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/mysql\\?.*"),"Explicit isolated local MySQL admin URL required");
  String suffix=UUID.randomUUID().toString().replace("-","");String fresh="phase5_f_"+suffix,upgrade="phase5_u_"+suffix;
  var admin=jdbc("mysql");assertTrue(admin.queryForObject("select version()",String.class).startsWith("8.4."));
  for(String schema:List.of(fresh,upgrade))admin.execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_0900_ai_ci");
  var f=flyway(fresh,null);assertEquals(10,f.migrate().migrationsExecuted);f.validate();
  var v1=flyway(upgrade,"1");assertEquals(1,v1.migrate().migrationsExecuted);
  var u=jdbc(upgrade);try(var connection=u.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/fixture/phase5-v1-data.sql"));}
  var before=data(u);assertFalse(before.isEmpty());
  var v2=flyway(upgrade,null);assertEquals(9,v2.migrate().migrationsExecuted);v2.validate();
  // All original columns/rows must remain exactly equal; only new nullable event fields differ.
  var after=data(u);stripReturnPolicyColumns(after);stripPhotoColumns(after);for(var row:after){if("incident_events".equals(row.get("_table")))for(String c:List.of("previous_status","next_status","assessed_damage","third_party_coverage","requested_amount","responsibility_basis")){assertNull(row.remove(c));}if("vehicles".equals(row.get("_table")))assertNull(row.remove("created_at"));}
  for(var row:after)if("locations".equals(row.get("_table")))assertNull(row.remove("visit_metadata"));
  assertEquals(before,after);
  assertEquals(structure(jdbc(fresh)),structure(u));
  assertEquals(29,u.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class));
  assertEquals(47,u.queryForObject("select count(*) from information_schema.referential_constraints where constraint_schema=database()",Integer.class));
  assertEquals(0,u.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='reviews' and column_name='vehicle_id'",Integer.class));
  assertEquals("bigint",u.queryForObject("select data_type from information_schema.columns where table_schema=database() and table_name='incident_events' and column_name='requested_amount'",String.class));
  assertEquals(0,v2.migrate().migrationsExecuted);
  for(String schema:List.of(fresh,upgrade))assertEquals(10,jdbc(schema).queryForObject("select count(*) from flyway_schema_history where success=1",Integer.class));
  String output=System.getenv("KDRIVE_MIGRATION_ARTIFACT_DIR");if(output!=null){Path dir=Path.of(output);Files.createDirectories(dir);Files.writeString(dir.resolve("migration-schema.txt"),"fresh="+fresh+"\nupgrade="+upgrade+"\n"+structure(u));Files.writeString(dir.resolve("upgrade-preservation.txt"),"before="+before+"\nafter="+after+"\nequal="+before.equals(after));Files.writeString(dir.resolve("flyway-history.txt"),u.queryForList("select * from flyway_schema_history order by installed_rank").toString());}
 }
 @Test void v2UpgradePreservesVehicleRowsAndAddsUnknownCreationDate(){
  String schema="phase5_v2_"+UUID.randomUUID().toString().replace("-","");
  jdbc("mysql").execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_0900_ai_ci");
  flyway(schema,"2").migrate();var j=jdbc(schema);
  try(var connection=j.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/fixture/phase5-v1-data.sql"));}catch(Exception e){throw new RuntimeException(e);}
  var before=data(j);var history=j.queryForList("select version,checksum,success from flyway_schema_history where version in ('1','2') order by version");
  var finalFlyway=flyway(schema,null);assertEquals(8,finalFlyway.migrate().migrationsExecuted);finalFlyway.validate();
  var after=data(j);stripReturnPolicyColumns(after);stripPhotoColumns(after);for(var row:after){if("vehicles".equals(row.get("_table")))assertNull(row.remove("created_at"));if("locations".equals(row.get("_table")))assertNull(row.remove("visit_metadata"));}assertEquals(before,after);
  assertEquals(history,j.queryForList("select version,checksum,success from flyway_schema_history where version in ('1','2') order by version"));
  assertEquals(1,j.queryForObject("select count(*) from flyway_schema_history where version='3' and success=1",Integer.class));
  assertEquals(29,j.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class));
  assertEquals(47,j.queryForObject("select count(*) from information_schema.referential_constraints where constraint_schema=database()",Integer.class));
 }
 @Test void v3UpgradeKeepsAllRowsAndHistoryAndAddsUnknownVisitMetadata()throws Exception{
  String schema="phase5_v3_"+UUID.randomUUID().toString().replace("-","");
  jdbc("mysql").execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_0900_ai_ci");
  flyway(schema,"3").migrate();var j=jdbc(schema);
  try(var connection=j.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/fixture/phase5-v1-data.sql"));}
  j.update("insert into location_categories(id,name) values('legacy-lc','Legacy category')");
  j.update("insert into locations(id,category_id,name,latitude,longitude,status) values('legacy-location','legacy-lc','Legacy location',16.0611,108.2232,'ACTIVE')");
  var before=data(j);var history=j.queryForList("select version,checksum,success from flyway_schema_history order by version");
  var upgrade=flyway(schema,null);assertEquals(7,upgrade.migrate().migrationsExecuted);upgrade.validate();
  var after=data(j);stripReturnPolicyColumns(after);stripPhotoColumns(after);for(var row:after)if("locations".equals(row.get("_table")))assertNull(row.remove("visit_metadata"));
  assertEquals(before,after);assertEquals(history,j.queryForList("select version,checksum,success from flyway_schema_history where version in ('1','2','3') order by version"));
  assertEquals(29,j.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class));
  assertNull(j.queryForObject("select visit_metadata from locations where id='legacy-location'",String.class));
  assertEquals(0,upgrade.migrate().migrationsExecuted);
 }
 private void stripReturnPolicyColumns(List<Map<String,Object>> rows){
  for(var row:rows)if("bookings".equals(row.get("_table")))for(String c:List.of("return_policy_version","return_policy_snapshot","return_policy_accepted_at"))assertNull(row.remove(c));
 }
 private void stripPhotoColumns(List<Map<String,Object>> rows){
  stripDepositColumns(rows);
  for(var row:rows)if("driver_licenses".equals(row.get("_table"))){
   assertEquals("MISSING",row.remove("photo_review_status"));
   for(String column:List.of("front_photo","back_photo","photo_review_reason","photo_reviewed_by","photo_reviewed_at"))assertNull(row.remove(column));
  }
 }
 @Test void v4UpgradePreservesLicenseAndPhysicalVerificationWithoutInventingApproval()throws Exception{
  String schema="phase5_v4_"+UUID.randomUUID().toString().replace("-","");
  jdbc("mysql").execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_0900_ai_ci");
  flyway(schema,"4").migrate();var j=jdbc(schema);
  try(var connection=j.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/fixture/phase5-v1-data.sql"));}
  String userId=j.queryForObject("select id from users order by id limit 1",String.class);
  j.update("insert into driver_licenses(id,user_id,license_number,scheme,license_class,expires_on,automatic_only,status,version) values('upgrade-license',?,'SYNTHETIC-UPGRADE','VN_2025','B','2030-12-31',false,'VERIFIED',7)",userId);
  var before=data(j);var history=j.queryForList("select version,checksum,success from flyway_schema_history order by version");
  var upgrade=flyway(schema,null);assertEquals(6,upgrade.migrate().migrationsExecuted);upgrade.validate();
  var after=data(j);stripReturnPolicyColumns(after);stripPhotoColumns(after);assertEquals(before,after);
  assertEquals(history,j.queryForList("select version,checksum,success from flyway_schema_history where version in ('1','2','3','4') order by version"));
  assertEquals(29,j.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class));
  assertEquals(47,j.queryForObject("select count(*) from information_schema.referential_constraints where constraint_schema=database()",Integer.class));
 }

 private void stripQrColumns(List<Map<String,Object>> rows){
  for(var row:rows)if("bookings".equals(row.get("_table"))){
   for(String column:List.of("deposit_method","deposit_mode","original_hold_until","payment_due_at","review_due_at","reported_at","deposit_receiver_snapshot"))assertNull(row.remove(column));
  }
 }
 private void stripDepositColumns(List<Map<String,Object>> rows){
  stripQrColumns(rows);
  for(var row:rows)if("bookings".equals(row.get("_table"))){
   assertEquals(false,row.remove("deposit_required"));assertEquals(0L,row.remove("deposit_amount"));
   for(String column:List.of("deposit_policy","deposit_approved_at","deposit_approved_by","deposit_license_version","deposit_txn_ref","cancellation_kind"))assertNull(row.remove(column));
  }
 }
 @Test void v5UpgradePreservesAllRowsAndLegacyDepositExemption()throws Exception{
  String schema="phase5_v5_"+UUID.randomUUID().toString().replace("-","");
  jdbc("mysql").execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_0900_ai_ci");
  flyway(schema,"5").migrate();var j=jdbc(schema);
  try(var connection=j.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/fixture/phase5-v1-data.sql"));}
  var before=data(j);assertFalse(before.isEmpty());var history=j.queryForList("select version,checksum,success from flyway_schema_history order by version");
  var upgrade=flyway(schema,null);assertEquals(5,upgrade.migrate().migrationsExecuted);upgrade.validate();
  var after=data(j);stripReturnPolicyColumns(after);stripDepositColumns(after);assertEquals(before,after);
  assertEquals(history,j.queryForList("select version,checksum,success from flyway_schema_history where version in ('1','2','3','4','5') order by version"));
  assertEquals(29,j.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class));
  assertEquals(47,j.queryForObject("select count(*) from information_schema.referential_constraints where constraint_schema=database()",Integer.class));
  assertEquals(0,upgrade.migrate().migrationsExecuted);
 }

 @Test void v6UpgradePreservesPaymentsAndAllowsExcessThenCancellationRefund()throws Exception{
  String schema="phase5_v6_"+UUID.randomUUID().toString().replace("-","");
  jdbc("mysql").execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_0900_ai_ci");
  flyway(schema,"6").migrate();var j=jdbc(schema);
  try(var connection=j.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/fixture/phase5-v1-data.sql"));}
  String booking=j.queryForObject("select id from bookings order by id limit 1",String.class);
  j.update("update bookings set deposit_required=true,deposit_amount=300,deposit_policy='sandbox-deposit-30-v1' where id=?",booking);
  j.update("insert into payment_transactions(id,booking_id,amount,status,created_at,expires_at,applied) values('legacy-pay',?,300,'SUCCEEDED',UTC_TIMESTAMP(),UTC_TIMESTAMP(),true)",booking);
  var oldHistory=j.queryForList("select version,checksum,success from flyway_schema_history order by installed_rank");
  var upgrade=flyway(schema,null);assertEquals(4,upgrade.migrate().migrationsExecuted);upgrade.validate();
  assertEquals(oldHistory,j.queryForList("select version,checksum,success from flyway_schema_history where cast(version as unsigned)<=6 order by installed_rank"));
  assertEquals(300,j.queryForObject("select applied_amount from payment_transactions where id='legacy-pay'",Long.class));
  assertEquals("VNPAY_SANDBOX",j.queryForObject("select deposit_method from bookings where id=?",String.class,booking));
  for(int n=1;n<=2;n++) j.update("insert into payment_refunds(id,payment_id,booking_id,amount,status,kind,reason,created_at,business_key) values(?,'legacy-pay',?,100,'REQUESTED',?,'Fixture reason',UTC_TIMESTAMP(),?)","refund-"+n,booking,"KIND"+n,"legacy-pay:KIND"+n);
  assertEquals(2,j.queryForObject("select count(*) from payment_refunds where payment_id='legacy-pay'",Integer.class));
  assertEquals(29,j.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class));
 }

 @Test void v9UpgradeAddsOnlyNullableReturnPolicyAndPreservesSnapshotsPaymentsAndHistory()throws Exception{
  String schema="phase5_v9_"+UUID.randomUUID().toString().replace("-","");jdbc("mysql").execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_0900_ai_ci");
  flyway(schema,"9").migrate();var j=jdbc(schema);
  try(var connection=j.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/fixture/phase5-v1-data.sql"));}
  String booking=j.queryForObject("select id from bookings order by id limit 1",String.class);
  j.update("insert into payment_transactions(id,booking_id,amount,status,created_at,expires_at,applied,applied_amount) values('v9-preserved',?,300,'SUCCEEDED',UTC_TIMESTAMP(),UTC_TIMESTAMP(),true,300)",booking);
  var before=data(j);var history=j.queryForList("select version,checksum,success from flyway_schema_history order by installed_rank");
  var upgraded=flyway(schema,null);assertEquals(1,upgraded.migrate().migrationsExecuted);upgraded.validate();
  var after=data(j);stripReturnPolicyColumns(after);assertEquals(before,after);
  assertEquals(history,j.queryForList("select version,checksum,success from flyway_schema_history where version<>'10' order by installed_rank"));
  assertEquals(29,j.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class));
  assertEquals(0,upgraded.migrate().migrationsExecuted);
 }

}
