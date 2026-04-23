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
class RateLimitStatePrunerTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void pruneStaleWindows_issuesDeleteWith24hThreshold() {
        when(jdbc.update(contains("DELETE FROM rate_limit_state"))).thenReturn(0);

        new RateLimitStatePruner(jdbc).pruneStaleWindows();

        verify(jdbc).update(contains("DELETE FROM rate_limit_state WHERE window_start < now() - INTERVAL '24 hours'"));
    }

    @Test
    void pruneStaleWindows_logsCountWhenRowsRemoved() {
        when(jdbc.update(contains("DELETE FROM rate_limit_state"))).thenReturn(7);

        // No throw — logging is best-effort; we only verify the SQL.
        new RateLimitStatePruner(jdbc).pruneStaleWindows();

        verify(jdbc).update(contains("DELETE"));
    }
}
