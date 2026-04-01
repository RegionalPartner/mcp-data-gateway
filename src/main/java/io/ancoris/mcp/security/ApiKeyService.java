package io.ancoris.mcp.security;

import io.ancoris.mcp.model.ApiKey;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class ApiKeyService {

    private final ApiKeyRepository repository;
    private final BCryptPasswordEncoder encoder;

    public ApiKeyService(ApiKeyRepository repository, BCryptPasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    public Optional<ApiKey> authenticate(String rawKey) {
        return repository.findAll().stream()
                .filter(key -> encoder.matches(rawKey, key.getKeyHash()))
                .findFirst();
    }
}
