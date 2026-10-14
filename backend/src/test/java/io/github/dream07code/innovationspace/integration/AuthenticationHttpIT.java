package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.*;
import io.github.dream07code.innovationspace.security.PasswordService;
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
@Import({OwnedDataSourceConfiguration.class,WebProbeConfiguration.class})
@DirtiesContext
@Testcontainers
class AuthenticationHttpIT {
    @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){MysqlTestSupport.registerMigratedDataSource(r,MYSQL);}
    @Autowired JdbcTemplate jdbc;@Autowired PasswordService passwords;@Value("${local.server.port}") int port;
    @BeforeEach void fixture(){
        for(String table:new String[]{"api_idempotency","auth_session","audit_log","leader_direction_scope","app_user","member"})jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO app_user(id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES (1,'mock-admin',?,'COLLEGE_ADMIN',true,'ENABLED','Synthetic Admin',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",passwords.encode("synthetic-initial"));
    }
    HttpResponse<String> send(String method,String path,String body,String token) throws Exception{
        try(var client=HttpClient.newHttpClient()){
            var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
            if(token!=null)b.header("Authorization","Bearer "+token);
            if(body!=null)b.header("Content-Type","application/json");
            var response=client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            ContractAssertions.response(method,path,response);return response;
        }
    }
    @Test void firstLoginIsRestrictedAndChangingPasswordRequiresFreshLogin() throws Exception {
        var login=send("POST","/api/v1/auth/login","{\"account\":\"mock-admin\",\"password\":\"synthetic-initial\"}",null);
        assertEquals(200,login.statusCode());assertEquals("no-store",login.headers().firstValue("Cache-Control").orElse(null));
        var data=HttpTestSupport.json(login).path("data");assertTrue(data.path("mustChangePassword").asBoolean());assertTrue(data.path("refreshToken").isNull());
        String token=data.path("accessToken").asText();
        assertEquals(200,send("GET","/api/v1/auth/me",null,token).statusCode());
        var blocked=send("GET","/api/v1/test-probe/ping",null,token);assertEquals(403,blocked.statusCode());
        assertEquals("PASSWORD_CHANGE_REQUIRED",HttpTestSupport.json(blocked).path("code").asText());
        assertEquals(200,send("PUT","/api/v1/auth/password","{\"oldPassword\":\"synthetic-initial\",\"newPassword\":\"Changed-Synthetic12!\"}",token).statusCode());
        assertEquals(401,send("GET","/api/v1/auth/me",null,token).statusCode());
        var again=send("POST","/api/v1/auth/login","{\"account\":\"mock-admin\",\"password\":\"Changed-Synthetic12!\"}",null);
        assertEquals(200,again.statusCode());assertFalse(HttpTestSupport.json(again).path("data").path("mustChangePassword").asBoolean());
        assertFalse(again.headers().allValues("Set-Cookie").stream().anyMatch(s->s.contains("JSESSIONID")));
    }
    @Test void credentialsUnknownFieldsAndRoutingKeepSafeStatuses() throws Exception{
        assertEquals(401,send("POST","/api/v1/auth/login","{\"account\":\"unknown\",\"password\":\"incorrect\"}",null).statusCode());
        assertEquals(400,send("POST","/api/v1/auth/login","{\"account\":\"mock-admin\",\"password\":\"synthetic-initial\",\"role\":\"COLLEGE_ADMIN\"}",null).statusCode());
        assertEquals(404,send("GET","/api/v1/register",null,null).statusCode());
        assertEquals(401,send("GET","/api/v1/auth/me",null,null).statusCode());
        assertEquals(405,send("GET","/api/v1/auth/login",null,null).statusCode());
    }
    @Test void refreshReplayAndLogoutInvalidateAccessAndNoStoreIsAlwaysPresent() throws Exception {
        jdbc.update("UPDATE app_user SET must_change_password=false WHERE id=1");
        var login=send("POST","/api/v1/auth/login","{\"account\":\"mock-admin\",\"password\":\"synthetic-initial\"}",null);
        var first=HttpTestSupport.json(login).path("data");
        String refresh=first.path("refreshToken").asText();
        var rotated=send("POST","/api/v1/auth/refresh","{\"refreshToken\":\""+refresh+"\"}",null);
        assertEquals(200,rotated.statusCode());assertEquals("no-store",rotated.headers().firstValue("Cache-Control").orElse(null));
        String access=HttpTestSupport.json(rotated).path("data").path("accessToken").asText();
        assertEquals(401,send("GET","/api/v1/auth/me",null,first.path("accessToken").asText()).statusCode());
        assertEquals(401,send("POST","/api/v1/auth/refresh","{\"refreshToken\":\""+refresh+"\"}",null).statusCode());
        assertEquals(401,send("GET","/api/v1/auth/me",null,access).statusCode());
    }
    @Test void rateLimitIs429WithRetryAfterAndDoesNotEchoCredentials() throws Exception {
        // PBKDF2 takes several seconds; start away from the fixed 60-second boundary so the
        // eleventh request cannot reset into a new bucket and incorrectly return 401.
        while (java.time.Instant.now().getEpochSecond() % 60 >= 50) Thread.sleep(100);
        HttpResponse<String> response=null;
        for(int i=0;i<11;i++)response=send("POST","/api/v1/auth/login","{\"account\":\"mock-rate-account\",\"password\":\"wrong-password\"}",null);
        assertEquals(429,response.statusCode());assertTrue(response.headers().firstValue("Retry-After").isPresent());assertFalse(response.body().contains("wrong-password"));
    }
}
