package io.github.dream07code.innovationspace.integration;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.core.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardApiContractIT {
  @Test
  void plannedRoleDashboardContractsAreScopedSafeAndFrontendReady() throws Exception {
    OpenAPI api;
    try (var stream = getClass().getResourceAsStream("/api-contract.yaml")) {
      api = Yaml.mapper().readValue(stream, OpenAPI.class);
    }

    var expectedRoles = Map.of(
        "/api/v1/dashboard/admin", Set.of("COLLEGE_ADMIN"),
        "/api/v1/dashboard/leader", Set.of("DIRECTION_LEADER"),
        "/api/v1/dashboard/student", Set.of("STUDENT"));
    for (var entry : expectedRoles.entrySet()) {
      var item = api.getPaths().get(entry.getKey());
      assertNotNull(item, entry.getKey());
      var operation = item.getGet();
      assertNotNull(operation, entry.getKey());
      assertEquals("planned", operation.getExtensions().get("x-implementation-status"));
      assertEquals(entry.getValue(), Set.copyOf((java.util.Collection<?>) operation.getExtensions().get("x-roles")));
      assertTrue(operation.getParameters().stream().anyMatch(parameter ->
          "range".equals(parameter.getName())
              && parameter.getSchema().getEnum().equals(java.util.List.of("7d", "30d"))));
      assertNotNull(operation.getResponses().get("400"));
    }

    for (String schema : new String[] {
        "AdminDashboard",
        "LeaderDashboard",
        "StudentDashboard",
        "AdminDashboardOverview",
        "LeaderDashboardOverview",
        "StudentDashboardOverview",
        "DashboardTrendPoint",
        "DirectionDashboardRow",
        "StudentDashboardRow",
        "AttentionItem",
        "RecentActivityItem",
        "StudentProgress",
        "StudentReportSummary",
        "StudentAssessmentSummary",
        "PointEntrySummary",
        "StudentRankingPreview",
        "AdminDashboardResponse",
        "LeaderDashboardResponse",
        "StudentDashboardResponse"
    }) {
      assertNotNull(api.getComponents().getSchemas().get(schema), schema);
    }

    assertExactProperties(api, "AdminDashboard",
        Set.of("range", "overview", "trends", "directions", "attention", "recentActivity"));
    assertExactProperties(api, "LeaderDashboard",
        Set.of("range", "overview", "students", "trends", "attention", "rankingPreview"));
    assertExactProperties(api, "StudentDashboard",
        Set.of("range", "overview", "progress", "reports", "assessments", "points", "rankingPreview"));

    var trend = api.getComponents().getSchemas().get("DashboardTrendPoint");
    var trendDate = (io.swagger.v3.oas.models.media.Schema<?>) trend.getProperties().get("date");
    assertEquals("string", trendDate.getType());
    assertEquals("date", trendDate.getFormat());
    assertExactProperties(api, "DashboardTrendPoint",
        Set.of("date", "dailySubmitted", "dailyScored", "pointsAdded"));

    var attention = api.getComponents().getSchemas().get("AttentionItem");
    assertExactProperties(api, "AttentionItem",
        Set.of("type", "memberId", "displayName", "directionName", "occurredAt", "status"));
    for (String forbidden : new String[] {"studentNo", "contact", "content", "code"}) {
      assertFalse(attention.getProperties().containsKey(forbidden), forbidden);
    }

    var studentRow = api.getComponents().getSchemas().get("StudentDashboardRow");
    assertEquals("string",
        ((io.swagger.v3.oas.models.media.StringSchema) studentRow.getProperties().get("memberId")).getType());
    assertFalse(studentRow.getProperties().containsKey("studentNo"));
    assertFalse(studentRow.getProperties().containsKey("contact"));

    var cwd = Path.of("").toAbsolutePath();
    var root = cwd.endsWith("backend") ? cwd.getParent() : cwd;
    assertEquals(-1, Files.mismatch(
        root.resolve("docs/api/openapi.yaml"),
        root.resolve("backend/src/main/resources/api-contract.yaml")));
  }

  private static void assertExactProperties(OpenAPI api, String schemaName, Set<String> expected) {
    var schema = api.getComponents().getSchemas().get(schemaName);
    assertEquals(expected, schema.getProperties().keySet(), schemaName);
    assertEquals(Boolean.FALSE, schema.getAdditionalProperties(), schemaName);
  }
}
