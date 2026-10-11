package io.github.dream07code.innovationspace.security;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.config.DatabaseSchemaGuard;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class AuthService {
    private final JdbcTemplate jdbc; private final PasswordService passwords; private final SessionService sessions;
    private final DatabaseSchemaGuard schema; private final TransactionTemplate tx; private final Clock clock; private final String initialPassword;
    public AuthService(JdbcTemplate jdbc, PasswordService passwords, SessionService sessions, DatabaseSchemaGuard schema,
                       PlatformTransactionManager manager, Clock clock, @Value("${app.auth.initial-password:}") String initialPassword) {
        this.jdbc=jdbc;this.passwords=passwords;this.sessions=sessions;this.schema=schema;this.tx=new TransactionTemplate(manager);this.clock=clock;this.initialPassword=initialPassword;
    }
    public SessionService.IssuedSession login(String account,String password) {
        schema.requireCurrentSchema();
        return tx.execute(transaction->{
            var users=jdbc.queryForList("SELECT id,password_hash,status FROM app_user WHERE account=? FOR UPDATE",account);
            if(users.isEmpty()){passwords.matches(password,passwords.encode("synthetic-dummy-password"));throw invalidCredentials();}
            if(!passwords.matches(password,(String)users.getFirst().get("password_hash")))throw invalidCredentials();
            long id=((Number)users.getFirst().get("id")).longValue();
            if(!"ENABLED".equals(users.getFirst().get("status")))throw invalidCredentials();
            SessionService.IssuedSession issued;
            try{issued=sessions.issueForUser(id);}catch(ApiException ex){throw invalidCredentials();}
            jdbc.update("UPDATE app_user SET last_login_at=? WHERE id=?",Timestamp.from(clock.instant()),id);
            return issued;
        });
    }
    public SessionService.RefreshResult refresh(String token){ return sessions.rotateRefresh(token); }
    public void changePassword(SessionPrincipal principal,String oldPassword,String newPassword){
        schema.requireCurrentSchema();
        tx.executeWithoutResult(status->{
            var user=jdbc.queryForMap("SELECT * FROM app_user WHERE id=? FOR UPDATE",principal.userId());
            if(!"ENABLED".equals(user.get("status"))||((Number)user.get("auth_revision")).longValue()!=principal.authRevision())throw new ApiException(401,ErrorCode.UNAUTHORIZED);
            String hash=(String)user.get("password_hash");
            if(!passwords.matches(oldPassword,hash)) throw new ApiException(400,ErrorCode.OLD_PASSWORD_INVALID);
            try{passwords.validateNewPassword(newPassword,hash,initialPassword);}catch(IllegalArgumentException ex){throw new ApiException(400,ErrorCode.PASSWORD_POLICY_VIOLATION);}
            jdbc.update("UPDATE app_user SET password_hash=?,must_change_password=false,auth_revision=auth_revision+1,updated_at=? WHERE id=?",
                    passwords.encode(newPassword),Timestamp.from(clock.instant()),principal.userId());
            jdbc.update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?),revoke_reason='PASSWORD_CHANGED' WHERE user_id=?",Timestamp.from(clock.instant()),principal.userId());
        });
    }
    public java.util.Map<String,Object> identity(long userId){
        var user=jdbc.queryForMap("SELECT * FROM app_user WHERE id=?",userId);
        var out=new java.util.LinkedHashMap<String,Object>();out.put("userId",Long.toString(userId));
        out.put("memberId",user.get("member_id")==null?null:user.get("member_id").toString());
        out.put("account",user.get("account"));out.put("displayName",user.get("display_name"));out.put("role",user.get("role"));
        out.put("directionId",user.get("member_id")==null?null:jdbc.queryForObject("SELECT direction_id FROM member WHERE id=?",String.class,user.get("member_id")));
        out.put("managedDirectionIds",jdbc.queryForList("SELECT direction_id FROM leader_direction_scope WHERE user_id=? ORDER BY direction_id",String.class,userId));return out;
    }
    public java.util.Map<String,Object> me(SessionPrincipal p){
        var out=new java.util.LinkedHashMap<String,Object>();out.put("user",identity(p.userId()));out.put("mustChangePassword",p.mustChangePassword());out.put("accountStatus","ENABLED");
        var profile=new java.util.LinkedHashMap<String,Object>();for(var key:new String[]{"studentNo","major","className","grade","contact"})profile.put(key,null);
        out.put("membershipStatus",null);
        if(p.memberId()!=null){var row=jdbc.queryForMap("SELECT * FROM member WHERE id=?",p.memberId());out.put("membershipStatus",row.get("membership_status"));
            profile.put("studentNo",row.get("student_no"));profile.put("major",row.get("major"));profile.put("className",row.get("class_name"));profile.put("grade",row.get("grade"));profile.put("contact",row.get("contact"));}
        out.put("profile",profile);return out;
    }
    private static ApiException invalidCredentials(){return new ApiException(401,ErrorCode.INVALID_CREDENTIALS);}
}
