package io.ancoris.mcp.security;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.model.ApiKey;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolAllowlistAspectTest {

    @Mock
    private AuditService auditService;

    @Mock
    private ProceedingJoinPoint pjp;

    @Mock
    private MethodSignature methodSignature;

    private ToolAllowlistAspect aspect;

    /** Fixture class exposing a method annotated with {@link Tool#name()}. */
    @SuppressWarnings("unused")
    static class Fixture {
        @Tool(name = "search_documents")
        public String searchDocuments() { return "ok"; }

        @Tool
        public String listSources() { return "ok"; }
    }

    @BeforeEach
    void setUp() {
        aspect = new ToolAllowlistAspect(auditService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enforceAllowlist_nullAllowedTools_allowsTool() throws Throwable {
        authenticateWith(null);
        configureSignatureForMethod("searchDocuments");
        when(pjp.proceed()).thenReturn("proceeded");

        Object result = aspect.enforceAllowlist(pjp);

        assertThat(result).isEqualTo("proceeded");
        verify(auditService, never()).log(anyString(), any(), any(), anyString());
    }

    @Test
    void enforceAllowlist_toolInAllowlist_allowsTool() throws Throwable {
        authenticateWith(Set.of("search_documents", "list_sources"));
        configureSignatureForMethod("searchDocuments");
        when(pjp.proceed()).thenReturn("proceeded");

        Object result = aspect.enforceAllowlist(pjp);

        assertThat(result).isEqualTo("proceeded");
        verify(auditService, never()).log(eq("tool_denied"), any(), any(), anyString());
    }

    @Test
    void enforceAllowlist_toolNotInAllowlist_throwsAndAudits() throws Throwable {
        authenticateWith(Set.of("list_sources"));
        configureSignatureForMethod("searchDocuments");

        assertThatThrownBy(() -> aspect.enforceAllowlist(pjp))
                .isInstanceOf(ToolInputRejectedException.class)
                .hasMessageContaining("search_documents");

        verify(auditService).log(eq("tool_denied"), any(UUID.class), any(Map.class), eq("denied"));
        verify(pjp, never()).proceed();
    }

    @Test
    void enforceAllowlist_emptyAllowlist_deniesAllTools() throws Throwable {
        authenticateWith(Set.of());
        configureSignatureForMethod("searchDocuments");

        assertThatThrownBy(() -> aspect.enforceAllowlist(pjp))
                .isInstanceOf(ToolInputRejectedException.class);

        verify(auditService).log(eq("tool_denied"), any(UUID.class), any(Map.class), eq("denied"));
        verify(pjp, never()).proceed();
    }

    @Test
    void enforceAllowlist_toolAnnotationWithoutName_fallsBackToMethodName() throws Throwable {
        authenticateWith(Set.of("listSources"));       // method name, not tool name
        configureSignatureForMethod("listSources");
        when(pjp.proceed()).thenReturn("proceeded");

        Object result = aspect.enforceAllowlist(pjp);

        assertThat(result).isEqualTo("proceeded");
    }

    @Test
    void enforceAllowlist_toolAnnotationWithoutName_deniedWhenMissingFromList() throws Throwable {
        authenticateWith(Set.of("search_documents")); // method name "listSources" absent
        configureSignatureForMethod("listSources");

        assertThatThrownBy(() -> aspect.enforceAllowlist(pjp))
                .isInstanceOf(ToolInputRejectedException.class)
                .hasMessageContaining("listSources");
    }

    @Test
    void enforceAllowlist_noAuthentication_allowsTool() throws Throwable {
        SecurityContextHolder.clearContext();
        configureSignatureForMethod("searchDocuments");
        when(pjp.proceed()).thenReturn("proceeded");

        Object result = aspect.enforceAllowlist(pjp);

        assertThat(result).isEqualTo("proceeded");
    }

    @Test
    void resolveToolName_extractsToolAnnotationName() throws Exception {
        Method method = Fixture.class.getMethod("searchDocuments");
        when(pjp.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);

        assertThat(ToolAllowlistAspect.resolveToolName(pjp)).isEqualTo("search_documents");
    }

    @Test
    void resolveToolName_fallsBackToMethodNameWhenToolNameBlank() throws Exception {
        Method method = Fixture.class.getMethod("listSources");
        when(pjp.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);

        assertThat(ToolAllowlistAspect.resolveToolName(pjp)).isEqualTo("listSources");
    }

    @Test
    void resolveToolName_nonMethodSignature_usesSignatureName() {
        // Simulate a non-MethodSignature (e.g. constructor advice) by using a plain Signature mock.
        org.aspectj.lang.Signature sig = org.mockito.Mockito.mock(org.aspectj.lang.Signature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getName()).thenReturn("fallbackName");

        assertThat(ToolAllowlistAspect.resolveToolName(pjp)).isEqualTo("fallbackName");
    }

    // --- helpers -----------------------------------------------------------

    private void configureSignatureForMethod(String methodName) throws NoSuchMethodException {
        Method method = Fixture.class.getMethod(methodName);
        when(pjp.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
    }

    private void authenticateWith(Set<String> allowedTools) {
        ApiKey key = new ApiKey();
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(key, "keyHash", "fakehash");
        ReflectionTestUtils.setField(key, "label", "test");
        ReflectionTestUtils.setField(key, "role", AccessRole.READ_ONLY);
        ReflectionTestUtils.setField(key, "revoked", false);
        ReflectionTestUtils.setField(key, "createdAt", Instant.now());
        ReflectionTestUtils.setField(key, "allowedTools", allowedTools);
        var auth = new UsernamePasswordAuthenticationToken(
                key, null, List.of(new SimpleGrantedAuthority("ROLE_READ_ONLY")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
