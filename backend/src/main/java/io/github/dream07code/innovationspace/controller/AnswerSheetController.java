package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.service.AnswerSheetService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 试卷与答题卡端点：学生作答、交卷判分、逐生明细下钻。 */
@RestController
@RequestMapping("/api/v1/assessments")
public class AnswerSheetController {
  private final AnswerSheetService service;

  public AnswerSheetController(AnswerSheetService service) {
    this.service = service;
  }

  @GetMapping("/{assessmentId}/paper")
  public ApiResponse<Map<String, Object>> paper(@PathVariable long assessmentId) {
    return ApiResponse.success(service.paper(assessmentId, actor()));
  }

  @PutMapping("/{assessmentId}/answer-sheet")
  public ApiResponse<Map<String, Object>> saveDraft(
      @PathVariable long assessmentId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(service.saveDraft(assessmentId, request, actor()));
  }

  @PostMapping("/{assessmentId}/answer-sheet/submit")
  public ResponseEntity<String> submit(
      @PathVariable long assessmentId, @RequestHeader("Idempotency-Key") String key) {
    return stored(service.submit(assessmentId, actor(), key));
  }

  @GetMapping("/{assessmentId}/my-answer-sheet")
  public ApiResponse<Map<String, Object>> mySheet(@PathVariable long assessmentId) {
    return ApiResponse.success(service.mySheet(assessmentId, actor()));
  }

  @GetMapping("/{assessmentId}/answer-sheets")
  public ApiResponse<Map<String, Object>> sheets(
      @PathVariable long assessmentId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Long memberId) {
    return ApiResponse.success(service.sheets(assessmentId, actor(), page, size, memberId));
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
