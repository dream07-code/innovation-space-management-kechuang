package io.github.dream07code.innovationspace.config;
import tools.jackson.databind.DeserializationFeature;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
@Configuration(proxyBeanMethods=false)
public class JacksonConfiguration {
 @Bean public JsonMapperBuilderCustomizer strictJson(){return builder->builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);}
}
