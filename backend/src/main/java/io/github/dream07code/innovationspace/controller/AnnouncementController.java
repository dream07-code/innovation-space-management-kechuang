package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.service.AnnouncementService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/announcements")
public class AnnouncementController {
  private final AnnouncementService announcements;

  public AnnouncementController(AnnouncementService announcements) {
    this.announcements = announcements;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list() {
    return ApiResponse.success(announcements.list(actor()));
  }

  @PostMapping
  public ResponseEntity<String> create(
      @RequestBody Map<String, Object> request, @RequestHeader("Idempotency-Key") String key) {
    return stored(announcements.create(request, actor(), key));
  }

  @PostMapping("/{announcementId}/withdraw")
  public ResponseEntity<String> withdraw(
      @PathVariable long announcementId, @RequestHeader("Idempotency-Key") String key) {
    return stored(announcements.withdraw(announcementId, actor(), key));
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
