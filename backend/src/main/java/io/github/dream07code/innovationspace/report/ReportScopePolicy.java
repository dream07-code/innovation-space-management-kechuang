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
    public static boolean validContent(ReportType type, Map<String, Object> content, boolean submitted) {
        if (!submitted) return true;
        if (type == ReportType.DAILY) return nonBlank(content.get("learningContent"));
        return nonBlank(content.get("summary")) && nonBlank(content.get("achievements"));
    }
    private static boolean nonBlank(Object v){return v instanceof String s&&!s.isBlank();}
    public static void requireOwner(SessionPrincipal p,long memberId){if(p.memberId()==null||p.memberId()!=memberId)throw new ApiException(403,ErrorCode.FORBIDDEN);}
}
