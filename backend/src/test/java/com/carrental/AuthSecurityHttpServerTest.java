package com.carrental;

import static org.junit.jupiter.api.Assertions.*;
import com.carrental.security.TokenService;
import com.carrental.security.PasswordPolicy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.jwt-secret=auth-security-test-only-at-least-32-bytes","app.seed-enabled=true","app.seed-password=AuthSecurityOnly123!"})
@Import(AuthSecurityHttpServerTest.TimeConfig.class)
class AuthSecurityHttpServerTest {
 static class FixtureClock extends Clock {
  Instant now=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
 }
 @TestConfiguration static class TimeConfig {@Bean @Primary FixtureClock fixtureClock(){return new FixtureClock();}}
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){
  String url=System.getenv("KDRIVE_TEST_DB_URL");
  if(url!=null&&!url.isBlank())CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
  r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:authsecurity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
  r.add("spring.datasource.username",()->url==null?"sa":System.getenv("KDRIVE_TEST_DB_USER"));r.add("spring.datasource.password",()->url==null?"":System.getenv("KDRIVE_TEST_DB_PASSWORD"));
 }
 @Autowired TestRestTemplate http;@Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder encoder;@Autowired FixtureClock clock;
 @BeforeEach void nextWindow(){clock.now=clock.now.plusSeconds(3600);}
 final String password="A safe test password 2026!";
 ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);return http.exchange("/api/v1"+path,method,new HttpEntity<>(body,h),Map.class);}
 Map register(String email){var r=call(HttpMethod.POST,"/auth/register",Map.of("email",email,"fullName","Fixture name","password",password,"role","ADMIN","status","LOCKED"),null);assertEquals(HttpStatus.CREATED,r.getStatusCode());return r.getBody();}
 String login(String email,String pass){var r=call(HttpMethod.POST,"/auth/login",Map.of("email",email,"password",pass),null);assertEquals(HttpStatus.OK,r.getStatusCode());return (String)r.getBody().get("token");}
 @Test void roleHashNormalizationAndActualTokenRevocation(){
  String email="Review-"+UUID.randomUUID()+"@Example.Test";Map created=register(email);Map profile=(Map)created.get("user");String id=(String)profile.get("id");
  assertEquals("USER",profile.get("role"));assertEquals("ACTIVE",profile.get("status"));assertEquals(email.toLowerCase(Locale.ROOT),profile.get("email"));
  String hash=jdbc.queryForObject("select password_hash from users where id=?",String.class,id);assertNotEquals(password,hash);assertTrue(hash.startsWith("$2a$12$")||hash.startsWith("$2b$12$"));assertTrue(encoder.matches(password,hash));
  assertEquals(HttpStatus.CONFLICT,call(HttpMethod.POST,"/auth/register",Map.of("email",email.toLowerCase(Locale.ROOT),"fullName","Other","password",password),null).getStatusCode());
  String first=(String)created.get("token"),second=login(email,password);String next="Another valid password 2026!";
  assertEquals(HttpStatus.OK,call(HttpMethod.PUT,"/me/password",Map.of("current",password,"next",next),first).getStatusCode());
  for(String token:List.of(first,second))assertEquals(HttpStatus.UNAUTHORIZED,call(HttpMethod.GET,"/auth/me",null,token).getStatusCode());
  assertEquals(HttpStatus.OK,call(HttpMethod.GET,"/auth/me",null,login(email,next)).getStatusCode());assertEquals(1L,jdbc.queryForObject("select token_version from users where id=?",Long.class,id));
 }
 @Test void normalizedEmailLoginThrottleAndWindowRecovery(){
  String email="absent-"+UUID.randomUUID()+"@example.test";
  for(int i=0;i<10;i++)assertEquals(HttpStatus.UNAUTHORIZED,call(HttpMethod.POST,"/auth/login",Map.of("email",i%2==0?email:email.toUpperCase(Locale.ROOT),"password",password),null).getStatusCode());
  assertEquals(HttpStatus.TOO_MANY_REQUESTS,call(HttpMethod.POST,"/auth/login",Map.of("email",email,"password",password),null).getStatusCode());
  clock.now=clock.now.plusSeconds(901);assertEquals(HttpStatus.UNAUTHORIZED,call(HttpMethod.POST,"/auth/login",Map.of("email",email,"password",password),null).getStatusCode());
 }
 @Test void ipLoginAndRegistrationLimitsApplyAcrossEmails(){
  for(int i=0;i<30;i++)assertEquals(HttpStatus.UNAUTHORIZED,call(HttpMethod.POST,"/auth/login",Map.of("email","missing-"+UUID.randomUUID()+"@example.test","password",password),null).getStatusCode());
  assertEquals(HttpStatus.TOO_MANY_REQUESTS,call(HttpMethod.POST,"/auth/login",Map.of("email","other@example.test","password",password),null).getStatusCode());
  for(int i=0;i<10;i++)register("signup-"+UUID.randomUUID()+"@example.test");long before=jdbc.queryForObject("select count(*) from users",Long.class);
  assertEquals(HttpStatus.TOO_MANY_REQUESTS,call(HttpMethod.POST,"/auth/register",Map.of("email","limited@example.test","fullName","Limited","password",password),null).getStatusCode());assertEquals(before,jdbc.queryForObject("select count(*) from users",Long.class));
 }
 @Test void passwordFailuresThrottleWithoutChangingHashOrTokens(){
  Map created=register("password-"+UUID.randomUUID()+"@example.test");String token=(String)created.get("token"),id=(String)((Map)created.get("user")).get("id");
  var before=jdbc.queryForList("select password_hash,token_version,version from users where id=?",id);
  for(int i=0;i<5;i++)assertEquals(HttpStatus.BAD_REQUEST,call(HttpMethod.PUT,"/me/password",Map.of("current","Wrong current 2026!","next","A valid newer password 2026!"),token).getStatusCode());
  assertEquals(HttpStatus.TOO_MANY_REQUESTS,call(HttpMethod.PUT,"/me/password",Map.of("current",password,"next","A valid newer password 2026!"),token).getStatusCode());assertEquals(before,jdbc.queryForList("select password_hash,token_version,version from users where id=?",id));
  clock.now=clock.now.plusSeconds(901);assertEquals(HttpStatus.OK,call(HttpMethod.PUT,"/me/password",Map.of("current",password,"next","A valid newer password 2026!"),token).getStatusCode());
 }
 @Test void commonPolicyRejectsWeakCompositionAndUtf8Overflow(){
  for(String bad:List.of("Aa1!abc","abcdefgh","ABCDEFGH","Abcdefgh","Abcdef12","password1!","Ab1!"+"é".repeat(35)))assertThrows(com.carrental.exception.DomainException.class,()->PasswordPolicy.validate(bad));
  PasswordPolicy.validate("Ax2#bcdE");PasswordPolicy.validate("Ab1!"+"é".repeat(34));
  long before=jdbc.queryForObject("select count(*) from users",Long.class);
  assertEquals(HttpStatus.BAD_REQUEST,call(HttpMethod.POST,"/auth/register",Map.of("email","weak@example.test","fullName","Weak","password","Password1!"),null).getStatusCode());assertEquals(before,jdbc.queryForObject("select count(*) from users",Long.class));
 }
 @Test void phoneValidationPreservesProfileOnRejection(){
  Map created=register("phone-"+UUID.randomUUID()+"@example.test");String token=(String)created.get("token"),id=(String)((Map)created.get("user")).get("id");
  var before=jdbc.queryForList("select full_name,phone,version from users where id=?",id);
  for(String phone:List.of("abc","123","+1234567890123456")){
   assertEquals(HttpStatus.BAD_REQUEST,call(HttpMethod.PUT,"/me/profile",Map.of("fullName","Fixture name","phone",phone,"version",0),token).getStatusCode());assertEquals(before,jdbc.queryForList("select full_name,phone,version from users where id=?",id));
  }
  var updated=call(HttpMethod.PUT,"/me/profile",Map.of("fullName","Fixture name","phone","+84 901-234-567","version",0),token);
  assertEquals(HttpStatus.OK,updated.getStatusCode());assertEquals("+84901234567",updated.getBody().get("phone"));
 }
}
