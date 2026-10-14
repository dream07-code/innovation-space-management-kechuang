package io.github.dream07code.innovationspace.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SessionServiceTest {
    @Test void sessionDTOIsRedactedAndPrincipalDoesNotCarryCredentials(){
        var data=new SessionService.IssuedSession("synthetic-access","synthetic-refresh",900,604800L,false,1);
        assertFalse(data.toString().contains("synthetic"));
        var principal=new SessionPrincipal(1,null,"COLLEGE_ADMIN",java.util.List.of(),false,1,1);
        assertFalse(principal.toString().contains("password"));
    }
}
