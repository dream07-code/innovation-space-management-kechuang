package io.github.dream07code.innovationspace.security;

import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

public final class RequestDigest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private RequestDigest() {}

  public static String digest(String json) {
    try {
      var node = JSON.readTree(json);
      var normalized = JSON.writeValueAsString(canonical(node));
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  private static Object canonical(tools.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      var result = new java.util.TreeMap<String, Object>();
      for (var entry : node.properties()) result.put(entry.getKey(), canonical(entry.getValue()));
      return result;
    }
    if (node.isArray()) {
      var result = new java.util.ArrayList<Object>();
      for (var item : node) result.add(canonical(item));
      return result;
    }
    return node;
  }
}
