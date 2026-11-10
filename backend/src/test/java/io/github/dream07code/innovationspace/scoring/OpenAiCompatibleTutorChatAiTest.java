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

/** 用本地 HTTP 服务模拟 openai-compatible 端点，验证通用助教客户端的请求体、解析与失败处理。 */
class OpenAiCompatibleTutorChatAiTest {
  private HttpServer server;

  private static final TutorChatAi.ChatInput INPUT =
      new TutorChatAi.ChatInput(
          new TutorChatAi.Problem(
              "统计质数个数",
              "MEDIUM",
              "给定 n，返回小于 n 的质数个数。",
              "一个整数 n",
              "一个整数",
              "数学,素数",
              "countPrimes",
              "int",
              "int",
              "python"),
          List.of(
              new TutorChatAi.Turn("user", "埃氏筛为什么从 i*i 开始？"),
              new TutorChatAi.Turn("assistant", "因为更小的倍数已经被更小的质数筛过了。")),
          "那复杂度是多少？");

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void sendsSelectedProblemAndHistoryAsSeparateMessages() {
    var answer = "从 i*i 开始是因为 i 的 2..i-1 倍都已被更小的质数标记过，复杂度 O(n log log n)。";
    var seen = new AtomicReference<String>();
    String baseUrl = start(200, replyWith(answer), seen);

    var reply = client(baseUrl).chat(INPUT);

    assertEquals(answer, reply.content());
    assertEquals("mimo-v2.6-flash", reply.model());

    String body = seen.get();
    assertTrue(body.contains("统计质数个数"), "选中题目必须进提示词");
    assertTrue(body.contains("countPrimes(int) -> int"), "函数签名要进提示词");
    assertTrue(body.contains("埃氏筛为什么从 i*i 开始？"), "历史对话必须进提示词");
    assertTrue(body.contains("那复杂度是多少？"), "本次问题必须进提示词");
    assertTrue(body.contains("不可信"), "题面必须标注为不可信输入");
    assertTrue(body.contains("不要给可直接提交的完整答案"), "必须明确禁止输出完整答案");
  }

  @Test
  void keepsMessagesAsSystemThenHistoryThenQuestion() {
    String baseUrl = start(200, replyWith("好的"), new AtomicReference<>());

    var messages = client(baseUrl).messagesFor(INPUT);

    assertEquals("system", messages.getFirst().get("role"));
    assertEquals("user", messages.get(1).get("role"));
    assertEquals("assistant", messages.get(2).get("role"));
    assertEquals("user", messages.getLast().get("role"));
    assertEquals("那复杂度是多少？", messages.getLast().get("content"));
  }

  @Test
  void worksWithoutSelectedProblem() {
    var input =
        new TutorChatAi.ChatInput(null, List.of(), "什么是时间复杂度？");
    String baseUrl = start(200, replyWith("衡量算法随输入规模增长的趋势。"), new AtomicReference<>());

    var reply = client(baseUrl).chat(input);

    assertEquals("衡量算法随输入规模增长的趋势。", reply.content());
  }

  @Test
  void stripsMarkdownFenceFromAnswer() {
    String baseUrl = start(200, replyWith("```\n围栏里的回答\n```"), new AtomicReference<>());

    assertEquals("围栏里的回答", client(baseUrl).chat(INPUT).content());
  }

  @Test
  void failsInsteadOfInventingAnswerWhenProviderErrors() {
    String baseUrl = start(500, "{}", new AtomicReference<>());

    assertThrows(TutorChatAi.Unavailable.class, () -> client(baseUrl).chat(INPUT));
  }

  @Test
  void failsWhenModelReturnsEmptyContent() {
    String baseUrl = start(200, replyWith("   "), new AtomicReference<>());

    assertThrows(TutorChatAi.Unavailable.class, () -> client(baseUrl).chat(INPUT));
  }

  @Test
  void refusesToRunWhenProviderIsNotOpenAiCompatible() {
    var properties = properties("http://127.0.0.1:1");
    properties.setProvider("heuristic");

    var client = new OpenAiCompatibleTutorChatAi(properties);

    assertThrows(TutorChatAi.Unavailable.class, () -> client.chat(INPUT));
  }

  /** openai-compatible 的响应外壳，content 里再放真正的回答。 */
  private static String replyWith(String content) {
    var escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    return "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
  }

  private static OpenAiCompatibleTutorChatAi client(String baseUrl) {
    return new OpenAiCompatibleTutorChatAi(properties(baseUrl));
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
