package io.ancoris.mcp.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DenialFragmentTest {

    @Test
    void queryRejected_producesBracketedMarker() {
        DataFragment f = DenialFragment.queryRejected("chat-role marker");
        assertThat(f.fragmentText()).startsWith("[QUERY_REJECTED] ").contains("chat-role marker");
        assertThat(f.docName()).isEqualTo(DenialFragment.DENIAL_DOC_NAME);
        assertThat(f.classification()).isEqualTo(DenialFragment.DENIAL_CLASSIFICATION);
        assertThat(f.chunkIndex()).isZero();
    }

    @Test
    void budgetExceeded_includesRetryAfterSeconds() {
        DataFragment f = DenialFragment.budgetExceeded(123L);
        assertThat(f.fragmentText()).isEqualTo("[BUDGET_EXCEEDED retryAfter=123s]");
        assertThat(f.docName()).isEqualTo(DenialFragment.DENIAL_DOC_NAME);
    }
}
