package io.github.dream07code.innovationspace.assessment;

import io.github.dream07code.innovationspace.assessment.adapter.MockOjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.local.LocalOjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Judge0OjProperties.class)
public class AssessmentOjConfiguration {
  @Bean
  OjAdapter assessmentOjAdapter(Judge0OjProperties properties) {
    return switch (properties.getProvider()) {
      case "judge0" -> new Judge0OjAdapter(properties);
      // local 直接用本机编译工具链判题，仅用于本地开发，不能作为生产判题后端
      case "local" -> new LocalOjAdapter(properties);
      default -> new MockOjAdapter();
    };
  }
}
