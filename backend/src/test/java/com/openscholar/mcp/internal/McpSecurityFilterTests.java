package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.openscholar.security.OidcSecurityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class McpSecurityFilterTests {

	private static final String REQUEST_ID_HEADER = "X-OpenScholar-Mcp-Request-Id";

	private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";

	private static final String API_KEY = "correct-local-key";

	@AfterEach
	void clearDiagnosticContext() {
		MDC.remove("mcpRequestId");
		SecurityContextHolder.clearContext();
	}

	@Test
	void acceptsCaseInsensitiveBearerAndAddsSecurityHeadersToSuccessfulRequests() throws Exception {
		McpSecurityFilter filter = filter(List.of("https://agent.example"));
		AtomicBoolean sawRequestIdInChain = new AtomicBoolean();
		Invocation invocation = invoke(filter, "/mcp", request -> {
			request.addHeader(HttpHeaders.AUTHORIZATION, "bEaReR " + API_KEY);
			request.addHeader(HttpHeaders.ORIGIN, "HTTPS://AGENT.EXAMPLE");
		}, response -> {
			assertThat(MDC.get("mcpRequestId")).isEqualTo(response.getHeader(REQUEST_ID_HEADER));
			sawRequestIdInChain.set(true);
		});

		assertThat(invocation.chainInvoked()).isTrue();
		assertThat(sawRequestIdInChain).isTrue();
		assertThat(invocation.response().getStatus()).isEqualTo(HttpServletResponse.SC_NO_CONTENT);
		assertSecurityHeaders(invocation.response());
		assertThat(invocation.response().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
		assertThat(MDC.get("mcpRequestId")).isNull();
	}

	@Test
	void permitsNativeClientsWithoutAnOrigin() throws Exception {
		Invocation invocation = invoke(filter(List.of()), "/mcp", authorized(), response -> {
		});

		assertThat(invocation.chainInvoked()).isTrue();
		assertThat(invocation.response().getStatus()).isEqualTo(HttpServletResponse.SC_NO_CONTENT);
		assertSecurityHeaders(invocation.response());
	}

	@Test
	void delegatesBearerAuthenticationToSpringSecurityWhenOidcIsEnabled() throws Exception {
		McpSecurityFilter filter = new McpSecurityFilter(
				new McpSecurityProperties(null, List.of()),
				new OidcSecurityProperties(
						true,
						java.net.URI.create("https://issuer.example"),
						java.net.URI.create("https://issuer.example/jwks"),
						"openscholar",
						java.net.URI.create("https://research.example/mcp")),
				defaultRateLimiter(),
				new SimpleMeterRegistry());

		Invocation invocation = invoke(filter, "/mcp", request -> {
		}, response -> {
		});

		assertThat(invocation.chainInvoked()).isTrue();
		assertThat(invocation.response().getStatus()).isEqualTo(HttpServletResponse.SC_NO_CONTENT);
		assertSecurityHeaders(invocation.response());
	}

	@Test
	void rejectsDisallowedOriginBeforeCheckingConfigurationOrCredentials() throws Exception {
		McpSecurityFilter filter = filter(new McpSecurityProperties(null, List.of()));
		Invocation invocation = invoke(filter, "/mcp", request -> {
			request.addHeader(HttpHeaders.ORIGIN, "https://attacker.example");
		}, response -> {
		});

		assertRejected(invocation, HttpServletResponse.SC_FORBIDDEN, "MCP_ORIGIN_REJECTED");
		assertThat(invocation.response().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
	}

	@Test
	void rejectsDuplicateOriginHeadersEvenWhenBothValuesAreAllowed() throws Exception {
		Invocation invocation = invoke(filter(List.of("https://agent.example")), "/mcp", request -> {
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
			request.addHeader(HttpHeaders.ORIGIN, "https://agent.example");
			request.addHeader(HttpHeaders.ORIGIN, "https://agent.example");
		}, response -> {
		});

		assertRejected(invocation, HttpServletResponse.SC_FORBIDDEN, "MCP_ORIGIN_REJECTED");
	}

	@Test
	void returnsServiceUnavailableWhenTheLocalKeyIsNotConfigured() throws Exception {
		McpSecurityFilter filter = filter(new McpSecurityProperties("  ", List.of()));
		Invocation invocation = invoke(filter, "/mcp", request -> {
		}, response -> {
		});

		assertRejected(invocation, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP_NOT_CONFIGURED");
		assertThat(invocation.response().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
	}

	@Test
	void rejectsMissingWrongAndMalformedBearerCredentials() throws Exception {
		McpSecurityFilter filter = filter(List.of());
		for (String authorization : List.of(
				"",
				"Basic " + API_KEY,
				"Bearer wrong-key",
				"Bearer",
				"Bearer  " + API_KEY,
				"Bearer " + API_KEY + " ")) {
			Invocation invocation = invoke(filter, "/mcp", request -> {
				if (!authorization.isEmpty()) {
					request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
				}
			}, response -> {
			});

			assertRejected(invocation, HttpServletResponse.SC_UNAUTHORIZED, "MCP_UNAUTHORIZED");
			assertThat(invocation.response().getHeader(HttpHeaders.WWW_AUTHENTICATE))
				.isEqualTo("Bearer realm=\"openscholar-mcp\"");
			assertThat(invocation.response().getContentAsString()).doesNotContain(API_KEY);
			if (!authorization.isEmpty()) {
				assertThat(invocation.response().getContentAsString()).doesNotContain(authorization);
			}
		}
	}

	@Test
	void rejectsDuplicateAuthorizationHeaders() throws Exception {
		Invocation invocation = invoke(filter(List.of()), "/mcp", request -> {
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
		}, response -> {
		});

		assertRejected(invocation, HttpServletResponse.SC_UNAUTHORIZED, "MCP_UNAUTHORIZED");
	}

	@Test
	void protectsNestedMcpPathsAndHonorsTheServletContextPath() throws Exception {
		McpSecurityFilter filter = filter(List.of());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/application/mcp/tools");
		request.setContextPath("/application");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainInvoked = new AtomicBoolean();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			chainInvoked.set(true);
			((HttpServletResponse) servletResponse).setStatus(HttpServletResponse.SC_NO_CONTENT);
		});

		assertThat(chainInvoked).isTrue();
		assertSecurityHeaders(response);
	}

	@Test
	void bypassesPathsThatOnlyShareTheMcpPrefix() throws Exception {
		McpSecurityFilter filter = filter(new McpSecurityProperties(null, List.of()));
		for (String path : List.of("/mcp-attacker", "/api/v1/system/status", "/api/v1/mcp")) {
			Invocation invocation = invoke(filter, path, request -> {
			}, response -> {
			});

			assertThat(invocation.chainInvoked()).isTrue();
			assertThat(invocation.response().getStatus()).isEqualTo(HttpServletResponse.SC_NO_CONTENT);
			assertThat(invocation.response().getHeader(REQUEST_ID_HEADER)).isNull();
			assertThat(invocation.response().getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
			assertThat(invocation.response().getHeader(CONTENT_TYPE_OPTIONS_HEADER)).isNull();
		}
	}

	@Test
	void replacesCallerSuppliedRequestIdsAndGeneratesANewIdPerRequest() throws Exception {
		McpSecurityFilter filter = filter(List.of());
		Invocation first = invoke(filter, "/mcp", request -> {
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
			request.addHeader(REQUEST_ID_HEADER, "caller-controlled");
		}, response -> {
		});
		Invocation second = invoke(filter, "/mcp", authorized(), response -> {
		});

		String firstId = first.response().getHeader(REQUEST_ID_HEADER);
		String secondId = second.response().getHeader(REQUEST_ID_HEADER);
		assertThat(firstId).isNotEqualTo("caller-controlled");
		assertThat(UUID.fromString(firstId)).isNotNull();
		assertThat(UUID.fromString(secondId)).isNotNull();
		assertThat(firstId).isNotEqualTo(secondId);
	}

	@Test
	void rateLimitsEachAuthenticatedRemoteAddressAndReturnsRetryAfter() throws Exception {
		McpRateLimitProperties rateLimitProperties = new McpRateLimitProperties(
				true, 1, Duration.ofSeconds(30), 100);
		McpRateLimiter rateLimiter = new McpRateLimiter(
				rateLimitProperties,
				Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));
		McpSecurityFilter filter = filter(
				new McpSecurityProperties(API_KEY, List.of()), rateLimiter, new SimpleMeterRegistry());

		Invocation first = invoke(filter, "/mcp", request -> {
			authorized().accept(request);
			request.setRemoteAddr("192.0.2.10");
		}, response -> {
		});
		Invocation limited = invoke(filter, "/mcp", request -> {
			authorized().accept(request);
			request.setRemoteAddr("192.0.2.10");
		}, response -> {
		});
		Invocation anotherClient = invoke(filter, "/mcp", request -> {
			authorized().accept(request);
			request.setRemoteAddr("192.0.2.11");
		}, response -> {
		});

		assertThat(first.chainInvoked()).isTrue();
		assertRejected(limited, HttpStatus.TOO_MANY_REQUESTS.value(), "MCP_RATE_LIMITED");
		assertThat(limited.response().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
		assertThat(anotherClient.chainInvoked()).isTrue();
	}

	@Test
	void rateLimitsHostedMcpByAuthenticatedSubjectInsteadOfSharedProxyAddress() throws Exception {
		McpRateLimiter rateLimiter = new McpRateLimiter(
				new McpRateLimitProperties(true, 1, Duration.ofMinutes(1), 100));
		McpSecurityFilter filter = new McpSecurityFilter(
				new McpSecurityProperties(null, List.of()),
				enabledOidc(),
				rateLimiter,
				new SimpleMeterRegistry());

		setOidcSubject("alice");
		Invocation aliceFirst = invoke(filter, "/mcp", request -> request.setRemoteAddr("10.0.0.2"), response -> {
		});
		Invocation aliceLimited = invoke(filter, "/mcp", request -> request.setRemoteAddr("10.0.0.2"), response -> {
		});
		setOidcSubject("bob");
		Invocation bobFirst = invoke(filter, "/mcp", request -> request.setRemoteAddr("10.0.0.2"), response -> {
		});

		assertThat(aliceFirst.chainInvoked()).isTrue();
		assertRejected(aliceLimited, HttpStatus.TOO_MANY_REQUESTS.value(), "MCP_RATE_LIMITED");
		assertThat(bobFirst.chainInvoked()).isTrue();
	}

	@Test
	void rejectedCredentialsDoNotConsumeTheAuthenticatedClientBudget() throws Exception {
		McpRateLimiter rateLimiter = new McpRateLimiter(new McpRateLimitProperties(
				true, 1, Duration.ofMinutes(1), 100));
		McpSecurityFilter filter = filter(
				new McpSecurityProperties(API_KEY, List.of()), rateLimiter, new SimpleMeterRegistry());

		Invocation unauthorized = invoke(filter, "/mcp", request -> {
			request.setRemoteAddr("192.0.2.20");
		}, response -> {
		});
		Invocation authorized = invoke(filter, "/mcp", request -> {
			authorized().accept(request);
			request.setRemoteAddr("192.0.2.20");
		}, response -> {
		});

		assertRejected(unauthorized, HttpServletResponse.SC_UNAUTHORIZED, "MCP_UNAUTHORIZED");
		assertThat(authorized.chainInvoked()).isTrue();
	}

	@Test
	void recordsRequestRejectionAndDurationMetrics() throws Exception {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		McpSecurityFilter filter = filter(
				new McpSecurityProperties(API_KEY, List.of()), defaultRateLimiter(), meterRegistry);

		invoke(filter, "/mcp", request -> {
		}, response -> {
		});

		assertThat(meterRegistry.counter("openscholar.mcp.requests").count()).isEqualTo(1);
		assertThat(meterRegistry.counter(
				"openscholar.mcp.rejections", "reason", "MCP_UNAUTHORIZED").count()).isEqualTo(1);
		assertThat(meterRegistry.timer("openscholar.mcp.request.duration").count()).isEqualTo(1);
	}

	private static McpSecurityFilter filter(List<String> allowedOrigins) {
		return filter(new McpSecurityProperties(API_KEY, allowedOrigins));
	}

	private static McpSecurityFilter filter(McpSecurityProperties securityProperties) {
		return filter(securityProperties, defaultRateLimiter(), new SimpleMeterRegistry());
	}

	private static McpSecurityFilter filter(
			McpSecurityProperties securityProperties,
			McpRateLimiter rateLimiter,
			SimpleMeterRegistry meterRegistry) {
		return new McpSecurityFilter(
				securityProperties,
				new OidcSecurityProperties(false, null, null, null, null),
				rateLimiter,
				meterRegistry);
	}

	private static McpRateLimiter defaultRateLimiter() {
		return new McpRateLimiter(new McpRateLimitProperties(
				true, 1_000, Duration.ofMinutes(1), 100));
	}

	private static OidcSecurityProperties enabledOidc() {
		return new OidcSecurityProperties(
				true,
				URI.create("https://issuer.example"),
				URI.create("https://issuer.example/jwks"),
				"openscholar",
				URI.create("https://research.example/mcp"));
	}

	private static void setOidcSubject(String subject) {
		Jwt jwt = Jwt.withTokenValue("synthetic-token")
				.header("alg", "RS256")
				.issuer("https://issuer.example")
				.subject(subject)
				.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
				jwt, List.of(new SimpleGrantedAuthority("SCOPE_openscholar.mcp"))));
	}

	private static Consumer<MockHttpServletRequest> authorized() {
		return request -> request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
	}

	private static Invocation invoke(
			McpSecurityFilter filter,
			String path,
			Consumer<MockHttpServletRequest> configure,
			Consumer<MockHttpServletResponse> inChain) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		configure.accept(request);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainInvoked = new AtomicBoolean();
		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			chainInvoked.set(true);
			inChain.accept(response);
			((HttpServletResponse) servletResponse).setStatus(HttpServletResponse.SC_NO_CONTENT);
		});
		return new Invocation(response, chainInvoked.get());
	}

	private static void assertRejected(Invocation invocation, int status, String code) throws Exception {
		assertThat(invocation.chainInvoked()).isFalse();
		assertThat(invocation.response().getStatus()).isEqualTo(status);
		assertThat(invocation.response().getContentType()).startsWith("application/problem+json");
		assertThat(invocation.response().getContentAsString()).contains("\"code\":\"" + code + "\"");
		assertSecurityHeaders(invocation.response());
	}

	private static void assertSecurityHeaders(MockHttpServletResponse response) {
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
		assertThat(response.getHeader(CONTENT_TYPE_OPTIONS_HEADER)).isEqualTo("nosniff");
		assertThat(UUID.fromString(response.getHeader(REQUEST_ID_HEADER))).isNotNull();
	}

	private record Invocation(MockHttpServletResponse response, boolean chainInvoked) {
	}
}
