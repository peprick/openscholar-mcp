package com.openscholar.search.internal;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.openscholar.provider.ProviderException;

final class ProviderFanoutUnavailableException extends RuntimeException {

	private final List<ProviderException> failures;

	ProviderFanoutUnavailableException(List<ProviderException> failures) {
		super("All enabled research providers failed", firstFailure(failures));
		this.failures = failures.stream()
				.map(failure -> Objects.requireNonNull(failure, "failures must not contain null"))
				.sorted(Comparator.comparing(failure -> failure.provider().name()))
				.toList();
	}

	List<String> warningCodes() {
		return failures.stream().map(ProviderException::errorCode).distinct().toList();
	}

	boolean retryable() {
		return failures.stream().anyMatch(ProviderException::retryable);
	}

	Duration retryAfter() {
		return failures.stream()
				.filter(ProviderException::retryable)
				.map(ProviderException::retryAfter)
				.filter(Objects::nonNull)
				.min(Duration::compareTo)
				.orElse(null);
	}

	private static ProviderException firstFailure(List<ProviderException> failures) {
		if (failures == null || failures.isEmpty()) {
			throw new IllegalArgumentException("Provider fan-out failure requires at least one provider failure");
		}
		return failures.stream()
				.filter(Objects::nonNull)
				.min(Comparator.comparing(failure -> failure.provider().name()))
				.orElseThrow(() -> new IllegalArgumentException(
						"Provider fan-out failures must not contain only null values"));
	}
}
