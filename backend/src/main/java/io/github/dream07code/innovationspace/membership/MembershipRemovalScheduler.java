package io.github.dream07code.innovationspace.membership;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MembershipRemovalScheduler {
  private final MembershipRemovalService service;
  private final MembershipRemovalProperties properties;

  public MembershipRemovalScheduler(
      MembershipRemovalService service, MembershipRemovalProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${app.membership.removal-check-interval-ms:3600000}")
  public void poll() {
    if (properties.isAutoRemovalEnabled()) service.removeDueMembers();
  }
}
