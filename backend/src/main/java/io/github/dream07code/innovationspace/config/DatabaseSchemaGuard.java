package io.github.dream07code.innovationspace.config;

import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaGuard implements HealthIndicator {
  private final DataSource dataSource;
  private final MigrationProperties properties;

  public DatabaseSchemaGuard(DataSource dataSource, MigrationProperties properties) {
    this.dataSource = dataSource;
    this.properties = properties;
  }

  public void requireCurrentSchema() {
    if (!isCurrent()) throw new SchemaUnavailableException();
  }

  @Override
  public Health health() {
    return isCurrent() ? Health.up().build() : Health.down().build();
  }

  private boolean isCurrent() {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY"
                    + " installed_rank DESC LIMIT 1");
        var result = statement.executeQuery()) {
      return result.next() && properties.getRequiredVersion().equals(result.getString(1));
    } catch (Exception ignored) {
      return false;
    }
  }
}
