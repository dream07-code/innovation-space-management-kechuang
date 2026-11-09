package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.service.LearningProfileService;
import io.github.dream07code.innovationspace.service.TutorChatService;
import io.github.dream07code.innovationspace.service.TutorConversationService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 助教：对话、会话管理与学习画像。
 *
 * <p>与按提交追问的 {@code /assessment-submissions/{id}/tutor} 是两条独立入口：
 * 这里不绑定提交，上下文只有会话关联的题目（可空），历史存在 tutor_message 里。
 */
@RestController
@RequestMapping("/api/v1/tutor")
public class TutorController {
  private final TutorChatService chat;
  private final TutorConversationService conversations;
  private final LearningProfileService profiles;

  public TutorController(
      TutorChatService chat,
      TutorConversationService conversations,
      LearningProfileService profiles) {
    this.chat = chat;
    this.conversations = conversations;
    this.profiles = profiles;
  }

  @PostMapping("/chat")
  public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
    return ApiResponse.success(chat.chat(request, MemberController.actor()));
  }

  @GetMapping("/conversations")
  public ApiResponse<Map<String, Object>> conversations(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(conversations.list(MemberController.actor(), page, size));
  }

  @PostMapping("/conversations")
  public ResponseEntity<String> createConversation(
      @RequestBody(required = false) Map<String, Object> request,
      @RequestHeader("Idempotency-Key") String key) {
    return stored(conversations.create(request, MemberController.actor(), key));
  }

  @GetMapping("/conversations/{conversationId}/messages")
  public ApiResponse<Map<String, Object>> messages(@PathVariable long conversationId) {
    return ApiResponse.success(conversations.messages(conversationId, MemberController.actor()));
  }

  @PatchMapping("/conversations/{conversationId}")
  public ApiResponse<Map<String, Object>> rename(
      @PathVariable long conversationId, @RequestBody Map<String, Object> request) {
    return ApiResponse.success(
        conversations.rename(conversationId, request, MemberController.actor()));
  }

  @PostMapping("/conversations/{conversationId}/delete")
  public ResponseEntity<String> deleteConversation(
      @PathVariable long conversationId, @RequestHeader("Idempotency-Key") String key) {
    return stored(conversations.delete(conversationId, MemberController.actor(), key));
  }

  @GetMapping("/profile")
  public ApiResponse<Map<String, Object>> profile() {
    return ApiResponse.success(profiles.profile(MemberController.actor()));
  }

  private static ResponseEntity<String> stored(IdempotencyService.StoredResponse result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.body());
  }
}
