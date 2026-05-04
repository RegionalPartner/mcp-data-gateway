package io.ancoris.mcp.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates OAuth {@code redirect_uri} values against a scheme + host allowlist
 * to defeat open-redirect attacks (CWE-601 / SonarRule javasecurity:S5146).
 *
 * <p>Rules applied in order:
 * <ol>
 *   <li>Reject null/blank input and any value containing control characters or CR/LF
 *       (which would otherwise let an attacker inject extra response headers).</li>
 *   <li>Reject malformed URIs and missing schemes.</li>
 *   <li>Reject dangerous schemes outright: {@code javascript}, {@code data},
 *       {@code file}, {@code vbscript}, {@code blob}, {@code about}, {@code jar}.</li>
 *   <li>For {@code http} / {@code https}: require a non-empty host with no userinfo.
 *       Loopback hosts ({@code localhost}, {@code 127.0.0.1}, {@code ::1}) are always
 *       allowed (covers Claude Code, MCP Inspector). Any other host must be listed in
 *       {@code mcp.oauth.allowed-redirect-hosts} (comma-separated, exact match).</li>
 *   <li>For other RFC-3986 custom schemes (e.g. {@code mistral-le-chat://callback}):
 *       require a non-empty authority or path so the OAuth code has somewhere to land.</li>
 * </ol>
 */
@Component
public class OAuthRedirectValidator {

    private static final Set<String> DANGEROUS_SCHEMES =
            Set.of("javascript", "data", "file", "vbscript", "blob", "about", "jar");

    private static final Set<String> LOOPBACK_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private static final Pattern SCHEME_PATTERN =
            Pattern.compile("[a-z][a-z0-9+.-]{0,30}");

    private final Set<String> allowedHosts;

    public OAuthRedirectValidator(
            @Value("${mcp.oauth.allowed-redirect-hosts:}") String allowedHostsCsv) {
        Set<String> hosts = new HashSet<>();
        for (String h : allowedHostsCsv.split(",")) {
            String trimmed = h.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        this.allowedHosts = Set.copyOf(hosts);
    }

    public boolean isAllowed(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            return false;
        }
        for (int i = 0; i < redirectUri.length(); i++) {
            char c = redirectUri.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        URI uri;
        try {
            uri = new URI(redirectUri);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            return false;
        }
        scheme = scheme.toLowerCase();
        if (DANGEROUS_SCHEMES.contains(scheme)) {
            return false;
        }
        if (!SCHEME_PATTERN.matcher(scheme).matches()) {
            return false;
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return isAllowedHttpHost(uri);
        }
        boolean hasAuthority = uri.getAuthority() != null && !uri.getAuthority().isBlank();
        boolean hasPath = uri.getPath() != null && !uri.getPath().isBlank();
        return hasAuthority || hasPath;
    }

    private boolean isAllowedHttpHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (uri.getUserInfo() != null) {
            return false;
        }
        host = host.toLowerCase();
        if (LOOPBACK_HOSTS.contains(host)) {
            return true;
        }
        return allowedHosts.contains(host);
    }
}
