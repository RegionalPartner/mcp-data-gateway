package io.ancoris.mcp.benchmark;

import io.ancoris.mcp.connector.ContentEncryptor;
import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.oauth.JwtTokenService;
import io.ancoris.mcp.security.HmacApiKeyHasher;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
public class SecurityBenchmark {

    private static final String PEPPER = "SecurityBenchmarkTestPepper!!!!!";
    private static final String HEX_KEY = "0".repeat(64);
    private static final String JWT_SECRET = "jmh-benchmark-jwt-secret-testing";
    private static final String TEST_API_KEY = "demo-readonly-key-001";
    private static final String TEST_PAYLOAD =
            "SELECT * FROM data_fragments WHERE tenant_id = 'demo' LIMIT 100";

    private HmacApiKeyHasher hasher;
    private ContentEncryptor encryptor;
    private JwtTokenService jwtService;
    private String storedHash;
    private byte[] encryptedPayload;
    private String validToken;

    @Setup
    public void setup() {
        hasher = new HmacApiKeyHasher(PEPPER);
        encryptor = new ContentEncryptor(HEX_KEY);
        jwtService = new JwtTokenService(JWT_SECRET);
        storedHash = hasher.hash(TEST_API_KEY);
        encryptedPayload = encryptor.encrypt(TEST_PAYLOAD);
        validToken = jwtService.issue(storedHash, AccessRole.READ_ONLY);
    }

    @Benchmark
    public String hmacHash() {
        return hasher.hash(TEST_API_KEY);
    }

    @Benchmark
    public boolean hmacMatches() {
        return hasher.matches(TEST_API_KEY, storedHash);
    }

    @Benchmark
    public byte[] aesEncrypt() {
        return encryptor.encrypt(TEST_PAYLOAD);
    }

    @Benchmark
    public String aesDecrypt() {
        return encryptor.decrypt(encryptedPayload);
    }

    @Benchmark
    public String jwtIssue() {
        return jwtService.issue(storedHash, AccessRole.READ_ONLY);
    }

    @Benchmark
    public boolean jwtValidate() {
        return jwtService.validate(validToken).isPresent();
    }
}
