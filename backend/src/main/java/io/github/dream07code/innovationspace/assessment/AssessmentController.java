package io.github.dream07code.innovationspace.assessment;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.member.MemberController;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assessments")
public class AssessmentController {
  private final AssessmentService service;

  public AssessmentController(AssessmentService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String directionId) {
    return ApiResponse.success(service.list(actor(), page, size, status, directionId));
  }

  @PostMapping
  public ResponseEntity<String> create(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    return stored(service.create(request, actor(), key));
  }

  @GetMapping("/{assessmentId}")
  public ApiResponse<Map<String, Object>> get(@PathVariable long assessmentId) {
    return ApiResponse.success(service.get(assessmentId, actor()));
  }

  @PatchMapping("/{assessmentId}")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable long assessmentId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(service.update(assessmentId, request, actor()));
  }

  @PostMapping("/{assessmentId}/publish")
  public ResponseEntity<String> publish(
      @PathVariable long assessmentId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(service.command(assessmentId, request, actor(), key, "publish"));
  }

  @PostMapping("/{assessmentId}/close")
  public ResponseEntity<String> close(
      @PathVariable long assessmentId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(service.command(assessmentId, request, actor(), key, "close"));
  }

  private static io.github.dream07code.innovationspace.security.SessionPrincipal actor() {
    return MemberController.actor();
  }

  private static ResponseEntity<String> stored(IdempotencyService.StoredResponse result) {
    return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
  }
}
