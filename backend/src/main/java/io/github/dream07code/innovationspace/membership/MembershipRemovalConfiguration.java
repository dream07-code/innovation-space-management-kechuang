package io.github.dream07code.innovationspace.membership;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MembershipRemovalProperties.class)
public class MembershipRemovalConfiguration {}
