package com.openscholar.paper.internal.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class PaperCatalogService implements PaperCatalog {

	private static final int MAX_METADATA_JSON_BYTES = 30 * 1024;

	private final PaperRepository paperRepository;
	private final PaperExternalIdRepository externalIdRepository;
	private final ProviderRecordRepository providerRecordRepository;
	private final AuthorRepository authorRepository;
	private final PaperAuthorRepository paperAuthorRepository;
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	PaperCatalogService(
			PaperRepository paperRepository,
			PaperExternalIdRepository externalIdRepository,
			ProviderRecordRepository providerRecordRepository,
			AuthorRepository authorRepository,
			PaperAuthorRepository paperAuthorRepository,
			JdbcTemplate jdbcTemplate,
			ObjectMapper objectMapper) {
		this.paperRepository = paperRepository;
		this.externalIdRepository = externalIdRepository;
		this.providerRecordRepository = providerRecordRepository;
		this.authorRepository = authorRepository;
		this.paperAuthorRepository = paperAuthorRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	@Transactional
	public PaperView upsert(
			CanonicalPaperCandidate candidate,
			ProviderRecordCandidate providerRecord,
			Instant now) {
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(providerRecord, "providerRecord");
		Objects.requireNonNull(now, "now");

		List<NormalizedIdentifier> identifiers = normalizeIdentifiers(candidate.identifiers());
		String provider = ProviderRecordEntity.normalizeProvider(providerRecord.provider());
		String providerRecordId = ProviderRecordEntity.cleanRequired(
				providerRecord.providerRecordId(), "Provider record identifier must not be blank");
		acquireLocks(identifiers, candidate.authors(), provider, providerRecordId);

		Map<NormalizedIdentifier, PaperExternalIdEntity> existingIdentifiers = findExistingIdentifiers(identifiers);
		Optional<ProviderRecordEntity> existingProviderRecord =
				providerRecordRepository.findByProviderAndProviderRecordId(provider, providerRecordId);
		if (existingProviderRecord.isPresent()
				&& providerRecord.retrievedAt().isBefore(existingProviderRecord.orElseThrow().retrievedAt())) {
			return loadViews(List.of(existingProviderRecord.orElseThrow().paperId()))
					.get(existingProviderRecord.orElseThrow().paperId());
		}
		UUID paperId = resolvePaperId(identifiers, existingIdentifiers, existingProviderRecord);

		Instant metadataTimestamp = providerRecord.providerUpdatedAt() == null
				? providerRecord.retrievedAt()
				: providerRecord.providerUpdatedAt();
		PaperEntity paper;
		if (paperId == null) {
			paper = paperRepository.save(PaperEntity.create(candidate, metadataTimestamp, now));
		} else {
			paper = paperRepository.findById(paperId)
					.orElseThrow(() -> new PaperCatalogConflictException(
							"An identifier references a paper that no longer exists"));
			paper.apply(candidate, metadataTimestamp, now);
		}
		Instant citationAsOf = candidate.citationCount() == null
				? null
				: Objects.requireNonNullElse(candidate.citationCountAsOf(), providerRecord.retrievedAt());
		paper.applyCitation(candidate.citationCount(), citationAsOf, now);

		attachIdentifiers(paper, identifiers, existingIdentifiers, now);

		Map<String, Object> metadataFragment = validateBoundedMetadata(providerRecord.metadataFragment());
		ProviderRecordEntity storedProviderRecord;
		boolean providerRecordAccepted;
		if (existingProviderRecord.isPresent()) {
			storedProviderRecord = existingProviderRecord.orElseThrow();
			if (!storedProviderRecord.paperId().equals(paper.id())) {
				throw conflict("Provider record", provider + ":" + providerRecordId);
			}
			providerRecordAccepted = storedProviderRecord.apply(providerRecord, metadataFragment, now);
		} else {
			storedProviderRecord = ProviderRecordEntity.create(paper, providerRecord, metadataFragment, now);
			providerRecordRepository.save(storedProviderRecord);
			providerRecordAccepted = true;
		}

		if (providerRecordAccepted) {
			replaceProviderAuthors(paper, storedProviderRecord, candidate.authors(), now);
		}

		paper.updateMetadataQuality(calculateMetadataQuality(paper), now);
		paperRepository.flush();
		return loadViews(List.of(paper.id())).get(paper.id());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, PaperView> findAllByIds(Collection<UUID> paperIds) {
		Objects.requireNonNull(paperIds, "paperIds");
		LinkedHashSet<UUID> requestedIds = paperIds.stream()
				.filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		return loadViews(requestedIds);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PaperView> findById(UUID paperId) {
		Objects.requireNonNull(paperId, "paperId");
		return Optional.ofNullable(loadViews(List.of(paperId)).get(paperId));
	}

	private void acquireLocks(
			List<NormalizedIdentifier> identifiers,
			List<PaperAuthorCandidate> authors,
			String provider,
			String providerRecordId) {
		List<String> lockKeys = identifiers.stream()
				.map(NormalizedIdentifier::lockKey)
				.collect(Collectors.toCollection(ArrayList::new));
		for (PaperAuthorCandidate author : authors) {
			String openAlexId = AuthorEntity.normalizeOpenAlexId(author.openAlexId());
			if (openAlexId != null) {
				lockKeys.add("author:openalex:" + openAlexId);
			}
			String orcid = AuthorEntity.normalizeOrcid(author.orcid());
			if (orcid != null) {
				lockKeys.add("author:orcid:" + orcid);
			}
		}
		lockKeys.add("provider:" + provider + ":" + providerRecordId);
		lockKeys.stream().distinct().sorted().forEach(key ->
				jdbcTemplate.queryForList(
						"select pg_advisory_xact_lock(hashtextextended(?, 0))", key));
	}

	private Map<NormalizedIdentifier, PaperExternalIdEntity> findExistingIdentifiers(
			List<NormalizedIdentifier> identifiers) {
		Map<NormalizedIdentifier, PaperExternalIdEntity> existing = new HashMap<>();
		for (NormalizedIdentifier identifier : identifiers) {
			externalIdRepository.findByIdTypeAndNamespaceAndNormalizedValue(
						identifier.type(), identifier.namespace(), identifier.normalizedValue())
					.ifPresent(entity -> existing.put(identifier, entity));
		}
		return existing;
	}

	private UUID resolvePaperId(
			List<NormalizedIdentifier> identifiers,
			Map<NormalizedIdentifier, PaperExternalIdEntity> existingIdentifiers,
			Optional<ProviderRecordEntity> existingProviderRecord) {
		UUID resolved = uniqueMatch(
				identifiers,
				existingIdentifiers,
				identifier -> identifier.type() == PaperIdentifierType.DOI,
				"DOI");
		if (resolved == null) {
			resolved = uniqueMatch(
					identifiers,
					existingIdentifiers,
					identifier -> identifier.type() == PaperIdentifierType.OPENALEX,
					"OpenAlex identifier");
		}
		if (resolved == null && existingProviderRecord.isPresent()) {
			resolved = existingProviderRecord.orElseThrow().paperId();
		}
		if (resolved == null) {
			resolved = uniqueMatch(identifiers, existingIdentifiers, identifier -> true, "identifier");
		}

		Set<UUID> allMatches = existingIdentifiers.values().stream()
				.map(PaperExternalIdEntity::paperId)
				.collect(Collectors.toSet());
		if (existingProviderRecord.isPresent()) {
			allMatches.add(existingProviderRecord.orElseThrow().paperId());
		}
		if (allMatches.size() > 1 || (resolved != null && !allMatches.isEmpty() && !allMatches.contains(resolved))) {
			throw new PaperCatalogConflictException(
					"Incoming identifiers resolve to different canonical papers");
		}
		return resolved;
	}

	private UUID uniqueMatch(
			List<NormalizedIdentifier> identifiers,
			Map<NormalizedIdentifier, PaperExternalIdEntity> existing,
			Predicate<NormalizedIdentifier> selector,
			String label) {
		Set<UUID> matches = identifiers.stream()
				.filter(selector)
				.map(existing::get)
				.filter(Objects::nonNull)
				.map(PaperExternalIdEntity::paperId)
				.collect(Collectors.toSet());
		if (matches.size() > 1) {
			throw new PaperCatalogConflictException(
					"Incoming " + label + " values resolve to different canonical papers");
		}
		return matches.stream().findFirst().orElse(null);
	}

	private void attachIdentifiers(
			PaperEntity paper,
			List<NormalizedIdentifier> identifiers,
			Map<NormalizedIdentifier, PaperExternalIdEntity> existing,
			Instant now) {
		for (NormalizedIdentifier identifier : identifiers) {
			PaperExternalIdEntity stored = existing.get(identifier);
			if (stored != null) {
				if (!stored.paperId().equals(paper.id())) {
					throw conflict(identifier.type().name(), identifier.normalizedValue());
				}
				continue;
			}
			externalIdRepository.save(PaperExternalIdEntity.create(
					paper,
					identifier.type(),
					identifier.namespace(),
					identifier.rawValue(),
					now));
		}
		externalIdRepository.flush();
	}

	private void replaceProviderAuthors(
			PaperEntity paper,
			ProviderRecordEntity providerRecord,
			List<PaperAuthorCandidate> candidates,
			Instant now) {
		Set<Integer> positions = new HashSet<>();
		for (PaperAuthorCandidate candidate : candidates) {
			if (candidate.position() < 0 || !positions.add(candidate.position())) {
				throw new IllegalArgumentException("Author positions must be non-negative and unique");
			}
		}

		Map<Integer, AuthorEntity> previousAnonymousAuthors = paperAuthorRepository
				.findByProviderRecordId(providerRecord.id()).stream()
				.filter(association -> association.author().openAlexId() == null)
				.filter(association -> association.author().orcid() == null)
				.collect(Collectors.toMap(PaperAuthorEntity::position, PaperAuthorEntity::author));
		paperAuthorRepository.deleteByProviderRecord_Id(providerRecord.id());
		paperAuthorRepository.flush();

		candidates.stream()
				.sorted(Comparator.comparingInt(PaperAuthorCandidate::position))
				.forEach(candidate -> {
					AuthorEntity author = resolveAuthor(candidate, previousAnonymousAuthors.get(candidate.position()), now);
					paperAuthorRepository.save(PaperAuthorEntity.create(
							paper,
							providerRecord,
							author,
							candidate.displayName(),
							candidate.position(),
							candidate.corresponding(),
							now));
				});
		paperAuthorRepository.flush();
	}

	private AuthorEntity resolveAuthor(
			PaperAuthorCandidate candidate, AuthorEntity previousAnonymousAuthor, Instant now) {
		String openAlexId = AuthorEntity.normalizeOpenAlexId(candidate.openAlexId());
		String orcid = AuthorEntity.normalizeOrcid(candidate.orcid());
		Optional<AuthorEntity> byOpenAlexId = openAlexId == null
				? Optional.empty()
				: authorRepository.findByOpenAlexId(openAlexId);
		Optional<AuthorEntity> byOrcid = orcid == null
				? Optional.empty()
				: authorRepository.findByOrcid(orcid);
		if (byOpenAlexId.isPresent()
				&& byOrcid.isPresent()
				&& !byOpenAlexId.orElseThrow().id().equals(byOrcid.orElseThrow().id())) {
			throw new PaperCatalogConflictException(
					"Author OpenAlex and ORCID identifiers resolve to different authors");
		}

		AuthorEntity author = byOpenAlexId.or(() -> byOrcid).orElse(null);
		if (author == null && openAlexId == null && orcid == null && previousAnonymousAuthor != null) {
			author = previousAnonymousAuthor;
		}
		if (author == null) {
			author = authorRepository.save(AuthorEntity.create(candidate, now));
		} else {
			author.enrich(candidate, now);
		}
		return author;
	}

	private BigDecimal calculateMetadataQuality(PaperEntity paper) {
		int points = 25;
		points += paper.abstractText() == null ? 0 : 20;
		points += paper.publicationDate() == null && paper.publicationYear() == null ? 0 : 10;
		points += paper.language() == null ? 0 : 5;
		points += paper.venueName() == null ? 0 : 10;
		points += paper.citationCount() == null ? 0 : 5;
		points += externalIdRepository.findByPaper_IdIn(List.of(paper.id())).isEmpty() ? 0 : 15;
		points += paperAuthorRepository.findForPaperIds(List.of(paper.id())).isEmpty() ? 0 : 10;
		return BigDecimal.valueOf(points)
				.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
	}

	private Map<UUID, PaperView> loadViews(Collection<UUID> requestedIds) {
		if (requestedIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, PaperEntity> papers = paperRepository.findAllById(requestedIds).stream()
				.collect(Collectors.toMap(PaperEntity::id, paper -> paper));
		Map<UUID, List<PaperIdentifier>> identifiersByPaper = externalIdRepository
				.findByPaper_IdIn(papers.keySet()).stream()
				.collect(Collectors.groupingBy(
						PaperExternalIdEntity::paperId,
						Collectors.mapping(
								entity -> new PaperIdentifier(
										entity.idType(), entity.namespace(), entity.rawValue()),
								Collectors.toList())));
		Map<UUID, List<PaperAuthorView>> authorsByPaper = selectCanonicalAuthors(
				paperAuthorRepository.findForPaperIds(papers.keySet()));

		Map<UUID, PaperView> views = new LinkedHashMap<>();
		for (UUID requestedId : requestedIds) {
			PaperEntity paper = papers.get(requestedId);
			if (paper == null) {
				continue;
			}
			List<PaperIdentifier> identifiers = new ArrayList<>(
					identifiersByPaper.getOrDefault(requestedId, List.of()));
			identifiers.sort(Comparator
					.comparing((PaperIdentifier identifier) -> identifier.type().name())
					.thenComparing(PaperIdentifier::namespace)
					.thenComparing(PaperIdentifier::value));
			views.put(requestedId, new PaperView(
					paper.id(),
					paper.title(),
					paper.abstractText(),
					paper.publicationDate(),
					paper.publicationYear(),
					paper.documentType(),
					paper.language(),
					paper.venueName(),
					paper.citationCount(),
					paper.citationCountAsOf(),
					identifiers,
					authorsByPaper.getOrDefault(requestedId, List.of())));
		}
		return Map.copyOf(views);
	}

	private Map<UUID, List<PaperAuthorView>> selectCanonicalAuthors(
			List<PaperAuthorEntity> associations) {
		Map<UUID, UUID> selectedProviderRecord = new HashMap<>();
		Map<UUID, List<PaperAuthorView>> authors = new HashMap<>();
		for (PaperAuthorEntity association : associations) {
			UUID providerRecordId = selectedProviderRecord.computeIfAbsent(
					association.paperId(), ignored -> association.providerRecordId());
			if (!providerRecordId.equals(association.providerRecordId())) {
				continue;
			}
			AuthorEntity author = association.author();
			authors.computeIfAbsent(association.paperId(), ignored -> new ArrayList<>())
					.add(new PaperAuthorView(
							author.id(),
							association.creditedName(),
							author.orcid(),
							author.openAlexId(),
							association.position(),
							association.corresponding()));
		}
		return authors;
	}

	private List<NormalizedIdentifier> normalizeIdentifiers(List<PaperIdentifier> identifiers) {
		Map<String, NormalizedIdentifier> normalized = new LinkedHashMap<>();
		for (PaperIdentifier identifier : identifiers) {
			Objects.requireNonNull(identifier, "Paper identifiers must not contain null");
			String namespace = identifier.type() == PaperIdentifierType.REPOSITORY
					? Objects.requireNonNullElse(identifier.namespace(), "").strip().toLowerCase(Locale.ROOT)
					: "";
			if (identifier.type() == PaperIdentifierType.REPOSITORY && namespace.isEmpty()) {
				throw new IllegalArgumentException("Repository identifiers require a namespace");
			}
			String rawValue = ProviderRecordEntity.cleanRequired(
					identifier.value(), "Paper identifier must not be blank");
			String normalizedValue = PaperExternalIdEntity.normalize(identifier.type(), rawValue);
			NormalizedIdentifier value = new NormalizedIdentifier(
					identifier.type(), namespace, normalizedValue, rawValue);
			normalized.putIfAbsent(value.lockKey(), value);
		}
		return List.copyOf(normalized.values());
	}

	private Map<String, Object> validateBoundedMetadata(Map<String, Object> metadata) {
		try {
			Map<String, Object> safeMetadata = metadata == null ? Map.of() : Map.copyOf(metadata);
			String json = objectMapper.writeValueAsString(safeMetadata);
			if (json.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_JSON_BYTES) {
				throw new IllegalArgumentException(
						"Provider metadata fragment must not exceed " + MAX_METADATA_JSON_BYTES + " bytes");
			}
			return safeMetadata;
		} catch (JacksonException exception) {
			throw new IllegalArgumentException("Provider metadata fragment must be valid JSON", exception);
		}
	}

	private PaperCatalogConflictException conflict(String kind, String value) {
		return new PaperCatalogConflictException(
				kind + " '" + value + "' is already attached to another paper");
	}

	private record NormalizedIdentifier(
			PaperIdentifierType type,
			String namespace,
			String normalizedValue,
			String rawValue) {

		String lockKey() {
			return "identifier:" + type.name() + ":" + namespace + ":" + normalizedValue;
		}
	}
}
