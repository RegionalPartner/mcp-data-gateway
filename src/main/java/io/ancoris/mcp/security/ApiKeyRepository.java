package io.ancoris.mcp.security;

import io.ancoris.mcp.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    // All active keys loaded for BCrypt matching (demo table < 100 rows)
}
