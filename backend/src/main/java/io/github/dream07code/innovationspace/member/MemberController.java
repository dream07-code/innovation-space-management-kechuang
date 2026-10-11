package io.github.dream07code.innovationspace.member;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {
  private final MemberService members;

  public MemberController(MemberService members) {
    this.members = members;
  }

  public static SessionPrincipal actor() {
    return (SessionPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String directionId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status) {
    return ApiResponse.success(members.list(actor(), page, size, directionId, keyword, status));
  }

  @GetMapping("/{memberId}")
  public ApiResponse<Map<String, Object>> get(@PathVariable long memberId) {
    return ApiResponse.success(members.get(memberId, actor()));
  }

  @PostMapping
  public ResponseEntity<String> create(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    var result = members.create(request, actor(), key);
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.body());
  }

  @PatchMapping("/{memberId}")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable long memberId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(members.update(memberId, request, actor()));
  }

  @PostMapping("/{memberId}/account-corrections")
  public ResponseEntity<String> correct(
      @PathVariable long memberId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    var result =
        members.adminAction(
            memberId,
            request,
            actor(),
            key,
            "/api/v1/members/" + memberId + "/account-corrections",
            () -> members.correct(memberId, request, actor()));
    return stored(result);
  }

  @PostMapping("/{memberId}/points")
  public ApiResponse<Map<String, Object>> addPoints(
      @PathVariable long memberId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(members.addPoints(memberId, request, actor()));
  }

  @PostMapping("/{memberId}/restore")
  public ResponseEntity<String> restore(
      @PathVariable long memberId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    var result =
        members.adminAction(
            memberId,
            request,
            actor(),
            key,
            "/api/v1/members/" + memberId + "/restore",
            () -> members.restore(memberId, request, actor()));
    return stored(result);
  }

  @PostMapping("/{memberId}/password-reset")
  public ResponseEntity<String> reset(
      @PathVariable long memberId,
      @RequestBody Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    var result =
        members.adminAction(
            memberId,
            request,
            actor(),
            key,
            "/api/v1/members/" + memberId + "/password-reset",
            () -> members.reset(memberId, request, actor()));
    return stored(result);
  }

  public static ResponseEntity<String> stored(IdempotencyService.StoredResponse result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.body());
  }
}
