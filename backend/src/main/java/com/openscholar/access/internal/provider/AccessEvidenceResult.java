package com.openscholar.access.internal.provider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AccessEvidenceResult(
		AccessSource source,
		AccessResolutionStatus status,
		List<AccessCandidate> candidates,
		Instant retrievedAt,
		Map<String, String> evidence) {

	public AccessEvidenceResult {
		source = Objects.requireNonNull(source, "source");
		status = Objects.requireNonNull(status, "status");
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
		evidence = copyEvidence(evidence);
		if (status == AccessResolutionStatus.RESOLVED && candidates.isEmpty()) {
			throw new IllegalArgumentException("A resolved result must include at least one candidate");
		}
		if (status != AccessResolutionStatus.RESOLVED && !candidates.isEmpty()) {
			throw new IllegalArgumentException("An unresolved result must not include candidates");
		}
		for (AccessCandidate candidate : candidates) {
			if (candidate.source() != source) {
				throw new IllegalArgumentException("Candidate source must match result source");
			}
		}
	}

	public static AccessEvidenceResult unresolved(
			AccessSource source,
			AccessResolutionStatus status,
			Instant retrievedAt,
			String reason) {
		if (status == AccessResolutionStatus.RESOLVED) {
			throw new IllegalArgumentException("Use a resolved result for candidates");
		}
		Map<String, String> evidence = reason == null ? Map.of() : Map.of("reason", reason);
		return new AccessEvidenceResult(source, status, List.of(), retrievedAt, evidence);
	}

	private static Map<String, String> copyEvidence(Map<String, String> value) {
		if (value == null || value.isEmpty()) {
			return Map.of();
		}
		if (value.size() > 12) {
			throw new IllegalArgumentException("Result evidence contains too many entries");
		}
		Map<String, String> copy = new LinkedHashMap<>();
		value.forEach((key, item) -> {
			if (key == null || key.isBlank() || key.length() > 100 || item == null || item.length() > 1_000) {
				throw new IllegalArgumentException("Result evidence contains an invalid entry");
			}
			copy.put(key, item);
		});
		return Map.copyOf(copy);
	}
}
