package io.github.dream07code.innovationspace.report;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

public final class ReportScopePolicy {
    private ReportScopePolicy() {}
    public static boolean canAccess(SessionPrincipal p,long memberId,long directionId,boolean draft){
        if(p.memberId()!=null&&p.memberId()==memberId)return true;
        if(draft)return false;
        return "COLLEGE_ADMIN".equals(p.role())||("DIRECTION_LEADER".equals(p.role())&&p.managedDirectionIds().contains(directionId));
    }
    public static boolean validContent(ReportType type,Map<String,Object> content,boolean submitted){
        if(!submitted)return true;String a=type==ReportType.DAILY?"learningContent":"summary",b=type==ReportType.DAILY?"gains":"achievements";return nonBlank(content.get(a))&&nonBlank(content.get(b));
    }
    private static boolean nonBlank(Object v){return v instanceof String s&&!s.isBlank();}
    public static void requireOwner(SessionPrincipal p,long memberId){if(p.memberId()==null||p.memberId()!=memberId)throw new ApiException(403,ErrorCode.FORBIDDEN);}
}
