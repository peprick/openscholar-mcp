package com.openscholar.access.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

class AccessProviderHttpSupportTests {

	@Test
	void boundsStreamingBodiesWhenContentLengthIsUnknown() throws IOException {
		var interceptor = AccessProviderHttpSupport.boundedResponseBody(8);
		ClientHttpResponse response = interceptor.intercept(
				null,
				new byte[0],
				(request, requestBody) -> response("123456789".getBytes(), -1));

		assertThatThrownBy(() -> response.getBody().readAllBytes())
				.isInstanceOf(IOException.class)
				.hasMessageContaining("exceeded 8 bytes");
	}

	@Test
	void allowsACompleteBodyAtTheExactLimit() throws IOException {
		var interceptor = AccessProviderHttpSupport.boundedResponseBody(8);
		ClientHttpResponse response = interceptor.intercept(
				null,
				new byte[0],
				(request, requestBody) -> response("12345678".getBytes(), -1));

		assertThat(response.getBody().readAllBytes()).asString().isEqualTo("12345678");
	}

	@Test
	void rejectsAnOversizedDeclaredContentLengthBeforeBuffering() throws IOException {
		var interceptor = AccessProviderHttpSupport.boundedResponseBody(8);
		ClientHttpResponse response = interceptor.intercept(
				null,
				new byte[0],
				(request, requestBody) -> response("ignored".getBytes(), 9));

		assertThatThrownBy(response::getBody)
				.isInstanceOf(IOException.class)
				.hasMessageContaining("exceeded 8 bytes");
	}

	private static ClientHttpResponse response(byte[] body, long contentLength) {
		return new ClientHttpResponse() {
			private final HttpHeaders headers = headers(contentLength);

			@Override
			public HttpStatusCode getStatusCode() {
				return HttpStatus.OK;
			}

			@Override
			public String getStatusText() {
				return "OK";
			}

			@Override
			public void close() {
			}

			@Override
			public InputStream getBody() {
				return new ByteArrayInputStream(body);
			}

			@Override
			public HttpHeaders getHeaders() {
				return headers;
			}
		};
	}

	private static HttpHeaders headers(long contentLength) {
		HttpHeaders headers = new HttpHeaders();
		if (contentLength >= 0) {
			headers.setContentLength(contentLength);
		}
		return headers;
	}
}
