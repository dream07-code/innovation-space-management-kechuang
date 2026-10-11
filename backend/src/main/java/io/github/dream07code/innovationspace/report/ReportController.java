package io.github.dream07code.innovationspace.report;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.member.MemberController;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportController {
  private final ReportService service;

  public ReportController(ReportService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/daily-reports")
  public ApiResponse<Map<String, Object>> dailyList(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Long memberId,
      @RequestParam(required = false) Long directionId,
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String scoreStatus) {
    return ApiResponse.success(
        service.list(
            ReportType.DAILY, actor(), page, size, memberId, directionId, fromDate, toDate, status, scoreStatus));
  }

  @PostMapping("/api/v1/daily-reports")
  public ResponseEntity<String> daily(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    return stored(service.create(request, actor(), key, ReportType.DAILY));
  }

  @GetMapping("/api/v1/weekly-reports")
  public ApiResponse<Map<String, Object>> weeklyList(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Long memberId,
      @RequestParam(required = false) Long directionId,
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String scoreStatus) {
    return ApiResponse.success(
        service.list(
            ReportType.WEEKLY, actor(), page, size, memberId, directionId, fromDate, toDate, status, scoreStatus));
  }

  @PostMapping("/api/v1/weekly-reports")
  public ResponseEntity<String> weekly(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    return stored(service.create(request, actor(), key, ReportType.WEEKLY));
  }

  @GetMapping("/api/v1/daily-reports/{reportId}")
  public ApiResponse<Map<String, Object>> dailyGet(@PathVariable("reportId") long reportId) {
    return ApiResponse.success(service.get(reportId, actor()));
  }

  @PatchMapping("/api/v1/daily-reports/{reportId}")
  public ApiResponse<Map<String, Object>> dailyPatch(
      @PathVariable("reportId") long reportId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(service.update(reportId, request, actor(), ReportType.DAILY));
  }

  @GetMapping("/api/v1/weekly-reports/{reportId}")
  public ApiResponse<Map<String, Object>> weeklyGet(@PathVariable("reportId") long reportId) {
    return ApiResponse.success(service.get(reportId, actor()));
  }

  @PatchMapping("/api/v1/weekly-reports/{reportId}")
  public ApiResponse<Map<String, Object>> weeklyPatch(
      @PathVariable("reportId") long reportId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(service.update(reportId, request, actor(), ReportType.WEEKLY));
  }

  @PostMapping("/api/v1/daily-reports/{reportId}/submit")
  public ResponseEntity<String> dailySubmit(
      @PathVariable("reportId") long reportId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(service.submit(reportId, version(request), actor(), key));
  }

  @PostMapping("/api/v1/weekly-reports/{reportId}/submit")
  public ResponseEntity<String> weeklySubmit(
      @PathVariable("reportId") long reportId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(service.submit(reportId, version(request), actor(), key));
  }

  private static long version(Map<String, Object> request) {
    Object value = request.get("version");
    if (!(value instanceof Number number) || number.longValue() < 1) throw ReportService.bad();
    return number.longValue();
  }

  private static SessionPrincipal actor() {
    return MemberController.actor();
  }

  private static ResponseEntity<String> stored(IdempotencyService.StoredResponse result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.body());
  }
}
