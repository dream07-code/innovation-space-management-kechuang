package io.github.dream07code.innovationspace.security;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.config.DatabaseSchemaGuard;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionService {
    public record IssuedSession(String accessToken,String refreshToken,long expiresIn,Long refreshExpiresIn,
                                boolean mustChangePassword,long userId) {
        @Override public String toString(){return "IssuedSession[redacted]";}
    }
    public record RefreshResult(boolean success, IssuedSession session) {}
    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final DatabaseSchemaGuard schema;
    public SessionService(JdbcTemplate jdbc,TokenService tokens,Clock clock,PlatformTransactionManager manager,DatabaseSchemaGuard schema){
        this.jdbc=jdbc;this.tokens=tokens;this.clock=clock;this.transaction=new TransactionTemplate(manager);this.schema=schema;
    }
    public IssuedSession issue(long userId){schema.requireCurrentSchema();return transaction.execute(s->issueLocked(lockUser(userId),UUID.randomUUID().toString(),clock.instant().plusSeconds(604800)));}
    private Map<String,Object> lockUser(long userId){
        var rows=jdbc.queryForList("SELECT * FROM app_user WHERE id=? FOR UPDATE",userId);
        if(rows.isEmpty())throw unauthorized();return rows.getFirst();
    }
    private void active(Map<String,Object> user){
        if(!"ENABLED".equals(user.get("status")))throw unauthorized();
        if(user.get("member_id")!=null && !"ACTIVE".equals(jdbc.queryForObject("SELECT membership_status FROM member WHERE id=?",String.class,user.get("member_id"))))throw unauthorized();
    }
    private IssuedSession issueLocked(Map<String,Object> user,String family,Instant absoluteExpiry){
        active(user);boolean restricted=Boolean.TRUE.equals(user.get("must_change_password"));
        long userId=((Number)user.get("id")).longValue();long revision=((Number)user.get("auth_revision")).longValue();
        var pair=tokens.issueNormal(userId,revision);
        String access=pair.access().raw(),refresh=restricted?null:pair.refresh().raw();
        Instant now=clock.instant();long seconds=restricted?300:900;
        jdbc.update("INSERT INTO auth_session(user_id,access_token_hash,refresh_token_hash,refresh_family_id,session_type,auth_revision,access_expires_at,refresh_expires_at,created_at) VALUES (?,UNHEX(?),UNHEX(?),?,?,?,?,?,?)",
                userId,tokens.digest(access),refresh==null?null:tokens.digest(refresh),restricted?null:family,restricted?"PASSWORD_CHANGE":"NORMAL",revision,
                Timestamp.from(now.plusSeconds(seconds)),restricted?null:Timestamp.from(absoluteExpiry),Timestamp.from(now));
        return new IssuedSession(access,refresh,seconds,restricted?null:java.time.Duration.between(now,absoluteExpiry).getSeconds(),restricted,userId);
    }
    public SessionPrincipal authenticateAccess(String raw){
        schema.requireCurrentSchema();try{tokens.validateSyntax(raw);}catch(IllegalArgumentException e){throw unauthorized();}
        var rows=jdbc.queryForList("SELECT s.*,u.role,u.member_id,u.status,u.must_change_password,u.auth_revision AS current_revision FROM auth_session s JOIN app_user u ON u.id=s.user_id WHERE s.access_token_hash=UNHEX(?)",tokens.digest(raw));
        if(rows.isEmpty())throw unauthorized();var row=rows.getFirst();
        if(row.get("revoked_at")!=null||row.get("rotated_at")!=null||!instant(row.get("access_expires_at")).isAfter(clock.instant())
                ||!"ENABLED".equals(row.get("status"))||((Number)row.get("auth_revision")).longValue()!=((Number)row.get("current_revision")).longValue())throw unauthorized();
        Long member=row.get("member_id")==null?null:((Number)row.get("member_id")).longValue();
        if(member!=null&&!"ACTIVE".equals(jdbc.queryForObject("SELECT membership_status FROM member WHERE id=?",String.class,member)))throw unauthorized();
        long user=((Number)row.get("user_id")).longValue();
        return new SessionPrincipal(user,member,(String)row.get("role"),jdbc.queryForList("SELECT direction_id FROM leader_direction_scope WHERE user_id=? ORDER BY direction_id",Long.class,user),
                Boolean.TRUE.equals(row.get("must_change_password"))||"PASSWORD_CHANGE".equals(row.get("session_type")),((Number)row.get("current_revision")).longValue(),((Number)row.get("id")).longValue());
    }
    public RefreshResult rotateRefresh(String raw){
        schema.requireCurrentSchema();try{tokens.validateSyntax(raw);}catch(IllegalArgumentException e){return new RefreshResult(false,null);}
        var candidates=jdbc.queryForList("SELECT user_id,id FROM auth_session WHERE refresh_token_hash=UNHEX(?)",tokens.digest(raw));
        if(candidates.isEmpty())return new RefreshResult(false,null);
        return transaction.execute(status->{
            var user=lockUser(((Number)candidates.getFirst().get("user_id")).longValue());
            var row=jdbc.queryForMap("SELECT * FROM auth_session WHERE id=? FOR UPDATE",candidates.getFirst().get("id"));
            if(row.get("rotated_at")!=null){
                jdbc.update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?),revoke_reason='REFRESH_REPLAY' WHERE refresh_family_id=?",Timestamp.from(clock.instant()),row.get("refresh_family_id"));
                return new RefreshResult(false,null);
            }
            if(row.get("revoked_at")!=null||row.get("refresh_expires_at")==null||!instant(row.get("refresh_expires_at")).isAfter(clock.instant())
                    ||!"ENABLED".equals(user.get("status"))||Boolean.TRUE.equals(user.get("must_change_password"))
                    ||((Number)user.get("auth_revision")).longValue()!=((Number)row.get("auth_revision")).longValue())return new RefreshResult(false,null);
            try{active(user);}catch(ApiException e){return new RefreshResult(false,null);}
            jdbc.update("UPDATE auth_session SET rotated_at=?,revoked_at=?,revoke_reason='ROTATED' WHERE id=?",Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),row.get("id"));
            return new RefreshResult(true,issueLocked(user,(String)row.get("refresh_family_id"),instant(row.get("refresh_expires_at"))));
        });
    }
    public void revokeSession(String access,String reason){
        var principal=authenticateAccess(access);transaction.executeWithoutResult(s->{lockUser(principal.userId());jdbc.update("UPDATE auth_session SET revoked_at=?,revoke_reason=? WHERE id=?",Timestamp.from(clock.instant()),reason,principal.sessionId());});
    }
    public void revokeAllForUser(long userId,String reason){
        transaction.executeWithoutResult(s->{lockUser(userId);jdbc.update("UPDATE app_user SET auth_revision=auth_revision+1 WHERE id=?",userId);
            jdbc.update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?),revoke_reason=? WHERE user_id=?",Timestamp.from(clock.instant()),reason,userId);});
    }
    private static Instant instant(Object timestamp){return timestamp instanceof Timestamp t?t.toInstant():((java.time.LocalDateTime)timestamp).toInstant(java.time.ZoneOffset.UTC);}
    private static ApiException unauthorized(){return new ApiException(401,ErrorCode.UNAUTHORIZED);}
}
