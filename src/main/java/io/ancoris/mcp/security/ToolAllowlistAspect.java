package io.ancoris.mcp.security;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.model.ApiKey;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * D5: enforces the per-API-key tool allowlist <em>before</em>
 * {@link RlsContextAspect} opens a transaction and before any tool body runs.
 *
 * <p>Ordering is load-bearing: {@link Ordered#HIGHEST_PRECEDENCE} must run first so a
 * denied tool never reaches the database. If this aspect were later than RlsContextAspect
 * we'd have already started a tx and paid the {@code set_config} round-trip before
 * returning a denial.
 *
 * <p>Null {@link ApiKey#getAllowedTools()} is treated as <em>unrestricted</em>
 * (backward-compat default for keys issued before D5). Non-null but empty set
 * denies every tool (intentional — operators can use {@code []} as a lock-down).
 *
 * <p>Unauthenticated invocations are allowed through here — {@link ApiKeyFilter}
 * already rejects anonymous traffic at the servlet layer. Keeping this aspect
 * permissive when auth is absent avoids accidental lock-out of the
 * {@code /actuator/health} path.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ToolAllowlistAspect {

    private final AuditService auditService;

    public ToolAllowlistAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object enforceAllowlist(ProceedingJoinPoint pjp) throws Throwable {
        String toolName = resolveToolName(pjp);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKey key) {
            Set<String> allowed = key.getAllowedTools();
            if (allowed != null && !allowed.contains(toolName)) {
                auditService.log(
                        "tool_denied",
                        key.getId(),
                        Map.of("tool", toolName, "reason", "allowlist_reject"),
                        "denied");
                throw new ToolInputRejectedException(
                        "tool '" + toolName + "' not in API key allowlist");
            }
        }
        return pjp.proceed();
    }

    /**
     * Extracts the {@code name} attribute from the method's {@link Tool} annotation;
     * falls back to the bare method name when the annotation omits {@code name()}
     * (which Spring AI treats as "use the method name" for tool discovery).
     */
    static String resolveToolName(ProceedingJoinPoint pjp) {
        if (pjp.getSignature() instanceof MethodSignature ms) {
            Method method = ms.getMethod();
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                String name = annotation.name();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
            return method.getName();
        }
        return pjp.getSignature().getName();
    }
}
