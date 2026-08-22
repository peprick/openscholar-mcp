package com.openscholar.security;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "openscholar.security.oidc", name = "enabled", havingValue = "true")
class OidcCurrentUserIdProvider implements CurrentUserIdProvider {

	private final OidcUserStore users;

	OidcCurrentUserIdProvider(OidcUserStore users) {
		this.users = users;
	}

	@Override
	public UUID currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof Jwt jwt)
				|| jwt.getIssuer() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
			throw new AccessDeniedException("An authenticated OIDC subject is required");
		}
		String subject = jwt.getSubject();
		if (subject.codePointCount(0, subject.length()) > 255 || subject.indexOf('\0') >= 0) {
			throw new AccessDeniedException("The authenticated OIDC subject cannot be stored safely");
		}
		return users.resolve(
				jwt.getIssuer().toString(),
				subject,
				displayName(jwt));
	}

	private static String displayName(Jwt jwt) {
		String value = firstNonBlank(
				jwt.getClaimAsString("name"),
				jwt.getClaimAsString("preferred_username"),
				jwt.getSubject());
		String normalized = value.replaceAll("[\\p{Cntrl}\\p{Z}]+", " ").strip();
		if (normalized.isEmpty()) {
			normalized = "OpenScholar user";
		}
		return truncateCodePoints(normalized, 120).stripTrailing();
	}

	private static String truncateCodePoints(String value, int maximumCodePoints) {
		int count = value.codePointCount(0, value.length());
		return count <= maximumCodePoints
				? value
				: value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "OpenScholar user";
	}
}
