package io.github.dream07code.innovationspace.member;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MemberScopePolicyTest {
 @Test void adminAllAndLeaderOnlyOwnedDirection(){
  var admin=new SessionPrincipal(1,null,"COLLEGE_ADMIN",java.util.List.of(),false,1,1);MemberScopePolicy.direction(admin,99);
  var leader=new SessionPrincipal(2,2L,"DIRECTION_LEADER",java.util.List.of(1L),false,1,2);MemberScopePolicy.direction(leader,1);
  assertThrows(RuntimeException.class,()->MemberScopePolicy.direction(leader,2));
  assertThrows(RuntimeException.class,()->MemberScopePolicy.staff(new SessionPrincipal(3,3L,"STUDENT",java.util.List.of(),false,1,3)));
 }
}
