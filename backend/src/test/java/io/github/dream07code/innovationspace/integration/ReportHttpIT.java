package io.github.dream07code.innovationspace.integration;
import io.github.dream07code.testsupport.*;
import io.github.dream07code.innovationspace.security.*;
import java.net.URI;import java.net.http.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.*;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.context.annotation.Import;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.test.annotation.DirtiesContext;import org.springframework.test.context.*;import org.testcontainers.junit.jupiter.*;import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.report.points-policy-status=READY","app.report.daily-base-points=1","app.auth.initial-password=synthetic-initial"})
@Import(OwnedDataSourceConfiguration.class) @DirtiesContext @Testcontainers
class ReportHttpIT {
 @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();@DynamicPropertySource static void db(DynamicPropertyRegistry r){MysqlTestSupport.registerMigratedDataSource(r,MYSQL);}
 @Autowired JdbcTemplate jdbc;@Autowired PasswordService passwords;@Autowired SessionService sessions;@Value("${local.server.port}")int port;String token;long memberId;
 @BeforeEach void fixture()throws Exception{MysqlTestSupport.migrate(MYSQL);for(String t:new String[]{"point_ledger","report_scoring_task","report_version","report_review","report","api_idempotency","leader_direction_scope","auth_session","audit_log","app_user","member"})jdbc.update("DELETE FROM "+t);jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(10,'MOCK001',1,'报告同学',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(10,10,'MOCK001',?,'STUDENT',false,'ENABLED','报告同学',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",passwords.encode("synthetic-password12"));token=sessions.issue(10).accessToken();memberId=10;}
 HttpResponse<String> send(String method,String path,String body,String key)throws Exception{try(var c=HttpClient.newHttpClient()){var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json");if(key!=null)b.header("Idempotency-Key",key);var r=c.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());return r;}}
 @Test void dailyDraftSubmitIsUniqueIdempotentAndScoresLater(){assertDoesNotThrow(()->{String body="{\"reportDate\":\"2026-10-11\",\"learningContent\":\"learned\",\"gains\":\"gain\"}";var draft=send("POST","/api/v1/daily-reports",body,"11111111-1111-4111-8111-111111111111");assertEquals(201,draft.statusCode());var id=HttpTestSupport.json(draft).path("data").path("id").asText();assertEquals(201,send("POST","/api/v1/daily-reports",body,"11111111-1111-4111-8111-111111111111").statusCode());var submitted=send("POST","/api/v1/daily-reports/"+id+"/submit","{\"version\":1}",null);assertEquals(200,submitted.statusCode());assertEquals("SUBMITTED",HttpTestSupport.json(submitted).path("data").path("status").asText());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE member_id=10",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM report_scoring_task",Integer.class));assertEquals(200,send("POST","/api/v1/daily-reports/"+id+"/submit","{\"version\":1}",null).statusCode());});}
 @Test void invalidSubmittedContentAndUnregisteredRoutesAreRejected()throws Exception{var draft=send("POST","/api/v1/daily-reports","{\"reportDate\":\"2026-10-11\"}","22222222-2222-4222-8222-222222222222");var id=HttpTestSupport.json(draft).path("data").path("id").asText();assertEquals(400,send("POST","/api/v1/daily-reports/"+id+"/submit","{\"version\":1}",null).statusCode());assertEquals(404,send("GET","/api/v1/monthly-assessments",null,null).statusCode());assertEquals(404,send("POST","/api/v1/daily-reports/"+id+"/delete",null,null).statusCode());}

 @Test void patchMergesVersionsAndInvalidSubmittedEditChangesNothing()throws Exception{
  var created=send("POST","/api/v1/daily-reports","{\"learningContent\":\"first\",\"gains\":\"initial\"}","33333333-3333-4333-8333-333333333333");
  assertEquals(201,created.statusCode());ContractAssertions.response("POST","/api/v1/daily-reports",created);
  var id=HttpTestSupport.json(created).path("data").path("id").asText();
  var patched=send("PATCH","/api/v1/daily-reports/"+id,"{\"version\":1,\"learningContent\":\"updated\"}",null);
  assertEquals(200,patched.statusCode());ContractAssertions.response("PATCH","/api/v1/daily-reports/"+id,patched);
  assertEquals(2,HttpTestSupport.json(patched).at("/data/version").asInt());
  assertEquals("updated",HttpTestSupport.json(patched).at("/data/learningContent").asText());
  assertEquals("initial",HttpTestSupport.json(patched).at("/data/gains").asText());
  assertEquals(409,send("PATCH","/api/v1/daily-reports/"+id,"{\"version\":1,\"learningContent\":\"stale\"}",null).statusCode());
  var submitted=send("POST","/api/v1/daily-reports/"+id+"/submit","{\"version\":2}",null);
  assertEquals(200,submitted.statusCode());ContractAssertions.response("POST","/api/v1/daily-reports/"+id+"/submit",submitted);
  jdbc.update("UPDATE report SET editable_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?",Long.valueOf(id));
  assertEquals(400,send("PATCH","/api/v1/daily-reports/"+id,"{\"version\":2,\"learningContent\":\"changed\",\"gains\":\"\"}",null).statusCode());
  assertEquals(2,HttpTestSupport.json(send("GET","/api/v1/daily-reports/"+id,null,null)).at("/data/version").asInt());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger",Integer.class));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM report_scoring_task",Integer.class));
  var valid=send("PATCH","/api/v1/daily-reports/"+id,"{\"version\":2,\"learningContent\":\"changed\",\"gains\":\"better\"}",null);
  assertEquals(200,valid.statusCode());
  assertEquals(3,HttpTestSupport.json(valid).at("/data/version").asInt());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger",Integer.class));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM report_scoring_task",Integer.class));
 }

 @Test void reportListsApplyObjectScopeBeforePagination()throws Exception{
  jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(11,'MOCK002',1,'同学乙',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
  jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(12,'MOCK003',1,'负责人',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
  jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(11,11,'MOCK002',?,'STUDENT',false,'ENABLED','同学乙',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",passwords.encode("synthetic-password12"));
  jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin',?,'COLLEGE_ADMIN',false,'ENABLED','学院管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",passwords.encode("synthetic-password12"));
  jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(21,12,'synthetic-leader',?,'DIRECTION_LEADER',false,'ENABLED','方向负责人',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",passwords.encode("synthetic-password12"));
  jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(21,1,UTC_TIMESTAMP(6))");
  var mine=send("POST","/api/v1/daily-reports","{\"learningContent\":\"private draft\",\"gains\":\"mine\"}","44444444-4444-4444-8444-444444444444");
  jdbc.update("INSERT INTO report(id,member_id,direction_id,report_type,report_date,status,current_version,score_status,first_submitted_at,created_at,updated_at) VALUES(101,11,1,'DAILY',CURDATE(),'SUBMITTED',1,'NOT_REQUESTED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
  jdbc.update("INSERT INTO report_version(id,report_id,version,content_json,content_hash,edited_by,created_at) VALUES(101,101,1,'{\"learningContent\":\"visible\",\"gains\":\"done\"}',UNHEX(SHA2('x',256)),11,UTC_TIMESTAMP(6))");
  var studentList=send("GET","/api/v1/daily-reports?page=1&size=20",null,null);
  assertEquals(200,studentList.statusCode());ContractAssertions.response("GET","/api/v1/daily-reports",studentList);
  assertEquals(1,HttpTestSupport.json(studentList).at("/data/total").asInt());
  assertEquals(403,send("GET","/api/v1/daily-reports?memberId=11",null,null).statusCode());
  var adminToken=sessions.issue(20).accessToken();
  var adminList=sendToken(adminToken,"GET","/api/v1/daily-reports?page=1&size=20",null);
  assertEquals(200,adminList.statusCode());assertEquals(1,HttpTestSupport.json(adminList).at("/data/total").asInt());
  assertEquals("101",HttpTestSupport.json(adminList).at("/data/items/0/id").asText());
  var leaderToken=sessions.issue(21).accessToken();
  var leaderList=sendToken(leaderToken,"GET","/api/v1/daily-reports?page=1&size=20",null);
  assertEquals(200,leaderList.statusCode());assertEquals(1,HttpTestSupport.json(leaderList).at("/data/total").asInt());
  assertFalse(HttpTestSupport.json(leaderList).toString().contains("private draft"));
 }

 HttpResponse<String> sendToken(String auth,String method,String path,String body)throws Exception{try(var c=HttpClient.newHttpClient()){var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+auth);if(body!=null)b.header("Content-Type","application/json");return c.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}}

 @Test void weeklyDraftPatchKeepsComputedWeekAndMergesContent()throws Exception{var created=send("POST","/api/v1/weekly-reports","{\"summary\":\"first week\",\"achievements\":\"done\"}","55555555-5555-4555-8555-555555555555");assertEquals(201,created.statusCode());ContractAssertions.response("POST","/api/v1/weekly-reports",created);var id=HttpTestSupport.json(created).path("data").path("id").asText();var patched=send("PATCH","/api/v1/weekly-reports/"+id,"{\"version\":1,\"summary\":\"updated week\"}",null);assertEquals(200,patched.statusCode());ContractAssertions.response("PATCH","/api/v1/weekly-reports/"+id,patched);assertEquals("updated week",HttpTestSupport.json(patched).at("/data/summary").asText());assertEquals("done",HttpTestSupport.json(patched).at("/data/achievements").asText());assertFalse(HttpTestSupport.json(patched).at("/data/weekEnd").asText().isBlank());}
}
