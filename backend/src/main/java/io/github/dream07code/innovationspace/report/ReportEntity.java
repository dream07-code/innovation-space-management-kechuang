package io.github.dream07code.innovationspace.report;
public record ReportEntity(Long id, Long memberId, Long directionId, ReportType reportType, java.time.LocalDate reportDate, java.time.LocalDate weekStart, java.time.LocalDate weekEnd, ReportStatus status, long currentVersion, ScoreStatus scoreStatus, Integer finalScore, Integer awardedPoints) { }
