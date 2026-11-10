package io.github.dream07code.innovationspace.tutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 学习画像聚合规则。
 *
 * <p>刻意做成纯函数：输入是本人已判题的提交、公开题库与已发布成绩，输出是画像快照，
 * 不碰数据库也不调模型。这样「为什么说这个知识点薄弱」「为什么推这道题」都能用单测钉死，
 * 评审追问时不需要跑整站。
 *
 * <p>口径：
 * <ul>
 *   <li>尝试题数 = 有过判题完成记录的题目数，考核提交与题库练习合并（同一题多次只算一题）；
 *   <li>提交数 = 考核提交数 + 练习次数，练习也是真实做题活动；
 *   <li>通过 = 该题至少有一次 ACCEPTED（考核提交或练习判题都算）；
 *   <li>薄弱知识点 = 尝试过、且至少有一题没通过的标签，按「未通过题数多、通过率低」排序；
 *   <li>推荐练习 = 公开题库里本人还没通过的题，优先命中最薄弱的标签；没有任何数据时不给推荐。
 * </ul>
 */
public final class LearningProfileAggregator {
  public static final int MAX_WEAK_POINTS = 3;
  public static final int MAX_RECOMMENDATIONS = 3;

  private LearningProfileAggregator() {}

  public static LearningProfile aggregate(
      long memberId,
      List<AttemptedProblem> attempted,
      List<CandidateProblem> published,
      Integer averageScore,
      Instant lastSubmittedAt,
      Instant now) {
    var problems = attempted == null ? List.<AttemptedProblem>of() : attempted;
    var candidates = published == null ? List.<CandidateProblem>of() : published;

    int attemptedCount = problems.size();
    int passedCount = (int) problems.stream().filter(AttemptedProblem::passed).count();
    int submissionCount = problems.stream().mapToInt(AttemptedProblem::submissions).sum();
    int passRate = attemptedCount == 0 ? 0 : (int) Math.round(passedCount * 100.0 / attemptedCount);

    var weakPoints = weakPoints(problems);
    var recommendations = recommendations(problems, candidates, weakPoints);

    return new LearningProfile(
        memberId,
        attemptedCount,
        passedCount,
        passRate,
        submissionCount,
        averageScore,
        lastSubmittedAt,
        weakPoints,
        recommendations,
        now);
  }

  private static List<LearningProfile.WeakPoint> weakPoints(List<AttemptedProblem> problems) {
    var stats = new LinkedHashMap<String, TagStat>();
    for (var problem : problems) {
      for (String tag : new LinkedHashSet<>(problem.tags())) {
        if (tag == null || tag.isBlank()) continue;
        stats.computeIfAbsent(tag, key -> new TagStat()).add(problem.passed());
      }
    }
    return stats.entrySet().stream()
        .filter(entry -> entry.getValue().failed() > 0)
        .map(entry -> {
          var stat = entry.getValue();
          // 同一题多个标签时每题只计一次，这里的通过率按题算，不按提交算。
          int rate = (int) Math.round(stat.passed() * 100.0 / stat.attempted());
          return new LearningProfile.WeakPoint(entry.getKey(), stat.attempted(), stat.passed(), rate);
        })
        .sorted(
            Comparator.comparingInt((LearningProfile.WeakPoint point) -> point.attempted() - point.passed())
                .reversed()
                .thenComparingInt(LearningProfile.WeakPoint::passRate)
                .thenComparing(LearningProfile.WeakPoint::tag))
        .limit(MAX_WEAK_POINTS)
        .toList();
  }

  private static List<LearningProfile.Recommendation> recommendations(
      List<AttemptedProblem> problems,
      List<CandidateProblem> candidates,
      List<LearningProfile.WeakPoint> weakPoints) {
    if (problems.isEmpty()) return List.of();

    var byProblem = new LinkedHashMap<Long, AttemptedProblem>();
    for (var problem : problems) byProblem.put(problem.problemId(), problem);

    var weakTags = new LinkedHashMap<String, LearningProfile.WeakPoint>();
    for (var point : weakPoints) weakTags.put(point.tag(), point);

    var scored = new ArrayList<Scored>();
    for (var candidate : candidates) {
      var history = byProblem.get(candidate.id());
      if (history != null && history.passed()) continue;
      List<String> matched = new ArrayList<>();
      for (String tag : candidate.tags()) if (weakTags.containsKey(tag)) matched.add(tag);
      scored.add(new Scored(candidate, history == null, matched));
    }

    scored.sort(
        Comparator.comparingInt((Scored item) -> item.matched().size())
            .reversed()
            .thenComparing(Scored::untried, Comparator.reverseOrder())
            .thenComparingInt(item -> difficultyOrder(item.problem().difficulty()))
            .thenComparingLong(item -> item.problem().id()));

    var out = new ArrayList<LearningProfile.Recommendation>(MAX_RECOMMENDATIONS);
    for (var item : scored) {
      if (out.size() >= MAX_RECOMMENDATIONS) break;
      out.add(
          new LearningProfile.Recommendation(
              item.problem().id(),
              item.problem().title(),
              item.problem().difficulty(),
              item.problem().tags(),
              reason(item, weakTags)));
    }
    return List.copyOf(out);
  }

  private static String reason(Scored item, Map<String, LearningProfile.WeakPoint> weakTags) {
    if (!item.matched().isEmpty()) {
      var point = weakTags.get(item.matched().getFirst());
      return "「" + point.tag() + "」目前 " + point.passed() + "/" + point.attempted() + " 通过，先补这个知识点。";
    }
    if (!item.untried()) return "这道题之前没通过，同题补练一次。";
    return "换一道还没通过的题目继续练。";
  }

  private static int difficultyOrder(String difficulty) {
    return switch (difficulty == null ? "" : difficulty) {
      case "EASY" -> 0;
      case "MEDIUM" -> 1;
      case "HARD" -> 2;
      default -> 3;
    };
  }

  private record Scored(CandidateProblem problem, boolean untried, List<String> matched) {}

  private static final class TagStat {
    private int attempted;
    private int passed;

    void add(boolean problemPassed) {
      attempted++;
      if (problemPassed) passed++;
    }

    int attempted() {
      return attempted;
    }

    int passed() {
      return passed;
    }

    int failed() {
      return attempted - passed;
    }
  }
}
