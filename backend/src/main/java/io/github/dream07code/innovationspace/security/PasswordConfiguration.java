package io.github.dream07code.innovationspace.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    RateLimitProperties.class)
public class PasswordConfiguration {
  @Bean
  public PasswordService passwordService() {
    return new PasswordService();
  }

  @Bean
  public TokenService tokenService() {
    return new TokenService();
  }
}
