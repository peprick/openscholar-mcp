package com.openscholar.mcp.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
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
	private static final String BEARER_PREFIX = "Bearer ";

	private final McpSecurityProperties properties;

	McpSecurityFilter(McpSecurityProperties properties) {
		this.properties = properties;
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
		MDC.put("mcpRequestId", requestId);
		try {
			if (!properties.allowsOrigin(request.getHeader(HttpHeaders.ORIGIN))) {
				writeProblem(response, HttpServletResponse.SC_FORBIDDEN,
						"MCP_ORIGIN_REJECTED", "The request Origin is not allowed.");
				return;
			}
			if (!properties.hasApiKey()) {
				writeProblem(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
						"MCP_NOT_CONFIGURED", "Set MCP_LOCAL_API_KEY before using the MCP endpoint.");
				return;
			}
			if (!hasValidBearerToken(request)) {
				response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"openscholar-mcp\"");
				writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED,
						"MCP_UNAUTHORIZED", "A valid local MCP bearer key is required.");
				return;
			}

			filterChain.doFilter(request, response);
		}
		finally {
			LOGGER.info("MCP request completed: requestId={}, method={}, status={}",
					requestId, request.getMethod(), response.getStatus());
			MDC.remove("mcpRequestId");
		}
	}

	private boolean hasValidBearerToken(HttpServletRequest request) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			return false;
		}
		byte[] actual = authorization.substring(BEARER_PREFIX.length())
				.getBytes(StandardCharsets.UTF_8);
		byte[] expected = properties.localApiKey().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(actual, expected);
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
