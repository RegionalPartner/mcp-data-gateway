package io.ancoris.mcp.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyExpirySweeperTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void sweep_issuesUpdateReconcilingStatusColumn() {
        when(jdbc.update(contains("UPDATE api_keys"))).thenReturn(0);

        new ApiKeyExpirySweeper(jdbc).sweep();

        verify(jdbc).update(contains("SET status = CASE"));
    }

    @Test
    void sweep_onlyUpdatesRowsWhereStatusIsStale() {
        when(jdbc.update(contains("UPDATE api_keys"))).thenReturn(3);

        new ApiKeyExpirySweeper(jdbc).sweep();

        verify(jdbc).update(contains("WHERE status <>"));
    }
}
