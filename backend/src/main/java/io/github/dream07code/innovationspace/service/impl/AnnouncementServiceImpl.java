package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AnnouncementService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 两级站内公告：direction_id 为空即学院级（全体可见），非空即方向级
 * （该方向学生、所辖负责人、管理员可见）。创建与撤回走幂等键；审计
 * 记录 scope 与方向，撤回为软删除（置 withdrawn_at），重复撤回幂等。
 */
@Service
public class AnnouncementServiceImpl implements AnnouncementService {
  private static final int MAX_TITLE = 120;
  private static final int MAX_CONTENT = 2000;
  private static final int LIST_LIMIT = 5;

  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder().build();

  public AnnouncementServiceImpl(
      JdbcTemplate jdbc, IdempotencyService idem, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  @Override
  public IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key) {
    if (request == null) throw bad();
    String title = text(request, "title", MAX_TITLE);
    String content = text(request, "content", MAX_CONTENT);
    Long directionId = parseOptionalDirection(request.get("directionId"));
    requirePublishRole(actor, directionId);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/announcements",
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  Instant now = clock.instant();
                  jdbc.update(
                      "INSERT INTO announcement(title,content,direction_id,created_by,created_at)"
                          + " VALUES(?,?,?,?,?)",
                      title,
                      content,
                      directionId,
                      actor.userId(),
                      Timestamp.from(now));
                  long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                  audit(actor, "ANNOUNCEMENT_CREATED", id, directionId);
                  return new IdempotencyService.StoredResponse(
                      201, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  @Override
  public Map<String, Object> list(SessionPrincipal actor) {
    StringBuilder where =
        new StringBuilder(" WHERE a.withdrawn_at IS NULL AND (a.direction_id IS NULL");
    List<Object> args = new ArrayList<>();
    if (!"COLLEGE_ADMIN".equals(actor.role())) {
      List<Long> visibleDirections = visibleDirectionIds(actor);
      if (visibleDirections.isEmpty()) {
        // 无所属方向的非管理员只能看到学院级公告。
        where.append(")");
      } else {
        where.append(" OR a.direction_id IN (");
        where.append(String.join(",", java.util.Collections.nCopies(visibleDirections.size(), "?")));
        where.append("))");
        args.addAll(visibleDirections);
      }
    } else {
      // 管理员全量可见：学院级 + 所有方向级。
      where.append(" OR a.direction_id IS NOT NULL)");
    }
    var rows =
        jdbc.queryForList(
            "SELECT a.id,a.title,a.content,a.direction_id,d.name AS direction_name,a.created_at"
                + " FROM announcement a LEFT JOIN direction d ON d.id=a.direction_id"
                + where
                + " ORDER BY a.created_at DESC,a.id DESC LIMIT "
                + LIST_LIMIT,
            args.toArray());
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      items.add(toItem(row));
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("items", items);
    out.put("total", items.size());
    return out;
  }

  @Override
  public IdempotencyService.StoredResponse withdraw(long id, SessionPrincipal actor, String key) {
    return idem.execute(
        actor,
        "POST",
        "/api/v1/announcements/" + id + "/withdraw",
        key,
        "{}",
        () ->
            tx.execute(
                status -> {
                  var row = lockedRow(id);
                  requireWithdrawRole(actor, row);
                  if (row.get("withdrawn_at") != null) {
                    // 已撤回：幂等返回现状。
                    return new IdempotencyService.StoredResponse(
                        200, json.writeValueAsString(ApiResponse.success(response(id))));
                  }
                  jdbc.update(
                      "UPDATE announcement SET withdrawn_at=? WHERE id=?",
                      Timestamp.from(clock.instant()),
                      id);
                  audit(
                      actor,
                      "ANNOUNCEMENT_WITHDRAWN",
                      id,
                      row.get("direction_id") == null
                          ? null
                          : ((Number) row.get("direction_id")).longValue());
                  return new IdempotencyService.StoredResponse(
                      200, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  private Map<String, Object> toItem(Map<String, Object> row) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("title", row.get("title"));
    out.put("content", row.get("content"));
    boolean directed = row.get("direction_id") != null;
    out.put("scope", directed ? "DIRECTION" : "COLLEGE");
    out.put("directionId", directed ? String.valueOf(row.get("direction_id")) : null);
    out.put("directionName", row.get("direction_name"));
    out.put("createdAt", time(row.get("created_at")));
    return out;
  }

  private Map<String, Object> response(long id) {
    var rows =
        jdbc.queryForList(
            "SELECT a.id,a.title,a.content,a.direction_id,d.name AS direction_name,a.created_at"
                + " FROM announcement a LEFT JOIN direction d ON d.id=a.direction_id WHERE a.id=?",
            id);
    if (rows.isEmpty()) throw notFound();
    return toItem(rows.getFirst());
  }

  private Map<String, Object> lockedRow(long id) {
    var rows =
        jdbc.queryForList("SELECT * FROM announcement WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  /** 学生=本人方向；负责人=所辖方向；管理员=全量（调用方特判，不进来）。 */
  private List<Long> visibleDirectionIds(SessionPrincipal actor) {
    if ("DIRECTION_LEADER".equals(actor.role())) {
      return actor.managedDirectionIds();
    }
    if (actor.memberId() == null) return List.of();
    Long direction =
        jdbc.queryForObject(
            "SELECT direction_id FROM member WHERE id=?", Long.class, actor.memberId());
    return direction == null ? List.of() : List.of(direction);
  }

  private void requirePublishRole(SessionPrincipal actor, Long directionId) {
    switch (actor.role()) {
      case "COLLEGE_ADMIN" -> {
        // 管理员可不带 directionId（学院级）或任选方向。
      }
      case "DIRECTION_LEADER" -> {
        if (directionId == null) throw bad();
        if (!actor.managedDirectionIds().contains(directionId))
          throw new ApiException(403, ErrorCode.FORBIDDEN);
      }
      default -> throw new ApiException(403, ErrorCode.FORBIDDEN);
    }
  }

  private void requireWithdrawRole(SessionPrincipal actor, Map<String, Object> row) {
    if ("COLLEGE_ADMIN".equals(actor.role())) return;
    if (!"DIRECTION_LEADER".equals(actor.role())) throw new ApiException(403, ErrorCode.FORBIDDEN);
    boolean directed = row.get("direction_id") != null;
    if (directed
        && !actor.managedDirectionIds()
            .contains(((Number) row.get("direction_id")).longValue())) {
      // 不可见的方向公告按不存在处理，避免枚举。
      throw notFound();
    }
    if (((Number) row.get("created_by")).longValue() != actor.userId()) {
      // 可见但非本人发布（含学院级）：无权撤回。
      throw new ApiException(403, ErrorCode.FORBIDDEN);
    }
  }

  private Long parseOptionalDirection(Object value) {
    if (value == null) return null;
    if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) throw bad();
    long direction = Long.parseLong(text);
    Long exists =
        jdbc.queryForObject("SELECT COUNT(*) FROM direction WHERE id=?", Long.class, direction);
    if (exists == null || exists == 0) throw notFound();
    return direction;
  }

  private void audit(SessionPrincipal actor, String action, long id, Long directionId) {
    jdbc.update(
        "INSERT INTO audit_log(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at) VALUES(?,?,'ANNOUNCEMENT',?,?,'SUCCESS',?)",
        actor.userId(),
        action,
        id,
        action,
        Timestamp.from(clock.instant()));
    jdbc.update(
        "UPDATE audit_log SET detail=JSON_OBJECT('directionId',?) WHERE id=LAST_INSERT_ID()",
        directionId);
  }

  private static String text(Map<String, Object> request, String field, int max) {
    Object value = request.get(field);
    if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw bad();
    return text;
  }

  private static Instant time(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
    return (Instant) value;
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }

  private static ApiException notFound() {
    return new ApiException(404, ErrorCode.NOT_FOUND);
  }
}
