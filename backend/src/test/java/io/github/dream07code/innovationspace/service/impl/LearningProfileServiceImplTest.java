package io.github.dream07code.innovationspace.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.tutor.AttemptedProblem;
import io.github.dream07code.innovationspace.tutor.CandidateProblem;
import io.github.dream07code.innovationspace.tutor.LearningProfile;
import io.github.dream07code.innovationspace.tutor.TutorRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 画像服务的读写语义。
 *
 * <p>两条底线：读画像必须顺手刷新快照（否则页面上的数字会和判题结果脱节）；
 * 没有做题记录时必须返回空状态并清掉旧快照，不能给一份 0% 的假画像。
 */
class LearningProfileServiceImplTest {
  private static final long MEMBER_ID = 101L;
  private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
  private static final SessionPrincipal ACTOR =
      new SessionPrincipal(1L, MEMBER_ID, "STUDENT", List.of(), false, 1L, 1L);

  private TutorRepository repository;
  private LearningProfileServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(TutorRepository.class);
    service =
        new LearningProfileServiceImpl(repository, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void emptyStateDeletesStaleSnapshotAndReturnsNoGuess() {
    when(repository.attemptedProblems(MEMBER_ID)).thenReturn(List.of());

    var body = service.profile(ACTOR);

    assertEquals(false, body.get("hasData"));
    assertNull(body.get("snapshotAt"));
    assertEquals(List.of(), body.get("weakPoints"));
    assertEquals(List.of(), body.get("recommendations"));
    @SuppressWarnings("unchecked")
    var overview = (Map<String, Object>) body.get("overview");
    assertEquals(0, overview.get("attemptedProblems"));
    assertNull(overview.get("averageScore"));
    verify(repository).deleteProfile(MEMBER_ID);
    verify(repository, never()).saveProfile(org.mockito.ArgumentMatchers.any());
    verify(repository, never()).publishedProblems(anyInt());
  }

  @Test
  void aggregatesAndRefreshesSnapshot() {
    when(repository.attemptedProblems(MEMBER_ID))
        .thenReturn(
            List.of(
                new AttemptedProblem(
                    1L, "两数之和", "EASY", List.of("数组"), 2, true, NOW.minusSeconds(600)),
                new AttemptedProblem(
                    2L, "滑动窗口", "MEDIUM", List.of("数组", "滑动窗口"), 1, false, NOW.minusSeconds(30))));
    when(repository.averagePublishedScore(MEMBER_ID)).thenReturn(82);
    when(repository.publishedProblems(500))
        .thenReturn(
            List.of(
                new CandidateProblem(1L, "两数之和", "EASY", List.of("数组")),
                new CandidateProblem(4L, "数组去重", "EASY", List.of("数组"))));

    var body = service.profile(ACTOR);

    assertEquals(true, body.get("hasData"));
    assertEquals("2026-11-09T20:00:00+08:00", body.get("snapshotAt"));
    @SuppressWarnings("unchecked")
    var overview = (Map<String, Object>) body.get("overview");
    assertEquals(2, overview.get("attemptedProblems"));
    assertEquals(1, overview.get("passedProblems"));
    assertEquals(50, overview.get("passRate"));
    assertEquals(3, overview.get("submissionCount"));
    assertEquals(82, overview.get("averageScore"));
    assertEquals("2026-11-09T19:59:30+08:00", overview.get("lastSubmittedAt"));

    @SuppressWarnings("unchecked")
    var weakPoints = (List<Map<String, Object>>) body.get("weakPoints");
    assertEquals(2, weakPoints.size());
    assertEquals("滑动窗口", weakPoints.getFirst().get("tag"));
    assertEquals("数组", weakPoints.get(1).get("tag"));
    assertEquals(1, weakPoints.get(1).get("passed"));
    assertEquals(2, weakPoints.get(1).get("attempted"));

    @SuppressWarnings("unchecked")
    var recommendations = (List<Map<String, Object>>) body.get("recommendations");
    assertEquals(1, recommendations.size());
    assertEquals("4", recommendations.getFirst().get("problemId"));
    assertTrue(String.valueOf(recommendations.getFirst().get("reason")).startsWith("「数组」"));

    var captor = ArgumentCaptor.forClass(LearningProfile.class);
    verify(repository).saveProfile(captor.capture());
    var saved = captor.getValue();
    assertEquals(2, saved.attemptedProblems());
    assertEquals(50, saved.passRate());
    assertEquals(NOW, saved.snapshotAt());
    verify(repository, never()).deleteProfile(anyLong());
  }

  @Test
  void requiresMemberProfile() {
    var admin = new SessionPrincipal(2L, null, "COLLEGE_ADMIN", List.of(), false, 1L, 2L);

    var error = assertThrows(ApiException.class, () -> service.profile(admin));

    assertEquals(403, error.status());
    verify(repository, never()).attemptedProblems(anyLong());
  }
}
