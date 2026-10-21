package io.github.dream07code.innovationspace.scoring;

import java.util.List;

public class ScoringProviderRegistry {
  private final ScoringProperties props;
  private final List<ReportScoringProvider> providers;

  public ScoringProviderRegistry(ScoringProperties props, List<ReportScoringProvider> providers) {
    props.validate();
    this.props = props;
    this.providers = providers;
  }

  public ReportScoringProvider provider() {
    if (!props.isEnabled()) throw new IllegalStateException("AI scoring disabled");
    if ("mock".equals(props.getProvider())) {
      return providers.stream()
          .filter(MockReportScoringProvider.class::isInstance)
          .findFirst()
          .orElseThrow();
    }
    if ("heuristic".equals(props.getProvider())) {
      return providers.stream()
          .filter(HeuristicReportScoringProvider.class::isInstance)
          .findFirst()
          .orElseThrow();
    }
    if ("openai-compatible".equals(props.getProvider())) {
      return providers.stream()
          .filter(OpenAiCompatibleReportScoringProvider.class::isInstance)
          .findFirst()
          .orElseThrow();
    }
    throw new IllegalArgumentException("Unknown AI provider");
  }
}
