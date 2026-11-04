package io.github.dream07code.innovationspace.assessment;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dream07code.innovationspace.assessment.service.QuizAnswerSheetScheduler;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AnswerSheetService;
import io.github.dream07code.innovationspace.service.AssessmentService;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 调度器只做委托：sweep 补交到期答题卡并关闭到期考核，异常不外抛（保住轮询）。 */
class QuizAnswerSheetSchedulerTest {

  private static final class FakeSheets implements AnswerSheetService {
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

  private static final class FakeAssessments implements AssessmentService {
    int closed;

    @Override
    public IdempotencyService.StoredResponse create(
        Map<String, Object> request, SessionPrincipal actor, String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> get(long id, SessionPrincipal actor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> list(
        SessionPrincipal actor, int page, int size, String status, String directionValue) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> update(
        long id, Map<String, Object> request, SessionPrincipal actor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IdempotencyService.StoredResponse command(
        long id, Map<String, Object> request, SessionPrincipal actor, String key, String action) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int closeExpiredAssessments() {
      closed++;
      return 1;
    }

    @Override
    public IdempotencyService.StoredResponse publishResults(
        long id, Map<String, Object> request, SessionPrincipal actor, String key) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void sweepSubmitsExpiredDraftsAndClosesExpiredAssessments() {
    var sheets = new FakeSheets();
    sheets.result = 3;
    var assessments = new FakeAssessments();
    var scheduler = new QuizAnswerSheetScheduler(sheets, assessments);

    scheduler.sweep();

    assertEquals(1, sheets.calls);
    assertEquals(1, assessments.closed);
  }

  @Test
  void sweepSwallowsServiceFailureToKeepPolling() {
    var sheets = new FakeSheets();
    sheets.blowUp = true;
    var assessments = new FakeAssessments();
    var scheduler = new QuizAnswerSheetScheduler(sheets, assessments);

    assertDoesNotThrow(scheduler::sweep);
    assertEquals(1, sheets.calls);
  }
}
