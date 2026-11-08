package io.github.dream07code.innovationspace.scoring;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 用本地 HTTP 服务模拟 openai-compatible 端点，验证批改客户端的解析与失败处理。 */
class OpenAiCompatibleSubmissionReportAiTest {
  private HttpServer server;

  private static final SubmissionReportAi.ReportInput INPUT =
      new SubmissionReportAi.ReportInput(
          "两数之和",
          "EASY",
          "读入两个整数，输出它们的和。",
          "python",
          "a,b=map(int,input().split())\nprint(a-b)",
          "WRONG_ANSWER",
          3,
          5,
          null,
          List.of(new SubmissionReportAi.FailedCase(4, "WRONG_ANSWER", "2 3", "5", "-1")));

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void returnsCauseAndAdviceFromModelOutput() {
    var reply =
        "{\"choices\":[{\"message\":{\"content\":\"{\\\"cause\\\":\\\"第 2 行用了减号，"
            + "应为加号。\\\",\\\"advice\\\":[\\\"把 a-b 改成 a+b\\\",\\\"用样例 2 3 再验一次\\\"]}\"}}]}";
    var seen = new AtomicReference<String>();
    String baseUrl = start(200, reply, seen);

    var analysis = client(baseUrl).analyze(INPUT);

    assertEquals("第 2 行用了减号，应为加号。", analysis.cause());
    assertEquals(List.of("把 a-b 改成 a+b", "用样例 2 3 再验一次"), analysis.advice());
    assertEquals("mimo-v2.6-flash", analysis.model());
    // 请求体里必须带上题面、判定和失败用例，模型才有依据
    assertTrue(seen.get().contains("两数之和"));
    assertTrue(seen.get().contains("WRONG_ANSWER"));
    assertTrue(seen.get().contains("untrusted"));
    assertFalse(seen.get().contains("student_no"));
  }

  @Test
  void acceptsJsonWrappedInCodeFence() {
    var reply =
        "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"cause\\\":\\\"循环边界少一位\\\","
            + "\\\"advice\\\":[\\\"把 < 改成 <=\\\"]}\\n```\"}}]}";
    String baseUrl = start(200, reply, new AtomicReference<>());

    var analysis = client(baseUrl).analyze(INPUT);

    assertEquals("循环边界少一位", analysis.cause());
    assertEquals(1, analysis.advice().size());
  }

  @Test
  void failsInsteadOfInventingContentWhenProviderErrors() {
    String baseUrl = start(500, "{}", new AtomicReference<>());

    var error = assertThrows(SubmissionReportAi.Unavailable.class, () -> client(baseUrl).analyze(INPUT));
    assertNotNull(error.getMessage());
  }

  @Test
  void failsWhenModelReturnsPlainText() {
    var reply = "{\"choices\":[{\"message\":{\"content\":\"这道题看起来不错\"}}]}";
    String baseUrl = start(200, reply, new AtomicReference<>());

    assertThrows(SubmissionReportAi.Unavailable.class, () -> client(baseUrl).analyze(INPUT));
  }

  @Test
  void failsWhenAdviceIsEmptyButCauseMissing() {
    var reply = "{\"choices\":[{\"message\":{\"content\":\"{\\\"advice\\\":[\\\"再想想\\\"]}\"}}]}";
    String baseUrl = start(200, reply, new AtomicReference<>());

    assertThrows(SubmissionReportAi.Unavailable.class, () -> client(baseUrl).analyze(INPUT));
  }

  @Test
  void refusesToRunWhenProviderIsNotOpenAiCompatible() {
    var properties = properties("http://127.0.0.1:1");
    properties.setProvider("heuristic");

    var client = new OpenAiCompatibleSubmissionReportAi(properties);

    assertThrows(SubmissionReportAi.Unavailable.class, () -> client.analyze(INPUT));
  }

  private static OpenAiCompatibleSubmissionReportAi client(String baseUrl) {
    return new OpenAiCompatibleSubmissionReportAi(properties(baseUrl));
  }

  private static ScoringProperties properties(String baseUrl) {
    var properties = new ScoringProperties();
    properties.setEnabled(true);
    properties.setProvider("openai-compatible");
    properties.setBaseUrl(baseUrl);
    properties.setApiKey("test-key");
    properties.setModel("mimo-v2.6-flash");
    properties.setConnectTimeoutMs(1000);
    properties.setRequestTimeoutMs(5000);
    return properties;
  }

  private String start(int status, String body, AtomicReference<String> seen) {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return "http://127.0.0.1:" + server.getAddress().getPort();
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }
}
