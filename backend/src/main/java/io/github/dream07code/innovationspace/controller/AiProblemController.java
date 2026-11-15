package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.service.AiProblemGenerationService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 出题：学生生成自己的练习题。
 *
 * <p>同步接口：生成 + 自测都在请求内完成，耗时通常十几秒到一分钟。刻意不落幂等表——
 * 幂等表的事务会锁住用户行直到响应返回，把一次模型调用塞进长事务不划算；
 * 重复提交的风险由学生端的按钮禁用与每人 10 分钟 6 道的限流兜住。
 */
@RestController
@RequestMapping("/api/v1")
public class AiProblemController {
  private final AiProblemGenerationService service;

  public AiProblemController(AiProblemGenerationService service) {
    this.service = service;
  }

  @PostMapping("/ai-generate-problem")
  public ApiResponse<Map<String, Object>> generate(@RequestBody Map<String, Object> request) {
    return ApiResponse.success(service.generate(request, MemberController.actor()));
  }
}
