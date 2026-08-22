package com.openscholar.security;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OidcSecurityProperties.class)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain applicationSecurity(HttpSecurity http, OidcSecurityProperties properties) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable);
		if (!properties.enabled()) {
			return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
		}
		RequestMatcher mcpEndpoint = new OrRequestMatcher(
				PathPatternRequestMatcher.pathPattern("/mcp"),
				PathPatternRequestMatcher.pathPattern("/mcp/**"));

		return http
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions
						.defaultAuthenticationEntryPointFor(
								(request, response, exception) -> {
									response.setStatus(HttpStatus.UNAUTHORIZED.value());
									response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
											mcpChallenge(properties, false));
								},
								mcpEndpoint)
						.defaultAccessDeniedHandlerFor(
								(request, response, exception) -> {
									response.setStatus(HttpStatus.FORBIDDEN.value());
									response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
											mcpChallenge(properties, true));
								},
								mcpEndpoint))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(
								"/actuator/health", "/actuator/health/**", "/actuator/prometheus",
								"/api/v1/system/status")
						.permitAll()
						.requestMatchers("/.well-known/oauth-protected-resource/**")
						.permitAll()
						.requestMatchers("/api/v1/collections/**", "/api/v1/library/**")
						.hasAuthority("SCOPE_openscholar.library")
						.requestMatchers("/api/v1/privacy/**")
						.hasAuthority("SCOPE_openscholar.privacy")
						.requestMatchers("/api/v1/refresh-jobs/**")
						.hasAuthority("SCOPE_openscholar.jobs")
						.requestMatchers("/mcp", "/mcp/**")
						.hasAuthority("SCOPE_openscholar.mcp")
						.requestMatchers("/api/v1/searches", "/api/v1/searches/**")
						.hasAuthority("SCOPE_openscholar.search")
						.requestMatchers(HttpMethod.POST, "/api/v1/papers/*/access/verify")
						.hasAuthority("SCOPE_openscholar.search")
						.requestMatchers(HttpMethod.GET, "/api/v1/papers/**")
						.permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/citations/export")
						.permitAll()
						.requestMatchers("/actuator/**")
						.hasAuthority("SCOPE_openscholar.ops")
						.anyRequest().denyAll())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(Customizer.withDefaults())
						.protectedResourceMetadata(metadata -> metadata
								.protectedResourceMetadataCustomizer(builder -> builder
										.resource(properties.mcpResourceUri().toString())
										.authorizationServer(properties.issuerUri().toString())
										.bearerMethod("header")
										.scope("openscholar.mcp")
										.resourceName("OpenScholar MCP")
										.tlsClientCertificateBoundAccessTokens(false))))
				.build();
	}

	@Bean
	JwtDecoder jwtDecoder(OidcSecurityProperties properties) {
		if (!properties.enabled()) {
			return token -> {
				throw new IllegalStateException("OIDC JWT decoding is disabled");
			};
		}
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString()).build();
		decoder.setJwtValidator(tokenValidator(properties));
		return decoder;
	}

	static OAuth2TokenValidator<Jwt> tokenValidator(OidcSecurityProperties properties) {
		JwtTimestampValidator timestamps = new JwtTimestampValidator();
		timestamps.setAllowEmptyExpiryClaim(false);
		OAuth2TokenValidator<Jwt> issuer = new JwtIssuerValidator(properties.issuerUri().toString());
		OAuth2TokenValidator<Jwt> audience = jwt -> audienceResult(jwt, properties.audience());
		return new DelegatingOAuth2TokenValidator<>(List.of(timestamps, issuer, audience));
	}

	private static OAuth2TokenValidatorResult audienceResult(Jwt jwt, String expectedAudience) {
		return jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error(
						"invalid_token", "The access token audience is not accepted", null));
	}

	private static String mcpChallenge(OidcSecurityProperties properties, boolean insufficientScope) {
		String prefix = insufficientScope ? "Bearer error=\"insufficient_scope\", " : "Bearer ";
		return prefix
				+ "resource_metadata=\"" + properties.mcpProtectedResourceMetadataUri() + "\", "
				+ "scope=\"openscholar.mcp\"";
	}
}
