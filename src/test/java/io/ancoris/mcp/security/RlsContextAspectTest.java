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
import static org.mockito.Mockito.times;
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
    // READ_ONLY principal → sets role + client ID
    // -----------------------------------------------------------------------

    @Test
    void applyRlsRole_readOnlyPrincipal_setsRoleAndClientId() throws Throwable {
        UUID keyId = UUID.randomUUID();
        authenticateWith(AccessRole.READ_ONLY, keyId);
        when(pjp.proceed()).thenReturn(null);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        captureAndInvokeCallback();

        aspect.applyRlsRole(pjp);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues().get(0)).isEqualTo("SET LOCAL app.mcp_role = 'READ_ONLY'");
        assertThat(sqlCaptor.getAllValues().get(1)).isEqualTo("SET LOCAL app.mcp_client_id = '" + keyId + "'");
    }

    // -----------------------------------------------------------------------
    // ADMIN principal → sets role + client ID
    // -----------------------------------------------------------------------

    @Test
    void applyRlsRole_adminPrincipal_setsRoleAndClientId() throws Throwable {
        UUID keyId = UUID.randomUUID();
        authenticateWith(AccessRole.ADMIN, keyId);
        when(pjp.proceed()).thenReturn(null);
        captureAndInvokeCallback();

        aspect.applyRlsRole(pjp);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues().get(0)).isEqualTo("SET LOCAL app.mcp_role = 'ADMIN'");
        assertThat(sqlCaptor.getAllValues().get(1)).isEqualTo("SET LOCAL app.mcp_client_id = '" + keyId + "'");
    }

    // -----------------------------------------------------------------------
    // No authentication → READ_ONLY role + nil UUID (fail-closed workspace)
    // -----------------------------------------------------------------------

    @Test
    void applyRlsRole_noAuthentication_defaultsToReadOnlyAndNilClientId() throws Throwable {
        SecurityContextHolder.clearContext();
        when(pjp.proceed()).thenReturn(null);
        captureAndInvokeCallback();

        aspect.applyRlsRole(pjp);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues().get(0)).isEqualTo("SET LOCAL app.mcp_role = 'READ_ONLY'");
        assertThat(sqlCaptor.getAllValues().get(1))
                .isEqualTo("SET LOCAL app.mcp_client_id = '00000000-0000-0000-0000-000000000000'");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void authenticateWith(AccessRole role, UUID id) {
        ApiKey key = new ApiKey();
        ReflectionTestUtils.setField(key, "id", id);
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
