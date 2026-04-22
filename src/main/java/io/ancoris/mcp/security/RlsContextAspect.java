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
 * SEC-RLS: injects two PostgreSQL session variables before every @Tool method so that
 * Row-Level Security policies can enforce access control at the database layer,
 * independently of the application-level filters in PostgresConnector and
 * DocumentSearchTool.
 *
 * <p>Variables set per transaction:
 * <ul>
 *   <li>{@code app.mcp_role} — {@code "READ_ONLY"} or {@code "ADMIN"} (from enum)</li>
 *   <li>{@code app.mcp_client_id} — the caller's API key UUID (workspace gate)</li>
 * </ul>
 *
 * <p>Injection safety: {@code role} comes from the {@link io.ancoris.mcp.model.AccessRole}
 * enum; {@code clientId} from {@link java.util.UUID#toString()} — both produce only
 * characters safe for SET LOCAL interpolation and never from user-supplied input.
 *
 * <p>Fail-safe: unauthenticated requests default to {@code READ_ONLY} role and the
 * nil UUID as client ID (which resolves to zero workspace rows — fail-closed).
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
        String role     = resolveRole();
        String clientId = resolveClientId();

        // Capture any checked exception thrown by the tool method so we can
        // re-throw it after the TransactionTemplate returns.
        Throwable[] toolException = new Throwable[1];

        Object result = txTemplate.execute(status -> {
            // Both values come from safe sources — see Javadoc for injection safety.
            jdbc.execute("SET LOCAL app.mcp_role = '" + role + "'");
            jdbc.execute("SET LOCAL app.mcp_client_id = '" + clientId + "'");
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

    private String resolveClientId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKey key) {
            return key.getId().toString(); // UUID.toString() — safe to interpolate
        }
        // Fail-closed: nil UUID has no workspace rows → client sees nothing.
        return "00000000-0000-0000-0000-000000000000";
    }
}
