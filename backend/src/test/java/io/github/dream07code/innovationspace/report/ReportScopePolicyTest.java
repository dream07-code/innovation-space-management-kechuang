package io.github.dream07code.innovationspace.report;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReportScopePolicyTest {
 @Test void studentOwnsOnlyTheirReportsAndLeaderCanReadAuthorizedDirection(){
  var student=new SessionPrincipal(1,10L,"STUDENT",java.util.List.of(),false,1,1);
  var leader=new SessionPrincipal(2,20L,"DIRECTION_LEADER",java.util.List.of(3L),false,1,2);
  assertTrue(ReportScopePolicy.canAccess(student,10L,3L,true));assertFalse(ReportScopePolicy.canAccess(student,11L,3L,false));
  assertTrue(ReportScopePolicy.canAccess(leader,11L,3L,false));assertFalse(ReportScopePolicy.canAccess(leader,11L,4L,false));
 }
 @Test void contentMustContainMandatoryFieldsOnlyWhenSubmitted(){
  assertTrue(ReportScopePolicy.validContent(ReportType.DAILY,java.util.Map.of(),false));
  assertFalse(ReportScopePolicy.validContent(ReportType.DAILY,java.util.Map.of(),true));
  assertTrue(ReportScopePolicy.validContent(ReportType.DAILY,java.util.Map.of("learningContent","learn","gains","gain"),true));
  assertFalse(ReportScopePolicy.validContent(ReportType.WEEKLY,java.util.Map.of("summary"," ","achievements","done"),true));
 }
}
