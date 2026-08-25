package com.openscholar.privacy.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.openscholar.privacy.PrivacyExportBusyException;
import org.springframework.stereotype.Component;

@Component
final class PrivacyExportConcurrencyGate {

	private final PrivacyExportProperties properties;
	private final Object monitor = new Object();
	private final Map<UUID, Integer> activeByPrincipal = new HashMap<>();
	private int activeGlobally;

	PrivacyExportConcurrencyGate(PrivacyExportProperties properties) {
		this.properties = Objects.requireNonNull(properties, "properties");
	}

	Permit acquire(UUID principalId) {
		Objects.requireNonNull(principalId, "principalId");
		synchronized (monitor) {
			int activeForPrincipal = activeByPrincipal.getOrDefault(principalId, 0);
			if (activeGlobally >= properties.globalPermits()
					|| activeForPrincipal >= properties.perPrincipalPermits()) {
				throw new PrivacyExportBusyException(properties.retryAfter());
			}
			activeGlobally++;
			activeByPrincipal.put(principalId, activeForPrincipal + 1);
		}
		return new Permit(this, principalId);
	}

	private void release(UUID principalId) {
		synchronized (monitor) {
			int activeForPrincipal = activeByPrincipal.getOrDefault(principalId, 0);
			if (activeForPrincipal < 1 || activeGlobally < 1) {
				throw new IllegalStateException("Privacy export permit accounting is inconsistent");
			}
			if (activeForPrincipal == 1) {
				activeByPrincipal.remove(principalId);
			}
			else {
				activeByPrincipal.put(principalId, activeForPrincipal - 1);
			}
			activeGlobally--;
		}
	}

	static final class Permit implements AutoCloseable {

		private final PrivacyExportConcurrencyGate gate;
		private final UUID principalId;
		private final AtomicBoolean closed = new AtomicBoolean();

		private Permit(PrivacyExportConcurrencyGate gate, UUID principalId) {
			this.gate = gate;
			this.principalId = principalId;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				gate.release(principalId);
			}
		}
	}
}
