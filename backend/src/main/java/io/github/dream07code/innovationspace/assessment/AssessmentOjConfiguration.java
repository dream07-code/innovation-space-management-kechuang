package io.github.dream07code.innovationspace.assessment;

import io.github.dream07code.innovationspace.assessment.adapter.MockOjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssessmentOjConfiguration {
  @Bean
  OjAdapter assessmentOjAdapter() {
    return new MockOjAdapter();
  }
}
