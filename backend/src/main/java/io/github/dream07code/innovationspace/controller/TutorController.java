package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.service.TutorChatService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用 AI 助教：学生不做题也能问知识点。
 *
 * <p>与按提交追问的 {@code /assessment-submissions/{id}/tutor} 是两条独立入口：
 * 这里不绑定提交，上下文只有学生当前选中的题目（可空），历史由前端保存，不落库。
 */
@RestController
@RequestMapping("/api/v1/tutor")
public class TutorController {
  private final TutorChatService service;

  public TutorController(TutorChatService service) {
    this.service = service;
  }

  @PostMapping("/chat")
  public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
    return ApiResponse.success(service.chat(request, MemberController.actor()));
  }
}
