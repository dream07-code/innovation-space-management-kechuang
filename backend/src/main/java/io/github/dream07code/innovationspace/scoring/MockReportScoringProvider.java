package io.github.dream07code.innovationspace.scoring;

/** Deterministic synthetic provider used only when explicitly enabled. */
public class MockReportScoringProvider implements ReportScoringProvider {
  @Override
  public ProviderScore score(ScoringInput input) {
    return new ProviderScore(3, "合成评分：内容具体、有实践和复盘。", "mock", "mock");
  }
}
