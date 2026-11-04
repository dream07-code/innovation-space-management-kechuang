package io.github.dream07code.innovationspace.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Schema 守卫的缓存行为：并发考试时每个请求都查一次库会白占连接，缓存后应显著减少查询次数，
 * 同时保留「版本不匹配立即拒绝」的语义。
 */
class DatabaseSchemaGuardTest {

  @Test
  void repeatedChecksReuseCachedResultWithinTtl() throws Exception {
    var dataSource = mock(DataSource.class);
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getString(1)).thenReturn("16");

    var properties = new MigrationProperties();
    properties.setRequiredVersion("16");
    var guard = new DatabaseSchemaGuard(dataSource, properties);

    for (int i = 0; i < 20; i++) assertDoesNotThrow(guard::requireCurrentSchema);

    // 20 次校验只允许落到一次数据库查询。
    verify(dataSource, times(1)).getConnection();
  }

  @Test
  void versionMismatchStillFailsImmediately() throws Exception {
    var dataSource = mock(DataSource.class);
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getString(1)).thenReturn("15");

    var properties = new MigrationProperties();
    properties.setRequiredVersion("16");
    var guard = new DatabaseSchemaGuard(dataSource, properties);

    assertThrows(SchemaUnavailableException.class, guard::requireCurrentSchema);
  }

  @Test
  void connectionFailureIsReportedAsUnavailable() throws Exception {
    var dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("down"));

    var guard = new DatabaseSchemaGuard(dataSource, new MigrationProperties());

    assertThrows(SchemaUnavailableException.class, guard::requireCurrentSchema);
  }
}
