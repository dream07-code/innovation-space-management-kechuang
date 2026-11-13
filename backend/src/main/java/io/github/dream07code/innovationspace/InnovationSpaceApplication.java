package io.github.dream07code.innovationspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude =
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class InnovationSpaceApplication {
  public static void main(String[] args) {
    var app = new SpringApplication(InnovationSpaceApplication.class);
    var maintenance = new java.util.concurrent.atomic.AtomicBoolean();
    app.addListeners(
        (org.springframework.context.ApplicationListener<
                org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent>)
            event -> {
              var environment = event.getEnvironment();
              String migration = environment.getProperty("app.migration.mode", "disabled");
              String bootstrap = environment.getProperty("app.bootstrap.mode", "disabled");
              // 图片迁移也是一次性维护任务：跑完就退出，不对外提供 HTTP。
              String imageMigration = environment.getProperty("app.storage.image-migration", "false");
              if (!java.util.Set.of("disabled", "migrate").contains(migration)
                  || !java.util.Set.of("disabled", "admin").contains(bootstrap)
                  || !java.util.Set.of("true", "false").contains(imageMigration))
                throw new IllegalStateException("Unsupported maintenance mode");
              if ("migrate".equals(migration) && "admin".equals(bootstrap))
                throw new IllegalStateException(
                    "Migration and administrator initialization cannot run together");
              boolean active =
                  !"disabled".equals(migration)
                      || !"disabled".equals(bootstrap)
                      || "true".equals(imageMigration);
              maintenance.set(active);
              if (active) {
                event
                    .getSpringApplication()
                    .setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
                environment
                    .getPropertySources()
                    .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                            "controlled-maintenance",
                            java.util.Map.of("spring.main.web-application-type", "none")));
              }
            });
    var context = app.run(args);
    if (maintenance.get()) context.close();
  }
}
