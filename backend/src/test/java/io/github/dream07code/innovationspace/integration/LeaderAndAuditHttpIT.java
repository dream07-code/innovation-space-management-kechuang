package io.github.dream07code.innovationspace.integration;
import io.github.dream07code.testsupport.*;
import io.github.dream07code.innovationspace.security.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="app.auth.initial-password=synthetic-initial")
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class LeaderAndAuditHttpIT {
 @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){MysqlTestSupport.registerMigratedDataSource(r,MYSQL);}
 @Autowired JdbcTemplate jdbc;@Autowired PasswordService passwords;@Autowired SessionService sessions;@Value("${local.server.port}")int port;String admin;String member;long user;
 @BeforeEach void fixture()throws Exception{
  for(String t:new String[]{"api_idempotency","auth_session","audit_log","leader_direction_scope","app_user","member"})jdbc.update("DELETE FROM "+t);
  jdbc.update("INSERT INTO app_user(id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES (1,'mock-admin',?,'COLLEGE_ADMIN',false,'ENABLED','Synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",passwords.encode("Synthetic-password12"));admin=sessions.issue(1).accessToken();
  var response=send("POST","/api/v1/members","{\"studentNo\":\"MOCK001\",\"name\":\"示例同学\",\"directionId\":\"1\"}",admin);
  var data=HttpTestSupport.json(response).path("data");member=data.path("id").asText();user=data.path("userId").asLong();
  jdbc.update("UPDATE app_user SET must_change_password=false WHERE id=?",user);
 }
 HttpResponse<String> send(String method,String path,String body,String token)throws Exception{
  try(var c=HttpClient.newHttpClient()){
   var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(token!=null)b.header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json");
   if(method.equals("POST"))b.header("Idempotency-Key",java.util.UUID.randomUUID().toString());
   return c.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
 }
 @Test void promotionRevocationCorrectionAndResetPreserveIdentityAndRevokeSessions()throws Exception{
  var student=sessions.issue(user).accessToken();
  var promotion=send("POST","/api/v1/direction-leaders","{\"memberId\":\""+member+"\",\"version\":1,\"managedDirectionIds\":[\"1\"],\"reason\":\"确认任命负责人\"}",admin);
  assertEquals(200,promotion.statusCode());assertEquals("DIRECTION_LEADER",HttpTestSupport.json(promotion).path("data").path("role").asText());assertThrows(RuntimeException.class,()->sessions.authenticateAccess(student));
  var leader=sessions.issue(user).accessToken();assertEquals(403,send("GET","/api/v1/direction-leaders",null,leader).statusCode());
  var correction=send("POST","/api/v1/members/"+member+"/account-corrections","{\"version\":2,\"newStudentNo\":\"MOCK002\",\"reason\":\"核对并更正学号\"}",admin);assertEquals(200,correction.statusCode());
  assertEquals(member,HttpTestSupport.json(correction).path("data").path("id").asText());assertThrows(RuntimeException.class,()->sessions.authenticateAccess(leader));
  var reset=send("POST","/api/v1/members/"+member+"/password-reset","{\"version\":3,\"reason\":\"本人申请密码重置\"}",admin);assertEquals(200,reset.statusCode());assertTrue(HttpTestSupport.json(reset).path("data").path("mustChangePassword").asBoolean());
  var revoke=send("POST","/api/v1/direction-leaders/"+member+"/revoke","{\"version\":4,\"reason\":\"调整负责人授权\"}",admin);assertEquals(200,revoke.statusCode());assertEquals("STUDENT",HttpTestSupport.json(revoke).path("data").path("role").asText());
  var audits=send("GET","/api/v1/audits",null,admin);assertEquals(200,audits.statusCode());assertTrue(HttpTestSupport.json(audits).path("data").path("total").asInt()>=4);assertFalse(audits.body().contains("synthetic-initial"));assertFalse(audits.body().contains("password_hash"));
 }
}
