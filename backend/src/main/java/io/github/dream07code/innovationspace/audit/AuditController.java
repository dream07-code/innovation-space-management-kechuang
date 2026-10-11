package io.github.dream07code.innovationspace.audit;
import io.github.dream07code.innovationspace.member.*;
import io.github.dream07code.innovationspace.common.api.ApiResponse;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {
 private final JdbcTemplate jdbc;public AuditController(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @GetMapping public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String resourceId,@RequestParam(required=false)String action){
  MemberScopePolicy.admin(MemberController.actor());if(page<1||size<1||size>200)throw MemberService.bad();var args=new ArrayList<Object>();String where=" WHERE 1=1";
  if(resourceId!=null){where+=" AND resource_id=?";args.add(MemberService.parseId(resourceId));}if(action!=null){where+=" AND action=?";args.add(action);}
  long total=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log"+where,Long.class,args.toArray());args.add(size);args.add((long)(page-1)*size);
  var rows=jdbc.queryForList("SELECT id,actor_user_id,action,resource_type,resource_id,reason,occurred_at,summary FROM audit_log"+where+" ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray());
  var items=new ArrayList<Map<String,Object>>();for(var r:rows){var m=new LinkedHashMap<String,Object>();m.put("id",r.get("id").toString());m.put("actorUserId",r.get("actor_user_id")==null?null:r.get("actor_user_id").toString());m.put("action",r.get("action"));m.put("resourceType",r.get("resource_type"));m.put("resourceId",r.get("resource_id")==null?null:r.get("resource_id").toString());m.put("reason",r.get("reason"));m.put("summary",r.get("summary"));
   Object stamp=r.get("occurred_at");java.time.Instant instant=stamp instanceof java.sql.Timestamp t?t.toInstant():((java.time.LocalDateTime)stamp).toInstant(java.time.ZoneOffset.UTC);m.put("occurredAt",instant.atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString());items.add(m);}
  return ApiResponse.success(Map.of("items",items,"page",page,"size",size,"total",total));
 }
}
