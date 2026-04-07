package io.ancoris.mcp.security;

import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.model.ApiKey;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RlsContextAspectTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private PlatformTransactionManager txManager;

    @Mock
    private TransactionStatus txStatus;

    @Mock
    private ProceedingJoinPoint pjp;

    private RlsContextAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RlsContextAspect(jdbc, txManager);
        // Make TransactionTemplate.execute() call the callback directly (no real transaction)
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // -----------------------------------------------------------------------
    // READ_ONLY principal → SET LOCAL app.mcp_role = 'READ_ONLY'
    // -----------------------------------------------------------------------

    @Test
    void applyRlsRole_readOnlyPrincipal_setsReadOnlyRole() throws Throwable {
        authenticateWith(AccessRole.READ_ONLY);
        when(pjp.proceed()).thenReturn(null);
        // Delegate transaction execution to the callback inline
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        captureAndInvokeCallback();

        aspect.applyRlsRole(pjp);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).isEqualTo("SET LOCAL app.mcp_role = 'READ_ONLY'");
    }

    // -----------------------------------------------------------------------
    // ADMIN principal → SET LOCAL app.mcp_role = 'ADMIN'
    // -----------------------------------------------------------------------

    @Test
    void applyRlsRole_adminPrincipal_setsAdminRole() throws Throwable {
        authenticateWith(AccessRole.ADMIN);
        when(pjp.proceed()).thenReturn(null);
        captureAndInvokeCallback();

        aspect.applyRlsRole(pjp);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).isEqualTo("SET LOCAL app.mcp_role = 'ADMIN'");
    }

    // -----------------------------------------------------------------------
    // No authentication → defaults to READ_ONLY (least privilege)
    // -----------------------------------------------------------------------

    @Test
    void applyRlsRole_noAuthentication_defaultsToReadOnly() throws Throwable {
        SecurityContextHolder.clearContext();
        when(pjp.proceed()).thenReturn(null);
        captureAndInvokeCallback();

        aspect.applyRlsRole(pjp);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).isEqualTo("SET LOCAL app.mcp_role = 'READ_ONLY'");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void authenticateWith(AccessRole role) {
        ApiKey key = new ApiKey();
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(key, "keyHash", "fakehash");
        ReflectionTestUtils.setField(key, "label", "test");
        ReflectionTestUtils.setField(key, "role", role);
        ReflectionTestUtils.setField(key, "revoked", false);
        ReflectionTestUtils.setField(key, "createdAt", Instant.now());
        var auth = new UsernamePasswordAuthenticationToken(
                key, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Makes the mock TransactionManager invoke the TransactionCallback synchronously
     * so the aspect body executes during the test without a real transaction.
     */
    @SuppressWarnings("unchecked")
    private void captureAndInvokeCallback() {
        when(txManager.getTransaction(any())).thenAnswer(inv -> txStatus);
        // TransactionTemplate.execute() calls doInTransaction on the same thread.
        // We replicate that by answering the commit call with a no-op.
    }
}
