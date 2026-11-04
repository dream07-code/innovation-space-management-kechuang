package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.service.AnswerSheetService;
import io.github.dream07code.innovationspace.service.AssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 截止扫描调度：补交窗口已结束考核的 DRAFT 答题卡，并把到期考核置为 CLOSED。
 *
 * <p>关闭不等于公布成绩：学生要等到负责人/管理员发布成绩后才能看到分数。
 * 调度模式对齐 ScoringWorkerScheduler（fixedDelay 轮询）；异常吞掉保日志，
 * 下一轮继续，单张答题卡失败不阻塞同批其余补交。
 */
@Component
public class QuizAnswerSheetScheduler {
  private static final Logger log = LoggerFactory.getLogger(QuizAnswerSheetScheduler.class);

  private final AnswerSheetService answerSheets;
  private final AssessmentService assessments;

  public QuizAnswerSheetScheduler(
      AnswerSheetService answerSheets, AssessmentService assessments) {
    this.answerSheets = answerSheets;
    this.assessments = assessments;
  }

  @Scheduled(fixedDelayString = "${app.quiz.sheet-sweep-interval-ms:30000}")
  public void sweep() {
    try {
      int submitted = answerSheets.submitExpiredDrafts();
      if (submitted > 0) log.info("Auto-submitted {} expired answer sheet(s)", submitted);
      int closed = assessments.closeExpiredAssessments();
      if (closed > 0) log.info("Auto-closed {} expired assessment(s)", closed);
    } catch (RuntimeException error) {
      log.warn("Answer sheet sweep failed, will retry next tick: {}", error.getMessage());
    }
  }
}
