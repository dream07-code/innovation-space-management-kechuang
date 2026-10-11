package io.github.dream07code.innovationspace.leader;
import io.github.dream07code.innovationspace.member.*;
import io.github.dream07code.innovationspace.security.*;
import io.github.dream07code.innovationspace.common.api.*;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.util.*;
import java.time.Clock;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class LeaderService {
 private final JdbcTemplate jdbc;private final MemberService members;private final Clock clock;private final TransactionTemplate tx;
 public LeaderService(JdbcTemplate jdbc,MemberService members,Clock clock,PlatformTransactionManager manager){this.jdbc=jdbc;this.members=members;this.clock=clock;this.tx=new TransactionTemplate(manager);}
 public Map<String,Object> list(SessionPrincipal actor,int page,int size,String direction){MemberScopePolicy.admin(actor);if(page<1||size<1||size>200)throw MemberService.bad();var args=new ArrayList<Object>();String where=" WHERE u.role='DIRECTION_LEADER'";
  if(direction!=null){where+=" AND EXISTS(SELECT 1 FROM leader_direction_scope s WHERE s.user_id=u.id AND s.direction_id=?)";args.add(MemberService.parseId(direction));}
  long total=jdbc.queryForObject("SELECT COUNT(*) FROM member m JOIN app_user u ON u.member_id=m.id"+where,Long.class,args.toArray());args.add(size);args.add((long)(page-1)*size);
  var rows=jdbc.queryForList("SELECT m.*,u.id user_id,u.role,u.status account_status,u.must_change_password FROM member m JOIN app_user u ON u.member_id=m.id"+where+" ORDER BY m.id LIMIT ? OFFSET ?",args.toArray());return Map.of("items",rows.stream().map(members::map).toList(),"page",page,"size",size,"total",total);
 }
 public Map<String,Object> appoint(long id,Map<String,Object> request,SessionPrincipal actor){
  MemberService.fields(request,Set.of("memberId","version","managedDirectionIds","reason"));String reason=MemberService.required(request,"reason",1000);var row=members.row(id);
  if(!"STUDENT".equals(row.get("role"))||!"ACTIVE".equals(row.get("membership_status"))||!"ENABLED".equals(row.get("account_status")))throw new ApiException(409,ErrorCode.INVALID_STATE);
  List<Long> directions=scopes(request);long user=((Number)row.get("user_id")).longValue();
  jdbc.update("UPDATE app_user SET role='DIRECTION_LEADER',auth_revision=auth_revision+1 WHERE id=?",user);grant(user,directions,actor);
  jdbc.update("UPDATE member SET version=version+1,updated_at=? WHERE id=?",Timestamp.from(clock.instant()),id);members.revoke(user,"LEADER_APPOINTED");members.audit(actor,"LEADER_APPOINTED",id,reason,"Appointed direction leader");return members.map(members.row(id));
 }
 public Map<String,Object> update(long id,Map<String,Object> request,SessionPrincipal actor){
  MemberScopePolicy.admin(actor);MemberService.fields(request,Set.of("version","reason","name","major","className","grade","contact","directionId","managedDirectionIds","accountStatus"));if(request.size()<3)throw MemberService.bad();
  String reason=MemberService.required(request,"reason",1000);
  return tx.execute(status->{members.lockAndVersion(id,request,actor);var row=members.row(id);if(!"DIRECTION_LEADER".equals(row.get("role")))throw new ApiException(409,ErrorCode.INVALID_STATE);
   long user=((Number)row.get("user_id")).longValue();if(request.containsKey("directionId"))members.ensureDirection(MemberService.parseId(MemberService.required(request,"directionId",19)));
   if(request.containsKey("managedDirectionIds"))grant(user,scopes(request),actor);
   if(request.containsKey("accountStatus")){String state=MemberService.required(request,"accountStatus",16);if(!Set.of("ENABLED","DISABLED").contains(state))throw MemberService.bad();jdbc.update("UPDATE app_user SET status=? WHERE id=?",state,user);}
   members.patch(id,request);if(request.containsKey("managedDirectionIds")||request.containsKey("accountStatus")){jdbc.update("UPDATE app_user SET auth_revision=auth_revision+1 WHERE id=?",user);members.revoke(user,"LEADER_CHANGED");}
   members.audit(actor,"LEADER_UPDATED",id,reason,"Updated direction leader");return members.map(members.row(id));});
 }
 public Map<String,Object> revoke(long id,Map<String,Object> request,SessionPrincipal actor){
  MemberService.fields(request,Set.of("version","reason"));String reason=MemberService.required(request,"reason",1000);var row=members.row(id);if(!"DIRECTION_LEADER".equals(row.get("role")))throw new ApiException(409,ErrorCode.INVALID_STATE);
  long user=((Number)row.get("user_id")).longValue();jdbc.update("DELETE FROM leader_direction_scope WHERE user_id=?",user);jdbc.update("UPDATE app_user SET role='STUDENT',auth_revision=auth_revision+1 WHERE id=?",user);
  jdbc.update("UPDATE member SET version=version+1,updated_at=? WHERE id=?",Timestamp.from(clock.instant()),id);members.revoke(user,"LEADER_REVOKED");members.audit(actor,"LEADER_REVOKED",id,reason,"Revoked direction leader");return members.map(members.row(id));
 }
 private List<Long> scopes(Map<String,Object> request){Object raw=request.get("managedDirectionIds");if(!(raw instanceof List<?> list)||list.isEmpty())throw MemberService.bad();var ids=new ArrayList<Long>();for(Object id:list){if(!(id instanceof String))throw MemberService.bad();long d=MemberService.parseId((String)id);members.ensureDirection(d);if(ids.contains(d))throw MemberService.bad();ids.add(d);}return ids;}
 private void grant(long user,List<Long> ids,SessionPrincipal actor){jdbc.update("DELETE FROM leader_direction_scope WHERE user_id=?",user);for(Long id:ids)jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at,granted_by) VALUES (?,?,?,?)",user,id,Timestamp.from(clock.instant()),actor.userId());}
}
