package io.github.dream07code.innovationspace.scoring;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ScoringProperties.class)
public class ScoringConfiguration {
  @Bean
  MockReportScoringProvider mockReportScoringProvider() {
    return new MockReportScoringProvider();
  }

  @Bean
  HeuristicReportScoringProvider heuristicReportScoringProvider() {
    return new HeuristicReportScoringProvider();
  }

  @Bean
  OpenAiCompatibleReportScoringProvider openAiCompatibleReportScoringProvider(ScoringProperties properties) {
    return new OpenAiCompatibleReportScoringProvider(properties);
  }

  /** 编程作业批改复用同一套 openai-compatible 配置，不额外接模型。 */
  @Bean
  OpenAiCompatibleSubmissionReportAi openAiCompatibleSubmissionReportAi(
      ScoringProperties properties) {
    return new OpenAiCompatibleSubmissionReportAi(properties);
  }

  /** AI 助教同样复用这套配置：批改是单轮结论，助教是多轮追问，只是提示词不同。 */
  @Bean
  OpenAiCompatibleSubmissionTutorAi openAiCompatibleSubmissionTutorAi(
      ScoringProperties properties) {
    return new OpenAiCompatibleSubmissionTutorAi(properties);
  }

  /** 通用助教（不绑定提交）同样复用这套配置，只换成学科助教的提示词。 */
  @Bean
  OpenAiCompatibleTutorChatAi openAiCompatibleTutorChatAi(ScoringProperties properties) {
    return new OpenAiCompatibleTutorChatAi(properties);
  }

  @Bean
  ScoringProviderRegistry scoringProviderRegistry(
      ScoringProperties properties, List<ReportScoringProvider> providers) {
    return new ScoringProviderRegistry(properties, providers);
  }
}
