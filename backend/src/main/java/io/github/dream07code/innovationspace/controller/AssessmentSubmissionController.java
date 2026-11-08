package io.github.dream07code.innovationspace.controller;
import io.github.dream07code.innovationspace.service.AssessmentSubmissionService;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.service.AssessmentSubmissionService;
import io.github.dream07code.innovationspace.service.SubmissionReportService;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AssessmentSubmissionController {
  private final AssessmentSubmissionService service;
  private final SubmissionReportService reports;

  public AssessmentSubmissionController(
      AssessmentSubmissionService service, SubmissionReportService reports) {
    this.service = service;
    this.reports = reports;
  }

  @PostMapping("/assessments/{assessmentId}/submissions")
  public ResponseEntity<String> create(
      @PathVariable long assessmentId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    var result = service.create(assessmentId, request, MemberController.actor(), key);
    return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
  }

  @GetMapping("/assessment-submissions")
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String assessmentId,
      @RequestParam(required = false) String problemId,
      @RequestParam(required = false) String memberId,
      @RequestParam(required = false) String status) {
    return ApiResponse.success(
        service.list(MemberController.actor(), page, size, assessmentId, problemId, memberId, status));
  }

  @GetMapping("/assessment-submissions/{submissionId}")
  public ApiResponse<Map<String, Object>> get(@PathVariable long submissionId) {
    return ApiResponse.success(service.get(submissionId, MemberController.actor()));
  }

  @GetMapping("/assessment-submissions/{submissionId}/code")
  public ApiResponse<Map<String, Object>> code(@PathVariable long submissionId) {
    return ApiResponse.success(service.code(submissionId, MemberController.actor()));
  }

  /** AI 批改报告：判题结果 + 错因解析 + 改进建议 + 推荐下一题，只读，不写成绩。 */
  @GetMapping("/assessment-submissions/{submissionId}/report")
  public ApiResponse<Map<String, Object>> report(@PathVariable long submissionId) {
    return ApiResponse.success(reports.report(submissionId, MemberController.actor()));
  }
}
