package io.github.dream07code.innovationspace.membership;
import io.github.dream07code.innovationspace.service.MembershipRemovalService;

import io.github.dream07code.innovationspace.config.DatabaseSchemaGuard;
import io.github.dream07code.innovationspace.config.SchemaUnavailableException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MembershipRemovalScheduler {
  private final MembershipRemovalService service;
  private final MembershipRemovalProperties properties;
  private final DatabaseSchemaGuard schema;

  public MembershipRemovalScheduler(
      MembershipRemovalService service,
      MembershipRemovalProperties properties,
      DatabaseSchemaGuard schema) {
    this.service = service;
    this.properties = properties;
    this.schema = schema;
  }

  @Scheduled(fixedDelayString = "${app.membership.removal-check-interval-ms:3600000}")
  public void poll() {
    if (!properties.isAutoRemovalEnabled()) return;
    try {
      schema.requireCurrentSchema();
      service.removeDueMembers();
    } catch (SchemaUnavailableException unavailable) {
      // Unmigrated startup is healthy to remain quiet; the next poll retries after migration.
    }
  }
}
