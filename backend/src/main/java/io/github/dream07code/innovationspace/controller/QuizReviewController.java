package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.service.QuizReviewService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 填空人工阅卷端点：按题横向列表、批量评分、撤销与进度。 */
@RestController
@RequestMapping("/api/v1/assessments")
public class QuizReviewController {
  private final QuizReviewService service;

  public QuizReviewController(QuizReviewService service) {
    this.service = service;
  }

  @GetMapping("/{assessmentId}/quiz-review")
  public ApiResponse<Map<String, Object>> review(
      @PathVariable long assessmentId,
      @RequestParam long questionId,
      @RequestParam(defaultValue = "all") String filter,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int size) {
    return ApiResponse.success(
        service.review(assessmentId, questionId, filter, page, size, actor()));
  }

  @GetMapping("/{assessmentId}/quiz-review/summary")
  public ApiResponse<Map<String, Object>> summary(@PathVariable long assessmentId) {
    return ApiResponse.success(service.summary(assessmentId, actor()));
  }

  @PostMapping("/{assessmentId}/quiz-review/scores")
  public ResponseEntity<String> grade(
      @PathVariable long assessmentId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(service.grade(assessmentId, request, actor(), key));
  }

  @PostMapping("/{assessmentId}/quiz-review/scores/{sheetId}/{position}/revoke")
  public ResponseEntity<String> revoke(
      @PathVariable long assessmentId,
      @PathVariable long sheetId,
      @PathVariable int position,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(service.revoke(assessmentId, sheetId, position, request, actor(), key));
  }

  private static io.github.dream07code.innovationspace.security.SessionPrincipal actor() {
    return MemberController.actor();
  }

  private static ResponseEntity<String> stored(IdempotencyService.StoredResponse result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.body());
  }
}
