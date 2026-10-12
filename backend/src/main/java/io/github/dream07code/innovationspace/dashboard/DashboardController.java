package io.github.dream07code.innovationspace.dashboard;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.member.MemberController;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
  private final DashboardAdminService admins;

  public DashboardController(DashboardAdminService admins) {
    this.admins = admins;
  }

  @GetMapping("/admin")
  public ApiResponse<Map<String, Object>> adminDashboard(
      @RequestParam(required = false) String range) {
    return ApiResponse.success(admins.summary(MemberController.actor(), range));
  }
}
