package com.openscholar.access.internal.persistence;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessLocationView;
import com.openscholar.access.AccessProviderCoverageView;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.ContentHandlingMode;
import com.openscholar.access.PaperAccessView;
import com.openscholar.access.internal.ResolvedAccessLocation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaperAccessStore {

	private final PaperAccessResolutionRepository resolutionRepository;
	private final PaperVersionRepository versionRepository;

	PaperAccessStore(
			PaperAccessResolutionRepository resolutionRepository,
			PaperVersionRepository versionRepository) {
		this.resolutionRepository = resolutionRepository;
		this.versionRepository = versionRepository;
	}

	@Transactional(readOnly = true)
	public Optional<StoredAccess> find(UUID paperId) {
		return resolutionRepository.findById(paperId)
				.map(resolution -> new StoredAccess(
						resolution.freshUntil(), toView(resolution, AccessDisposition.CACHE_HIT)));
	}

	@Transactional
	public PaperAccessView store(
			UUID paperId,
			AccessStatus status,
			AccessDisposition disposition,
			Instant checkedAt,
			Instant freshUntil,
			List<AccessProviderCoverageView> providerCoverage,
			List<String> warnings,
			Set<String> successfullyRefreshedSources,
			List<ResolvedAccessLocation> locations) {
		Instant now = checkedAt;
		for (String rawSource : successfullyRefreshedSources) {
			String source = normalizeSource(rawSource);
			for (PaperVersionEntity existing :
					versionRepository.findAllByPaperIdAndSourceAndActiveTrue(paperId, source)) {
				existing.deactivate(now);
			}
		}

		for (ResolvedAccessLocation location : locations) {
			String source = normalizeSource(location.source());
			String locationKey = locationKey(source, location.sourceKey());
			PaperVersionEntity entity = versionRepository
					.findByPaperIdAndSourceAndSourceLocationKey(paperId, source, locationKey)
					.orElseGet(() -> PaperVersionEntity.create(paperId, source, locationKey, location, now));
			if (entity.id() != null) {
				entity.apply(location, now);
			}
			versionRepository.save(entity);
		}
		versionRepository.flush();

		List<Map<String, Object>> coverageJson = providerCoverage.stream()
				.map(item -> Map.<String, Object>of(
						"provider", item.provider(),
						"status", item.status(),
						"candidateCount", item.candidateCount()))
				.toList();
		PaperAccessResolutionEntity resolution = resolutionRepository.findById(paperId)
				.orElseGet(() -> PaperAccessResolutionEntity.create(
						paperId, status, checkedAt, freshUntil, coverageJson, warnings, now));
		resolution.apply(status, checkedAt, freshUntil, coverageJson, warnings, now);
		resolutionRepository.saveAndFlush(resolution);
		return toView(resolution, disposition);
	}

	private PaperAccessView toView(
			PaperAccessResolutionEntity resolution, AccessDisposition disposition) {
		List<AccessLocationView> locations = versionRepository
				.findAllByPaperIdAndActiveTrue(resolution.paperId()).stream()
				.map(PaperAccessStore::toView)
				.sorted(locationOrder())
				.toList();
		return new PaperAccessView(
				resolution.paperId(),
				resolution.status(),
				disposition,
				resolution.checkedAt(),
				resolution.freshUntil(),
				coverageViews(resolution.providerCoverage()),
				resolution.warnings(),
				locations);
	}

	private static AccessLocationView toView(PaperVersionEntity entity) {
		return new AccessLocationView(
				entity.id(),
				entity.source(),
				entity.best(),
				entity.accessStatus(),
				entity.versionType(),
				entity.hostType(),
				parseSafeUri(entity.landingUrl()),
				parseSafeUri(entity.pdfUrl()),
				entity.hostDomain(),
				entity.licenseCode(),
				entity.evidence(),
				ContentHandlingMode.valueOf(entity.contentHandling()),
				entity.verificationStatus(),
				entity.verificationHttpStatus(),
				entity.verificationContentType(),
				entity.verificationFailureCode(),
				entity.providerUpdatedAt(),
				entity.retrievedAt(),
				entity.lastSeenAt(),
				entity.verifiedAt());
	}

	private static List<AccessProviderCoverageView> coverageViews(List<Map<String, Object>> items) {
		return items.stream()
				.map(item -> new AccessProviderCoverageView(
						String.valueOf(item.get("provider")),
						String.valueOf(item.get("status")),
						numberAsInt(item.get("candidateCount"))))
				.toList();
	}

	private static Comparator<AccessLocationView> locationOrder() {
		return Comparator
				.comparingInt((AccessLocationView location) -> statusPriority(location.accessStatus()))
				.thenComparing(AccessLocationView::best, Comparator.reverseOrder())
				.thenComparing(AccessLocationView::source)
				.thenComparing(AccessLocationView::id);
	}

	private static int statusPriority(AccessStatus status) {
		return switch (status) {
			case OPEN_PDF -> 0;
			case REPOSITORY_COPY, PREPRINT -> 1;
			case OPEN_LANDING_PAGE -> 2;
			case ABSTRACT_ONLY, RESTRICTED, UNKNOWN, UNAVAILABLE -> 3;
		};
	}

	private static String locationKey(String source, String rawKey) {
		if (rawKey == null || rawKey.isBlank() || rawKey.length() > 4096) {
			throw new IllegalArgumentException("Access source key must contain between 1 and 4096 characters");
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(
					(source + "\n" + rawKey.strip()).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String normalizeSource(String source) {
		if (source == null || source.isBlank()) {
			throw new IllegalArgumentException("Access source must not be blank");
		}
		return source.strip().toUpperCase(Locale.ROOT);
	}

	private static URI parseSafeUri(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(value);
			return uri.isAbsolute()
					&& "https".equalsIgnoreCase(uri.getScheme())
					&& uri.getHost() != null
					&& uri.getUserInfo() == null
					? uri
					: null;
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static int numberAsInt(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	public record StoredAccess(Instant freshUntil, PaperAccessView view) {

		public boolean isFreshAt(Instant now) {
			return now.isBefore(freshUntil);
		}
	}
}
