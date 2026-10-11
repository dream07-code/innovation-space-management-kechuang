package io.github.dream07code.innovationspace.member;

import io.github.dream07code.innovationspace.common.api.*;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MemberService {
  private final JdbcTemplate jdbc;
  private final MemberMapper mapper;
  private final PasswordService passwords;
  private final SessionService sessions;
  private final IdempotencyService idem;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String initial;
  private final JsonMapper json = JsonMapper.builder().build();

  public MemberService(
      JdbcTemplate jdbc,
      MemberMapper mapper,
      PasswordService passwords,
      SessionService sessions,
      IdempotencyService idem,
      PlatformTransactionManager manager,
      Clock clock,
      @Value("${app.auth.initial-password:}") String initial) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.passwords = passwords;
    this.sessions = sessions;
    this.idem = idem;
    this.tx = new TransactionTemplate(manager);
    this.clock = clock;
    this.initial = initial;
  }

  public Map<String, Object> map(Map<String, Object> r) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", r.get("id").toString());
    m.put("userId", r.get("user_id").toString());
    m.put("studentNo", r.get("student_no"));
    m.put("name", r.get("name"));
    m.put("major", r.get("major"));
    m.put("className", r.get("class_name"));
    m.put("grade", r.get("grade"));
    m.put("contact", r.get("contact"));
    m.put("role", r.get("role"));
    m.put("directionId", r.get("direction_id").toString());
    m.put(
        "managedDirectionIds",
        jdbc.queryForList(
            "SELECT direction_id FROM leader_direction_scope WHERE user_id=? ORDER BY direction_id",
            String.class,
            r.get("user_id")));
    m.put("membershipStatus", r.get("membership_status"));
    m.put("accountStatus", r.get("account_status"));
    m.put("mustChangePassword", r.get("must_change_password"));
    for (String[] names :
        new String[][] {
          {"joinedAt", "joined_at"},
          {"membershipStartedAt", "membership_started_at"},
          {"removedAt", "removed_at"},
          {"createdAt", "created_at"},
          {"updatedAt", "updated_at"}
        }) m.put(names[0], time(r.get(names[1])));
    m.put("removalReason", r.get("removal_reason"));
    m.put("totalPoints", 0);
    m.put("version", r.get("version"));
    return m;
  }

  private String time(Object v) {
    if (v == null) return null;
    Instant i =
        v instanceof Timestamp t ? t.toInstant() : ((LocalDateTime) v).toInstant(ZoneOffset.UTC);
    return i.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
  }

  public Map<String, Object> row(long id) {
    var result = mapper.find(id);
    if (result == null) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return result;
  }

  public Map<String, Object> get(long id, SessionPrincipal actor) {
    var r = row(id);
    MemberScopePolicy.staff(actor);
    if (!"COLLEGE_ADMIN".equals(actor.role())
        && (!"STUDENT".equals(r.get("role"))
            || !actor.managedDirectionIds().contains(((Number) r.get("direction_id")).longValue())))
      throw new ApiException(404, ErrorCode.NOT_FOUND);
    return map(r);
  }

  public Map<String, Object> list(
      SessionPrincipal actor, int page, int size, String direction, String keyword, String status) {
    MemberScopePolicy.staff(actor);
    if (page < 1 || size < 1 || size > 200) throw bad();
    Long requestedDirection = direction == null ? null : parseId(direction);
    if (requestedDirection != null) MemberScopePolicy.direction(actor, requestedDirection);
    if (keyword != null && keyword.length() > 100) throw bad();
    if (status != null && !Set.of("ACTIVE", "REMOVED").contains(status)) throw bad();
    var result =
        mapper.list(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size),
            "COLLEGE_ADMIN".equals(actor.role()),
            actor.managedDirectionIds(),
            requestedDirection,
            keyword,
            status);
    return Map.of(
        "items",
        result.getRecords().stream().map(this::map).toList(),
        "page",
        page,
        "size",
        size,
        "total",
        result.getTotal());
  }

  public IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key) {
    fields(
        request,
        Set.of("studentNo", "name", "major", "className", "grade", "contact", "directionId"));
    validateProfile(request);
    String account = required(request, "studentNo", 32), name = required(request, "name", 64);
    if (!account.matches("[A-Za-z0-9_-]+")) throw bad();
    long direction = parseId(required(request, "directionId", 19));
    MemberScopePolicy.direction(actor, direction);
    ensureDirection(direction);
    if (initial.isBlank()) throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/members",
        key,
        json.writeValueAsString(request),
        () -> {
          if (jdbc.queryForObject(
                      "SELECT COUNT(*) FROM app_user WHERE account=?", Integer.class, account)
                  > 0
              || jdbc.queryForObject(
                      "SELECT COUNT(*) FROM member WHERE student_no=?", Integer.class, account)
                  > 0) throw new ApiException(409, ErrorCode.ACCOUNT_ALREADY_EXISTS);
          var now = Timestamp.from(clock.instant());
          jdbc.update(
              "INSERT INTO"
                  + " member(student_no,direction_id,name,major,class_name,grade,contact,joined_at,membership_started_at,created_at,updated_at)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
              account,
              direction,
              name,
              request.get("major"),
              request.get("className"),
              request.get("grade"),
              request.get("contact"),
              now,
              now,
              now,
              now);
          long member = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
          jdbc.update(
              "INSERT INTO"
                  + " app_user(member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
                  + " VALUES (?,?,?,'STUDENT',true,'ENABLED',?,?,?)",
              member,
              account,
              passwords.encode(initial),
              name,
              now,
              now);
          audit(actor, "MEMBER_CREATED", member, null, "Created member");
          return new IdempotencyService.StoredResponse(
              201, json.writeValueAsString(ApiResponse.success(map(row(member)))));
        });
  }

  public Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor) {
    fields(
        request,
        Set.of("version", "name", "major", "className", "grade", "contact", "directionId"));
    validateProfile(request);
    if (request.size() < 2) throw bad();
    get(id, actor);
    return tx.execute(
        s -> {
          lockAndVersion(id, request, actor);
          var before = row(id);
          if (!"STUDENT".equals(before.get("role")))
            throw new ApiException(403, ErrorCode.FORBIDDEN);
          if (request.containsKey("directionId")) {
            MemberScopePolicy.admin(actor);
            ensureDirection(parseId(required(request, "directionId", 19)));
          }
          patch(id, request);
          audit(actor, "MEMBER_UPDATED", id, null, "Updated member profile");
          return map(row(id));
        });
  }

  public void lockAndVersion(long id, Map<String, Object> request, SessionPrincipal actor) {
    var target = row(id);
    long targetUser = ((Number) target.get("user_id")).longValue();
    jdbc.queryForList(
        "SELECT id FROM app_user WHERE id IN (?,?) ORDER BY id FOR UPDATE",
        actor.userId(),
        targetUser);
    var current =
        jdbc.queryForMap(
            "SELECT auth_revision,status,role FROM app_user WHERE id=?", actor.userId());
    if (((Number) current.get("auth_revision")).longValue() != actor.authRevision()
        || !"ENABLED".equals(current.get("status"))
        || !actor.role().equals(current.get("role")))
      throw new ApiException(401, ErrorCode.UNAUTHORIZED);
    var refreshed = row(id);
    if (!"COLLEGE_ADMIN".equals(actor.role())) {
      if (!"STUDENT".equals(refreshed.get("role"))
          || jdbc.queryForObject(
                  "SELECT COUNT(*) FROM leader_direction_scope WHERE user_id=? AND direction_id=?",
                  Integer.class,
                  actor.userId(),
                  refreshed.get("direction_id"))
              != 1) throw new ApiException(403, ErrorCode.FORBIDDEN);
    }
    var member = jdbc.queryForMap("SELECT version FROM member WHERE id=? FOR UPDATE", id);
    if (!(request.get("version") instanceof Number n)
        || n.doubleValue() != n.longValue()
        || n.longValue() < 1) throw bad();
    if (n.longValue() != ((Number) member.get("version")).longValue())
      throw new ApiException(409, ErrorCode.VERSION_CONFLICT);
  }

  public void patch(long id, Map<String, Object> request) {
    var sets = new ArrayList<String>();
    var args = new ArrayList<Object>();
    for (var field :
        Map.of(
                "name",
                "name",
                "major",
                "major",
                "className",
                "class_name",
                "grade",
                "grade",
                "contact",
                "contact",
                "directionId",
                "direction_id")
            .entrySet())
      if (request.containsKey(field.getKey())) {
        if ("name".equals(field.getKey())) required(request, "name", 64);
        sets.add(field.getValue() + "=?");
        args.add(request.get(field.getKey()));
      }
    sets.add("version=version+1");
    sets.add("updated_at=?");
    args.add(Timestamp.from(clock.instant()));
    args.add(id);
    jdbc.update("UPDATE member SET " + String.join(",", sets) + " WHERE id=?", args.toArray());
    if (request.containsKey("name"))
      jdbc.update("UPDATE app_user SET display_name=? WHERE member_id=?", request.get("name"), id);
  }

  public void audit(
      SessionPrincipal actor, String action, long member, String reason, String summary) {
    jdbc.update(
        "INSERT INTO"
            + " audit_log(actor_user_id,action,resource_type,resource_id,reason,summary,result,occurred_at)"
            + " VALUES (?,?,'MEMBER',?,?,?,'SUCCESS',?)",
        actor.userId(),
        action,
        member,
        reason,
        summary,
        Timestamp.from(clock.instant()));
  }

  public void ensureDirection(long direction) {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM direction WHERE id=? AND enabled=true", Integer.class, direction)
        != 1) throw bad();
  }

  public static void fields(Map<String, Object> body, Set<String> allowed) {
    if (body == null || !allowed.containsAll(body.keySet())) throw bad();
  }

  public static String required(Map<String, Object> body, String field, int max) {
    Object v = body.get(field);
    if (!(v instanceof String text) || text.isBlank() || text.length() > max) throw bad();
    return text;
  }

  public static long parseId(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw bad();
    try {
      long id = Long.parseLong(value);
      if (id <= 0) throw bad();
      return id;
    } catch (NumberFormatException e) {
      throw bad();
    }
  }

  public static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }

  public static void validateProfile(Map<String, Object> request) {
    for (String f : new String[] {"major", "className", "contact"})
      if (request.containsKey(f)
          && request.get(f) != null
          && (!(request.get(f) instanceof String t) || t.length() > 100)) throw bad();
    if (request.containsKey("grade") && request.get("grade") != null) {
      if (!(request.get("grade") instanceof Number n)
          || n.doubleValue() != n.intValue()
          || n.intValue() < 1900
          || n.intValue() > 2200) throw bad();
    }
    if (request.containsKey("name")) required(request, "name", 64);
  }

  public IdempotencyService.StoredResponse adminAction(
      long member,
      Map<String, Object> request,
      SessionPrincipal actor,
      String key,
      String path,
      java.util.function.Supplier<Map<String, Object>> operation) {
    MemberScopePolicy.admin(actor);
    return idem.execute(
        actor,
        "POST",
        path,
        key,
        json.writeValueAsString(request),
        () -> {
          lockAndVersion(member, request, actor);
          return new IdempotencyService.StoredResponse(
              200, json.writeValueAsString(ApiResponse.success(operation.get())));
        });
  }

  public Map<String, Object> correct(long id, Map<String, Object> request, SessionPrincipal actor) {
    fields(request, Set.of("version", "newStudentNo", "reason"));
    String number = required(request, "newStudentNo", 32),
        reason = required(request, "reason", 1000);
    var before = row(id);
    long target = ((Number) before.get("user_id")).longValue();
    if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE account=? AND id<>?",
                Integer.class,
                number,
                target)
            > 0
        || jdbc.queryForObject(
                "SELECT COUNT(*) FROM member WHERE student_no=? AND id<>?",
                Integer.class,
                number,
                id)
            > 0) throw new ApiException(409, ErrorCode.ACCOUNT_ALREADY_EXISTS);
    jdbc.update(
        "UPDATE member SET student_no=?,version=version+1,updated_at=? WHERE id=?",
        number,
        Timestamp.from(clock.instant()),
        id);
    jdbc.update(
        "UPDATE app_user SET account=?,auth_revision=auth_revision+1 WHERE id=?", number, target);
    revoke(target, "ACCOUNT_CORRECTED");
    audit(actor, "ACCOUNT_CORRECTED", id, reason, "Corrected member account");
    jdbc.update(
        "UPDATE audit_log SET detail=JSON_OBJECT('oldStudentNo',?,'newStudentNo',?) WHERE"
            + " id=LAST_INSERT_ID()",
        before.get("student_no"),
        number);
    return map(row(id));
  }

  public Map<String, Object> reset(long id, Map<String, Object> request, SessionPrincipal actor) {
    fields(request, Set.of("version", "reason"));
    String reason = required(request, "reason", 1000);
    if (initial.isBlank()) throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    long user = ((Number) row(id).get("user_id")).longValue();
    jdbc.update(
        "UPDATE app_user SET"
            + " password_hash=?,must_change_password=true,auth_revision=auth_revision+1 WHERE id=?",
        passwords.encode(initial),
        user);
    jdbc.update(
        "UPDATE member SET version=version+1,updated_at=? WHERE id=?",
        Timestamp.from(clock.instant()),
        id);
    revoke(user, "PASSWORD_RESET");
    audit(actor, "PASSWORD_RESET", id, reason, "Reset member password");
    return map(row(id));
  }

  public void revoke(long user, String reason) {
    jdbc.update(
        "UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?),revoke_reason=? WHERE user_id=?",
        Timestamp.from(clock.instant()),
        reason,
        user);
  }
}
