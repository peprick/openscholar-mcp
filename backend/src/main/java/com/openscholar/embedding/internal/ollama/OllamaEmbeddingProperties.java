package com.openscholar.embedding.internal.ollama;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(OllamaEmbeddingProperties.PREFIX)
public record OllamaEmbeddingProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("http://127.0.0.1:11434") URI baseUrl,
		String expectedDigest,
		@DefaultValue("false") boolean localOnlyConfirmed,
		@DefaultValue("2s") Duration connectTimeout,
		@DefaultValue("60s") Duration readTimeout,
		@DefaultValue("5m") String keepAlive) {

	public static final String PREFIX = "openscholar.embedding.ollama";

	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern SAFE_GO_DURATION = Pattern.compile(
			"(?:[1-9][0-9]*(?:ns|us|ms|s|m|h))+");

	public OllamaEmbeddingProperties {
		baseUrl = requireLoopbackRoot(baseUrl);
		if (expectedDigest == null || !SHA_256.matcher(expectedDigest).matches()) {
			throw new IllegalArgumentException(
					"Ollama expectedDigest must be a bare lowercase 64-character SHA-256 digest");
		}
		if (!localOnlyConfirmed) {
			throw new IllegalArgumentException(
					"Ollama localOnlyConfirmed must be true after cloud features are disabled on the Ollama server");
		}
		connectTimeout = requirePositive(connectTimeout, "connectTimeout");
		readTimeout = requirePositive(readTimeout, "readTimeout");
		if (keepAlive == null || keepAlive.length() > 32
				|| !SAFE_GO_DURATION.matcher(keepAlive).matches()) {
			throw new IllegalArgumentException(
					"Ollama keepAlive must be a positive ASCII Go duration such as 5m or 1h30m");
		}
	}

	private static URI requireLoopbackRoot(URI value) {
		if (value == null || !value.isAbsolute() || value.getHost() == null
				|| !"http".equals(value.getScheme().toLowerCase(Locale.ROOT))
				|| value.getUserInfo() != null
				|| value.getRawQuery() != null
				|| value.getRawFragment() != null
				|| !isRootPath(value.getRawPath())
				|| !isLoopbackHost(value.getHost())) {
			throw new IllegalArgumentException(
					"Ollama baseUrl must be a root HTTP URL on a numeric loopback IP address");
		}
		return value;
	}

	private static boolean isRootPath(String path) {
		return path == null || path.isEmpty() || path.equals("/");
	}

	private static boolean isLoopbackHost(String value) {
		String host = value.toLowerCase(Locale.ROOT);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		if (host.equals("::1")
				|| host.equals("0:0:0:0:0:0:0:1")) {
			return true;
		}
		String[] octets = host.split("\\.", -1);
		if (octets.length != 4 || !octets[0].equals("127")) {
			return false;
		}
		for (String octet : octets) {
			try {
				int number = Integer.parseInt(octet);
				if (number < 0 || number > 255) {
					return false;
				}
			}
			catch (NumberFormatException exception) {
				return false;
			}
		}
		return true;
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("Ollama " + name + " must be positive");
		}
		return value;
	}
}
