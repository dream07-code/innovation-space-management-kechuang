package io.github.dream07code.innovationspace.controller;
import io.github.dream07code.innovationspace.service.AssessmentEvaluationService;
import io.github.dream07code.innovationspace.service.AssessmentReviewService;

import io.github.dream07code.innovationspace.service.AssessmentEvaluationService;
import io.github.dream07code.innovationspace.service.AssessmentReviewService;
import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assessment-submissions")
public class AssessmentEvaluationController {
  private final AssessmentEvaluationService evaluations;
  private final AssessmentReviewService reviews;

  public AssessmentEvaluationController(
      AssessmentEvaluationService evaluations, AssessmentReviewService reviews) {
    this.evaluations = evaluations;
    this.reviews = reviews;
  }

  @GetMapping("/{submissionId}/ai-evaluation")
  public ApiResponse<Map<String, Object>> evaluation(@PathVariable long submissionId) {
    return ApiResponse.success(evaluations.get(submissionId, MemberController.actor()));
  }

  @GetMapping("/{submissionId}/reviews")
  public ApiResponse<Map<String, Object>> listReviews(
      @PathVariable long submissionId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(reviews.list(submissionId, MemberController.actor(), page, size));
  }

  @PostMapping("/{submissionId}/reviews")
  public ResponseEntity<String> createReview(
      @PathVariable long submissionId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    IdempotencyService.StoredResponse result =
        reviews.create(submissionId, request, MemberController.actor(), key);
    return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
  }
}
