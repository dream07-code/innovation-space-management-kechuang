package io.github.dream07code.innovationspace.controller;
import io.github.dream07code.innovationspace.service.DashboardAdminService;
import io.github.dream07code.innovationspace.service.DashboardLeaderService;
import io.github.dream07code.innovationspace.service.DashboardStudentService;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
  private final DashboardAdminService admins;
  private final DashboardLeaderService leaders;
  private final DashboardStudentService students;

  public DashboardController(
      DashboardAdminService admins, DashboardLeaderService leaders, DashboardStudentService students) {
    this.admins = admins;
    this.leaders = leaders;
    this.students = students;
  }

  @GetMapping("/leader")
  public ApiResponse<Map<String, Object>> leaderDashboard(
      @RequestParam(required = false) String range) {
    return ApiResponse.success(leaders.summary(MemberController.actor(), range));
  }

  @GetMapping("/student")
  public ApiResponse<Map<String, Object>> studentDashboard(
      @RequestParam(required = false) String range) {
    return ApiResponse.success(students.summary(MemberController.actor(), range));
  }

  @GetMapping("/admin")
  public ApiResponse<Map<String, Object>> adminDashboard(
      @RequestParam(required = false) String range) {
    return ApiResponse.success(admins.summary(MemberController.actor(), range));
  }
}
