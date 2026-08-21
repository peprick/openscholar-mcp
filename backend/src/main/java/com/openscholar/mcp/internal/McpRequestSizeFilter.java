package com.openscholar.mcp.internal;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnBean(McpPayloadProperties.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
class McpRequestSizeFilter extends OncePerRequestFilter {

	private static final String MCP_PATH = "/mcp";
	private static final String REJECTIONS_METRIC = "openscholar.mcp.rejections";
	private static final String REJECTION_CODE = "MCP_REQUEST_TOO_LARGE";

	private final McpPayloadProperties properties;
	private final Counter rejectionCounter;

	McpRequestSizeFilter(McpPayloadProperties properties, MeterRegistry meterRegistry) {
		this.properties = properties;
		this.rejectionCounter = Counter.builder(REJECTIONS_METRIC)
				.description("MCP HTTP requests rejected before tool dispatch")
				.tag("reason", REJECTION_CODE)
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
		long maximumBytes = properties.maxRequestBytes();
		if (request.getContentLengthLong() > maximumBytes) {
			reject(response);
			return;
		}

		byte[] body = request.getInputStream().readNBytes(Math.toIntExact(maximumBytes + 1));
		if (body.length > maximumBytes) {
			reject(response);
			return;
		}

		filterChain.doFilter(new CachedBodyRequest(request, body), response);
	}

	private void reject(HttpServletResponse response) throws IOException {
		rejectionCounter.increment();
		response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"type\":\"urn:openscholar:problem:mcp-request-too-large\""
				+ ",\"title\":\"MCP request rejected\",\"status\":413"
				+ ",\"code\":\"" + REJECTION_CODE + "\""
				+ ",\"detail\":\"The MCP request body exceeds the configured byte limit.\"}");
	}

	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		private CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body.clone();
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}

		@Override
		public ServletInputStream getInputStream() {
			return new ByteArrayServletInputStream(body);
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), requestCharset()));
		}

		private Charset requestCharset() {
			String encoding = getCharacterEncoding();
			if (encoding == null || encoding.isBlank()) {
				return StandardCharsets.UTF_8;
			}
			try {
				return Charset.forName(encoding);
			}
			catch (IllegalArgumentException exception) {
				return StandardCharsets.UTF_8;
			}
		}
	}

	private static final class ByteArrayServletInputStream extends ServletInputStream {

		private final ByteArrayInputStream input;

		private ByteArrayServletInputStream(byte[] body) {
			this.input = new ByteArrayInputStream(body);
		}

		@Override
		public int read() {
			return input.read();
		}

		@Override
		public int read(byte[] bytes, int offset, int length) {
			return input.read(bytes, offset, length);
		}

		@Override
		public boolean isFinished() {
			return input.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			throw new IllegalStateException("Asynchronous reads are not supported for bounded MCP bodies");
		}
	}
}
