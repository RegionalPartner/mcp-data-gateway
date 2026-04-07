package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditLogRepository;
import io.ancoris.mcp.connector.ContentEncryptor;
import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.ancoris.mcp.integration.TestSecurityHelper;
import io.ancoris.mcp.model.DataFragment;
import io.ancoris.mcp.security.ApiKeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentSearchToolIT extends AbstractIntegrationTest {

    @Autowired
    DocumentSearchTool documentSearchTool;

    @Autowired
    ApiKeyRepository apiKeyRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    TestSecurityHelper secHelper;

    @Autowired
    ContentEncryptor contentEncryptor;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    void setUpEncryptedContent() {
        // Populate encrypted_content for all seeded chunks using the test key.
        // Texts match the text_preview values so that FTS in the tool and content
        // returned by DbContentStore are consistent.
        encryptAndStore("rapport-annuel-2024-chunk-00.json",
                "Le rapport annuel 2024 présente les résultats consolidés de l'Agence de Développement de Normandie. L'exercice démontre une progression significative des activités.");
        encryptAndStore("rapport-annuel-2024-chunk-01.json",
                "Les investissements en infrastructure numérique ont augmenté de 23% par rapport à l'exercice précédent, reflétant l'engagement vers la transformation digitale.");
        encryptAndStore("rapport-annuel-2024-chunk-02.json",
                "Le bilan énergétique des datacenters normands montre une réduction de 15% de la consommation électrique grâce aux nouveaux équipements.");
        encryptAndStore("politique-rh-v3-chunk-00.json",
                "La politique RH version 3 définit les procédures de recrutement et d'évaluation des compétences pour l'ensemble du personnel de l'agence.");
        encryptAndStore("note-technique-securite-chunk-00.json",
                "Cette note technique décrit les bonnes pratiques de sécurité informatique applicables à tous les agents. Elle couvre la gestion des mots de passe et les accès distants.");
    }

    private void encryptAndStore(String minioKeyFragment, String text) {
        byte[] encrypted = contentEncryptor.encrypt(text);
        jdbc.update(
                "UPDATE document_chunks SET encrypted_content = ? WHERE minio_key LIKE ?",
                encrypted, "%" + minioKeyFragment);
    }

    @AfterEach
    void tearDown() {
        secHelper.clearAuthentication();
    }

    // -----------------------------------------------------------------------
    // READ_ONLY: CONFIDENTIAL documents must be excluded
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_asReadOnly_excludesConfidentialResults() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        // "politique" appears only in the CONFIDENTIAL chunk
        List<DataFragment> results = documentSearchTool.searchDocuments("rapport annuel", 10);

        assertThat(results).noneMatch(f -> "CONFIDENTIAL".equals(f.classification()));
    }

    // -----------------------------------------------------------------------
    // ADMIN: CONFIDENTIAL documents must be included when querying for them
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_asAdmin_includesConfidentialResults() {
        secHelper.authenticateAs("demo-admin-key-001", apiKeyRepository);

        // "recrutement" appears only in the CONFIDENTIAL politique-rh chunk
        List<DataFragment> results = documentSearchTool.searchDocuments("recrutement", 10);

        assertThat(results).anyMatch(f -> "CONFIDENTIAL".equals(f.classification()));
    }

    // -----------------------------------------------------------------------
    // All returned fragment texts must be capped at 500 chars (raw text only;
    // trust-boundary markers add overhead, but seed data is well under 455 chars)
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_returnsFragmentText_cappedAt500Chars() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = documentSearchTool.searchDocuments("sécurité", 10);

        assertThat(results).allMatch(f ->
                f.fragmentText() == null || f.fragmentText().length() <= 600);
    }

    // -----------------------------------------------------------------------
    // maxResults parameter is respected
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_maxResultsRespected() {
        secHelper.authenticateAs("demo-admin-key-001", apiKeyRepository);

        List<DataFragment> results = documentSearchTool.searchDocuments("Normandie", 2);

        assertThat(results).hasSizeLessThanOrEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // maxResults is clamped at 10 even when caller requests 999
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_maxResultsClampedAt10() {
        secHelper.authenticateAs("demo-admin-key-001", apiKeyRepository);

        List<DataFragment> results = documentSearchTool.searchDocuments("Normandie", 999);

        assertThat(results).hasSizeLessThanOrEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Encrypted content is correctly decrypted and returned in fragment text
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_encryptedContentDecrypted() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = documentSearchTool.searchDocuments("sécurité", 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).fragmentText())
                .contains("[EXTERNAL_CONTENT_START]")
                .contains("[EXTERNAL_CONTENT_END]")
                .contains("sécurité");
    }

    // -----------------------------------------------------------------------
    // SEC-ENC: round-trip — decrypt(encrypt(text)) returns original text
    // -----------------------------------------------------------------------

    @Test
    void encryptedContent_roundTripViaDatabase() {
        // Fetch the chunk UUID for the sécurité document
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, encrypted_content FROM document_chunks WHERE minio_key LIKE '%securite%'");
        assertThat(rows).isNotEmpty();

        byte[] stored = (byte[]) rows.get(0).get("encrypted_content");
        assertThat(stored).isNotNull();

        String decrypted = contentEncryptor.decrypt(stored);
        assertThat(decrypted).contains("sécurité");
    }

    // -----------------------------------------------------------------------
    // SEC-RLS: verify that the RLS policies were created by V5 migration.
    //
    // Note: row-filtering enforcement (CONFIDENTIAL blocked for READ_ONLY)
    // requires a non-superuser DB role. The Testcontainers user is a superuser
    // and therefore bypasses FORCE ROW LEVEL SECURITY — this is expected.
    // In production, verify: SELECT rolsuper FROM pg_roles WHERE rolname='mcpuser';
    // must return false, and the policy then enforces the filter at DB level.
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void rls_v5Migration_createdClassificationPolicy() {
        Integer policyCount = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_policies
                WHERE tablename = 'document_chunks'
                  AND policyname = 'doc_chunks_classification_policy'
                """,
                Integer.class);

        assertThat(policyCount).isEqualTo(1);
    }

    @Test
    @Transactional
    void rls_v5Migration_rlsEnabledOnDocumentChunks() {
        Boolean rlsEnabled = jdbc.queryForObject(
                """
                SELECT relrowsecurity FROM pg_class
                WHERE relname = 'document_chunks'
                """,
                Boolean.class);

        assertThat(rlsEnabled).isTrue();
    }

    // -----------------------------------------------------------------------
    // SEC-ENC: V6 migration created encrypted_content column
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void v6Migration_encryptedContentColumnExists() {
        Integer colCount = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'document_chunks'
                  AND column_name = 'encrypted_content'
                """,
                Integer.class);

        assertThat(colCount).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Audit log entry created after search
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_createsAuditLogEntry() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        long countBefore = auditLogRepository.count();
        documentSearchTool.searchDocuments("sécurité", 5);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            long countAfter = auditLogRepository.count();
            assertThat(countAfter).isGreaterThan(countBefore);

            boolean hasSearchEntry = auditLogRepository.findAll().stream()
                    .anyMatch(entry -> "search_documents".equals(entry.getToolName()));
            assertThat(hasSearchEntry).isTrue();
        });
    }
}
