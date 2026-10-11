package io.github.dream07code.innovationspace.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DatabaseCredentialsProperties.class, MigrationProperties.class})
public class DatabaseConfiguration {}
