package io.ancoris.mcp.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccessRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // SEC-001: lifecycle fields
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * D5: per-key MCP tool allowlist. {@code null} means unrestricted (all tools allowed).
     * Stored as JSONB; example value: {@code ["search_documents","list_sources"]}.
     * Read by {@link io.ancoris.mcp.security.ToolAllowlistAspect} before any tool body runs.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_tools", columnDefinition = "jsonb")
    private Set<String> allowedTools;

    /**
     * D5: persisted lifecycle projection. Three values {@code ACTIVE},
     * {@code REVOKED}, {@code EXPIRED}. NOT a GENERATED column — PostgreSQL
     * rejects now()-based expressions as "not immutable", so status is a plain
     * column kept in sync by {@code ApiKeyExpirySweeper}. {@code revoked} and
     * {@code expires_at} remain the primary source of truth for authentication;
     * status is a cached projection used for defence-in-depth filters and
     * observability. Hibernate may INSERT/UPDATE this column; the DB CHECK
     * constraint enforces valid values.
     */
    @Column(name = "status")
    private String status;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.status == null) {
            this.status = "ACTIVE";
        }
    }

    public UUID getId() { return id; }
    public String getKeyHash() { return keyHash; }
    public String getLabel() { return label; }
    public AccessRole getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Set<String> getAllowedTools() { return allowedTools; }
    public String getStatus() { return status; }
}
