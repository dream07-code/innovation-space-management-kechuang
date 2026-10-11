package io.github.dream07code.innovationspace.report;
import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.member.MemberController;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class ReportController {
 private final ReportService service;public ReportController(ReportService s){service=s;}
 @PostMapping("/api/v1/daily-reports")public org.springframework.http.ResponseEntity<String> daily(@RequestBody Map<String,Object> r,@RequestHeader("Idempotency-Key")String k){return stored(service.create(r,actor(),k,ReportType.DAILY));}
 @PostMapping("/api/v1/weekly-reports")public org.springframework.http.ResponseEntity<String> weekly(@RequestBody Map<String,Object> r,@RequestHeader("Idempotency-Key")String k){return stored(service.create(r,actor(),k,ReportType.WEEKLY));}
 @GetMapping("/api/v1/daily-reports/{id}")public ApiResponse<Map<String,Object>> dailyGet(@PathVariable long id){return ApiResponse.success(service.get(id,actor()));}
 @GetMapping("/api/v1/weekly-reports/{id}")public ApiResponse<Map<String,Object>> weeklyGet(@PathVariable long id){return ApiResponse.success(service.get(id,actor()));}
 @PostMapping("/api/v1/daily-reports/{id}/submit")public ApiResponse<Map<String,Object>> dailySubmit(@PathVariable long id,@RequestBody Map<String,Object> r){return ApiResponse.success(service.submit(id,version(r),actor()));}
 @PostMapping("/api/v1/weekly-reports/{id}/submit")public ApiResponse<Map<String,Object>> weeklySubmit(@PathVariable long id,@RequestBody Map<String,Object> r){return ApiResponse.success(service.submit(id,version(r),actor()));}
 private static long version(Map<String,Object> r){Object v=r.get("version");if(!(v instanceof Number n)||n.longValue()<1)throw ReportService.bad();return n.longValue();}
 private static SessionPrincipal actor(){return MemberController.actor();}
 private static org.springframework.http.ResponseEntity<String> stored(IdempotencyService.StoredResponse r){return org.springframework.http.ResponseEntity.status(r.status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(r.body());}
}
