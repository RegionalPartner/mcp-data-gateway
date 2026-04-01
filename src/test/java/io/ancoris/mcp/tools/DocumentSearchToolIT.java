package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditLogRepository;
import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.ancoris.mcp.integration.TestSecurityHelper;
import io.ancoris.mcp.model.DataFragment;
import io.ancoris.mcp.security.ApiKeyRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentSearchToolIT extends AbstractIntegrationTest {

    private static final String TEST_BUCKET = "mcp-test-documents";

    @Autowired
    DocumentSearchTool documentSearchTool;

    @Autowired
    ApiKeyRepository apiKeyRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    TestSecurityHelper secHelper;

    @Autowired
    MinioClient minioClient;

    @BeforeAll
    void setUpMinioObjects() throws Exception {
        // Ensure bucket exists
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(TEST_BUCKET).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());
        }

        // Upload chunks matching the minio_key values in V2__seed.sql
        uploadChunk("chunks/rapport-annuel-2024-chunk-00.json",
                "Le rapport annuel 2024 présente les résultats consolidés de l'Agence de Développement de Normandie. L'exercice démontre une progression significative des activités.");
        uploadChunk("chunks/rapport-annuel-2024-chunk-01.json",
                "Les investissements en infrastructure numérique ont augmenté de 23% par rapport à l'exercice précédent, reflétant l'engagement vers la transformation digitale.");
        uploadChunk("chunks/rapport-annuel-2024-chunk-02.json",
                "Le bilan énergétique des datacenters normands montre une réduction de 15% de la consommation électrique grâce aux nouveaux équipements.");
        uploadChunk("chunks/politique-rh-v3-chunk-00.json",
                "La politique RH version 3 définit les procédures de recrutement et d'évaluation des compétences pour l'ensemble du personnel de l'agence.");
        uploadChunk("chunks/note-technique-securite-chunk-00.json",
                "Cette note technique décrit les bonnes pratiques de sécurité informatique applicables à tous les agents. Elle couvre la gestion des mots de passe et les accès distants.");
    }

    private void uploadChunk(String key, String text) throws Exception {
        String json = "{\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(TEST_BUCKET)
                        .object(key)
                        .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                        .contentType("application/json")
                        .build());
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
    // All returned fragment texts must be capped at 500 chars
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_returnsFragmentText_cappedAt500Chars() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = documentSearchTool.searchDocuments("sécurité", 10);

        assertThat(results).allMatch(f ->
                f.fragmentText() == null || f.fragmentText().length() <= 500);
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
