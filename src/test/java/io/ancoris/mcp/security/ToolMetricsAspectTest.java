package io.ancoris.mcp.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolMetricsAspectTest {

    @Mock
    private ProceedingJoinPoint pjp;

    @Mock
    private MethodSignature methodSignature;

    private SimpleMeterRegistry registry;
    private ToolMetricsAspect aspect;

    @SuppressWarnings("unused")
    static class Fixture {
        @Tool(name = "search_documents")
        public String searchDocuments() { return "ok"; }

        @Tool
        public String listSources() { return "ok"; }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aspect = new ToolMetricsAspect(registry);
    }

    @Test
    void recordToolCall_success_incrementsOkCounter() throws Throwable {
        configureSignatureForMethod("searchDocuments");
        when(pjp.proceed()).thenReturn("result");

        Object result = aspect.recordToolCall(pjp);

        assertThat(result).isEqualTo("result");
        assertThat(counterCount("search_documents", "ok")).isEqualTo(1.0);
        assertThat(counterCount("search_documents", "error")).isEqualTo(0.0);
    }

    @Test
    void recordToolCall_thrown_incrementsErrorCounterAndRethrows() throws Throwable {
        configureSignatureForMethod("searchDocuments");
        when(pjp.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.recordToolCall(pjp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(counterCount("search_documents", "error")).isEqualTo(1.0);
        assertThat(counterCount("search_documents", "ok")).isEqualTo(0.0);
    }

    @Test
    void recordToolCall_unnamedTool_usesMethodName() throws Throwable {
        configureSignatureForMethod("listSources");
        when(pjp.proceed()).thenReturn("result");

        aspect.recordToolCall(pjp);

        assertThat(counterCount("listSources", "ok")).isEqualTo(1.0);
    }

    @Test
    void recordToolCall_repeated_accumulates() throws Throwable {
        configureSignatureForMethod("searchDocuments");
        when(pjp.proceed()).thenReturn("result");

        for (int i = 0; i < 5; i++) {
            aspect.recordToolCall(pjp);
        }

        assertThat(counterCount("search_documents", "ok")).isEqualTo(5.0);
    }

    private double counterCount(String tool, String outcome) {
        return registry.counter("mcp.tool.calls", "tool", tool, "outcome", outcome).count();
    }

    private void configureSignatureForMethod(String methodName) throws NoSuchMethodException {
        Method method = Fixture.class.getMethod(methodName);
        when(pjp.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
    }
}
