package io.github.dream07code.innovationspace.common.validation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.util.LinkedHashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 字段校验：多带、缺字段、越界都要点名到字段，且错误码仍是 INVALID_REQUEST。 */
class RequestFieldsTest {

  @Test
  void unknownFieldIsNamedInTheError() {
    var request = new LinkedHashMap<String, Object>();
    request.put("language", "java");
    request.put("code", "class Main{}");
    request.put("problemId", "1");

    var error =
        assertThrows(
            ApiException.class, () -> RequestFields.rejectUnknown(request, Set.of("language", "code")));

    assertEquals(400, error.status());
    assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    assertTrue(error.getMessage().contains("unknown field: problemId"), error.getMessage());
  }

  @Test
  void missingFieldIsNamedInTheError() {
    var request = new LinkedHashMap<String, Object>();
    request.put("language", "java");

    var error =
        assertThrows(
            ApiException.class, () -> RequestFields.requirePresent(request, "language", "code"));

    assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    assertTrue(error.getMessage().contains("missing field: code"), error.getMessage());
  }

  @Test
  void outOfRangeIntegerExplainsTheLegalRange() {
    var error =
        assertThrows(
            ApiException.class, () -> RequestFields.integerInRange(0, "reviewVersion", 1, 1_000_000));

    assertEquals(400, error.status());
    assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    // 说清是哪一项、为什么不合法、合法值应该是什么；不回显取值。
    assertTrue(error.getMessage().contains("reviewVersion"), error.getMessage());
    assertTrue(error.getMessage().contains("between 1 and 1000000"), error.getMessage());
    assertFalse(error.getMessage().contains("0,"), "不应回显传入的取值");
  }

  @Test
  void acceptsValueInsideTheRange() {
    assertEquals(3L, RequestFields.integerInRange(3, "reviewVersion", 1, 1_000_000));
  }

  @Test
  void rejectsNonInteger() {
    var error =
        assertThrows(
            ApiException.class, () -> RequestFields.integerInRange(1.5, "score", 0, 100));
    assertTrue(error.getMessage().contains("score must be an integer"), error.getMessage());
  }
}
