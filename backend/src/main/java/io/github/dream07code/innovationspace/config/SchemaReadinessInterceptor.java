package io.github.dream07code.innovationspace.config;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
@Component
public class SchemaReadinessInterceptor implements HandlerInterceptor {
    private final DatabaseSchemaGuard guard;
    public SchemaReadinessInterceptor(DatabaseSchemaGuard guard){this.guard=guard;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
        if(request.getRequestURI().startsWith("/api/v1/") && handler instanceof HandlerMethod) guard.requireCurrentSchema(); return true;
    }
}
