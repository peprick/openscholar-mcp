package com.openscholar.access.internal.verification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Objects;

interface HttpProbeClient {

	HttpProbeResponse exchange(HttpProbeRequest request) throws IOException, InterruptedException;
}

record HttpProbeRequest(
		URI uri,
		ProbeMethod method,
		Duration timeout,
		int maxResponseBytes,
		String accept) {

	HttpProbeRequest {
		Objects.requireNonNull(uri, "uri");
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(timeout, "timeout");
		Objects.requireNonNull(accept, "accept");
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		if (maxResponseBytes < 0) {
			throw new IllegalArgumentException("maxResponseBytes must not be negative");
		}
		if (method == ProbeMethod.RANGE_GET && maxResponseBytes == 0) {
			throw new IllegalArgumentException("range GET probes must allow at least one response byte");
		}
	}
}

enum ProbeMethod {
	HEAD,
	RANGE_GET
}

record HttpProbeResponse(int statusCode, HttpHeaders headers, byte[] bodyPrefix) {

	HttpProbeResponse {
		Objects.requireNonNull(headers, "headers");
		bodyPrefix = bodyPrefix == null ? new byte[0] : bodyPrefix.clone();
	}

	@Override
	public byte[] bodyPrefix() {
		return bodyPrefix.clone();
	}
}
