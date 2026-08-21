package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpRequestSizeFilterTests {

	private static final int MAXIMUM_BYTES = 1_024;

	@Test
	void buffersAndReplaysAnAllowedBodyExactly() throws Exception {
		byte[] body = "{\"jsonrpc\":\"2.0\"}".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequest request = request("/mcp", body);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean invoked = new AtomicBoolean();

		filter().doFilter(request, response, (servletRequest, servletResponse) -> {
			invoked.set(true);
			HttpServletRequest replayed = (HttpServletRequest) servletRequest;
			assertThat(replayed.getContentLengthLong()).isEqualTo(body.length);
			assertThat(replayed.getInputStream().readAllBytes()).containsExactly(body);
		});

		assertThat(invoked).isTrue();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void rejectsAnOversizedDeclaredBodyBeforeTheChain() throws Exception {
		byte[] body = new byte[MAXIMUM_BYTES + 1];
		MockHttpServletRequest request = request("/mcp", body);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean invoked = new AtomicBoolean();

		filter().doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

		assertRejected(response, invoked);
	}

	@Test
	void rejectsAnOversizedStreamingBodyBeforeTheChain() throws Exception {
		byte[] body = new byte[MAXIMUM_BYTES + 1];
		MockHttpServletRequest base = request("/mcp", body);
		HttpServletRequest streamed = new HttpServletRequestWrapper(base) {
			@Override
			public int getContentLength() {
				return -1;
			}

			@Override
			public long getContentLengthLong() {
				return -1;
			}
		};
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean invoked = new AtomicBoolean();

		filter().doFilter(streamed, response,
				(servletRequest, servletResponse) -> invoked.set(true));

		assertRejected(response, invoked);
	}

	@Test
	void bypassesNonMcpPathsWithoutReadingTheirBodies() throws Exception {
		MockHttpServletRequest request = request("/api/v1/searches", new byte[MAXIMUM_BYTES + 1]);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean invoked = new AtomicBoolean();

		filter().doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

		assertThat(invoked).isTrue();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	private static MockHttpServletRequest request(String path, byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.setContentType(MediaType.APPLICATION_JSON_VALUE);
		request.setContent(body);
		return request;
	}

	private static McpRequestSizeFilter filter() {
		return new McpRequestSizeFilter(
				new McpPayloadProperties((long) MAXIMUM_BYTES, 2_048L),
				new SimpleMeterRegistry());
	}

	private static void assertRejected(
			MockHttpServletResponse response, AtomicBoolean invoked) throws IOException {
		assertThat(invoked).isFalse();
		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(response.getContentAsString())
				.contains("MCP_REQUEST_TOO_LARGE")
				.doesNotContain("byte[1025]");
	}
}
