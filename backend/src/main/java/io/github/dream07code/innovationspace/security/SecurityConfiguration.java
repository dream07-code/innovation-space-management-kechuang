package io.github.dream07code.innovationspace.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type =
        org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
  @Bean
  BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter(
      SessionService sessions,
      @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
          org.springframework.beans.factory.ObjectProvider<
                  org.springframework.web.servlet.mvc.method.annotation
                      .RequestMappingHandlerMapping>
              mapping) {
    return new BearerTokenAuthenticationFilter(sessions, mapping);
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<BearerTokenAuthenticationFilter>
      bearerRegistration(BearerTokenAuthenticationFilter filter) {
    var bean = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
    bean.setEnabled(false);
    return bean;
  }

  @Bean
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http, BearerTokenAuthenticationFilter bearer) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
