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
   var response=c.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());ContractAssertions.response(method,path,response);return response;
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
 @Test void appointmentAndScopeReplacementKeepRestrictedAuthorizationHistory()throws Exception {
  var appointed=send("POST","/api/v1/direction-leaders","{\"memberId\":\""+member+"\",\"version\":1,\"managedDirectionIds\":[\"1\",\"2\"],\"reason\":\"确认任命负责人\"}",admin);assertEquals(200,appointed.statusCode());
  var changed=send("PATCH","/api/v1/direction-leaders/"+member,"{\"version\":2,\"managedDirectionIds\":[\"3\"],\"accountStatus\":\"DISABLED\",\"reason\":\"调整方向授权范围\"}",admin);assertEquals(200,changed.statusCode());
  var detail=jdbc.queryForObject("SELECT detail FROM audit_log WHERE action='LEADER_UPDATED'",String.class);
  assertNotNull(detail);assertTrue(detail.contains("oldScopes"));assertTrue(detail.contains("newScopes"));assertTrue(detail.contains("DISABLED"));
  var output=send("GET","/api/v1/audits",null,admin);assertFalse(output.body().contains("oldScopes"));
 }
 @Test void staleRepeatableReadSnapshotCannotAuthorizeRevokedLeader()throws Exception {
  send("POST","/api/v1/direction-leaders","{\"memberId\":\""+member+"\",\"version\":1,\"managedDirectionIds\":[\"1\"],\"reason\":\"确认任命负责人\"}",admin);
  var created=send("POST","/api/v1/members","{\"studentNo\":\"MOCK004\",\"name\":\"目标同学\",\"directionId\":\"1\"}",admin);
  long target=HttpTestSupport.json(created).path("data").path("id").asLong();
  var leader=sessions.authenticateAccess(sessions.issue(user).accessToken());
  var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
  assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status->{
   jdbc.queryForObject("SELECT name FROM member WHERE id=?",String.class,target);
   try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
    executor.submit(()->{jdbc.update("UPDATE app_user SET status='DISABLED',auth_revision=auth_revision+1 WHERE id=?",user);}).get();
   }catch(Exception ex){throw new RuntimeException(ex);}
   memberService.update(target,java.util.Map.of("version",1,"name","Unauthorized update"),leader);
  }));
  assertEquals("目标同学",jdbc.queryForObject("SELECT name FROM member WHERE id=?",String.class,target));
 }
 @Test void correctedStudentNumberMustRespectTheSameIdentityPatternAsCreation()throws Exception {
  var response=send("POST","/api/v1/members/"+member+"/account-corrections","{\"version\":1,\"newStudentNo\":\"bad account\",\"reason\":\"核对并更正学号\"}",admin);
  assertEquals(400,response.statusCode());
  assertEquals("MOCK001",jdbc.queryForObject("SELECT student_no FROM member WHERE id=?",String.class,Long.parseLong(member)));
 }
 @Autowired org.springframework.transaction.PlatformTransactionManager manager;
 @Autowired io.github.dream07code.innovationspace.member.MemberService memberService;
}
