package io.github.dream07code.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class HttpTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpTestSupport() { }

    public static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    public static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
