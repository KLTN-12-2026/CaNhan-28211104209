package com.carrental;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTargetGuardTest {
 @Test void refusesProtectedPortsBeforeOpeningConnection() {
  var calls=new AtomicInteger();
  for(int port:new int[]{3306,33385}) {
   String url="jdbc:mysql://127.0.0.1:"+port+"/kdrive_fixture?useSSL=false";
   assertThrows(IllegalStateException.class,()->MySqlMigrationTest.verifyMigrationTarget(url.replace("/kdrive_fixture?","/mysql?"),url,calls::incrementAndGet));
  }
  assertEquals(0,calls.get());
 }
 @Test void refusesMissingOrDifferentFixtureBeforeOpeningConnection() {
  var calls=new AtomicInteger();String fixture="jdbc:mysql://127.0.0.1:33676/kdrive_fixture?useSSL=false";
  assertThrows(IllegalStateException.class,()->MySqlMigrationTest.verifyMigrationTarget("jdbc:mysql://127.0.0.1:33677/mysql?useSSL=false",fixture,calls::incrementAndGet));
  assertThrows(IllegalStateException.class,()->MySqlMigrationTest.verifyMigrationTarget("jdbc:mysql://127.0.0.1:33676/mysql?useSSL=false",null,calls::incrementAndGet));
  assertEquals(0,calls.get());
 }
 @Test void propagatesMarkerRefusalAndAcceptsVerifiedFixture() {
  String fixture="jdbc:mysql://127.0.0.1:33676/kdrive_fixture?useSSL=false",admin=fixture.replace("/kdrive_fixture?","/mysql?");
  var rejection=new IllegalStateException("marker mismatch");
  assertSame(rejection,assertThrows(IllegalStateException.class,()->MySqlMigrationTest.verifyMigrationTarget(admin,fixture,()->{throw rejection;})));
  var calls=new AtomicInteger();MySqlMigrationTest.verifyMigrationTarget(admin,fixture,calls::incrementAndGet);assertEquals(1,calls.get());
 }
}
