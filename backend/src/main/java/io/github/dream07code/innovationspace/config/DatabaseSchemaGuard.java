package io.github.dream07code.innovationspace.config;

import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaGuard implements HealthIndicator {
  /**
   * 校验结果缓存时长。
   *
   * <p>每个请求都去查一次 flyway_schema_history 会额外占用一个数据库连接：50 人同时交卷时请求数翻倍，
   * 连接池被这类重复查询挤占。库结构只在发布迁移时变化，缓存两秒既不影响「迁移未完成就拒绝服务」的
   * 语义，又能把这类查询从每请求一次降到每两秒一次。
   */
  private static final long CACHE_NANOS = 2_000_000_000L;

  private final DataSource dataSource;
  private final MigrationProperties properties;

  private volatile boolean cachedCurrent;
  private volatile long cachedAtNanos;
  private volatile boolean hasCache;

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
    if (hasCache && System.nanoTime() - cachedAtNanos < CACHE_NANOS) return cachedCurrent;
    synchronized (this) {
      if (hasCache && System.nanoTime() - cachedAtNanos < CACHE_NANOS) return cachedCurrent;
      boolean current = querySchemaIsCurrent();
      cachedCurrent = current;
      cachedAtNanos = System.nanoTime();
      hasCache = true;
      return current;
    }
  }

  private boolean querySchemaIsCurrent() {
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
