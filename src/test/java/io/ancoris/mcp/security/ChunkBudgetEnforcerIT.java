package io.ancoris.mcp.security;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D5: integration test for {@link ChunkBudgetEnforcer}.
 *
 * <p>Hourly cap is overridden to 10 via TestPropertySource so the test can
 * exceed the budget in a handful of writes instead of 10 000.
 *
 * <p>Key assertions:
 * <ul>
 *   <li>UPSERT creates the first row for (api_key_id, window_start)</li>
 *   <li>Subsequent calls increment the same row atomically</li>
 *   <li>Exceeding the cap raises {@link BudgetExceededException} with sensible retryAfter</li>
 *   <li>REQUIRES_NEW propagation: the budget counter persists even if the caller's
 *       outer transaction rolls back (prevents rollback-loop amplification)</li>
 * </ul>
 */
@TestPropertySource(properties = "gateway.chunk-budget.hourly-cap=10")
class ChunkBudgetEnforcerIT extends AbstractIntegrationTest {

    @Autowired
    private ChunkBudgetEnforcer enforcer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private UUID keyId;

    @BeforeEach
    void setUp() {
        keyId = UUID.randomUUID();
        // No api_keys row is needed — rate_limit_state has no FK to api_keys.
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM rate_limit_state WHERE api_key_id = ?", keyId);
    }

    @Test
    void commitChunks_firstCall_insertsRow() {
        enforcer.commitChunks(keyId, 3);

        Long total = jdbc.queryForObject(
                "SELECT chunk_count FROM rate_limit_state WHERE api_key_id = ?",
                Long.class, keyId);

        assertThat(total).isEqualTo(3L);
    }

    @Test
    void commitChunks_repeatedCalls_incrementsCounter() {
        enforcer.commitChunks(keyId, 2);
        enforcer.commitChunks(keyId, 5);

        Long total = jdbc.queryForObject(
                "SELECT chunk_count FROM rate_limit_state WHERE api_key_id = ?",
                Long.class, keyId);

        assertThat(total).isEqualTo(7L);
    }

    @Test
    void commitChunks_whenCapExceeded_throwsBudgetExceeded() {
        // Cap is 10 (see @TestPropertySource).
        enforcer.commitChunks(keyId, 9);   // total = 9 → under cap, ok

        // total becomes 11 → over cap → throws.
        assertThatThrownBy(() -> enforcer.commitChunks(keyId, 2))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Chunk budget exceeded");
    }

    @Test
    void commitChunks_retryAfterSecondsPositive() {
        assertThatThrownBy(() -> enforcer.commitChunks(keyId, 15))
                .isInstanceOf(BudgetExceededException.class)
                .extracting(ex -> ((BudgetExceededException) ex).getRetryAfterSeconds())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LONG)
                .isBetween(1L, 3600L);
    }

    /**
     * REQUIRES_NEW isolation test: wrap commitChunks in an outer transaction that
     * is rolled back. If REQUIRES_NEW is working, the budget row persists.
     */
    @Test
    void commitChunks_isolatedByRequiresNew_persistsAcrossOuterRollback() {
        TransactionTemplate outerTx = new TransactionTemplate(txManager);
        outerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        outerTx.execute(status -> {
            enforcer.commitChunks(keyId, 4);
            status.setRollbackOnly(); // outer tx rolls back
            return null;
        });

        // If REQUIRES_NEW wasn't set, this row would be lost with the outer rollback.
        Long total = jdbc.queryForObject(
                "SELECT chunk_count FROM rate_limit_state WHERE api_key_id = ?",
                Long.class, keyId);

        assertThat(total).isEqualTo(4L);
    }

    @Test
    void secondsUntilNextHour_alwaysPositive() {
        long seconds = ChunkBudgetEnforcer.secondsUntilNextHour(java.time.Instant.now());
        assertThat(seconds).isBetween(1L, 3600L);
    }

    @Test
    void commitChunks_nullApiKeyId_failsClosed() {
        assertThatThrownBy(() -> enforcer.commitChunks(null, 1))
                .isInstanceOf(BudgetExceededException.class);
    }

    @Test
    void getHourlyCap_reflectsConfiguredValue() {
        assertThat(enforcer.getHourlyCap()).isEqualTo(10L);
    }
}
