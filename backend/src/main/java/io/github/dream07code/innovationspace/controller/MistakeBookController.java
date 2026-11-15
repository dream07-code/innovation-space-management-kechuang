package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.service.MistakeBookService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 错题本（仅学生本人）。
 *
 * <p>列表给判题结论与来源，错因和上次代码按题目单独取：错因要调模型（几秒到十几秒），
 * 放进列表会拖慢整个页面，因此由前端逐行按需拉取。
 */
@RestController
@RequestMapping("/api/v1/mistake-book")
public class MistakeBookController {
  private final MistakeBookService service;

  public MistakeBookController(MistakeBookService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list() {
    return ApiResponse.success(service.list(MemberController.actor()));
  }

  @GetMapping("/{problemId}/cause")
  public ApiResponse<Map<String, Object>> cause(@PathVariable long problemId) {
    return ApiResponse.success(service.cause(problemId, MemberController.actor()));
  }

  @GetMapping("/{problemId}/last-code")
  public ApiResponse<Map<String, Object>> lastCode(@PathVariable long problemId) {
    return ApiResponse.success(service.lastCode(problemId, MemberController.actor()));
  }
}
