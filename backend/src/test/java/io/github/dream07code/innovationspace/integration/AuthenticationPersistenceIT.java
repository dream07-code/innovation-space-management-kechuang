package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OwnedDataSourceConfiguration;
import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.innovationspace.security.PasswordService;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class AuthenticationPersistenceIT {
    @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){MysqlTestSupport.registerMigratedDataSource(r,MYSQL);}
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    @Autowired PasswordService passwords;
    @Autowired IdempotencyService idempotency;
    @BeforeEach void fixture(){
        for(String table:new String[]{"api_idempotency","auth_session","audit_log","leader_direction_scope","app_user","member"})jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO app_user (id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES (1,?,?, 'COLLEGE_ADMIN',false,'ENABLED','Synthetic Admin',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                "mock-admin",passwords.encode("synthetic-pass-12"));
    }
    @Test void rotationRevokesOldAccessAndReplayCommitsFamilyRevocation(){
        var first=sessions.issue(1);
        assertEquals(1,sessions.authenticateAccess(first.accessToken()).userId());
        assertFalse(jdbc.queryForObject("SELECT HEX(access_token_hash) FROM auth_session",String.class).contains(first.accessToken()));
        var rotated=sessions.rotateRefresh(first.refreshToken());assertTrue(rotated.success());
        assertThrows(RuntimeException.class,()->sessions.authenticateAccess(first.accessToken()));
        assertEquals(1,sessions.authenticateAccess(rotated.session().accessToken()).userId());
        assertFalse(sessions.rotateRefresh(first.refreshToken()).success());
        assertThrows(RuntimeException.class,()->sessions.authenticateAccess(rotated.session().accessToken()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE revoked_at IS NOT NULL",Integer.class));
    }
    @Test void restrictedSessionHasNoRefreshAndAuthRevisionInvalidatesAccess(){
        jdbc.update("UPDATE app_user SET must_change_password=true WHERE id=1");
        var issued=sessions.issue(1);assertNull(issued.refreshToken());assertTrue(issued.mustChangePassword());
        assertTrue(sessions.authenticateAccess(issued.accessToken()).mustChangePassword());
        jdbc.update("UPDATE app_user SET auth_revision=auth_revision+1 WHERE id=1");
        assertThrows(RuntimeException.class,()->sessions.authenticateAccess(issued.accessToken()));
    }
    @Test void sameKeyReplaysAndDifferentPayloadIsRejected(){
        var actor=sessions.authenticateAccess(sessions.issue(1).accessToken());
        String key="11111111-1111-4111-8111-111111111111";
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var one=idempotency.execute(actor,"POST","/members",key,"{\"a\":1}",()->{calls.incrementAndGet();return new IdempotencyService.StoredResponse(201,"{\"id\":\"9\"}");});
        var two=idempotency.execute(actor,"POST","/members",key,"{\"a\":1}",()->{fail("must replay");return null;});
        assertEquals(one,two);assertEquals(1,calls.get());
        assertThrows(RuntimeException.class,()->idempotency.execute(actor,"POST","/members",key,"{\"a\":2}",()->one));
    }
    @Test void racingRefreshIsSingleWinnerAndReplayStillRevokesTheWinner() throws Exception {
        var first=sessions.issue(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var latch=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<SessionService.RefreshResult> work=()->{latch.await();return sessions.rotateRefresh(first.refreshToken());};
            var a=executor.submit(work);var b=executor.submit(work);latch.countDown();
            var resultA=a.get(10,java.util.concurrent.TimeUnit.SECONDS);var resultB=b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(resultA.success(),resultB.success());
            var winner=resultA.success()?resultA:resultB;
            assertThrows(RuntimeException.class,()->sessions.authenticateAccess(winner.session().accessToken()));
        }
    }
    @Test void failedOperationDoesNotLeaveAnIdempotencyRecord(){
        var actor=sessions.authenticateAccess(sessions.issue(1).accessToken());
        String key="22222222-2222-4222-8222-222222222222";
        assertThrows(IllegalStateException.class,()->idempotency.execute(actor,"POST","/members",key,"{}",()->{throw new IllegalStateException("synthetic");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency",Integer.class));
    }
    @Test void expiredOrDisabledSessionsFailAndRepeatedLogoutCannotReviveThem(){
        var expired=sessions.issue(1);
        jdbc.update("UPDATE auth_session SET access_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,refresh_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND");
        assertThrows(RuntimeException.class,()->sessions.authenticateAccess(expired.accessToken()));
        assertFalse(sessions.rotateRefresh(expired.refreshToken()).success());
        var logout=sessions.issue(1);sessions.revokeSession(logout.accessToken(),"LOGOUT");
        assertThrows(RuntimeException.class,()->sessions.authenticateAccess(logout.accessToken()));
        assertFalse(sessions.rotateRefresh(logout.refreshToken()).success());
        var disabled=sessions.issue(1);jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=1");
        assertThrows(RuntimeException.class,()->sessions.authenticateAccess(disabled.accessToken()));
    }
}
