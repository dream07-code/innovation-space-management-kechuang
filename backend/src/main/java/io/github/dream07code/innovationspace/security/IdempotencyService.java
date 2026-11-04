package io.github.dream07code.innovationspace.security;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyService {
  public record StoredResponse(int status, String body) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final TransactionTemplate tx;

  public IdempotencyService(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    // 幂等键查重走「唯一索引精确匹配」，不需要间隙锁。默认 REPEATABLE READ 下，
    // SELECT ... FOR UPDATE 命中空区间会加间隙锁：几十名学生同时交卷时，不同幂等键
    // 之间也会互相锁等待甚至死锁。改用 READ COMMITTED 只锁命中的记录。
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public StoredResponse execute(
      SessionPrincipal actor,
      String method,
      String path,
      String key,
      String json,
      Supplier<StoredResponse> operation) {
    try {
      if (key == null || !UUID.fromString(key).toString().equalsIgnoreCase(key))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, ErrorCode.INVALID_REQUEST);
    }
    String digest = RequestDigest.digest(json);
    try {
      return tx.execute(
          s -> {
            var user =
                jdbc.queryForMap(
                    "SELECT status,auth_revision FROM app_user WHERE id=? FOR UPDATE",
                    actor.userId());
            if (!"ENABLED".equals(user.get("status"))
                || ((Number) user.get("auth_revision")).longValue() != actor.authRevision())
              throw new ApiException(401, ErrorCode.UNAUTHORIZED);
            var rows =
                jdbc.queryForList(
                    "SELECT * FROM api_idempotency WHERE actor_user_id=? AND http_method=? AND"
                        + " normalized_path=? AND idempotency_key=? FOR UPDATE",
                    actor.userId(),
                    method,
                    path,
                    key);
            if (!rows.isEmpty()) {
              var row = rows.getFirst();
              Object expiry = row.get("expires_at");
              java.time.Instant expires =
                  expiry instanceof Timestamp t
                      ? t.toInstant()
                      : ((java.time.LocalDateTime) expiry).toInstant(java.time.ZoneOffset.UTC);
              if (expires.isAfter(clock.instant())) return replayOf(row, digest);
              jdbc.update("DELETE FROM api_idempotency WHERE id=?", row.get("id"));
            }
            StoredResponse result = operation.get();
            jdbc.update(
                "INSERT INTO"
                    + " api_idempotency(actor_user_id,http_method,normalized_path,idempotency_key,request_digest,response_status,response_body,created_at,expires_at)"
                    + " VALUES (?,?,?,?,UNHEX(?),?,?,?,?)",
                actor.userId(),
                method,
                path,
                key,
                digest,
                result.status(),
                result.body(),
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant().plusSeconds(86400)));
            return result;
          });
    } catch (DuplicateKeyException race) {
      // 同键并发：READ COMMITTED 下两个请求都可能穿过上面的查询，先提交者写入成功，
      // 后者在插入唯一键时才失败。此时事务已整体回滚（业务副作用不会重复），
      // 直接重放先写入方保存的响应即可。
      return replay(actor, method, path, key, digest);
    }
  }

  /** 同键并发落败后重放已提交的结果；找不到则按幂等冲突处理。 */
  private StoredResponse replay(
      SessionPrincipal actor, String method, String path, String key, String digest) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM api_idempotency WHERE actor_user_id=? AND http_method=? AND"
                + " normalized_path=? AND idempotency_key=?",
            actor.userId(),
            method,
            path,
            key);
    if (rows.isEmpty()) throw new ApiException(409, ErrorCode.IDEMPOTENCY_CONFLICT);
    return replayOf(rows.getFirst(), digest);
  }

  private StoredResponse replayOf(Map<String, Object> row, String digest) {
    if (!Arrays.equals((byte[]) row.get("request_digest"), HexFormat.of().parseHex(digest)))
      throw new ApiException(409, ErrorCode.IDEMPOTENCY_CONFLICT);
    Object body = row.get("response_body");
    String raw =
        body instanceof byte[] bytes
            ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            : body.toString();
    // 统一成结构化 JSON 再输出，避免历史上被二次序列化的字符串响应。
    return new StoredResponse(
        ((Number) row.get("response_status")).intValue(),
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .writeValueAsString(
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw)));
  }
}
