package io.github.dream07code.innovationspace.report;
public record ReportVersionEntity(Long id, Long reportId, long version, String contentJson, byte[] contentHash, Long editedBy) { }
