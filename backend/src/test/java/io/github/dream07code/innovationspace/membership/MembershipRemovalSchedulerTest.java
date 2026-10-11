package io.github.dream07code.innovationspace.membership;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class MembershipRemovalSchedulerTest {
  @Test
  void pollCallsServiceOnlyWhenEnabled() {
    var service = mock(MembershipRemovalService.class);
    var properties = new MembershipRemovalProperties();
    properties.setAutoRemovalEnabled(false);
    new MembershipRemovalScheduler(service, properties).poll();
    verifyNoInteractions(service);

    properties.setAutoRemovalEnabled(true);
    new MembershipRemovalScheduler(service, properties).poll();
    verify(service, times(1)).removeDueMembers();
  }
}
