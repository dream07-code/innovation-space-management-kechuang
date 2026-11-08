package io.github.dream07code.innovationspace.config;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;

@Configuration(proxyBeanMethods = false)
@Profile("dev & !prod")
public class OpenApiConfiguration {
  @Bean
  public OpenAPI innovationSpaceOpenApi() {
    return new OpenAPI().info(new Info().title("AI 编程实训助手 API").version("v1"));
  }

  @Bean
  public GroupedOpenApi innovationSpaceApi() {
    return GroupedOpenApi.builder().group("innovation-space").pathsToMatch("/api/v1/**").build();
  }

  @Bean
  public GlobalOpenApiCustomizer implementedApiStatusCustomizer() {
    final OpenAPI contract;
    try (var input = new ClassPathResource("api-contract.yaml").getInputStream()) {
      contract = Yaml.mapper().readValue(input, OpenAPI.class);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("API contract could not be loaded");
    }
    return openApi -> {
      // Document only registered endpoints. The approved DTO schemas also describe map-based
      // responses.
      // Test probes have no matching contract entry and retain their generated test-only
      // documentation.
      openApi
          .getPaths()
          .forEach(
              (path, item) ->
                  item.readOperations()
                      .forEach(
                          operation -> {
                            if (contract.getPaths().containsKey(path)) {
                              operation.addExtension("x-implementation-status", "implemented");
                            }
                          }));
      openApi
          .getPaths()
          .forEach(
              (path, item) -> {
                var approved = contract.getPaths().get(path);
                if (approved != null) {
                  for (var entry : new java.util.ArrayList<>(item.readOperationsMap().entrySet())) {
                    var operation = approved.readOperationsMap().get(entry.getKey());
                    if (operation != null) {
                      operation.addExtension("x-implementation-status", "implemented");
                      item.operation(entry.getKey(), operation);
                    }
                  }
                }
              });
      contract.getComponents().getSchemas().forEach(openApi.getComponents()::addSchemas);
      openApi.getComponents().setParameters(contract.getComponents().getParameters());
      openApi.getComponents().setResponses(contract.getComponents().getResponses());
      openApi.getComponents().setSecuritySchemes(contract.getComponents().getSecuritySchemes());
      openApi.setSecurity(contract.getSecurity());
    };
  }
}
