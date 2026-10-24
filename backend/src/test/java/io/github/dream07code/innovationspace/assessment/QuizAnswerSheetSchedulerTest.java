package io.github.dream07code.innovationspace.assessment;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dream07code.innovationspace.assessment.service.QuizAnswerSheetScheduler;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AnswerSheetService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 调度器只做委托：sweep 一次调用 submitExpiredDrafts，异常不外抛（保住轮询）。 */
class QuizAnswerSheetSchedulerTest {
  private static final SessionPrincipal NO_ACTOR = null;

  private static final class FakeService implements AnswerSheetService {
    int calls;
    int result;
    boolean blowUp;

    @Override
    public Map<String, Object> paper(long assessmentId, SessionPrincipal actor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> saveDraft(
        long assessmentId, Map<String, Object> request, SessionPrincipal actor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IdempotencyService.StoredResponse submit(
        long assessmentId, SessionPrincipal actor, String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> mySheet(long assessmentId, SessionPrincipal actor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> sheets(
        long assessmentId, SessionPrincipal actor, int page, int size, Long memberId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int submitExpiredDrafts() {
      calls++;
      if (blowUp) throw new RuntimeException("db down");
      return result;
    }

    @Override
    public int submitAllDrafts(long assessmentId) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void sweepDelegatesToSubmitExpiredDraftsOnce() {
    var service = new FakeService();
    service.result = 3;
    var scheduler = new QuizAnswerSheetScheduler(service);

    scheduler.sweep();

    assertEquals(1, service.calls);
  }

  @Test
  void sweepSwallowsServiceFailureToKeepPolling() {
    var service = new FakeService();
    service.blowUp = true;
    var scheduler = new QuizAnswerSheetScheduler(service);

    assertDoesNotThrow(scheduler::sweep);
    assertEquals(1, service.calls);
  }
}
