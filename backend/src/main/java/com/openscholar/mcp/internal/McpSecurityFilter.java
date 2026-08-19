package com.openscholar.mcp.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnBean(McpSecurityProperties.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class McpSecurityFilter extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(McpSecurityFilter.class);
	private static final String MCP_PATH = "/mcp";
	private static final String REQUEST_ID_HEADER = "X-OpenScholar-Mcp-Request-Id";
	private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
	private static final String BEARER_SCHEME = "Bearer";
	private static final String REQUESTS_METRIC = "openscholar.mcp.requests";
	private static final String REJECTIONS_METRIC = "openscholar.mcp.rejections";
	private static final String DURATION_METRIC = "openscholar.mcp.request.duration";

	private final McpSecurityProperties properties;
	private final McpRateLimiter rateLimiter;
	private final MeterRegistry meterRegistry;
	private final Counter requestCounter;
	private final Timer requestTimer;

	McpSecurityFilter(
			McpSecurityProperties properties,
			McpRateLimiter rateLimiter,
			MeterRegistry meterRegistry) {
		this.properties = properties;
		this.rateLimiter = rateLimiter;
		this.meterRegistry = meterRegistry;
		this.requestCounter = Counter.builder(REQUESTS_METRIC)
				.description("Total MCP HTTP requests processed by the security filter")
				.register(meterRegistry);
		this.requestTimer = Timer.builder(DURATION_METRIC)
				.description("MCP HTTP request duration through the security filter")
				.register(meterRegistry);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !path.equals(MCP_PATH) && !path.startsWith(MCP_PATH + "/");
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		response.setHeader(REQUEST_ID_HEADER, requestId);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader(CONTENT_TYPE_OPTIONS_HEADER, "nosniff");
		MDC.put("mcpRequestId", requestId);
		Timer.Sample timerSample = Timer.start(meterRegistry);
		try {
			if (hasMultipleValues(request, HttpHeaders.ORIGIN)
					|| !properties.allowsOrigin(request.getHeader(HttpHeaders.ORIGIN))) {
				reject(response, HttpServletResponse.SC_FORBIDDEN,
						"MCP_ORIGIN_REJECTED", "The request Origin is not allowed.");
				return;
			}
			if (!properties.hasApiKey()) {
				reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
						"MCP_NOT_CONFIGURED", "Set MCP_LOCAL_API_KEY before using the MCP endpoint.");
				return;
			}
			if (!hasValidBearerToken(request)) {
				response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"openscholar-mcp\"");
				reject(response, HttpServletResponse.SC_UNAUTHORIZED,
						"MCP_UNAUTHORIZED", "A valid local MCP bearer key is required.");
				return;
			}
			McpRateLimiter.Decision rateLimitDecision = rateLimiter.acquire(request.getRemoteAddr());
			if (!rateLimitDecision.permitted()) {
				response.setHeader(HttpHeaders.RETRY_AFTER,
						Long.toString(rateLimitDecision.retryAfterSeconds()));
				reject(response, HttpStatus.TOO_MANY_REQUESTS.value(),
						"MCP_RATE_LIMITED", "The MCP request limit for this client has been exceeded.");
				return;
			}

			filterChain.doFilter(request, response);
		}
		finally {
			requestCounter.increment();
			timerSample.stop(requestTimer);
			LOGGER.info("MCP request completed: requestId={}, method={}, status={}",
					requestId, request.getMethod(), response.getStatus());
			MDC.remove("mcpRequestId");
		}
	}

	private boolean hasValidBearerToken(HttpServletRequest request) {
		if (hasMultipleValues(request, HttpHeaders.AUTHORIZATION)) {
			return false;
		}
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || authorization.length() <= BEARER_SCHEME.length()
				|| !authorization.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())
				|| authorization.charAt(BEARER_SCHEME.length()) != ' ') {
			return false;
		}
		byte[] actual = authorization.substring(BEARER_SCHEME.length() + 1)
				.getBytes(StandardCharsets.UTF_8);
		byte[] expected = properties.localApiKey().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(actual, expected);
	}

	private void reject(
			HttpServletResponse response, int status, String code, String detail) throws IOException {
		Counter.builder(REJECTIONS_METRIC)
				.description("MCP HTTP requests rejected before tool dispatch")
				.tag("reason", code)
				.register(meterRegistry)
				.increment();
		writeProblem(response, status, code, detail);
	}

	private static boolean hasMultipleValues(HttpServletRequest request, String name) {
		Enumeration<String> values = request.getHeaders(name);
		if (values == null || !values.hasMoreElements()) {
			return false;
		}
		values.nextElement();
		return values.hasMoreElements();
	}

	private static void writeProblem(
			HttpServletResponse response, int status, String code, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.getWriter().write("{\"type\":\"urn:openscholar:problem:"
				+ code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')
				+ "\",\"title\":\"MCP request rejected\",\"status\":" + status
				+ ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
	}
}
