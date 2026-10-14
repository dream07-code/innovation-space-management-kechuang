package io.github.dream07code.innovationspace.points;
public record PointLedgerEntity(Long id, Long memberId, Long reportId, Long reportVersionId, int delta, String source, java.time.Instant businessEffectiveAt, String ruleVersion, String eventKey) { }
