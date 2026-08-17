package com.openscholar.access.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessLocationView;
import com.openscholar.access.AccessProviderCoverageView;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessUnavailableException;
import com.openscholar.access.PaperAccessUseCase;
import com.openscholar.access.PaperAccessView;
import com.openscholar.access.internal.persistence.PaperAccessStore;
import com.openscholar.access.internal.provider.AccessCandidate;
import com.openscholar.access.internal.provider.AccessEvidenceLookup;
import com.openscholar.access.internal.provider.AccessEvidenceProvider;
import com.openscholar.access.internal.provider.AccessEvidenceResult;
import com.openscholar.access.internal.provider.AccessProviderException;
import com.openscholar.access.internal.provider.AccessResolutionStatus;
import com.openscholar.access.internal.provider.AccessSource;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import org.springframework.stereotype.Service;

@Service
class PaperAccessService implements PaperAccessUseCase {

	private final PaperCatalog paperCatalog;
	private final List<AccessEvidenceProvider> providers;
	private final AccessCandidateVerifier candidateVerifier;
	private final PaperAccessStore accessStore;
	private final AccessProperties properties;
	private final Clock clock;

	PaperAccessService(
			PaperCatalog paperCatalog,
			List<AccessEvidenceProvider> providers,
			AccessCandidateVerifier candidateVerifier,
			PaperAccessStore accessStore,
			AccessProperties properties,
			Clock clock) {
		this.paperCatalog = paperCatalog;
		this.providers = providers.stream()
				.sorted(Comparator.comparing(provider -> provider.source().name()))
				.toList();
		this.candidateVerifier = candidateVerifier;
		this.accessStore = accessStore;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public PaperAccessView get(UUID paperId) {
		PaperView paper = requirePaper(paperId);
		return accessStore.find(paperId)
				.map(PaperAccessStore.StoredAccess::view)
				.orElseGet(() -> unresolvedView(paper));
	}

	@Override
	public PaperAccessView resolve(UUID paperId, boolean forceRefresh) {
		PaperView paper = requirePaper(paperId);
		Instant now = clock.instant();
		Optional<PaperAccessStore.StoredAccess> previous = accessStore.find(paperId);
		if (!forceRefresh && previous.isPresent() && previous.orElseThrow().isFreshAt(now)) {
			return previous.orElseThrow().view();
		}

		AccessEvidenceLookup lookup = lookup(paper);
		if (lookup.normalizedDoi() == null && lookup.canonicalArxivId() == null) {
			return accessStore.store(
					paperId,
					fallbackStatus(paper),
					AccessDisposition.NO_SUPPORTED_IDENTIFIER,
					now,
					now.plus(properties.getCacheTtl()),
					List.of(),
					List.of("NO_SUPPORTED_IDENTIFIER"),
					Set.of(),
					List.of());
		}

		List<AccessProviderCoverageView> coverage = new ArrayList<>();
		List<AccessEvidenceResult> results = new ArrayList<>();
		LinkedHashSet<String> warnings = new LinkedHashSet<>();
		List<AccessProviderException> failures = new ArrayList<>();
		for (AccessEvidenceProvider provider : providers) {
			try {
				AccessEvidenceResult result = provider.resolve(lookup);
				results.add(result);
				coverage.add(new AccessProviderCoverageView(
						result.source().name(), result.status().name(), result.candidates().size()));
				if (result.status() == AccessResolutionStatus.NOT_CONFIGURED) {
					warnings.add(result.source().name() + "_NOT_CONFIGURED");
				}
			}
			catch (AccessProviderException exception) {
				failures.add(exception);
				coverage.add(new AccessProviderCoverageView(
						exception.source().name(), "FAILED", 0));
				warnings.add(exception.errorCode());
			}
		}

		boolean anyCompletedProvider = results.stream().anyMatch(result -> switch (result.status()) {
			case RESOLVED, CLOSED, NO_RECORD -> true;
			case NOT_APPLICABLE, NOT_CONFIGURED -> false;
		});
		if (!failures.isEmpty() && !anyCompletedProvider) {
			if (previous.isPresent()) {
				return withFallback(previous.orElseThrow().view(), warnings);
			}
			throw new AccessUnavailableException(
					"Access providers could not complete the request", failures.getFirst());
		}

		List<ResolvedAccessLocation> verifiedLocations = new ArrayList<>();
		Set<String> successfullyRefreshedSources = new HashSet<>();
		boolean providerReportedCandidates = false;
		int remainingVerifications = properties.getMaxLocationsToVerify();
		for (AccessEvidenceResult result : results) {
			if (result.status() == AccessResolutionStatus.CLOSED
					|| result.status() == AccessResolutionStatus.NO_RECORD) {
				successfullyRefreshedSources.add(result.source().name());
			}
			if (result.status() != AccessResolutionStatus.RESOLVED) {
				continue;
			}
			providerReportedCandidates = true;
			boolean acceptedForSource = false;
			for (AccessCandidate candidate : orderedCandidates(result.candidates())) {
				if (remainingVerifications-- <= 0) {
					warnings.add("ACCESS_VERIFICATION_LIMIT_REACHED");
					break;
				}
				var outcome = candidateVerifier.verify(candidate, result.retrievedAt());
				if (outcome.warningCode() != null) {
					warnings.add(outcome.warningCode());
				}
				if (outcome.location().isPresent()) {
					verifiedLocations.add(outcome.location().orElseThrow());
					acceptedForSource = true;
				}
			}
			if (acceptedForSource) {
				successfullyRefreshedSources.add(result.source().name());
			}
		}

		List<AccessLocationView> retainedLocations = previous
				.map(PaperAccessStore.StoredAccess::view)
				.map(PaperAccessView::locations)
				.orElseGet(List::of).stream()
				.filter(location -> !successfullyRefreshedSources.contains(location.source()))
				.toList();
		AccessStatus status = overallStatus(
				paper,
				verifiedLocations,
				retainedLocations,
				results,
				!failures.isEmpty(),
				providerReportedCandidates);
		AccessDisposition disposition = previous.isEmpty()
				? AccessDisposition.RESOLVED
				: forceRefresh ? AccessDisposition.FORCED_REFRESH : AccessDisposition.REFRESHED;
		return accessStore.store(
				paperId,
				status,
				disposition,
				now,
				now.plus(properties.getCacheTtl()),
				coverage,
				List.copyOf(warnings),
				successfullyRefreshedSources,
				verifiedLocations);
	}

	private PaperView requirePaper(UUID paperId) {
		return paperCatalog.findById(paperId)
				.orElseThrow(() -> new PaperNotFoundException(paperId));
	}

	private static AccessEvidenceLookup lookup(PaperView paper) {
		String doi = identifier(paper, PaperIdentifierType.DOI);
		String arxiv = identifier(paper, PaperIdentifierType.ARXIV);
		return new AccessEvidenceLookup(doi, arxiv);
	}

	private static String identifier(PaperView paper, PaperIdentifierType type) {
		return paper.identifiers().stream()
				.filter(identifier -> identifier.type() == type)
				.map(identifier -> identifier.value())
				.findFirst()
				.orElse(null);
	}

	private static List<AccessCandidate> orderedCandidates(List<AccessCandidate> candidates) {
		return candidates.stream()
				.sorted(Comparator.comparing(AccessCandidate::best).reversed()
						.thenComparing(candidate -> candidate.sourceKey().toLowerCase(Locale.ROOT)))
				.toList();
	}

	private static AccessStatus overallStatus(
			PaperView paper,
			List<ResolvedAccessLocation> newLocations,
			List<AccessLocationView> retainedLocations,
			List<AccessEvidenceResult> providerResults,
			boolean hasProviderFailure,
			boolean providerReportedCandidates) {
		List<AccessStatus> available = new ArrayList<>();
		newLocations.forEach(location -> available.add(location.accessStatus()));
		retainedLocations.forEach(location -> available.add(location.accessStatus()));
		if (!available.isEmpty()) {
			return available.stream().min(Comparator.comparingInt(PaperAccessService::priority)).orElseThrow();
		}
		if (hasProviderFailure) {
			return AccessStatus.UNKNOWN;
		}
		if (providerReportedCandidates) {
			return AccessStatus.UNAVAILABLE;
		}
		if (providerResults.stream().anyMatch(result -> result.status() == AccessResolutionStatus.CLOSED)) {
			return AccessStatus.RESTRICTED;
		}
		return fallbackStatus(paper);
	}

	private static int priority(AccessStatus status) {
		return switch (status) {
			case OPEN_PDF -> 0;
			case PREPRINT, REPOSITORY_COPY -> 1;
			case OPEN_LANDING_PAGE -> 2;
			case ABSTRACT_ONLY -> 3;
			case RESTRICTED -> 4;
			case UNKNOWN -> 5;
			case UNAVAILABLE -> 6;
		};
	}

	private static AccessStatus fallbackStatus(PaperView paper) {
		return paper.abstractText() == null || paper.abstractText().isBlank()
				? AccessStatus.UNKNOWN
				: AccessStatus.ABSTRACT_ONLY;
	}

	private static PaperAccessView unresolvedView(PaperView paper) {
		return new PaperAccessView(
				paper.id(),
				fallbackStatus(paper),
				AccessDisposition.NOT_YET_RESOLVED,
				null,
				null,
				List.of(),
				List.of(),
				List.of());
	}

	private static PaperAccessView withFallback(
			PaperAccessView previous, Set<String> additionalWarnings) {
		LinkedHashSet<String> warnings = new LinkedHashSet<>(previous.warnings());
		warnings.addAll(additionalWarnings);
		return new PaperAccessView(
				previous.paperId(),
				previous.status(),
				AccessDisposition.STALE_FALLBACK,
				previous.checkedAt(),
				previous.freshUntil(),
				previous.providerCoverage(),
				List.copyOf(warnings),
				previous.locations());
	}
}
