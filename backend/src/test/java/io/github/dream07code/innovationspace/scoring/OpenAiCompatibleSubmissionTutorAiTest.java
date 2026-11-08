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

/** 用本地 HTTP 服务模拟 openai-compatible 端点，验证助教客户端的请求体、解析与失败处理。 */
class OpenAiCompatibleSubmissionTutorAiTest {
  private HttpServer server;

  private static final SubmissionTutorAi.TutorInput INPUT =
      new SubmissionTutorAi.TutorInput(
          new SubmissionTutorAi.Problem(
              "两数之和", "EASY", "读入两个整数，输出它们的和。", "一行两个整数", "一个整数", "基础语法"),
          "java",
          "class Solution {\n  public int sum(int a, int b) { return a - b; }\n}",
          new SubmissionTutorAi.Judge(
              "WRONG_ANSWER",
              0,
              1,
              null,
              List.of(new SubmissionTutorAi.FailedCase(1, "WRONG_ANSWER", "[1,2]", "-1", "3"))),
          List.of(
              new SubmissionTutorAi.Turn("user", "这道题为什么错？"),
              new SubmissionTutorAi.Turn("assistant", "第 3 行用了减号。")),
          "那为什么我本机跑样例是对的？");

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void returnsPlainTextAnswerAndSendsFullContext() {
    var reply =
        "{\"choices\":[{\"message\":{\"content\":\"因为第 3 行写的是 a - b，题目要的是求和。\"}}]}";
    var seen = new AtomicReference<String>();
    String baseUrl = start(200, reply, seen);

    var answer = client(baseUrl).chat(INPUT);

    assertEquals("因为第 3 行写的是 a - b，题目要的是求和。", answer.content());
    assertEquals("mimo-v2.6-flash", answer.model());

    String body = seen.get();
    // 上下文口径固定为：题目 + 代码 + 判题结果，缺一模型就答不到点上
    assertTrue(body.contains("两数之和"), "题面必须进提示词");
    assertTrue(body.contains("WRONG_ANSWER"), "判定结果必须进提示词");
    assertTrue(body.contains("a - b"), "学生代码必须进提示词");
    assertTrue(body.contains("[1,2]"), "失败用例必须进提示词");
    // 历史对话与本次问题分别作为独立消息，不能糊成一条
    assertTrue(body.contains("那为什么我本机跑样例是对的？"));
    assertTrue(body.contains("第 3 行用了减号。"));
    assertTrue(body.contains("不可信"), "代码与题面必须标注为不可信输入");
  }

  @Test
  void keepsMessagesAsSystemThenHistoryThenQuestion() {
    String baseUrl = start(200, replyWith("好的"), new AtomicReference<>());

    var messages = client(baseUrl).messagesFor(INPUT);

    assertEquals("system", messages.getFirst().get("role"));
    assertEquals("user", messages.get(1).get("role"));
    assertEquals("assistant", messages.get(2).get("role"));
    assertEquals("user", messages.getLast().get("role"));
    assertEquals("那为什么我本机跑样例是对的？", messages.getLast().get("content"));
  }

  @Test
  void stripsMarkdownFenceFromAnswer() {
    var reply = replyWith("```\n这是围栏里的回答\n```");
    String baseUrl = start(200, reply, new AtomicReference<>());

    assertEquals("这是围栏里的回答", client(baseUrl).chat(INPUT).content());
  }

  @Test
  void failsInsteadOfInventingAnswerWhenProviderErrors() {
    String baseUrl = start(500, "{}", new AtomicReference<>());

    assertThrows(
        SubmissionTutorAi.Unavailable.class, () -> client(baseUrl).chat(INPUT));
  }

  @Test
  void failsWhenModelReturnsEmptyContent() {
    String baseUrl = start(200, replyWith("   "), new AtomicReference<>());

    assertThrows(
        SubmissionTutorAi.Unavailable.class, () -> client(baseUrl).chat(INPUT));
  }

  @Test
  void refusesToRunWhenProviderIsNotOpenAiCompatible() {
    var properties = properties("http://127.0.0.1:1");
    properties.setProvider("heuristic");

    var client = new OpenAiCompatibleSubmissionTutorAi(properties);

    assertThrows(SubmissionTutorAi.Unavailable.class, () -> client.chat(INPUT));
  }

  /** openai-compatible 的响应外壳，content 里再放真正的回答。 */
  private static String replyWith(String content) {
    var escaped =
        content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    return "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
  }

  private static OpenAiCompatibleSubmissionTutorAi client(String baseUrl) {
    return new OpenAiCompatibleSubmissionTutorAi(properties(baseUrl));
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
