package io.github.dream07code.innovationspace.assessment;

import io.github.dream07code.innovationspace.assessment.adapter.MockOjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Judge0OjProperties.class)
public class AssessmentOjConfiguration {
  @Bean
  OjAdapter assessmentOjAdapter(Judge0OjProperties properties) {
    return "judge0".equals(properties.getProvider())
        ? new Judge0OjAdapter(properties)
        : new MockOjAdapter();
  }
}
