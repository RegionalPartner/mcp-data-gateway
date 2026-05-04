package io.ancoris.mcp.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Counts every {@code @Tool} invocation and labels it by tool name and outcome
 * ({@code ok} / {@code error}). MCP routes all tool traffic through a single
 * URL, so the generic Spring web HTTP timer cannot disambiguate which tool was
 * called — this aspect closes that gap.
 *
 * <p>Ordering: {@link Ordered#LOWEST_PRECEDENCE} — runs <em>after</em>
 * {@link ToolAllowlistAspect} (HIGHEST) and after {@link RlsContextAspect} so
 * the counter only increments for invocations that actually entered tool code.
 * Allowlist denials surface in audit logs, not in this counter.
 *
 * <p>Cardinality discipline: never add tenant identifiers as labels
 * ({@code workspace_id} / {@code api_key_id} / {@code mcp_client_id}). Per-tenant
 * slicing belongs in the audit pipeline, not in Prometheus. Tool name is bounded
 * by code (one tag value per @Tool method), outcome is binary — both safe.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ToolMetricsAspect {

    private static final String METRIC_NAME = "mcp.tool.calls";

    private final MeterRegistry registry;

    public ToolMetricsAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object recordToolCall(ProceedingJoinPoint pjp) throws Throwable {
        String toolName = ToolAllowlistAspect.resolveToolName(pjp);
        String outcome = "ok";
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            outcome = "error";
            throw t;
        } finally {
            registry.counter(METRIC_NAME, Tags.of("tool", toolName, "outcome", outcome))
                    .increment();
        }
    }
}
