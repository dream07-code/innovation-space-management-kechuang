package io.github.dream07code.innovationspace.membership;
import io.github.dream07code.innovationspace.service.MembershipRemovalService;

import io.github.dream07code.innovationspace.config.DatabaseSchemaGuard;
import io.github.dream07code.innovationspace.config.SchemaUnavailableException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class MembershipRemovalSchedulerTest {
  @Test
  void pollCallsServiceOnlyWhenEnabled() {
    var service = mock(MembershipRemovalService.class);
    var properties = new MembershipRemovalProperties();
    var schema = mock(DatabaseSchemaGuard.class);
    properties.setAutoRemovalEnabled(false);
    new MembershipRemovalScheduler(service, properties, schema).poll();
    verifyNoInteractions(service);

    properties.setAutoRemovalEnabled(true);
    new MembershipRemovalScheduler(service, properties, schema).poll();
    verify(service, times(1)).removeDueMembers();

    doThrow(new SchemaUnavailableException()).when(schema).requireCurrentSchema();
    new MembershipRemovalScheduler(service, properties, schema).poll();
    verify(service, times(1)).removeDueMembers();
  }
}
