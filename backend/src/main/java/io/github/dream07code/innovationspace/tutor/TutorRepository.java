package io.github.dream07code.innovationspace.tutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 助教会话、消息与学习画像的持久化边界。
 *
 * <p>做成接口是为了让服务层可以脱离数据库做单测：越权访问、历史截断、画像聚合这些
 * 真正需要钉住的语义都在服务层，SQL 只负责存取。
 */
public interface TutorRepository {

  long createConversation(long memberId, String title, Long problemId, Instant now);

  Optional<TutorConversationSummary> findConversation(long conversationId);

  /** 本人会话，最近更新在前。 */
  List<TutorConversationSummary> listConversations(long memberId, int limit, int offset);

  long countConversations(long memberId);

  void renameConversation(long conversationId, String title, Instant now);

  void setConversationProblem(long conversationId, long problemId, Instant now);

  void deleteConversation(long conversationId);

  void touchConversation(long conversationId, Instant now);

  long appendMessage(long conversationId, String role, String content, Instant now);

  /** 最近的 limit 条消息，按时间正序返回，便于直接拼进提示词。 */
  List<TutorMessage> recentMessages(long conversationId, int limit);

  long countMessages(long conversationId);

  /**
   * 本人所有判题完成的题目，一题一行；考核提交与题库练习合并统计。
   *
   * <p>练习只计入活动指标（提交数/做题数/通过率），不参与 {@link #averagePublishedScore(long)}。
   */
  List<AttemptedProblem> attemptedProblems(long memberId);

  /** 已发布成绩按考核汇总后的平均分（与成绩页同口径）；无已发布成绩时返回 null，不按 0 分算。 */
  Integer averagePublishedScore(long memberId);

  /** 公开题库候选题，只含 id/标题/难度/标签。 */
  List<CandidateProblem> publishedProblems(int limit);

  /** 覆盖写入本人员像快照（一成员一行）。 */
  void saveProfile(LearningProfile profile);

  /** 没有任何做题数据时删掉旧快照，避免展示过期画像。 */
  void deleteProfile(long memberId);
}
