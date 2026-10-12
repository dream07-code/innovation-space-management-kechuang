package io.github.dream07code.innovationspace.integration;

import com.sun.net.httpserver.HttpServer;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenSourceOjAdapterIT {
  @Test
  void judge0AdapterMapsStatusesAndNeverReturnsRawCodeOrProviderOutput() throws Exception {
    var responses = new ArrayDeque<String>();
    responses.add(response(3, "Accepted", "secret stdout", "secret stderr"));
    responses.add(response(3, "Accepted", "secret stdout", "secret stderr"));
    var request = new AtomicReference<String>();
    var auth = new AtomicReference<String>();
    var server = start(responses, request, auth);
    try {
      var properties = new Judge0OjProperties();
      properties.setProvider("judge0");
      properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      properties.setApiToken("synthetic-oj-token");
      properties.setConnectTimeoutMs(1000);
      properties.setRequestTimeoutMs(2000);
      var adapter = new Judge0OjAdapter(properties);
      var result =
          adapter.judge(
              new OjSubmission(
                  "java",
                  "class Main { secret-code }",
                  java.util.List.of(
                      new OjSubmission.TestCase("1", "one"),
                      new OjSubmission.TestCase("2", "two"))));
      assertEquals(OjVerdict.ACCEPTED, result.verdict());
      assertEquals(2, result.passedTestCases());
      assertEquals(2, result.totalTestCases());
      assertTrue(result.safeError() == null || !result.safeError().contains("secret"));
      assertFalse(String.valueOf(result).contains("secret-code"));
      assertFalse(String.valueOf(result).contains("secret stdout"));
      assertFalse(String.valueOf(result).contains("secret stderr"));
      assertTrue(request.get().contains("\"source_code\""));
      assertFalse(request.get().contains("synthetic-oj-token"));
      assertEquals("synthetic-oj-token", auth.get());
      } finally {
        server.stop(0);
      }
  }

  @Test
  void judge0AdapterMapsFailuresToSafeVerdicts() throws Exception {
    for (var expected :
        java.util.List.of(
            new Object[] {4, OjVerdict.WRONG_ANSWER},
            new Object[] {5, OjVerdict.TIME_LIMIT_EXCEEDED},
            new Object[] {6, OjVerdict.COMPILATION_ERROR},
            new Object[] {7, OjVerdict.RUNTIME_ERROR},
            new Object[] {15, OjVerdict.MEMORY_LIMIT_EXCEEDED},
            new Object[] {13, OjVerdict.INTERNAL_ERROR})) {
      var responses = new ArrayDeque<String>();
      responses.add(response((Integer) expected[0], "synthetic status", "raw output", "raw error"));
      var server = start(responses, new AtomicReference<>(), new AtomicReference<>());
      try {
        var properties = new Judge0OjProperties();
        properties.setProvider("judge0");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeoutMs(1000);
        properties.setRequestTimeoutMs(2000);
        var result =
            new Judge0OjAdapter(properties)
                .judge(
                    new OjSubmission(
                        "java",
                        "class Main {}",
                        java.util.List.of(new OjSubmission.TestCase("", "x"))));
        assertEquals(expected[1], result.verdict());
        assertFalse(String.valueOf(result).contains("raw output"));
        assertFalse(String.valueOf(result).contains("raw error"));
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void malformedAndUnavailableResponsesBecomeInternalError() throws Exception {
    var responses = new ArrayDeque<String>();
    responses.add("{not-json");
    var server = start(responses, new AtomicReference<>(), new AtomicReference<>());
      try {
      var properties = new Judge0OjProperties();
      properties.setProvider("judge0");
      properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      properties.setConnectTimeoutMs(1000);
      properties.setRequestTimeoutMs(2000);
      var result =
          new Judge0OjAdapter(properties)
              .judge(
                  new OjSubmission(
                      "java", "class Main {}", java.util.List.of(new OjSubmission.TestCase("", "x"))));
      assertEquals(OjVerdict.INTERNAL_ERROR, result.verdict());
      assertNotNull(result.safeError());
      assertFalse(result.safeError().contains("{not-json"));
      } finally {
        server.stop(0);
      }
  }

  private static String response(int status, String description, String stdout, String stderr) {
    return "{\"status\":{\"id\":" + status + ",\"description\":\"" + description + "\"},"
        + "\"stdout\":\"" + stdout + "\",\"stderr\":\"" + stderr + "\",\"time\":\"0.01\",\"memory\":1024}";
  }

  private static HttpServer start(
      Deque<String> responses, AtomicReference<String> request, AtomicReference<String> auth)
      throws IOException {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/submissions",
        exchange -> {
          request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          auth.set(exchange.getRequestHeaders().getFirst("X-Auth-Token"));
          byte[] body =
              (responses.isEmpty() ? "{}" : responses.removeFirst()).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    return server;
  }
}
