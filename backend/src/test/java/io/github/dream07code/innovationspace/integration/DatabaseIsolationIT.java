package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.MysqlTestSupport;
import java.sql.DriverManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;

@org.springframework.context.annotation.Import(io.github.dream07code.testsupport.OwnedDataSourceConfiguration.class)
@SpringBootTest
@ActiveProfiles("default")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseIsolationIT {
    @Container static final MySQLContainer OWNED = MysqlTestSupport.newMysqlContainer();
    @Container static final MySQLContainer DECOY = MysqlTestSupport.newMysqlContainer().withDatabaseName("decoy_test");

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        MysqlTestSupport.registerDataSource(registry, OWNED);
        // These pool-specific values attempt to override the explicitly owned test connection.
        registry.add("spring.datasource.hikari.jdbc-url", DECOY::getJdbcUrl);
        registry.add("spring.datasource.hikari.username", DECOY::getUsername);
        registry.add("spring.datasource.hikari.password", DECOY::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "DELETE FROM bootstrap_probe");
    }

    @Autowired private DataSource dataSource;

    @Test
    void ambientPoolSettingsCannotRedirectConnectionOrExecuteSqlOnDecoy() throws Exception {
        try (var decoy = DriverManager.getConnection(DECOY.getJdbcUrl(), DECOY.getUsername(), DECOY.getPassword());
                var statement = decoy.createStatement()) {
            statement.execute("CREATE TABLE bootstrap_probe (id INT PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO bootstrap_probe VALUES (777)");
            try {
                try (var connection = dataSource.getConnection()) {
                    // Read-only identity check precedes any application test schema setup.
                    assertEquals(OWNED.getJdbcUrl(), connection.getMetaData().getURL());
                }
            } finally {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM bootstrap_probe WHERE id=777")) {
                    result.next();
                    assertEquals(1, result.getInt(1), "non-owned decoy data must remain intact");
                }
            }
        }
    }
}
