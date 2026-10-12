package io.github.dream07code.innovationspace.assessment;

import io.github.dream07code.innovationspace.assessment.service.AssessmentResultService;
import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.member.MemberController;
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
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.list(assessmentId, MemberController.actor(), page, size));
  }
}
