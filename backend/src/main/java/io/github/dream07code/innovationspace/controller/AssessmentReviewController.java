package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.service.AssessmentReviewService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 人工评阅接口。
 *
 * <p>OJ 考核取消 AI 评分后，这里只保留人工批阅的查询与提交；
 * 人工分覆盖按测试点计算的自动分。
 */
@RestController
@RequestMapping("/api/v1/assessment-submissions")
public class AssessmentReviewController {
  private final AssessmentReviewService reviews;

  public AssessmentReviewController(AssessmentReviewService reviews) {
    this.reviews = reviews;
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
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.body());
  }
}
