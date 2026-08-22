package com.openscholar.security;

import java.net.URI;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.security.oidc")
public record OidcSecurityProperties(
		boolean enabled,
		URI issuerUri,
		URI jwkSetUri,
		String audience,
		URI mcpResourceUri) {

	public OidcSecurityProperties {
		audience = audience == null || audience.isBlank() ? null : audience.strip();
		if (enabled) {
			issuerUri = requireSecureUri(issuerUri, "issuerUri");
			jwkSetUri = requireSecureUri(jwkSetUri, "jwkSetUri");
			if (issuerUri.toString().length() > 512) {
				throw new IllegalArgumentException("OIDC issuerUri must not exceed 512 characters");
			}
			if (audience == null || audience.length() > 255) {
				throw new IllegalArgumentException(
						"OIDC audience is required and must not exceed 255 characters when OIDC is enabled");
			}
			mcpResourceUri = requireSecureUri(mcpResourceUri, "mcpResourceUri");
			if (!"/mcp".equals(mcpResourceUri.getPath()) || mcpResourceUri.toString().length() > 2048) {
				throw new IllegalArgumentException(
						"OIDC mcpResourceUri must be the canonical HTTPS MCP endpoint ending in /mcp");
			}
		}
	}

	private static URI requireSecureUri(URI value, String name) {
		if (value == null || !value.isAbsolute() || value.getHost() == null
				|| value.getUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
			throw new IllegalArgumentException("OIDC " + name + " must be an absolute credential-free HTTP(S) URL");
		}
		String scheme = value.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(value.getHost()))) {
			throw new IllegalArgumentException(
					"OIDC " + name + " must use HTTPS, except for explicit loopback development URLs");
		}
		return value;
	}

	private static boolean isLoopback(String host) {
		return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
				|| host.equals("::1") || host.equals("[::1]");
	}

	public URI mcpProtectedResourceMetadataUri() {
		if (!enabled || mcpResourceUri == null) {
			throw new IllegalStateException("OIDC MCP protected-resource metadata is disabled");
		}
		return URI.create(mcpResourceUri.getScheme() + "://" + mcpResourceUri.getRawAuthority()
				+ "/.well-known/oauth-protected-resource" + mcpResourceUri.getRawPath());
	}
}
