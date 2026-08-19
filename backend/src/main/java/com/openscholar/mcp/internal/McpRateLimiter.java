package com.openscholar.mcp.internal;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class McpRateLimiter {

	private static final int CLEANUP_INTERVAL = 256;
	private static final String UNKNOWN_CLIENT = "unknown";

	private final McpRateLimitProperties properties;
	private final Clock clock;
	private final ConcurrentMap<String, ClientWindow> clientWindows = new ConcurrentHashMap<>();
	private final AtomicLong acquisitions = new AtomicLong();
	private final Object newClientMonitor = new Object();
	private long nextClientExpiryMillis = Long.MAX_VALUE;

	@Autowired
	McpRateLimiter(McpRateLimitProperties properties) {
		this(properties, Clock.systemUTC());
	}

	McpRateLimiter(McpRateLimitProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	Decision acquire(String clientIdentifier) {
		if (!properties.enabled()) {
			return Decision.allowed();
		}

		long now = clock.millis();
		String clientKey = normalizeClientIdentifier(clientIdentifier);
		if (acquisitions.incrementAndGet() % CLEANUP_INTERVAL == 0) {
			cleanupExpiredWindows(now);
		}

		while (true) {
			if (!clientWindows.containsKey(clientKey)) {
				Decision newClientDecision = registerNewClient(clientKey, now);
				if (newClientDecision != null) {
					return newClientDecision;
				}
			}

			AtomicReference<Decision> decision = new AtomicReference<>();
			clientWindows.computeIfPresent(clientKey, (key, window) -> {
				if (window.hasExpired(now)) {
					decision.set(Decision.allowed());
					return new ClientWindow(1, resetAt(now));
				}
				if (window.requestCount() < properties.requestsPerWindow()) {
					decision.set(Decision.allowed());
					return new ClientWindow(window.requestCount() + 1, window.resetAtMillis());
				}

				decision.set(Decision.rejected(retryAfterSeconds(now, window.resetAtMillis())));
				return window;
			});
			if (decision.get() != null) {
				return decision.get();
			}
		}
	}

	private Decision registerNewClient(String clientKey, long now) {
		synchronized (newClientMonitor) {
			if (clientWindows.containsKey(clientKey)) {
				return null;
			}
			if (clientWindows.size() >= properties.maximumTrackedClients()) {
				if (now >= nextClientExpiryMillis) {
					removeExpiredWindowsAndTrackNextExpiry(now);
				}
				if (clientWindows.size() >= properties.maximumTrackedClients()) {
					long retryAt = nextClientExpiryMillis == Long.MAX_VALUE
							? resetAt(now)
							: nextClientExpiryMillis;
					return Decision.rejected(retryAfterSeconds(now, retryAt));
				}
			}
			long resetAtMillis = resetAt(now);
			clientWindows.put(clientKey, new ClientWindow(1, resetAtMillis));
			nextClientExpiryMillis = Math.min(nextClientExpiryMillis, resetAtMillis);
			return Decision.allowed();
		}
	}

	private void cleanupExpiredWindows(long now) {
		synchronized (newClientMonitor) {
			removeExpiredWindowsAndTrackNextExpiry(now);
		}
	}

	private void removeExpiredWindowsAndTrackNextExpiry(long now) {
		clientWindows.forEach((client, window) -> {
			if (window.hasExpired(now)) {
				clientWindows.remove(client, window);
			}
		});
		nextClientExpiryMillis = clientWindows.values()
			.stream()
			.mapToLong(ClientWindow::resetAtMillis)
			.min()
			.orElse(Long.MAX_VALUE);
	}

	private long resetAt(long now) {
		try {
			return Math.addExact(now, properties.window().toMillis());
		}
		catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	private static long retryAfterSeconds(long now, long resetAtMillis) {
		long remainingMillis = Math.max(1, resetAtMillis - now);
		return Math.max(1, Math.ceilDiv(remainingMillis, 1_000));
	}

	private static String normalizeClientIdentifier(String clientIdentifier) {
		return clientIdentifier == null || clientIdentifier.isBlank()
				? UNKNOWN_CLIENT
				: clientIdentifier.strip();
	}

	record Decision(boolean permitted, long retryAfterSeconds) {

		private static Decision allowed() {
			return new Decision(true, 0);
		}

		private static Decision rejected(long retryAfterSeconds) {
			return new Decision(false, retryAfterSeconds);
		}
	}

	private record ClientWindow(int requestCount, long resetAtMillis) {

		private boolean hasExpired(long now) {
			return now >= resetAtMillis;
		}
	}
}
