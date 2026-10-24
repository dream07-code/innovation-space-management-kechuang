package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.service.QuizQuestionService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 客观题库端点：仅负责人/管理员，学生在服务层按 404 处理。 */
@RestController
@RequestMapping("/api/v1/quiz-questions")
public class QuizQuestionController {
  private final QuizQuestionService service;

  public QuizQuestionController(QuizQuestionService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return ApiResponse.success(service.list(actor(), page, size, type, status, keyword));
  }

  @PostMapping
  public ResponseEntity<String> create(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    return stored(service.create(request, actor(), key));
  }

  @GetMapping("/{questionId}")
  public ApiResponse<Map<String, Object>> get(@PathVariable long questionId) {
    return ApiResponse.success(service.get(questionId, actor()));
  }

  @PatchMapping("/{questionId}")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable long questionId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(service.update(questionId, request, actor()));
  }

  @PostMapping("/{questionId}/retire")
  public ResponseEntity<String> retire(
      @PathVariable long questionId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(service.retire(questionId, request, actor(), key));
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
