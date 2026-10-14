package io.github.dream07code.innovationspace.bootstrap;

import io.github.dream07code.innovationspace.config.DatabaseSchemaGuard;
import io.github.dream07code.innovationspace.security.PasswordService;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.bootstrap.mode", havingValue = "admin")
public class AdminBootstrapCommand implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final PasswordService passwords;
  private final DatabaseSchemaGuard schema;
  private final Environment env;
  private final TransactionTemplate tx;
  private final Clock clock;

  public AdminBootstrapCommand(
      JdbcTemplate jdbc,
      PasswordService passwords,
      DatabaseSchemaGuard schema,
      Environment env,
      PlatformTransactionManager manager,
      Clock clock) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.schema = schema;
    this.env = env;
    this.tx = new TransactionTemplate(manager);
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    schema.requireCurrentSchema();
    String account = env.getProperty("ADMIN_ACCOUNT"),
        password = env.getProperty("ADMIN_PASSWORD"),
        display = env.getProperty("ADMIN_DISPLAY_NAME");
    if (account == null
        || account.isBlank()
        || account.length() > 64
        || password == null
        || password.isBlank()
        || display == null
        || display.isBlank()
        || display.length() > 64)
      throw new IllegalStateException("Administrator bootstrap inputs are required");
    if (password.codePointCount(0, password.length()) < 12
        || password.codePointCount(0, password.length()) > 64)
      throw new IllegalStateException("Administrator password policy violation");
    tx.executeWithoutResult(
        s -> {
          jdbc.queryForMap(
              "SELECT lock_name FROM bootstrap_lock WHERE lock_name='ADMIN_BOOTSTRAP' FOR UPDATE");
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM app_user WHERE role='COLLEGE_ADMIN'", Integer.class)
              > 0) return;
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM app_user WHERE account=?", Integer.class, account)
              > 0)
            throw new IllegalStateException("Administrator bootstrap cannot use this account");
          jdbc.update(
              "INSERT INTO"
                  + " app_user(account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
                  + " VALUES (?,?,'COLLEGE_ADMIN',false,'ENABLED',?,?,?)",
              account,
              passwords.encode(password),
              display,
              Timestamp.from(clock.instant()),
              Timestamp.from(clock.instant()));
        });
    System.out.println("Administrator initialization completed");
  }
}
