package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AssessmentAiProviderRegistry {
  private final ScoringProperties properties;
  private final List<AssessmentAiProvider> providers;

  public AssessmentAiProviderRegistry(ScoringProperties properties, List<AssessmentAiProvider> providers) {
    properties.validate();
    this.properties = properties;
    this.providers = providers;
  }

  public boolean enabled() {
    return properties.isEnabled();
  }

  /** 本地计算的提供方（mock / heuristic）不会调用外部服务，可安全地自动重算。 */
  public boolean local() {
    String provider = properties.getProvider();
    return "mock".equals(provider) || "heuristic".equals(provider);
  }

  public AssessmentAiProvider provider() {
    if (!properties.isEnabled()) throw new IllegalStateException("AI evaluation disabled");
    return switch (properties.getProvider()) {
      case "mock" -> providers.stream()
          .filter(MockAssessmentScoringService.class::isInstance)
          .findFirst()
          .orElseThrow();
      case "heuristic" -> providers.stream()
          .filter(HeuristicAssessmentAiProvider.class::isInstance)
          .findFirst()
          .orElseThrow();
      case "openai-compatible" -> providers.stream()
          .filter(OpenAiCompatibleAssessmentAiProvider.class::isInstance)
          .findFirst()
          .orElseThrow();
      default -> throw new IllegalArgumentException("Unknown AI provider");
    };
  }
}
