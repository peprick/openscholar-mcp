package com.openscholar.embedding.internal.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

class OllamaResponseSizeLimitInterceptorTests {

	private static final HttpRequest REQUEST = new HttpRequest() {
		private final HttpHeaders headers = new HttpHeaders();

		@Override
		public HttpMethod getMethod() {
			return HttpMethod.GET;
		}

		@Override
		public URI getURI() {
			return URI.create("http://127.0.0.1:11434/api/version");
		}

		@Override
		public Map<String, Object> getAttributes() {
			return Map.of();
		}

		@Override
		public HttpHeaders getHeaders() {
			return headers;
		}
	};

	@Test
	void rejectsAnOversizedDeclaredBodyBeforeReadingIt() {
		StubResponse response = new StubResponse(new byte[] {1});
		response.headers.setContentLength(9);
		OllamaResponseSizeLimitInterceptor interceptor =
				new OllamaResponseSizeLimitInterceptor(8);

		assertThatThrownBy(() -> interceptor.intercept(
				REQUEST, new byte[0], (request, body) -> response))
				.isInstanceOf(OllamaResponseTooLargeException.class)
				.hasMessageContaining("8 byte");
		assertThat(response.closed).isTrue();
	}

	@Test
	void boundsAResponseWithoutAContentLengthWhileItStreams() throws Exception {
		StubResponse response = new StubResponse(new byte[9]);
		OllamaResponseSizeLimitInterceptor interceptor =
				new OllamaResponseSizeLimitInterceptor(8);

		ClientHttpResponse limited = interceptor.intercept(
				REQUEST, new byte[0], (request, body) -> response);

		assertThatThrownBy(() -> limited.getBody().readAllBytes())
				.isInstanceOf(OllamaResponseTooLargeException.class)
				.hasMessageContaining("8 byte");
	}

	@Test
	void acceptsAResponseAtTheExactLimit() throws Exception {
		StubResponse response = new StubResponse(new byte[8]);
		OllamaResponseSizeLimitInterceptor interceptor =
				new OllamaResponseSizeLimitInterceptor(8);

		ClientHttpResponse limited = interceptor.intercept(
				REQUEST, new byte[0], (request, body) -> response);

		assertThat(limited.getBody().readAllBytes()).hasSize(8);
	}

	private static final class StubResponse implements ClientHttpResponse {

		private final HttpHeaders headers = new HttpHeaders();
		private final InputStream body;
		private boolean closed;

		private StubResponse(byte[] body) {
			this.body = new ByteArrayInputStream(body);
		}

		@Override
		public HttpStatusCode getStatusCode() {
			return HttpStatus.OK;
		}

		@Override
		public String getStatusText() {
			return "OK";
		}

		@Override
		public HttpHeaders getHeaders() {
			return headers;
		}

		@Override
		public InputStream getBody() {
			return body;
		}

		@Override
		public void close() {
			closed = true;
			try {
				body.close();
			}
			catch (IOException ignored) {
				// ByteArrayInputStream.close() is specified as a no-op.
			}
		}
	}
}
