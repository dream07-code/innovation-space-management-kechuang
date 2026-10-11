package io.github.dream07code.innovationspace.security;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
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
            if (expires.isAfter(clock.instant())) {
              if (!java.util.Arrays.equals(
                  (byte[]) row.get("request_digest"), java.util.HexFormat.of().parseHex(digest)))
                throw new ApiException(409, ErrorCode.IDEMPOTENCY_CONFLICT);
              Object body = row.get("response_body");
              String replay =
                  body instanceof byte[] bytes
                      ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                      : body.toString();
              return new StoredResponse(
                  ((Number) row.get("response_status")).intValue(),
                  tools.jackson.databind.json.JsonMapper.builder()
                      .build()
                      .writeValueAsString(
                          tools.jackson.databind.json.JsonMapper.builder()
                              .build()
                              .readTree(replay)));
            }
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
  }
}
