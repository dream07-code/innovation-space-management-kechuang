package io.github.dream07code.testsupport;

import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class MysqlTestSupport {
    public static final String MYSQL_IMAGE = "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b";

    private MysqlTestSupport() { }

    public static MySQLContainer newMysqlContainer() {
        return new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE).asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("bootstrap_test")
                .withUsername("bootstrap_user")
                .withPassword(UUID.randomUUID().toString())
                .withUrlParam("connectionTimeZone", "UTC")
                .withUrlParam("forceConnectionTimeZoneToSession", "true")
                .withStartupTimeout(Duration.ofSeconds(90));
    }

    public static void registerDataSource(DynamicPropertyRegistry registry, MySQLContainer container) {
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    public static void migrate(MySQLContainer container) {
        Flyway.configure().dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    public static void registerMigratedDataSource(DynamicPropertyRegistry registry, MySQLContainer container) {
        migrate(container);
        registerDataSource(registry, container);
    }
}
