package io.ancoris.mcp.security;

import io.ancoris.mcp.model.ApiKey;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SEC-RLS: injects two PostgreSQL session variables before every @Tool method so that
 * Row-Level Security policies can enforce access control at the database layer,
 * independently of the application-level filters in PostgresConnector and
 * DocumentSearchTool.
 *
 * <p>Variables set per transaction (via {@code set_config(key, value, true)} — local to tx):
 * <ul>
 *   <li>{@code app.mcp_role} — {@code "READ_ONLY"} or {@code "ADMIN"} (from enum)</li>
 *   <li>{@code app.mcp_client_id} — the caller's API key UUID (workspace gate)</li>
 * </ul>
 *
 * <p>Injection safety: both values use bind parameters in {@code set_config} — not string
 * interpolation — providing defence-in-depth against any hypothetical injection via the
 * role or UUID fields.
 *
 * <p>Connection-pool defence: an {@code afterCompletion} synchronization resets both
 * settings to their database defaults after each transaction (commit or rollback), so a
 * recycled connection from the HikariCP pool never leaks a previous caller's context.
 * HikariCP is also configured with {@code connection-init-sql: "RESET ALL"} as a
 * belt-and-suspenders fallback.
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
            // Use set_config with bind parameters — defence-in-depth against injection.
            // The third argument 'true' makes the setting LOCAL to this transaction,
            // equivalent to SET LOCAL, so it is automatically reverted on commit/rollback.
            // Use execute(PreparedStatementCallback) because set_config returns a result;
            // JdbcTemplate.update() would throw "A result was returned when none was expected."
            setConfig("app.mcp_role", role);
            setConfig("app.mcp_client_id", clientId);

            // Register an afterCompletion hook to explicitly RESET both settings once
            // the transaction ends.  This guards against connection-pool reuse in the
            // unlikely event that SET LOCAL does not revert (e.g. savepoint edge cases
            // or future pgvector connection poolers that replay state).
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int transactionStatus) {
                                try {
                                    jdbc.execute("RESET app.mcp_role");
                                    jdbc.execute("RESET app.mcp_client_id");
                                } catch (Exception ignored) {
                                    // Best-effort reset — connection may already be closed.
                                    // HikariCP connection-init-sql RESET ALL is the final backstop.
                                }
                            }
                        });
            }

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

    /**
     * Calls {@code set_config(key, value, true)} via a PreparedStatement callback.
     * {@code set_config} returns the value it just set (a SELECT-like function), so
     * {@code JdbcTemplate.update()} would throw "A result was returned when none was
     * expected." Using {@code execute(PreparedStatementCallback)} sidesteps that by
     * calling {@code ps.execute()} rather than {@code ps.executeUpdate()}.
     */
    private void setConfig(String key, String value) {
        jdbc.execute(
                "SELECT set_config(?, ?, true)",
                (PreparedStatementCallback<Void>) ps -> {
                    ps.setString(1, key);
                    ps.setString(2, value);
                    ps.execute();
                    return null;
                });
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
            return key.getId().toString(); // UUID.toString() — safe value
        }
        // Fail-closed: nil UUID has no workspace rows → client sees nothing.
        return "00000000-0000-0000-0000-000000000000";
    }
}
