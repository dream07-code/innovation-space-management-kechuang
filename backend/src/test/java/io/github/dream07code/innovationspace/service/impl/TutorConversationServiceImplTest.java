package io.github.dream07code.innovationspace.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import io.github.dream07code.innovationspace.tutor.TutorConversation;
import io.github.dream07code.innovationspace.tutor.TutorConversationSummary;
import io.github.dream07code.innovationspace.tutor.TutorMessage;
import io.github.dream07code.innovationspace.tutor.TutorRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 会话管理语义。
 *
 * <p>最关键的一条：会话只属于本人。别人的会话 id 必须和「不存在」返回同样的 404，
 * 否则学生可以用 id 探测同学开了什么话题。
 */
class TutorConversationServiceImplTest {
  private static final long MEMBER_ID = 101L;
  private static final long OTHER_MEMBER_ID = 202L;
  private static final long CONVERSATION_ID = 9001L;
  private static final long PROBLEM_ID = 6001L;
  private static final SessionPrincipal ACTOR =
      new SessionPrincipal(1L, MEMBER_ID, "STUDENT", List.of(), false, 1L, 1L);
  private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

  private TutorRepository repository;
  private AssessmentProblemService problems;
  private IdempotencyService idem;
  private TutorConversationServiceImpl service;
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void setUp() {
    repository = mock(TutorRepository.class);
    problems = mock(AssessmentProblemService.class);
    idem = mock(IdempotencyService.class);
    when(problems.get(anyLong(), any())).thenReturn(problem());
    when(repository.createConversation(anyLong(), anyString(), any(), any()))
        .thenReturn(CONVERSATION_ID);
    when(repository.findConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(summary(CONVERSATION_ID, MEMBER_ID)));
    when(idem.execute(any(), anyString(), anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());

    service =
        new TutorConversationServiceImpl(
            repository,
            problems,
            idem,
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(PlatformTransactionManager.class));
  }

  @Test
  void createUsesProblemTitleWhenTitleIsMissing() {
    var result = service.create(Map.of("problemId", String.valueOf(PROBLEM_ID)), ACTOR, key());

    assertEquals(201, result.status());
    verify(repository).createConversation(eq(MEMBER_ID), eq("两数之和"), eq(PROBLEM_ID), any());
    assertEquals(String.valueOf(CONVERSATION_ID), payload(result.body()).get("id"));
    assertEquals("两数之和", payload(result.body()).get("problemTitle"));
    assertEquals(0, payload(result.body()).get("messageCount"));
  }

  @Test
  void createKeepsExplicitTitleAndAllowsNoProblem() {
    var request = new LinkedHashMap<String, Object>();
    request.put("title", "  动态规划入门  ");

    service.create(request, ACTOR, key());

    verify(repository).createConversation(eq(MEMBER_ID), eq("动态规划入门"), eq(null), any());
  }

  @Test
  void createRejectsProblemTheStudentCannotSee() {
    when(problems.get(anyLong(), any())).thenThrow(new ApiException(404, ErrorCode.NOT_FOUND));

    var error =
        assertThrows(
            ApiException.class,
            () ->
                service.create(
                    Map.of("problemId", String.valueOf(PROBLEM_ID)), ACTOR, key()));

    assertEquals(404, error.status());
    verify(repository, never()).createConversation(anyLong(), anyString(), any(), any());
  }

  @Test
  void createRequiresMemberProfile() {
    var admin = new SessionPrincipal(2L, null, "COLLEGE_ADMIN", List.of(), false, 1L, 2L);

    var error = assertThrows(ApiException.class, () -> service.create(Map.of(), admin, key()));

    assertEquals(403, error.status());
  }

  @Test
  void listReturnsOwnConversationsWithTotal() {
    when(repository.listConversations(MEMBER_ID, 20, 0))
        .thenReturn(
            List.of(
                summary(CONVERSATION_ID, MEMBER_ID),
                summary(9002L, MEMBER_ID)));
    when(repository.countConversations(MEMBER_ID)).thenReturn(5L);

    var body = service.list(ACTOR, 1, 20);

    assertEquals(2, ((List<?>) body.get("items")).size());
    assertEquals(5L, body.get("total"));
    assertEquals(1, body.get("page"));
    assertEquals(20, body.get("size"));
  }

  @Test
  void listCapsPageSize() {
    service.list(ACTOR, 0, 500);

    verify(repository).listConversations(MEMBER_ID, 50, 0);
  }

  @Test
  void messagesMarkTruncationWhenOlderMessagesExist() {
    when(repository.countMessages(CONVERSATION_ID)).thenReturn(250L);
    when(repository.recentMessages(CONVERSATION_ID, 200))
        .thenReturn(List.of(message(1, "user", "第一问"), message(2, "assistant", "第一答")));

    var body = service.messages(CONVERSATION_ID, ACTOR);

    assertEquals(true, body.get("truncated"));
    assertEquals(250L, body.get("total"));
    assertEquals(2, ((List<?>) body.get("items")).size());
    @SuppressWarnings("unchecked")
    var conversation = (Map<String, Object>) body.get("conversation");
    assertEquals(String.valueOf(CONVERSATION_ID), conversation.get("id"));
  }

  @Test
  void messagesOfAnotherMemberAreNotFound() {
    when(repository.findConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(summary(CONVERSATION_ID, OTHER_MEMBER_ID)));

    var error =
        assertThrows(ApiException.class, () -> service.messages(CONVERSATION_ID, ACTOR));

    assertEquals(404, error.status());
    verify(repository, never()).recentMessages(anyLong(), anyInt());
  }

  @Test
  void renameRejectsBlankTitle() {
    var error =
        assertThrows(
            ApiException.class,
            () -> service.rename(CONVERSATION_ID, Map.of("title", "   "), ACTOR));

    assertEquals(400, error.status());
    verify(repository, never()).renameConversation(anyLong(), anyString(), any());
  }

  @Test
  void renameWritesNewTitle() {
    when(repository.findConversation(CONVERSATION_ID))
        .thenReturn(
            Optional.of(
                new TutorConversationSummary(
                    new TutorConversation(
                        CONVERSATION_ID, MEMBER_ID, "新标题", null, NOW, NOW),
                    null,
                    3)));

    var body = service.rename(CONVERSATION_ID, Map.of("title", "新标题"), ACTOR);

    verify(repository).renameConversation(eq(CONVERSATION_ID), eq("新标题"), any());
    assertEquals("新标题", body.get("title"));
  }

  @Test
  void deleteOtherMembersConversationIsNotFound() {
    when(repository.findConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(summary(CONVERSATION_ID, OTHER_MEMBER_ID)));

    var error =
        assertThrows(ApiException.class, () -> service.delete(CONVERSATION_ID, ACTOR, key()));

    assertEquals(404, error.status());
    verify(repository, never()).deleteConversation(anyLong());
  }

  @Test
  void deleteOwnConversationReportsDeletion() {
    var result = service.delete(CONVERSATION_ID, ACTOR, key());

    verify(repository).deleteConversation(CONVERSATION_ID);
    assertEquals(200, result.status());
    assertEquals(true, payload(result.body()).get("deleted"));
    assertEquals(String.valueOf(CONVERSATION_ID), payload(result.body()).get("conversationId"));
  }

  @Test
  void rejectsInvalidProblemId() {
    var error =
        assertThrows(
            ApiException.class, () -> service.create(Map.of("problemId", "abc"), ACTOR, key()));

    assertEquals(400, error.status());
    verify(problems, never()).get(anyLong(), any());
  }

  private Map<?, ?> payload(String body) {
    var root = (Map<?, ?>) json.readValue(body, Map.class);
    return (Map<?, ?>) root.get("data");
  }

  private static String key() {
    return "6f1a7c1e-2b1a-4c3d-9f11-0a2b3c4d5e6f";
  }

  private static TutorConversationSummary summary(long id, long memberId) {
    return new TutorConversationSummary(
        new TutorConversation(id, memberId, "标题", null, NOW, NOW), "两数之和", 2);
  }

  private static TutorMessage message(long id, String role, String content) {
    return new TutorMessage(id, role, content, NOW);
  }

  private static Map<String, Object> problem() {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", String.valueOf(PROBLEM_ID));
    row.put("title", "两数之和");
    return row;
  }
}
