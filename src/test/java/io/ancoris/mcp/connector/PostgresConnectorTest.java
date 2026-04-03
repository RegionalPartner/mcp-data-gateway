package io.ancoris.mcp.connector;

import io.ancoris.mcp.model.AccessRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresConnectorTest {

    @Mock
    private JdbcTemplate jdbc;

    private PostgresConnector connector;

    @BeforeEach
    void setUp() {
        connector = new PostgresConnector(jdbc);
    }

    // -----------------------------------------------------------------------
    // READ_ONLY role — salary must be absent from the SELECT list
    // -----------------------------------------------------------------------

    @Test
    void query_readOnly_salaryColumnExcluded() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), any(Integer.class))).thenReturn(List.of());

        connector.query("employees", null, AccessRole.READ_ONLY, 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), any(Integer.class));
        assertThat(sqlCaptor.getValue()).doesNotContain("salary");
    }

    // -----------------------------------------------------------------------
    // ADMIN role — salary must be present in the SELECT list
    // -----------------------------------------------------------------------

    @Test
    void query_admin_salaryColumnIncluded() {
        when(jdbc.queryForList(anyString(), any(Integer.class))).thenReturn(List.of());

        connector.query("employees", null, AccessRole.ADMIN, 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), any(Integer.class));
        assertThat(sqlCaptor.getValue()).contains("salary");
    }

    // -----------------------------------------------------------------------
    // Unknown table must throw SecurityException (table not in allowlist)
    // -----------------------------------------------------------------------

    @Test
    void query_unknownTable_throwsSecurityException() {
        assertThatThrownBy(() -> connector.query("audit_logs", null, AccessRole.ADMIN, 10))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Access denied");
    }

    // -----------------------------------------------------------------------
    // SQL injection in table name — also rejected by allowlist
    // -----------------------------------------------------------------------

    @Test
    void query_injectedTableName_throwsSecurityException() {
        assertThatThrownBy(() -> connector.query("employees; DROP TABLE employees;--",
                null, AccessRole.READ_ONLY, 10))
                .isInstanceOf(SecurityException.class);
    }

    // -----------------------------------------------------------------------
    // READ_ONLY filtering on a hidden column — must throw SecurityException
    // -----------------------------------------------------------------------

    @Test
    void query_filterOnHiddenColumn_throwsSecurityException() {
        assertThatThrownBy(() ->
                connector.query("employees", Map.of("salary", "50000"), AccessRole.READ_ONLY, 10))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Access denied");
    }

    // -----------------------------------------------------------------------
    // ADMIN can filter on salary
    // -----------------------------------------------------------------------

    @Test
    void query_adminFilterOnSalary_allowed() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        connector.query("employees", Map.of("salary", "50000"), AccessRole.ADMIN, 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), any(Object[].class));
        assertThat(sqlCaptor.getValue()).contains("WHERE").contains("salary");
    }

    // -----------------------------------------------------------------------
    // getVisibleColumns — READ_ONLY must not include salary
    // -----------------------------------------------------------------------

    @Test
    void getVisibleColumns_readOnly_noSalary() {
        List<String> cols = connector.getVisibleColumns("employees", AccessRole.READ_ONLY);
        assertThat(cols).doesNotContain("salary");
        assertThat(cols).contains("name", "department", "email");
    }

    // -----------------------------------------------------------------------
    // getVisibleColumns — ADMIN must include salary
    // -----------------------------------------------------------------------

    @Test
    void getVisibleColumns_admin_hasSalary() {
        List<String> cols = connector.getVisibleColumns("employees", AccessRole.ADMIN);
        assertThat(cols).contains("salary");
    }

    // -----------------------------------------------------------------------
    // maxRows is forwarded as a LIMIT parameter (not baked into SQL literals)
    // -----------------------------------------------------------------------

    @Test
    void query_maxRows_passedAsParameter() {
        when(jdbc.queryForList(anyString(), any(Integer.class))).thenReturn(List.of());

        connector.query("employees", null, AccessRole.READ_ONLY, 42);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(jdbc).queryForList(anyString(), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(42);
    }
}
