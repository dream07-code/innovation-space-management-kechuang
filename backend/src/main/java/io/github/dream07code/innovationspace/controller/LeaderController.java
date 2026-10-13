package io.github.dream07code.innovationspace.controller;
import io.github.dream07code.innovationspace.service.MemberService;
import io.github.dream07code.innovationspace.service.LeaderService;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.member.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/direction-leaders")
public class LeaderController {
  private final LeaderService leaders;
  private final MemberService members;

  public LeaderController(LeaderService leaders, MemberService members) {
    this.leaders = leaders;
    this.members = members;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String directionId) {
    return ApiResponse.success(leaders.list(MemberController.actor(), page, size, directionId));
  }

  @PostMapping
  public ResponseEntity<String> appoint(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    long id = MemberService.parseId(MemberService.required(request, "memberId", 19));
    var actor = MemberController.actor();
    return MemberController.stored(
        members.adminAction(
            id,
            request,
            actor,
            key,
            "/api/v1/direction-leaders",
            () -> leaders.appoint(id, request, actor)));
  }

  @PatchMapping("/{memberId}")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable long memberId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(leaders.update(memberId, request, MemberController.actor()));
  }

  @PostMapping("/{memberId}/revoke")
  public ResponseEntity<String> revoke(
      @PathVariable long memberId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    var actor = MemberController.actor();
    return MemberController.stored(
        members.adminAction(
            memberId,
            request,
            actor,
            key,
            "/api/v1/direction-leaders/" + memberId + "/revoke",
            () -> leaders.revoke(memberId, request, actor)));
  }
}
