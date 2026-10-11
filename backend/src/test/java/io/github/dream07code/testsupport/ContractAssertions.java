package io.github.dream07code.testsupport;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Asserts actual HTTP bodies against the reviewed design contract, not generated response examples. */
public final class ContractAssertions {
    private static final OpenAPI API = load();
    private ContractAssertions() { }

    private static OpenAPI load() {
        try (var input = ContractAssertions.class.getResourceAsStream("/api-contract.yaml")) {
            return Yaml.mapper().readValue(input, OpenAPI.class);
        } catch (Exception exception) { throw new IllegalStateException("Test contract unavailable"); }
    }

    public static void response(String method, String rawPath, HttpResponse<String> response) {
        String path = rawPath.split("\\?", 2)[0];
        for (var route : API.getPaths().entrySet()) {
            String pattern = route.getKey().replaceAll("\\{[^}]+}", "[^/]+");
            if (!path.matches(pattern)) continue;
            var operation = route.getValue().readOperationsMap().entrySet().stream()
                    .filter(e -> e.getKey().name().equalsIgnoreCase(method)).map(Map.Entry::getValue).findFirst().orElse(null);
            if (operation == null) return;
            var expected = operation.getResponses().get(Integer.toString(response.statusCode()));
            assertNotNull(expected, "Unexpected HTTP status " + response.statusCode() + " for " + method + " " + path);
            if (expected.get$ref() != null) expected = API.getComponents().getResponses().get(last(expected.get$ref()));
            validate(expected.getContent().get("application/json").getSchema(), HttpTestSupport.json(response), "$ " + path);
            return;
        }
    }

    private static String last(String ref) { return ref.substring(ref.lastIndexOf('/') + 1); }

    @SuppressWarnings("unchecked")
    private static void validate(Schema<?> schema, JsonNode value, String location) {
        if (schema.get$ref() != null) schema = API.getComponents().getSchemas().get(last(schema.get$ref()));
        assertNotNull(schema, location);
        if (value.isNull()) {
            assertEquals(Boolean.TRUE, schema.getNullable(), "Unexpected null " + location);
            return;
        }
        if (schema.getEnum() != null) {
            assertTrue(schema.getEnum().stream().anyMatch(x -> x != null && x.toString().equals(value.isString() ? value.asText() : value.toString())), "Enum " + location);
        }
        if ("object".equals(schema.getType())) {
            assertTrue(value.isObject(), location);
            if (schema.getRequired() != null) for (String key : schema.getRequired()) assertTrue(value.has(key), "Missing " + location + "." + key);
            if (schema.getProperties() != null) for (var property : schema.getProperties().entrySet()) {
                if (value.has(property.getKey())) validate(property.getValue(), value.path(property.getKey()), location + "." + property.getKey());
            }
        } else if ("array".equals(schema.getType())) {
            assertTrue(value.isArray(), location);
            for (var item : value) validate(schema.getItems(), item, location + "[]");
        } else if ("string".equals(schema.getType())) {
            assertTrue(value.isString(), location);
            if (schema.getPattern() != null) assertTrue(value.asText().matches(schema.getPattern()), "Pattern " + location);
            if ("date-time".equals(schema.getFormat())) assertDoesNotThrow(() -> OffsetDateTime.parse(value.asText()), location);
        } else if ("integer".equals(schema.getType())) {
            assertTrue(value.isIntegralNumber(), location);
            if (schema.getMinimum() != null) assertTrue(value.asLong() >= schema.getMinimum().longValue(), location);
            if (schema.getMaximum() != null) assertTrue(value.asLong() <= schema.getMaximum().longValue(), location);
        } else if ("boolean".equals(schema.getType())) assertTrue(value.isBoolean(), location);
    }
}
