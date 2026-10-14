package io.github.dream07code.innovationspace.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class SchemaReadinessConfiguration implements WebMvcConfigurer {
  private final SchemaReadinessInterceptor interceptor;

  public SchemaReadinessConfiguration(SchemaReadinessInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor);
  }
}
