package io.github.dream07code.innovationspace.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 用本地 HTTP 服务模拟 Judge0，验证部署侧配置差异下的自愈行为。 */
class Judge0OjAdapterTest {
  private static final List<OjSubmission.TestCase> ONE_CASE =
      List.of(new OjSubmission.TestCase("1", "1"));
  private static final List<OjSubmission.TestCase> TWO_CASES =
      List.of(new OjSubmission.TestCase("1", "1"), new OjSubmission.TestCase("2", "2"));
  private static final String RLIMIT_TIME_FIELD = "enable_per_process_and_thread_time_limit";
  private static final String RLIMIT_MEMORY_FIELD = "enable_per_process_and_thread_memory_limit";

  private HttpServer server;
  private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
    requestBodies.clear();
  }

  @Test
  void retriesWithRlimitWhenJudge0FailsOnCgroupV2() throws Exception {
    start(
        new Reply(200, "{\"status\":{\"id\":13,\"description\":\"Internal Error\"}}"),
        new Reply(200, accepted()),
        new Reply(200, accepted()));

    var result = adapter(false).judge(new OjSubmission("python", "print(1)", TWO_CASES));

    assertEquals(OjVerdict.ACCEPTED, result.verdict());
    assertEquals(2, result.passedTestCases());
    // 首个测试点用默认模式失败后改用 rlimit，并在后续测试点沿用该模式
    assertEquals(3, requestBodies.size());
    assertFalse(requestBodies.getFirst().contains(RLIMIT_TIME_FIELD));
    assertTrue(requestBodies.get(1).contains(RLIMIT_TIME_FIELD));
    assertTrue(requestBodies.get(1).contains(RLIMIT_MEMORY_FIELD));
    assertTrue(requestBodies.get(2).contains(RLIMIT_TIME_FIELD));
  }

  @Test
  void retriesWithoutRlimitWhenJudge0RejectsTheFields() throws Exception {
    start(
        new Reply(422, "{\"error\":\"Unprocessable Entity\"}"),
        new Reply(200, accepted()));

    var result = adapter(true).judge(new OjSubmission("python", "print(1)", ONE_CASE));

    assertEquals(OjVerdict.ACCEPTED, result.verdict());
    assertEquals(2, requestBodies.size());
    assertTrue(requestBodies.getFirst().contains(RLIMIT_TIME_FIELD));
    assertFalse(requestBodies.getLast().contains(RLIMIT_TIME_FIELD));
  }

  @Test
  void surfacesInternalErrorWithoutLeakingCodeWhenJudge0RejectsToken() throws Exception {
    start(new Reply(401, "{\"error\":\"Unauthorized\"}"));

    var result = adapter(false).judge(new OjSubmission("python", "print('secret-marker')", ONE_CASE));

    assertEquals(OjVerdict.INTERNAL_ERROR, result.verdict());
    assertEquals(0, result.passedTestCases());
    assertEquals(1, requestBodies.size());
    assertFalse(String.valueOf(result).contains("secret-marker"));
    assertFalse(String.valueOf(result).contains("Unauthorized"));
  }

  private Judge0OjAdapter adapter(boolean rlimitFallback) throws IOException {
    var properties = new Judge0OjProperties();
    properties.setProvider("judge0");
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setUseRlimitFallback(rlimitFallback);
    return new Judge0OjAdapter(properties);
  }

  private void start(Reply... replies) throws IOException {
    var counter = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/submissions",
        exchange -> {
          requestBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          var reply = replies[Math.min(counter.getAndIncrement(), replies.length - 1)];
          byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  private static String accepted() {
    return "{\"status\":{\"id\":3,\"description\":\"Accepted\"},\"time\":\"0.012\",\"memory\":2048}";
  }

  private record Reply(int status, String body) {}
}
