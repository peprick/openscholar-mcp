package com.openscholar.access.internal;

import java.time.Instant;
import java.util.Optional;

import com.openscholar.access.internal.provider.AccessCandidate;

public interface AccessCandidateVerifier {

	CandidateVerificationOutcome verify(AccessCandidate candidate, Instant retrievedAt);

	record CandidateVerificationOutcome(
			Optional<ResolvedAccessLocation> location,
			String warningCode) {

		public CandidateVerificationOutcome {
			location = location == null ? Optional.empty() : location;
		}

		public static CandidateVerificationOutcome accepted(ResolvedAccessLocation location) {
			return new CandidateVerificationOutcome(Optional.of(location), null);
		}

		public static CandidateVerificationOutcome rejected(String warningCode) {
			return new CandidateVerificationOutcome(Optional.empty(), warningCode);
		}
	}
}
