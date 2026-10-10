package io.github.dream07code.innovationspace.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("dev & !prod")
public class OpenApiConfiguration {
    @Bean
    public OpenAPI innovationSpaceOpenApi() {
        return new OpenAPI().info(new Info().title("学院科创空间管理系统 API").version("v1"));
    }

    @Bean
    public GroupedOpenApi innovationSpaceApi() {
        return GroupedOpenApi.builder().group("innovation-space").pathsToMatch("/api/v1/**").build();
    }
}
