package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.LearningProfileService;
import io.github.dream07code.innovationspace.tutor.AttemptedProblem;
import io.github.dream07code.innovationspace.tutor.LearningProfile;
import io.github.dream07code.innovationspace.tutor.LearningProfileAggregator;
import io.github.dream07code.innovationspace.tutor.TutorRepository;
import io.github.dream07code.innovationspace.tutor.TutorSupport;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 学习画像的实现。
 *
 * <p>每次读取都按最新数据重算并覆盖快照：判题完成后刷新页面就能看到新数字，
 * 不需要额外的定时任务，也不会出现「快照比判题还旧」的错觉。
 *
 * <p>空状态不是「查不到就返回默认值」，而是真的没有做题记录：
 * 此时删掉旧快照并明确返回 {@code hasData=false}，前端显示空状态引导去题库，
 * 而不是展示一份 0% 通过率加空推荐的假画像。
 */
@Service
public class LearningProfileServiceImpl implements LearningProfileService {
  /** 推荐只在公开题库里挑，上限防止题库很大时把整库读进内存。 */
  private static final int MAX_CANDIDATES = 500;

  private final TutorRepository repository;
  private final Clock clock;

  public LearningProfileServiceImpl(TutorRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public Map<String, Object> profile(SessionPrincipal actor) {
    if (actor == null || actor.memberId() == null)
      throw new ApiException(403, ErrorCode.FORBIDDEN);
    long memberId = actor.memberId();

    List<AttemptedProblem> attempted = repository.attemptedProblems(memberId);
    if (attempted.isEmpty()) {
      // 没有做题记录就不再保留旧快照：学生换了档案或提交被清理时，画像应该一起空掉。
      repository.deleteProfile(memberId);
      return empty();
    }

    Integer averageScore = repository.averagePublishedScore(memberId);
    Instant lastSubmittedAt =
        attempted.stream()
            .map(AttemptedProblem::lastSubmittedAt)
            .filter(java.util.Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(null);

    var profile =
        LearningProfileAggregator.aggregate(
            memberId,
            attempted,
            repository.publishedProblems(MAX_CANDIDATES),
            averageScore,
            lastSubmittedAt,
            clock.instant());
    repository.saveProfile(profile);
    return view(profile);
  }

  private Map<String, Object> view(LearningProfile profile) {
    var overview = new LinkedHashMap<String, Object>();
    overview.put("attemptedProblems", profile.attemptedProblems());
    overview.put("passedProblems", profile.passedProblems());
    overview.put("passRate", profile.passRate());
    overview.put("submissionCount", profile.submissionCount());
    overview.put("averageScore", profile.averageScore());
    overview.put("lastSubmittedAt", TutorSupport.time(profile.lastSubmittedAt()));

    var weakPoints = new ArrayList<Map<String, Object>>(profile.weakPoints().size());
    for (var point : profile.weakPoints()) {
      var item = new LinkedHashMap<String, Object>();
      item.put("tag", point.tag());
      item.put("attempted", point.attempted());
      item.put("passed", point.passed());
      item.put("passRate", point.passRate());
      weakPoints.add(item);
    }

    var recommendations = new ArrayList<Map<String, Object>>(profile.recommendations().size());
    for (var recommendation : profile.recommendations()) {
      var item = new LinkedHashMap<String, Object>();
      item.put("problemId", String.valueOf(recommendation.problemId()));
      item.put("title", recommendation.title());
      item.put("difficulty", recommendation.difficulty());
      item.put("tags", recommendation.tags());
      item.put("reason", recommendation.reason());
      recommendations.add(item);
    }

    var out = new LinkedHashMap<String, Object>();
    out.put("hasData", true);
    out.put("snapshotAt", TutorSupport.time(profile.snapshotAt()));
    out.put("overview", overview);
    out.put("weakPoints", weakPoints);
    out.put("recommendations", recommendations);
    return out;
  }

  /** 空状态：数字全零、列表全空，不带任何推测内容。 */
  private Map<String, Object> empty() {
    var overview = new LinkedHashMap<String, Object>();
    overview.put("attemptedProblems", 0);
    overview.put("passedProblems", 0);
    overview.put("passRate", 0);
    overview.put("submissionCount", 0);
    overview.put("averageScore", null);
    overview.put("lastSubmittedAt", null);

    var out = new LinkedHashMap<String, Object>();
    out.put("hasData", false);
    out.put("snapshotAt", null);
    out.put("overview", overview);
    out.put("weakPoints", List.of());
    out.put("recommendations", List.of());
    return out;
  }
}
