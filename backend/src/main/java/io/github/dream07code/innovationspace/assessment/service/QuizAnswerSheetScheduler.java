package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.service.AnswerSheetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 截止自动交卷调度：周期扫描窗口已结束考核的 DRAFT 答题卡，补交并判分。
 *
 * <p>调度模式对齐 ScoringWorkerScheduler（fixedDelay 轮询）；异常吞掉保日志，
 * 下一轮继续，单张答题卡失败不阻塞同批其余补交。
 */
@Component
public class QuizAnswerSheetScheduler {
  private static final Logger log = LoggerFactory.getLogger(QuizAnswerSheetScheduler.class);

  private final AnswerSheetService answerSheets;

  public QuizAnswerSheetScheduler(AnswerSheetService answerSheets) {
    this.answerSheets = answerSheets;
  }

  @Scheduled(fixedDelayString = "${app.quiz.sheet-sweep-interval-ms:30000}")
  public void sweep() {
    try {
      int submitted = answerSheets.submitExpiredDrafts();
      if (submitted > 0) log.info("Auto-submitted {} expired answer sheet(s)", submitted);
    } catch (RuntimeException error) {
      log.warn("Answer sheet sweep failed, will retry next tick: {}", error.getMessage());
    }
  }
}
