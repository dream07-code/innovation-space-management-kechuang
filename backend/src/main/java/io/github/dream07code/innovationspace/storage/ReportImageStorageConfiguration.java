package io.github.dream07code.innovationspace.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReportImageStorageProperties.class)
public class ReportImageStorageConfiguration {

  @Bean
  ReportImageStorage reportImageStorage(ReportImageStorageProperties properties) {
    return new CosReportImageStorage(properties);
  }
}
