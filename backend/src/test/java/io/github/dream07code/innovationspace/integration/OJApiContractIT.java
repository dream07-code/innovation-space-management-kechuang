package io.github.dream07code.innovationspace.integration;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OJApiContractIT {
  @Test
  void plannedOjAssessmentContractSupportsFrontendParallelDevelopment() throws Exception {
    OpenAPI api;
    try (var stream = getClass().getResourceAsStream("/api-contract.yaml")) {
      api = Yaml.mapper().readValue(stream, OpenAPI.class);
    }

    for (String path : new String[] {
        "/api/v1/assessments",
        "/api/v1/assessments/{assessmentId}",
        "/api/v1/assessments/{assessmentId}/publish",
        "/api/v1/assessments/{assessmentId}/close",
        "/api/v1/assessment-problems",
        "/api/v1/assessment-problems/import",
        "/api/v1/assessment-problems/{problemId}/practice-test-cases",
        "/api/v1/assessment-problems/{problemId}/practice-run",
        "/api/v1/assessment-problems/{problemId}/trial-run",
        "/api/v1/assessment-problems/{problemId}",
        "/api/v1/assessment-problems/{problemId}/test-cases",
        "/api/v1/assessment-problems/{problemId}/retire",
        "/api/v1/assessments/{assessmentId}/submissions",
        "/api/v1/assessment-submissions",
        "/api/v1/assessment-submissions/{submissionId}",
        "/api/v1/assessment-submissions/{submissionId}/code",
        "/api/v1/assessment-submissions/{submissionId}/reviews",
        "/api/v1/assessments/{assessmentId}/results"
    }) {
      assertNotNull(api.getPaths().get(path), path);
      assertTrue(
          api.getPaths().get(path).readOperations().stream()
              .allMatch(operation -> "planned".equals(operation.getExtensions().get("x-implementation-status"))),
          path);
    }

    for (String schema : new String[] {
        "Assessment",
        "AssessmentProblem",
        "AssessmentSubmission",
        "OjJudgeResult",
        "OjTestCaseResult",
        "OjSampleCase",
        "AssessmentReview",
        "AssessmentResult",
        "CreateAssessmentRequest",
        "UpdateAssessmentRequest",
        "CreateAssessmentProblemRequest",
        "UpdateAssessmentProblemRequest",
        "ImportAssessmentProblemsRequest",
        "AssessmentProblemImportResponse",
        "AssessmentProblemImportResult",
        "AssessmentProblemImportError",
        "AssessmentProblemTestCaseInput",
        "AssessmentProblemTestCase",
        "AssessmentProblemTestCasesResponse",
        "AssessmentProblemPracticeTestCasesResponse",
        "CreateOjPracticeRunRequest",
        "OjPracticeTestCaseResult",
        "OjPracticeRunResult",
        "OjPracticeRunResponse",
        "CreateAssessmentSubmissionRequest",
        "CreateAssessmentReviewRequest",
        "AssessmentResponse",
        "AssessmentPageResponse",
        "AssessmentProblemResponse",
        "AssessmentProblemPageResponse",
        "AssessmentSubmissionResponse",
        "AssessmentSubmissionPageResponse",
        "AssessmentReviewPageResponse",
        "AssessmentResultPageResponse"
    }) {
      assertNotNull(api.getComponents().getSchemas().get(schema), schema);
    }

    var createProblem = api.getComponents().getSchemas().get("CreateAssessmentProblemRequest");
    assertTrue(createProblem.getRequired().contains("assessmentTestCases"));
    assertTrue(createProblem.getProperties().containsKey("practiceTestCases"));
    var problemTestCases = api.getComponents().getSchemas().get("AssessmentProblemTestCasesResponse");
    var problemTestCasesData =
        ((io.swagger.v3.oas.models.media.ObjectSchema)
                problemTestCases.getProperties().get("data"))
            .getRequired();
    assertTrue(problemTestCasesData.contains("assessmentTestCases"));
    assertTrue(problemTestCasesData.contains("practiceTestCases"));

    var submission = api.getComponents().getSchemas().get("AssessmentSubmission");
    assertTrue(submission.getProperties().containsKey("id"));
    assertEquals("string", ((io.swagger.v3.oas.models.media.StringSchema) submission.getProperties().get("id")).getType());
    var judge = api.getComponents().getSchemas().get("OjJudgeResult");
    assertTrue(judge.getProperties().containsKey("testCaseResults"));
    assertFalse(judge.getProperties().containsKey("input"));
    assertFalse(judge.getProperties().containsKey("expectedOutput"));
    assertFalse(judge.getProperties().containsKey("stdout"));
    assertFalse(judge.getProperties().containsKey("stderr"));

    assertFalse(submission.getProperties().containsKey("code"));
    assertFalse(submission.getProperties().containsKey("privateTestData"));
    assertTrue(api.getComponents().getSchemas().get("CreateAssessmentSubmissionRequest").getProperties().containsKey("code"));

    var result = api.getComponents().getSchemas().get("AssessmentResult");
    assertTrue(result.getProperties().containsKey("autoScore"));
    assertFalse(result.getProperties().containsKey("aiScore"));
    assertTrue(result.getProperties().containsKey("humanScore"));
    assertTrue(result.getProperties().containsKey("finalScore"));
    assertFalse(result.getProperties().containsKey("points"));

    var cwd = Path.of("").toAbsolutePath();
    var root = cwd.endsWith("backend") ? cwd.getParent() : cwd;
    assertEquals(-1, Files.mismatch(root.resolve("docs/api/openapi.yaml"), root.resolve("backend/src/main/resources/api-contract.yaml")));
  }
}
