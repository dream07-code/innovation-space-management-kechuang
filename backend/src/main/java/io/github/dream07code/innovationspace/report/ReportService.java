package io.github.dream07code.innovationspace.report;

import io.github.dream07code.innovationspace.common.api.*;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReportService {
  private final JdbcTemplate jdbc; private final IdempotencyService idem; private final ReportProperties props; private final Clock clock; private final TransactionTemplate tx; private final JsonMapper json=JsonMapper.builder().build(); private final ReportPeriodPolicy periods;
  public ReportService(JdbcTemplate jdbc,IdempotencyService idem,ReportProperties props,Clock clock,PlatformTransactionManager manager){this.jdbc=jdbc;this.idem=idem;this.props=props;this.clock=clock;this.tx=new TransactionTemplate(manager);this.periods=new ReportPeriodPolicy(clock);}
  public IdempotencyService.StoredResponse create(Map<String,Object> request,SessionPrincipal p,String key,ReportType type){
    ReportScopePolicy.requireOwner(p,p.memberId()); validateFields(request,type,true); var date=periods.dailyPeriod();var week=periods.weeklyPeriod();
    String reportDate=type==ReportType.DAILY?date.toString():null,weekStart=type==ReportType.WEEKLY?week.start().toString():null,weekEnd=type==ReportType.WEEKLY?week.end().toString():null;
    if(type==ReportType.DAILY&&request.get("reportDate")!=null&&!date.toString().equals(request.get("reportDate")))throw bad();
    if(type==ReportType.WEEKLY&&request.get("weekStart")!=null&&!week.start().toString().equals(request.get("weekStart")))throw bad();
    return idem.execute(p,"POST","/api/v1/"+(type==ReportType.DAILY?"daily-reports":"weekly-reports"),key,json.writeValueAsString(request),()->tx.execute(s->{
      String duplicate=type==ReportType.DAILY?"SELECT id FROM report WHERE member_id=? AND report_type='DAILY' AND report_date=?":"SELECT id FROM report WHERE member_id=? AND report_type='WEEKLY' AND week_start=?";
      if(!jdbc.queryForList(duplicate,p.memberId(),type==ReportType.DAILY?reportDate:weekStart).isEmpty())throw new ApiException(409,ErrorCode.REPORT_ALREADY_EXISTS);
      long direction=jdbc.queryForObject("SELECT direction_id FROM member WHERE id=?",Long.class,p.memberId());var now=Timestamp.from(clock.instant());
      jdbc.update("INSERT INTO report(member_id,direction_id,report_type,report_date,week_start,week_end,status,current_version,score_status,created_at,updated_at) VALUES (?,?,?,?,?,?, 'DRAFT',1,'NOT_REQUESTED',?,?)",p.memberId(),direction,type.name(),reportDate,weekStart,weekEnd,now,now);
      long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);var content=new LinkedHashMap<>(request);content.remove("reportDate");content.remove("weekStart");content.remove("weekEnd");insertVersion(id,1,p.userId(),content,now);return new IdempotencyService.StoredResponse(201,json.writeValueAsString(ApiResponse.success(response(id))));
    }));
  }
  public Map<String,Object> get(long id,SessionPrincipal p){var row=locked(id,false);if(!ReportScopePolicy.canAccess(p,((Number)row.get("member_id")).longValue(),((Number)row.get("direction_id")).longValue(),"DRAFT".equals(row.get("status"))))throw new ApiException(404,ErrorCode.NOT_FOUND);return response(id);}
  public Map<String,Object> submit(long id,long version,SessionPrincipal p){return tx.execute(s->{var row=locked(id,true);ReportScopePolicy.requireOwner(p,((Number)row.get("member_id")).longValue());if(((Number)row.get("current_version")).longValue()!=version)throw new ApiException(409,ErrorCode.VERSION_CONFLICT);if("SUBMITTED".equals(row.get("status")))return response(id);var content=content(id,version);ReportType type=ReportType.valueOf((String)row.get("report_type"));if(!ReportScopePolicy.validContent(type,content,true))throw bad();if(!"READY".equals(props.getPointsPolicyStatus()))throw new ApiException(503,ErrorCode.RULES_NOT_CONFIGURED);long versionId=jdbc.queryForObject("SELECT id FROM report_version WHERE report_id=? AND version=?",Long.class,id,version);var now=Timestamp.from(clock.instant());jdbc.update("UPDATE report SET status='SUBMITTED',first_submitted_at=?,score_status='QUEUED',updated_at=? WHERE id=?",now,now,id);String event="REPORT_SUBMISSION:"+id+":"+version;if(jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE event_key=?",Integer.class,event)==0)jdbc.update("INSERT INTO point_ledger(member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(?,?,?,?,'SUBMISSION_BASE',?,?,?,?)",row.get("member_id"),id,versionId,1,now,now,"v1",event);jdbc.update("INSERT INTO report_scoring_task(report_id,report_version_id,status,attempt,created_at,updated_at) VALUES(?,?, 'QUEUED',0,?,?)",id,versionId,now,now);return response(id);});}
  public Map<String,Object> response(long id){var r=locked(id,false);var out=new LinkedHashMap<String,Object>();out.put("id",Long.toString(id));out.put("memberId",r.get("member_id").toString());out.put("directionId",r.get("direction_id").toString());out.put("status",r.get("status"));out.put("version",r.get("current_version"));out.put("scoreStatus",r.get("score_status"));out.put("awardedPoints",jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=?",Integer.class,id));out.put("finalScore",r.get("final_score"));out.put("scoreReason",r.get("score_reason"));out.put("reportDate",r.get("report_date"));out.put("weekStart",r.get("week_start"));out.put("weekEnd",r.get("week_end"));out.put("editable",!"SUBMITTED".equals(r.get("status")));out.putAll(content(id,((Number)r.get("current_version")).longValue()));return out;}
  private Map<String,Object> content(long id,long version){var rows=jdbc.queryForList("SELECT content_json FROM report_version WHERE report_id=? AND version=?",id,version);if(rows.isEmpty())return Map.of();try{return json.readValue((String)rows.getFirst().get("content_json"),Map.class);}catch(Exception e){return Map.of();}}
  private void insertVersion(long id,long version,long user,Map<String,Object> content,Timestamp now){try{String text=json.writeValueAsString(content);jdbc.update("INSERT INTO report_version(report_id,version,content_json,content_hash,edited_by,created_at) VALUES(?,?,?,UNHEX(SHA2(?,256)),?,?)",id,version,text,text,user,now);}catch(Exception e){throw new IllegalStateException(e);}}
  private Map<String,Object> locked(long id,boolean lock){var q="SELECT * FROM report WHERE id=?"+(lock?" FOR UPDATE":"");var rows=jdbc.queryForList(q,id);if(rows.isEmpty())throw new ApiException(404,ErrorCode.NOT_FOUND);return rows.getFirst();}
  private void validateFields(Map<String,Object> r,ReportType type,boolean create){Set<String> allowed=type==ReportType.DAILY?Set.of("reportDate","learningContent","gains","problems","nextPlan","links"):Set.of("weekStart","summary","achievements","reflections","nextWeekPlan","links");if(!allowed.containsAll(r.keySet()))throw bad();}
  public static ApiException bad(){return new ApiException(400,ErrorCode.INVALID_REQUEST);}
}
