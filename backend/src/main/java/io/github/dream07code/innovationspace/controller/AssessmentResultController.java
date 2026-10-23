package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.service.AssessmentResultService;
import io.github.dream07code.innovationspace.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assessments")
public class AssessmentResultController {
  private final AssessmentResultService service;

  public AssessmentResultController(AssessmentResultService service) {
    this.service = service;
  }

  @GetMapping("/{assessmentId}/results")
  public ApiResponse<Map<String, Object>> list(
      @PathVariable long assessmentId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Long memberId) {
    return ApiResponse.success(
        service.list(assessmentId, MemberController.actor(), page, size, memberId));
  }

  /** 按成员汇总的成绩：每人一行，含总分与平均分。 */
  @GetMapping("/{assessmentId}/member-scores")
  public ApiResponse<Map<String, Object>> memberScores(
      @PathVariable long assessmentId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(
        service.memberScores(assessmentId, MemberController.actor(), page, size));
  }
}
