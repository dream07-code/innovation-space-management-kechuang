package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.AssessmentService;
import io.github.dream07code.innovationspace.assessment.*;


import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

@Service
public class AssessmentServiceImpl implements AssessmentService {
  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final AssessmentStatePolicy states = new AssessmentStatePolicy();
  private final JsonMapper json = JsonMapper.builder().build();

  public AssessmentServiceImpl(
      JdbcTemplate jdbc, IdempotencyService idem, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  public IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key) {
    long directionId = validateCreate(request);
    requireManageDirection(actor, directionId);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/assessments",
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  Instant now = clock.instant();
                  jdbc.update(
                      "INSERT INTO assessment(title,description,direction_id,status,start_at,end_at,allowed_languages,version,created_by,created_at,updated_at)"
                          + " VALUES(?,?,?,'DRAFT',?,?,?,'1',?,?,?)",
                      requiredText(request, "title", 120),
                      requiredText(request, "description", 10000),
                      directionId,
                      offset(request.get("startAt")),
                      offset(request.get("endAt")),
                      json.writeValueAsString(languages(request.get("allowedLanguages"))),
                      actor.userId(),
                      Timestamp.from(now),
                      Timestamp.from(now));
                  long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                  saveProblems(id, problemIds(request.get("problemIds")));
                  audit(actor, "ASSESSMENT_CREATED", id, "Created assessment");
                  return new IdempotencyService.StoredResponse(
                      201, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  public Map<String, Object> get(long id, SessionPrincipal actor) {
    var row = row(id);
    requireVisible(actor, row);
    return response(id);
  }

  public Map<String, Object> list(
      SessionPrincipal actor, int page, int size, String status, String directionValue) {
    if (page < 1 || size < 1 || size > 200) throw bad();
    if (status != null && !Set.of("DRAFT", "PUBLISHED", "CLOSED", "ARCHIVED").contains(status)) throw bad();
    Long directionId = directionValue == null ? null : parseId(directionValue);
    var args = new ArrayList<Object>();
    var where = new StringBuilder(" WHERE 1=1");
    if ("COLLEGE_ADMIN".equals(actor.role())) {
      // no scope restriction
    } else if ("DIRECTION_LEADER".equals(actor.role())) {
      if (actor.managedDirectionIds().isEmpty()) where.append(" AND 1=0");
      else {
        where.append(" AND direction_id IN (")
            .append(String.join(",", java.util.Collections.nCopies(actor.managedDirectionIds().size(), "?")))
            .append(")");
        args.addAll(actor.managedDirectionIds());
      }
    } else {
      where.append(" AND status='PUBLISHED' AND direction_id=?");
      args.add(memberDirection(actor.memberId()));
    }
    if (directionId != null) {
      where.append(" AND direction_id=?");
      args.add(directionId);
    }
    if (status != null) {
      where.append(" AND status=?");
      args.add(status);
    }
    String from = " FROM assessment" + where;
    List<Long> ids =
        jdbc.queryForList(
            "SELECT id" + from + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            Long.class,
            append(args, size, (page - 1) * size));
    List<Map<String, Object>> items = new ArrayList<>();
    for (Long id : ids) items.add(response(id));
    Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
    return Map.of("items", items, "page", page, "size", size, "total", total);
  }

  public Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor) {
    if (request == null || request.size() < 2 || !request.containsKey("version")) throw bad();
    long version = version(request.get("version"));
    return tx.execute(
        status -> {
          var before = locked(id);
          requireManageDirection(actor, ((Number) before.get("direction_id")).longValue());
          if (!"DRAFT".equals(before.get("status"))) throw new ApiException(409, ErrorCode.INVALID_STATE);
          if (version != ((Number) before.get("version")).longValue())
            throw new ApiException(409, ErrorCode.VERSION_CONFLICT);
          Map<String, Object> values = new LinkedHashMap<>(request);
          values.remove("version");
          if (!Set.of("title", "description", "startAt", "endAt", "allowedLanguages", "problemIds")
              .containsAll(values.keySet())) throw bad();
          var fields = new ArrayList<String>();
          var args = new ArrayList<Object>();
          if (values.containsKey("title")) { fields.add("title=?"); args.add(requiredText(values, "title", 120)); }
          if (values.containsKey("description")) { fields.add("description=?"); args.add(requiredText(values, "description", 10000)); }
          if (values.containsKey("startAt")) { fields.add("start_at=?"); args.add(offset(values.get("startAt"))); }
          if (values.containsKey("endAt")) { fields.add("end_at=?"); args.add(offset(values.get("endAt"))); }
          if (values.containsKey("allowedLanguages")) { fields.add("allowed_languages=?"); args.add(json.writeValueAsString(languages(values.get("allowedLanguages")))); }
          if (fields.isEmpty() && !values.containsKey("problemIds")) throw bad();
          fields.add("version=version+1");
          fields.add("updated_at=?");
          args.add(Timestamp.from(clock.instant()));
          args.add(id);
          jdbc.update("UPDATE assessment SET " + String.join(",", fields) + " WHERE id=?", args.toArray());
          if (values.containsKey("problemIds")) {
            jdbc.update("DELETE FROM assessment_problem_link WHERE assessment_id=?", id);
            saveProblems(id, problemIds(values.get("problemIds")));
          }
          audit(actor, "ASSESSMENT_UPDATED", id, "Updated assessment");
          return response(id);
        });
  }

  public IdempotencyService.StoredResponse command(
      long id, Map<String, Object> request, SessionPrincipal actor, String key, String action) {
    if (request == null || request.size() != 1 || !request.containsKey("version")) throw bad();
    long version = version(request.get("version"));
    AssessmentStatus target =
        "publish".equals(action) ? AssessmentStatus.PUBLISHED : AssessmentStatus.CLOSED;
    String path = "/api/v1/assessments/" + id + "/" + action;
    return idem.execute(
        actor,
        "POST",
        path,
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  var before = locked(id);
                  requireManageDirection(actor, ((Number) before.get("direction_id")).longValue());
                  AssessmentStatus current = AssessmentStatus.valueOf((String) before.get("status"));
                  if (!states.canTransition(current, target))
                    throw new ApiException(409, ErrorCode.INVALID_STATE);
                  if (version != ((Number) before.get("version")).longValue())
                    throw new ApiException(409, ErrorCode.VERSION_CONFLICT);
                  jdbc.update(
                      "UPDATE assessment SET status=?,version=version+1,updated_at=? WHERE id=?",
                      target.name(),
                      Timestamp.from(clock.instant()),
                      id);
                  if (target == AssessmentStatus.PUBLISHED) {
                    var publishedProblemIds =
                        jdbc.queryForList(
                            "SELECT p.id FROM assessment_problem p JOIN assessment_problem_link l"
                                + " ON l.problem_id=p.id WHERE l.assessment_id=? AND p.status='DRAFT'"
                                + " ORDER BY l.position",
                            Long.class,
                            id);
                    jdbc.update(
                        "UPDATE assessment_problem p JOIN assessment_problem_link l"
                            + " ON l.problem_id=p.id SET p.status='PUBLISHED',p.version=p.version+1,"
                            + " p.updated_at=? WHERE l.assessment_id=? AND p.status='DRAFT'",
                        Timestamp.from(clock.instant()),
                        id);
                    for (Long problemId : publishedProblemIds)
                      auditProblem(actor, "ASSESSMENT_PROBLEM_PUBLISHED", problemId);
                  }
                  audit(actor, "ASSESSMENT_" + target.name(), id, "Changed assessment state");
                  return new IdempotencyService.StoredResponse(
                      200, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  private long validateCreate(Map<String, Object> request) {
    if (request == null) throw bad();
    requiredText(request, "title", 120);
    requiredText(request, "description", 10000);
    long directionId = parseId(requiredText(request, "directionId", 19));
    var start = offset(request.get("startAt"));
    var end = offset(request.get("endAt"));
    if (!end.isAfter(start)) throw bad();
    languages(request.get("allowedLanguages"));
    problemIds(request.get("problemIds"));
    return directionId;
  }

  private void saveProblems(long assessmentId, List<Long> problemIds) {
    int position = 1;
    for (Long problemId : problemIds) {
      Integer exists = jdbc.queryForObject(
          "SELECT COUNT(*) FROM assessment_problem WHERE id=? AND status<>'RETIRED'",
          Integer.class,
          problemId);
      if (exists == null || exists == 0) throw new ApiException(400, ErrorCode.INVALID_REQUEST);
      jdbc.update(
          "INSERT INTO assessment_problem_link(assessment_id,problem_id,position) VALUES(?,?,?)",
          assessmentId,
          problemId,
          position++);
    }
  }

  private void requireManageDirection(SessionPrincipal actor, long directionId) {
    if ("COLLEGE_ADMIN".equals(actor.role())) return;
    if ("DIRECTION_LEADER".equals(actor.role()) && actor.managedDirectionIds().contains(directionId)) return;
    throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  private void requireVisible(SessionPrincipal actor, Map<String, Object> row) {
    long directionId = ((Number) row.get("direction_id")).longValue();
    if ("COLLEGE_ADMIN".equals(actor.role())) return;
    if ("DIRECTION_LEADER".equals(actor.role()) && actor.managedDirectionIds().contains(directionId)) return;
    if ("STUDENT".equals(actor.role())
        && "PUBLISHED".equals(row.get("status"))
        && directionId == memberDirection(actor.memberId())) return;
    throw new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private long memberDirection(Long memberId) {
    if (memberId == null) throw new ApiException(403, ErrorCode.FORBIDDEN);
    return jdbc.queryForObject("SELECT direction_id FROM member WHERE id=?", Long.class, memberId);
  }

  private Map<String, Object> row(long id) {
    var rows = jdbc.queryForList("SELECT * FROM assessment WHERE id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private Map<String, Object> locked(long id) {
    var rows = jdbc.queryForList("SELECT * FROM assessment WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private Map<String, Object> response(long id) {
    var row = row(id);
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("title", row.get("title"));
    out.put("description", row.get("description"));
    out.put("directionId", String.valueOf(row.get("direction_id")));
    out.put("directionName", jdbc.queryForObject("SELECT name FROM direction WHERE id=?", String.class, row.get("direction_id")));
    out.put("status", row.get("status"));
    out.put("startAt", time(row.get("start_at")));
    out.put("endAt", time(row.get("end_at")));
    out.put("allowedLanguages", json.readValue(String.valueOf(row.get("allowed_languages")), List.class));
    out.put("problemIds", jdbc.queryForList("SELECT problem_id FROM assessment_problem_link WHERE assessment_id=? ORDER BY position", String.class, id));
    out.put("version", row.get("version"));
    out.put("createdAt", time(row.get("created_at")));
    out.put("updatedAt", time(row.get("updated_at")));
    return out;
  }

  private void audit(SessionPrincipal actor, String action, long id, String summary) {
    jdbc.update(
        "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at)"
            + " VALUES(?,?, 'ASSESSMENT',?,?, 'SUCCESS',?)",
        actor.userId(),
        action,
        id,
        summary,
        Timestamp.from(clock.instant()));
  }

  private void auditProblem(SessionPrincipal actor, String action, long id) {
    jdbc.update(
        "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at)"
            + " VALUES(?,?, 'ASSESSMENT_PROBLEM',?,?, 'SUCCESS',?)",
        actor.userId(),
        action,
        id,
        "Published linked problem",
        Timestamp.from(clock.instant()));
  }

  private static Object[] append(List<Object> args, Object... extra) {
    Object[] result = new Object[args.size() + extra.length];
    for (int i = 0; i < args.size(); i++) result[i] = args.get(i);
    for (int i = 0; i < extra.length; i++) result[args.size() + i] = extra[i];
    return result;
  }

  private static String requiredText(Map<String, Object> request, String field, int max) {
    Object value = request.get(field);
    if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw bad();
    return text;
  }

  private static long parseId(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw bad();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw bad();
    }
  }

  private static long version(Object value) {
    if (!(value instanceof Number number) || number.longValue() < 1) throw bad();
    return number.longValue();
  }

  @SuppressWarnings("unchecked")
  private static List<String> languages(Object value) {
    if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 6) throw bad();
    var result = new ArrayList<String>();
    for (Object item : list) {
      if (!(item instanceof String language)
          || !Set.of("java", "python", "cpp", "javascript", "go", "rust").contains(language)) throw bad();
      result.add(language);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<Long> problemIds(Object value) {
    if (!(value instanceof List<?> list)) throw bad();
    var result = new ArrayList<Long>();
    for (Object item : list) {
      if (!(item instanceof String id)) throw bad();
      result.add(parseId(id));
    }
    return result;
  }

  private static OffsetDateTime offset(Object value) {
    if (!(value instanceof String text)) throw bad();
    try {
      return OffsetDateTime.parse(text);
    } catch (RuntimeException error) {
      throw bad();
    }
  }

  private static String time(Object value) {
    Instant instant =
        value instanceof Timestamp timestamp
            ? timestamp.toInstant()
            : value instanceof LocalDateTime local
                ? local.toInstant(ZoneOffset.UTC)
                : (Instant) value;
    return instant.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
