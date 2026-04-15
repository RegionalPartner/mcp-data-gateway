package io.ancoris.mcp.connector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorSearchConnectorTest {

    @Mock
    private JdbcTemplate jdbc;

    private VectorSearchConnector connector;

    @BeforeEach
    void setUp() {
        connector = new VectorSearchConnector(jdbc);
    }

    // -----------------------------------------------------------------------
    // search: SQL contains ::vector cast
    // -----------------------------------------------------------------------

    @Test
    void search_sqlContainsVectorCast() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        connector.search(new float[768], List.of("'PUBLIC'"), 5);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), any(Object[].class));
        assertThat(sqlCaptor.getValue()).contains("::vector");
    }

    // -----------------------------------------------------------------------
    // search: vector param is formatted as pgvector bracket-comma literal
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void search_vectorFormattedAsLiteral() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        float[] v = new float[]{0.1f, 0.2f, 0.3f};
        connector.search(v, List.of("'PUBLIC'"), 5);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), argsCaptor.capture());
        String vectorStr = (String) argsCaptor.getValue()[0];
        assertThat(vectorStr).startsWith("[").endsWith("]").contains("0.1").contains("0.2");
    }

    // -----------------------------------------------------------------------
    // search: SQL contains the correct classification IN clause
    // -----------------------------------------------------------------------

    @Test
    void search_sqlContainsAllowedClassifications() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        connector.search(new float[768], List.of("'PUBLIC'", "'INTERNAL'"), 5);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("'PUBLIC'").contains("'INTERNAL'").doesNotContain("'CONFIDENTIAL'");
    }

    // -----------------------------------------------------------------------
    // search: limit is forwarded as a parameter, not baked into SQL
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void search_limitPassedAsParameter() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        connector.search(new float[768], List.of("'PUBLIC'"), 7);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[1]).isEqualTo(7);
    }

    // -----------------------------------------------------------------------
    // search: embedding IS NOT NULL filter in SQL
    // -----------------------------------------------------------------------

    @Test
    void search_sqlFiltersNullEmbeddings() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        connector.search(new float[768], List.of("'PUBLIC'"), 5);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCaptor.capture(), any(Object[].class));
        assertThat(sqlCaptor.getValue()).contains("embedding IS NOT NULL");
    }
}
