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
class DirectionAndMemberHttpIT {
 @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){MysqlTestSupport.registerMigratedDataSource(r,MYSQL);}
 @Autowired JdbcTemplate jdbc;@Autowired PasswordService passwords;@Autowired SessionService sessions;@Value("${local.server.port}") int port;
 String admin;
 @BeforeEach void fixture(){
  for(String t:new String[]{"api_idempotency","auth_session","audit_log","leader_direction_scope","app_user","member"})jdbc.update("DELETE FROM "+t);
  jdbc.update("INSERT INTO app_user(id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES (1,'mock-admin',?,'COLLEGE_ADMIN',false,'ENABLED','Synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",passwords.encode("Synthetic-password12"));
  admin=sessions.issue(1).accessToken();
 }
 HttpResponse<String> send(String method,String path,String body,String token,String key)throws Exception{
  try(var c=HttpClient.newHttpClient()){
   var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(token!=null)b.header("Authorization","Bearer "+token);
   if(key!=null)b.header("Idempotency-Key",key);if(body!=null)b.header("Content-Type","application/json");
   return c.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
 }
 @Test void adminCreatesExactlyOneStudentAndPartialUpdatesRespectVersion()throws Exception{
  var directions=send("GET","/api/v1/directions",null,admin,null);assertEquals(200,directions.statusCode());assertEquals(7,HttpTestSupport.json(directions).path("data").size());
  String body="{\"studentNo\":\"MOCK001\",\"name\":\"示例同学\",\"directionId\":\"1\"}",key="11111111-1111-4111-8111-111111111111";
  var created=send("POST","/api/v1/members",body,admin,key);assertEquals(201,created.statusCode());
  var data=HttpTestSupport.json(created).path("data");assertTrue(data.path("id").isString());assertEquals("STUDENT",data.path("role").asText());assertTrue(data.path("mustChangePassword").asBoolean());assertEquals(0,data.path("totalPoints").asInt());
  assertEquals(201,send("POST","/api/v1/members",body,admin,key).statusCode());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM member",Integer.class));
  String id=data.path("id").asText();
  var changed=send("PATCH","/api/v1/members/"+id,"{\"version\":1,\"contact\":null}",admin,null);assertEquals(200,changed.statusCode());assertEquals(2,HttpTestSupport.json(changed).path("data").path("version").asInt());
  assertEquals(409,send("PATCH","/api/v1/members/"+id,"{\"version\":1,\"name\":\"旧版本\"}",admin,null).statusCode());
  assertEquals(400,send("PATCH","/api/v1/members/"+id,"{\"version\":2,\"studentNo\":\"wrong\"}",admin,null).statusCode());
 }
 @Test void studentCannotCreateAndLeaderCannotTargetAnotherDirection()throws Exception{
  var response=send("POST","/api/v1/members","{\"studentNo\":\"MOCK002\",\"name\":\"方向负责人\",\"directionId\":\"1\"}",admin,"22222222-2222-4222-8222-222222222222");
  long userId=HttpTestSupport.json(response).path("data").path("userId").asLong();
  jdbc.update("UPDATE app_user SET must_change_password=false WHERE id=?",userId);
  var student=sessions.issue(userId).accessToken();
  assertEquals(403,send("GET","/api/v1/members",null,student,null).statusCode());
  jdbc.update("UPDATE app_user SET role='DIRECTION_LEADER',auth_revision=auth_revision+1 WHERE id=?",userId);
  jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at,granted_by) VALUES (?,1,UTC_TIMESTAMP(6),1)",userId);
  var leader=sessions.issue(userId).accessToken();
  assertEquals(403,send("POST","/api/v1/members","{\"studentNo\":\"MOCK003\",\"name\":\"跨方向\",\"directionId\":\"2\"}",leader,"33333333-3333-4333-8333-333333333333").statusCode());
  var list=send("GET","/api/v1/members",null,leader,null);assertEquals(200,list.statusCode());assertEquals(0,HttpTestSupport.json(list).path("data").path("total").asInt());
 }
 @Test void listFiltersBeforeCountAndNeverReturnsPasswordOrHash()throws Exception{
  var list=send("GET","/api/v1/members?page=1&size=20",null,admin,null);
  assertEquals(200,list.statusCode());String body=list.body();assertFalse(body.contains("password_hash"));assertFalse(body.contains("synthetic-initial"));
 }
}
