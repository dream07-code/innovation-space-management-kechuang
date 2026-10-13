package io.github.dream07code.innovationspace.controller;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assessment-problems")
public class AssessmentProblemController {
  private final AssessmentProblemService service;

  public AssessmentProblemController(AssessmentProblemService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.list(actor(), page, size));
  }

  @PostMapping
  public ResponseEntity<String> create(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    return stored(service.create(request, actor(), key));
  }

  @PostMapping("/import")
  public ResponseEntity<String> importProblems(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    return stored(service.importProblems(request, actor(), key));
  }

  @GetMapping("/{problemId}")
  public ApiResponse<Map<String, Object>> get(@PathVariable long problemId) {
    return ApiResponse.success(service.get(problemId, actor()));
  }

  @GetMapping("/{problemId}/test-cases")
  public ApiResponse<Map<String, Object>> testCases(@PathVariable long problemId) {
    return ApiResponse.success(service.testCases(problemId, actor()));
  }

  @PatchMapping("/{problemId}")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable long problemId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(service.update(problemId, request, actor()));
  }

  @PostMapping("/{problemId}/retire")
  public ResponseEntity<String> retire(
      @PathVariable long problemId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(service.retire(problemId, request, actor(), key));
  }

  private static io.github.dream07code.innovationspace.security.SessionPrincipal actor() {
    return MemberController.actor();
  }

  private static ResponseEntity<String> stored(IdempotencyService.StoredResponse result) {
    return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
  }
}
