package io.github.dream07code.innovationspace.report;
public record ScoringTaskEntity(Long id, Long reportId, Long reportVersionId, ScoreStatus status, String provider, String model, int attempt, Integer score, String reason) { }
