package io.github.dream07code.innovationspace.tutor;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 助教相关表的 JDBC 实现。
 *
 * <p>只做存取，不做业务判断：越权、截断、聚合口径都在服务层/聚合器里，
 * 这样单测不需要起数据库。JSON 列（标签、薄弱点、推荐）按字符串读写，
 * 读回来再解析成字符串列表，坏数据当空列表处理，不把脏结构带进接口。
 */
@Repository
public class JdbcTutorRepository implements TutorRepository {
  private final JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  public JdbcTutorRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long createConversation(long memberId, String title, Long problemId, Instant now) {
    var keys = new GeneratedKeyHolder();
    var stamp = Timestamp.from(now);
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "INSERT INTO tutor_conversation(member_id,title,problem_id,created_at,updated_at)"
                      + " VALUES(?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          statement.setLong(1, memberId);
          statement.setString(2, title);
          statement.setObject(3, problemId);
          statement.setTimestamp(4, stamp);
          statement.setTimestamp(5, stamp);
          return statement;
        },
        keys);
    var key = keys.getKey();
    if (key == null) throw new IllegalStateException("tutor_conversation insert returned no key");
    return key.longValue();
  }

  @Override
  public Optional<TutorConversationSummary> findConversation(long conversationId) {
    var rows =
        jdbc.queryForList(
            "SELECT c.id,c.member_id,c.title,c.problem_id,c.created_at,c.updated_at,p.title problem_title,"
                + " (SELECT COUNT(*) FROM tutor_message m WHERE m.conversation_id=c.id) message_count"
                + " FROM tutor_conversation c LEFT JOIN assessment_problem p ON p.id=c.problem_id"
                + " WHERE c.id=?",
            conversationId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(summary(rows.getFirst()));
  }

  @Override
  public List<TutorConversationSummary> listConversations(long memberId, int limit, int offset) {
    var out = new ArrayList<TutorConversationSummary>();
    for (var row : jdbc.queryForList(
        "SELECT c.id,c.member_id,c.title,c.problem_id,c.created_at,c.updated_at,p.title problem_title,"
            + " (SELECT COUNT(*) FROM tutor_message m WHERE m.conversation_id=c.id) message_count"
            + " FROM tutor_conversation c LEFT JOIN assessment_problem p ON p.id=c.problem_id"
            + " WHERE c.member_id=? ORDER BY c.updated_at DESC,c.id DESC LIMIT ? OFFSET ?",
        memberId,
        limit,
        offset)) out.add(summary(row));
    return out;
  }

  @Override
  public long countConversations(long memberId) {
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tutor_conversation WHERE member_id=?", Long.class, memberId);
    return total == null ? 0L : total;
  }

  @Override
  public void renameConversation(long conversationId, String title, Instant now) {
    jdbc.update(
        "UPDATE tutor_conversation SET title=?,updated_at=? WHERE id=?",
        title,
        Timestamp.from(now),
        conversationId);
  }

  @Override
  public void setConversationProblem(long conversationId, long problemId, Instant now) {
    jdbc.update(
        "UPDATE tutor_conversation SET problem_id=?,updated_at=? WHERE id=?",
        problemId,
        Timestamp.from(now),
        conversationId);
  }

  @Override
  public void deleteConversation(long conversationId) {
    // 消息由外键 ON DELETE CASCADE 一起删，这里不手工删消息表。
    jdbc.update("DELETE FROM tutor_conversation WHERE id=?", conversationId);
  }

  @Override
  public void touchConversation(long conversationId, Instant now) {
    jdbc.update(
        "UPDATE tutor_conversation SET updated_at=? WHERE id=?",
        Timestamp.from(now),
        conversationId);
  }

  @Override
  public long appendMessage(long conversationId, String role, String content, Instant now) {
    var keys = new GeneratedKeyHolder();
    var stamp = Timestamp.from(now);
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "INSERT INTO tutor_message(conversation_id,role,content,created_at)"
                      + " VALUES(?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          statement.setLong(1, conversationId);
          statement.setString(2, role);
          statement.setString(3, content);
          statement.setTimestamp(4, stamp);
          return statement;
        },
        keys);
    var key = keys.getKey();
    if (key == null) throw new IllegalStateException("tutor_message insert returned no key");
    return key.longValue();
  }

  @Override
  public List<TutorMessage> recentMessages(long conversationId, int limit) {
    var out = new ArrayList<TutorMessage>();
    // 先取最近 limit 条（倒序），再翻回正序：历史只需要最近若干轮，不必把整段对话读出来。
    for (var row : jdbc.queryForList(
        "SELECT id,role,content,created_at FROM ("
            + " SELECT id,role,content,created_at FROM tutor_message WHERE conversation_id=?"
            + " ORDER BY id DESC LIMIT ?) recent ORDER BY id ASC",
        conversationId,
        limit)) {
      out.add(
          new TutorMessage(
              longValue(row.get("id")),
              String.valueOf(row.get("role")),
              String.valueOf(row.get("content")),
              instant(row.get("created_at"))));
    }
    return out;
  }

  @Override
  public long countMessages(long conversationId) {
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tutor_message WHERE conversation_id=?",
            Long.class,
            conversationId);
    return total == null ? 0L : total;
  }

  @Override
  public List<AttemptedProblem> attemptedProblems(long memberId) {
    var out = new ArrayList<AttemptedProblem>();
    // 活动口径 = 考核提交 + 题库练习，两者按「本人 + 题目」并成一条流再分组：
    // - 只算已判出结论的：还在判题中的提交既不算学会也不算没学会；
    // - 同一题无论提交几次、练习几次，都只算一题，通过 = 任意一次 ACCEPTED；
    // - 提交数把练习次数一并计入（练习也是学生真实做过的活动）。
    // 练习不产生成绩，因此这个口径只影响活动指标，不影响「已发布成绩均分」。
    for (var row : jdbc.queryForList(
        "SELECT p.id,p.title,p.difficulty,p.tags,COUNT(*) submissions,"
            + " MAX(a.accepted) accepted,MAX(a.occurred_at) last_submitted_at"
            + " FROM ("
            + "  SELECT s.problem_id,CASE WHEN j.verdict='ACCEPTED' THEN 1 ELSE 0 END accepted,"
            + "   s.submitted_at occurred_at"
            + "  FROM assessment_submission s"
            + "  LEFT JOIN oj_judge_result j ON j.submission_id=s.id"
            + "  WHERE s.member_id=? AND s.status IN ('JUDGED','FAILED','NEEDS_REVIEW')"
            + "  UNION ALL"
            + "  SELECT pa.problem_id,CASE WHEN pa.verdict='ACCEPTED' THEN 1 ELSE 0 END,"
            + "   pa.created_at"
            + "  FROM problem_practice_attempt pa WHERE pa.member_id=?"
            + " ) a"
            + " JOIN assessment_problem p ON p.id=a.problem_id"
            + " GROUP BY p.id,p.title,p.difficulty,p.tags ORDER BY p.id",
        memberId,
        memberId)) {
      out.add(
          new AttemptedProblem(
              longValue(row.get("id")),
              String.valueOf(row.get("title")),
              row.get("difficulty") == null ? null : String.valueOf(row.get("difficulty")),
              tags(row.get("tags")),
              intValue(row.get("submissions")),
              intValue(row.get("accepted")) == 1,
              instant(row.get("last_submitted_at"))));
    }
    return out;
  }

  @Override
  public Integer averagePublishedScore(long memberId) {
    // 与成绩页同一口径：一份考核算一个分，取「每题最后一次提交的 final_score 之和 + 客观题答题卡分」，
    // 再对已发布的考核求平均。不能直接 AVG(assessment_submission.final_score)：那是按单题提交平均，
    // 同一题多次提交会被重复计入，数值和成绩页对不上。
    return jdbc.queryForObject(
        "SELECT ROUND(AVG(t.total_score)) FROM ("
            + " SELECT a.id,"
            + " COALESCE(SUM(r.final_score),0) + COALESCE(MAX(sh.total_score),0) AS total_score"
            + " FROM assessment a"
            + " LEFT JOIN ("
            + "  SELECT s.assessment_id,s.id AS submission_id,"
            + "   ROW_NUMBER() OVER (PARTITION BY s.assessment_id,s.problem_id"
            + "    ORDER BY s.submitted_at DESC,s.id DESC) AS rn"
            + "  FROM assessment_submission s WHERE s.member_id=?"
            + " ) l ON l.assessment_id=a.id AND l.rn=1"
            + " LEFT JOIN assessment_result r ON r.submission_id=l.submission_id"
            + " LEFT JOIN assessment_answer_sheet sh"
            + "  ON sh.assessment_id=a.id AND sh.member_id=?"
            + " WHERE a.results_published_at IS NOT NULL"
            + "  AND (l.submission_id IS NOT NULL OR sh.total_score IS NOT NULL)"
            + " GROUP BY a.id"
            + ") t",
        Integer.class,
        memberId,
        memberId);
  }

  @Override
  public List<CandidateProblem> publishedProblems(int limit) {
    var out = new ArrayList<CandidateProblem>();
    for (var row : jdbc.queryForList(
        "SELECT id,title,difficulty,tags FROM assessment_problem WHERE status='PUBLISHED'"
            + " ORDER BY id LIMIT ?",
        limit)) {
      out.add(
          new CandidateProblem(
              longValue(row.get("id")),
              String.valueOf(row.get("title")),
              row.get("difficulty") == null ? null : String.valueOf(row.get("difficulty")),
              tags(row.get("tags"))));
    }
    return out;
  }

  @Override
  public void saveProfile(LearningProfile profile) {
    jdbc.update(
        "INSERT INTO tutor_learning_profile(member_id,attempted_problems,passed_problems,pass_rate,"
            + " submission_count,average_score,last_submitted_at,weak_points,recommendations,snapshot_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)"
            + " ON DUPLICATE KEY UPDATE attempted_problems=VALUES(attempted_problems),"
            + " passed_problems=VALUES(passed_problems),pass_rate=VALUES(pass_rate),"
            + " submission_count=VALUES(submission_count),average_score=VALUES(average_score),"
            + " last_submitted_at=VALUES(last_submitted_at),weak_points=VALUES(weak_points),"
            + " recommendations=VALUES(recommendations),snapshot_at=VALUES(snapshot_at)",
        profile.memberId(),
        profile.attemptedProblems(),
        profile.passedProblems(),
        profile.passRate(),
        profile.submissionCount(),
        profile.averageScore(),
        profile.lastSubmittedAt() == null ? null : Timestamp.from(profile.lastSubmittedAt()),
        json.writeValueAsString(profile.weakPoints()),
        json.writeValueAsString(profile.recommendations()),
        Timestamp.from(profile.snapshotAt()));
  }

  @Override
  public void deleteProfile(long memberId) {
    jdbc.update("DELETE FROM tutor_learning_profile WHERE member_id=?", memberId);
  }

  private TutorConversationSummary summary(Map<String, Object> row) {
    return new TutorConversationSummary(
        conversation(row),
        row.get("problem_title") == null ? null : String.valueOf(row.get("problem_title")),
        longValue(row.get("message_count")));
  }

  private TutorConversation conversation(Map<String, Object> row) {
    Object problemId = row.get("problem_id");
    return new TutorConversation(
        longValue(row.get("id")),
        longValue(row.get("member_id")),
        String.valueOf(row.get("title")),
        problemId == null ? null : longValue(problemId),
        instant(row.get("created_at")),
        instant(row.get("updated_at")));
  }

  @SuppressWarnings("unchecked")
  private List<String> tags(Object raw) {
    if (raw == null) return List.of();
    try {
      var parsed = (List<Object>) json.readValue(String.valueOf(raw), List.class);
      var out = new ArrayList<String>(parsed.size());
      for (Object item : parsed) if (item != null) out.add(String.valueOf(item));
      return out;
    } catch (RuntimeException error) {
      // 标签是展示用数据，坏行不应让整个画像 500；当作没有标签处理。
      return List.of();
    }
  }

  private static long longValue(Object value) {
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static int intValue(Object value) {
    return value == null ? 0 : ((Number) value).intValue();
  }

  /** 驱动可能回 Timestamp，也可能回 LocalDateTime（会话时区已强制 UTC）。 */
  private static Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
    if (value instanceof Instant instant) return instant;
    return Instant.parse(String.valueOf(value));
  }
}
