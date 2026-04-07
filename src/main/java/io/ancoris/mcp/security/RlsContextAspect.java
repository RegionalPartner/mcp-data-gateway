package io.ancoris.mcp.security;

import io.ancoris.mcp.model.ApiKey;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SEC-RLS: injects a PostgreSQL session variable before every @Tool method so that
 * Row-Level Security policies can enforce access control at the database layer,
 * independently of the application-level filters in PostgresConnector and
 * DocumentSearchTool.
 *
 * <p>Design: {@code SET LOCAL app.mcp_role = '<role>'} is scoped to the current
 * transaction. The {@link TransactionTemplate} (PROPAGATION_REQUIRED) ensures the
 * SET LOCAL and all subsequent SQL in the tool run in the same transaction.
 *
 * <p>Injection safety: the {@code role} value comes exclusively from the
 * {@link io.ancoris.mcp.model.AccessRole} enum — either {@code "READ_ONLY"} or
 * {@code "ADMIN"} — never from user-supplied input. String interpolation into the
 * SET LOCAL statement is therefore safe from SQL injection.
 *
 * <p>Fail-safe: if there is no authenticated principal (should not happen in normal
 * flow, as {@link ApiKeyFilter} rejects unauthenticated requests) the aspect defaults
 * to {@code READ_ONLY} — the least-privilege option.
 */
@Aspect
@Component
public class RlsContextAspect {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    public RlsContextAspect(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object applyRlsRole(ProceedingJoinPoint pjp) throws Throwable {
        String role = resolveRole();

        // Capture any checked exception thrown by the tool method so we can
        // re-throw it after the TransactionTemplate returns.
        Throwable[] toolException = new Throwable[1];

        Object result = txTemplate.execute(status -> {
            // Role comes from an enum — safe to interpolate (not user input).
            jdbc.execute("SET LOCAL app.mcp_role = '" + role + "'");
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                status.setRollbackOnly();
                toolException[0] = t;
                return null;
            }
        });

        if (toolException[0] != null) {
            throw toolException[0];
        }
        return result;
    }

    private String resolveRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKey key) {
            return key.getRole().name();   // "READ_ONLY" or "ADMIN"
        }
        return "READ_ONLY";               // fail-safe: least privilege
    }
}
