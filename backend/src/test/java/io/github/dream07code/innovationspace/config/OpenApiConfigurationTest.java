package io.github.dream07code.innovationspace.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class OpenApiConfigurationTest {
    @Test
    void documentationBeansAreDevOnlyAndDescribeTheApprovedGroup() throws Exception {
        var configuration = Class.forName("io.github.dream07code.innovationspace.config.OpenApiConfiguration");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev");
            context.register(configuration);
            context.refresh();
            var api = context.getBean(OpenAPI.class);
            assertEquals("AI 编程实训助手 API", api.getInfo().getTitle());
            assertEquals("v1", api.getInfo().getVersion());
            var group = context.getBean(GroupedOpenApi.class);
            assertEquals("innovation-space", group.getGroup());
            assertEquals(java.util.List.of("/api/v1/**"), group.getPathsToMatch());
            assertTrue(group.getPackagesToScan() == null || group.getPackagesToScan().isEmpty());
        }
        for (String[] profiles : new String[][]{{"default"}, {"prod"}, {"prod", "dev"}, {"dev", "prod"}}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles(profiles);
                context.register(configuration);
                context.refresh();
                assertTrue(context.getBeansOfType(OpenAPI.class).isEmpty());
                assertTrue(context.getBeansOfType(GroupedOpenApi.class).isEmpty());
            }
        }
    }
}
