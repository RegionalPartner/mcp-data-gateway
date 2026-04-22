package io.ancoris.mcp.security;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.model.ApiKey;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * D5: three-tier filter for free-text queries arriving at search tools
 * (DocumentSearchTool, SemanticSearchTool).
 *
 * <ul>
 *   <li><b>Reject</b>: chat-role markers at line start ({@code system:}, {@code user:},
 *       {@code assistant:}), model special tokens ({@code <|im_start|>}, {@code <|im_end|>},
 *       {@code <|endoftext|>}, {@code <|system|>}, {@code <|assistant|>}, {@code <|user|>}),
 *       base64 blobs of 256 chars or more, control characters (excluding tab and newline),
 *       or strings longer than {@link #MAX_QUERY_LENGTH}. Throws
 *       {@link ToolInputRejectedException}.</li>
 *   <li><b>Strip + audit</b>: Unicode Tags block {@code [U+E0000..U+E007F]} is used for
 *       invisible prompt-injection payloads. Removed silently; event audited.</li>
 *   <li><b>Flag + allow</b>: classic instruction-override phrases ("ignore previous
 *       instructions", "disregard the above", ...) are allowed through but tagged
 *       {@code flagged=true} so downstream layers (tools) can mark the audit entry.</li>
 * </ul>
 *
 * <p>Stateless; {@link #validate(String)} is pure and safe to call without
 * authentication context. Audit events use {@link SecurityContextHolder} best-effort —
 * absent authentication is handled.
 */
@Component
public class QueryGuard {

    public static final int MAX_QUERY_LENGTH = 2000;

    /** Chat-role markers at line start ({@code (?m)} = multiline mode, {@code ^} = any line start). */
    private static final Pattern CHAT_ROLE_MARKERS =
            Pattern.compile("(?im)^\\s*(system|user|assistant)\\s*:");

    /** Model special tokens — expanded to cover OpenAI, Anthropic, and Llama conventions. */
    private static final Pattern SPECIAL_TOKENS = Pattern.compile(
            "<\\|(?:im_start|im_end|endoftext|system|assistant|user|startoftext|fim_prefix|fim_middle|fim_suffix)\\|>",
            Pattern.CASE_INSENSITIVE);

    /**
     * Continuous base64 blob of 256+ chars. Base64 alphabet is {@code [A-Za-z0-9+/=]};
     * we require word-boundary bracketing so normal prose doesn't match.
     */
    private static final Pattern LONG_BASE64 =
            Pattern.compile("\\b[A-Za-z0-9+/=]{256,}\\b");

    /** ASCII / Unicode control chars, EXCLUDING tab ({@code \\t}) and newline/CR ({@code \\n \\r}). */
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** Unicode Tags supplementary plane ({@code U+E0000..U+E007F}) — invisible by design. */
    private static final Pattern UNICODE_TAGS =
            Pattern.compile("[\\x{E0000}-\\x{E007F}]");

    /** Classic jailbreak phrases. Flag-and-allow — not blocked, just surfaced to audit. */
    private static final Pattern INSTRUCTION_OVERRIDE = Pattern.compile(
            "(?i)\\b(?:"
                    + "ignore (?:all|any|the|previous|prior|above) (?:prior|previous|above)?\\s*instructions?"
                    + "|disregard (?:the|any|all|previous) (?:above|prior|previous)?\\s*(?:instructions?|prompts?|rules?)"
                    + "|forget (?:the|all|your) (?:above|prior|previous)? ?(?:instructions?|rules?|prompt)"
                    + "|system prompt (?:override|bypass|reset)"
                    + "|you are now"
                    + "|act as "
                    + ")");

    private final AuditService auditService;

    public QueryGuard(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Runs the three-tier filter.
     *
     * @return {@link ValidationResult} with {@code accepted=true} always when returned;
     *         if acceptance is not possible, this throws {@link ToolInputRejectedException}.
     * @throws ToolInputRejectedException if any reject-tier pattern matches.
     */
    public ValidationResult validate(String rawQuery) {
        if (rawQuery == null) {
            throw new ToolInputRejectedException("query is null");
        }
        if (rawQuery.length() > MAX_QUERY_LENGTH) {
            auditReject("length_exceeded", rawQuery.length());
            throw new ToolInputRejectedException(
                    "query exceeds " + MAX_QUERY_LENGTH + " chars (got " + rawQuery.length() + ")");
        }
        if (CHAT_ROLE_MARKERS.matcher(rawQuery).find()) {
            auditReject("chat_role_marker", null);
            throw new ToolInputRejectedException("chat-role marker detected");
        }
        if (SPECIAL_TOKENS.matcher(rawQuery).find()) {
            auditReject("model_special_token", null);
            throw new ToolInputRejectedException("model special token detected");
        }
        if (LONG_BASE64.matcher(rawQuery).find()) {
            auditReject("long_base64_blob", null);
            throw new ToolInputRejectedException("suspicious long base64 blob detected");
        }
        if (CONTROL_CHARS.matcher(rawQuery).find()) {
            auditReject("control_chars", null);
            throw new ToolInputRejectedException("control characters detected");
        }

        // ----- Tier 2: strip + audit -----
        String stripped = rawQuery;
        if (UNICODE_TAGS.matcher(stripped).find()) {
            stripped = UNICODE_TAGS.matcher(stripped).replaceAll("");
            auditReject("unicode_tags_stripped", null);
        }

        // ----- Tier 3: flag + allow -----
        boolean flagged = INSTRUCTION_OVERRIDE.matcher(stripped).find();
        String rejectionReason = null;
        if (flagged) {
            auditFlag("instruction_override");
        }

        return new ValidationResult(true, stripped, flagged, rejectionReason);
    }

    private void auditReject(String pattern, Integer metric) {
        try {
            auditService.log("query_guard_reject", currentKeyIdOrNull(),
                    metric != null ? Map.of("pattern", pattern, "length", metric)
                                   : Map.of("pattern", pattern),
                    "rejected");
        } catch (Exception ignored) {
            // Audit failure must never mask the rejection — the exception still propagates.
        }
    }

    private void auditFlag(String pattern) {
        try {
            auditService.log("query_guard_flag", currentKeyIdOrNull(),
                    Map.of("pattern", pattern), "flagged");
        } catch (Exception ignored) {
            // Best-effort audit.
        }
    }

    private static UUID currentKeyIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKey key) {
            return key.getId();
        }
        return null;
    }

    /**
     * Outcome of {@link #validate(String)}. When returned, the query has been accepted
     * (possibly after strip-tier cleansing). {@code flagged=true} means an instruction-
     * override phrase was present but allowed through.
     */
    public record ValidationResult(
            boolean accepted,
            String sanitizedQuery,
            boolean flagged,
            String rejectionReason) {
    }
}
