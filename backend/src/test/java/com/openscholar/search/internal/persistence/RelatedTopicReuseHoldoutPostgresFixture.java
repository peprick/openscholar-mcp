package com.openscholar.search.internal.persistence;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic PostgreSQL projection of a label-free related-topic holdout corpus.
 * All rows are evaluation-only, namespace-derived, and removed when the staged
 * corpus closes.
 */
final class RelatedTopicReuseHoldoutPostgresFixture {

	private static final String ID_DOMAIN =
			"openscholar-related-topic-holdout-fixture-v1\0";
	private static final Instant SEEDED_AT = Instant.parse("2026-08-30T00:00:00Z");
	private static final int SEARCH_RESULTS_PER_SNAPSHOT = 50;

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final PlatformTransactionManager transactionManager;

	RelatedTopicReuseHoldoutPostgresFixture(
			JdbcTemplate jdbcTemplate,
			ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.transactionManager = Objects.requireNonNull(
				transactionManager, "transactionManager");
	}

	StagedCorpus stage(RelatedTopicReuseHoldoutBundle.RankingCorpus input) {
		Objects.requireNonNull(input, "input");
		FixturePlan plan = plan(input);
		return inWriteTransaction(() -> {
			assertNoIdCollision(plan);
			insertOwner(plan.targetOwnerId(), "Holdout target owner");
			insertOwner(plan.otherOwnerId(), "Holdout other owner");
			for (PlannedPaper paper : plan.papers()) {
				insertPaper(input.corpusSha256(), paper);
			}
			insertVisibility(plan);
			return new StagedCorpus(input, plan);
		});
	}

	static UUID deterministicId(String corpusSha256, String entityKind, String logicalKey) {
		requireDigest(corpusSha256, "corpusSha256");
		String kind = requireText(entityKind, "entityKind");
		String key = requireText(logicalKey, "logicalKey");
		byte[] digest = sha256Bytes((ID_DOMAIN + corpusSha256 + "\0" + kind + "\0" + key)
				.getBytes(StandardCharsets.UTF_8));
		digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
		digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
		ByteBuffer bytes = ByteBuffer.wrap(digest, 0, 16);
		return new UUID(bytes.getLong(), bytes.getLong());
	}

	private FixturePlan plan(RelatedTopicReuseHoldoutBundle.RankingCorpus input) {
		String corpusSha256 = input.corpusSha256();
		UUID targetOwnerId = deterministicId(corpusSha256, "owner", "target");
		UUID otherOwnerId = deterministicId(corpusSha256, "owner", "other");
		UUID hiddenOtherCollectionId = deterministicId(
				corpusSha256, "collection", "hidden-other-owner");

		Map<String, RelatedTopicReuseHoldoutBundle.LineageKind> lineageKinds =
				new LinkedHashMap<>();
		for (RelatedTopicReuseHoldoutBundle.Lineage lineage : input.corpus().lineages()) {
			if (lineageKinds.putIfAbsent(lineage.key(), lineage.kind()) != null) {
				throw new IllegalArgumentException("holdout lineage keys must be unique");
			}
		}
		Map<String, UUID> paperIds = new LinkedHashMap<>();
		Map<UUID, String> keysByPaperId = new LinkedHashMap<>();
		List<PlannedPaper> papers = new ArrayList<>();
		Set<String> targetVisibleKeys = new LinkedHashSet<>();
		for (RelatedTopicReuseHoldoutBundle.Candidate candidate :
				input.corpus().candidates()) {
			RelatedTopicReuseHoldoutBundle.LineageKind lineageKind =
					lineageKinds.get(candidate.lineageKey());
			if (lineageKind == null) {
				throw new IllegalArgumentException("holdout candidate has an unknown lineage");
			}
			UUID paperId = deterministicId(corpusSha256, "paper", candidate.key());
			if (paperIds.putIfAbsent(candidate.key(), paperId) != null
					|| keysByPaperId.putIfAbsent(paperId, candidate.key()) != null) {
				throw new IllegalArgumentException("holdout candidate keys must be unique");
			}
			if (lineageKind.targetVisible()) {
				targetVisibleKeys.add(candidate.key());
			}
			papers.add(plannedPaper(
					corpusSha256, candidate, lineageKind, paperId));
		}

		Set<UUID> cleanupPaperIds = new LinkedHashSet<>(paperIds.values());
		Set<UUID> cleanupAuthorIds = new LinkedHashSet<>();
		papers.stream().flatMap(paper -> paper.authors().stream())
				.map(PlannedAuthor::authorId)
				.forEach(cleanupAuthorIds::add);
		for (RelatedTopicReuseHoldoutBundle.Query query : input.corpus().queries()) {
			for (HiddenRole role : HiddenRole.values()) {
				String hiddenKey = hiddenKey(corpusSha256, query.key(), role);
				cleanupPaperIds.add(deterministicId(corpusSha256, "paper", hiddenKey));
				cleanupAuthorIds.add(deterministicId(
						corpusSha256, "author", hiddenKey + ":0"));
			}
		}
		assertUniqueIds(
				targetOwnerId,
				otherOwnerId,
				hiddenOtherCollectionId,
				papers,
				cleanupPaperIds,
				cleanupAuthorIds);
		return new FixturePlan(
				corpusSha256,
				targetOwnerId,
				otherOwnerId,
				hiddenOtherCollectionId,
				List.copyOf(papers),
				Map.copyOf(paperIds),
				Map.copyOf(keysByPaperId),
				Set.copyOf(targetVisibleKeys),
				Set.copyOf(cleanupPaperIds),
				Set.copyOf(cleanupAuthorIds));
	}

	private PlannedPaper plannedPaper(
			String corpusSha256,
			RelatedTopicReuseHoldoutBundle.Candidate candidate,
			RelatedTopicReuseHoldoutBundle.LineageKind lineageKind,
			UUID paperId) {
		String recordId = recordId(corpusSha256, candidate.key());
		UUID providerRecordId = deterministicId(
				corpusSha256, "provider-record", candidate.key());
		List<PlannedAuthor> authors = new ArrayList<>();
		for (int index = 0; index < candidate.authors().size(); index++) {
			authors.add(new PlannedAuthor(
					deterministicId(corpusSha256, "author", candidate.key() + ":" + index),
					deterministicId(
							corpusSha256, "paper-author", candidate.key() + ":" + index),
					authorExternalId(corpusSha256, candidate.key(), index),
					candidate.authors().get(index),
					index));
		}
		return new PlannedPaper(
				candidate.key(),
				paperId,
				deterministicId(corpusSha256, "external-id", candidate.key()),
				providerRecordId,
				recordId,
				candidate.title(),
				candidate.abstractText(),
				candidate.publicationYear(),
				candidate.documentType(),
				candidate.language(),
				candidate.venueName(),
				candidate.citationCount(),
				candidate.reportedOpenAccess(),
				lineageKind,
				candidate.lineageKey(),
				List.copyOf(authors));
	}

	private void insertPaper(String corpusSha256, PlannedPaper paper) {
		jdbcTemplate.update("""
				INSERT INTO paper
				    (id, title, normalized_title, abstract_text, publication_year,
				     document_type, language, venue_name, citation_count,
				     citation_count_as_of, metadata_quality, metadata_updated_at,
				     version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
				""",
				paper.paperId(),
				paper.title(),
				normalizeTitle(paper.title()),
				paper.abstractText(),
				paper.publicationYear(),
				paper.documentType().name(),
				paper.language(),
				paper.venueName(),
				paper.citationCount(),
				paper.citationCount() == null ? null : Timestamp.from(SEEDED_AT),
				metadataQuality(paper),
				Timestamp.from(SEEDED_AT),
				Timestamp.from(SEEDED_AT),
				Timestamp.from(SEEDED_AT));
		String normalizedRecordId = paper.recordId().toLowerCase(Locale.ROOT);
		jdbcTemplate.update("""
				INSERT INTO paper_external_id
				    (id, paper_id, id_type, namespace, normalized_value, raw_value, created_at)
				VALUES (?, ?, 'OPENALEX', '', ?, ?, ?)
				""",
				paper.externalId(),
				paper.paperId(),
				normalizedRecordId,
				normalizedRecordId,
				Timestamp.from(SEEDED_AT));
		String pathKey = shortHash(paper.externalKey(), 24);
		jdbcTemplate.update("""
				INSERT INTO provider_record
				    (id, paper_id, provider, provider_record_id, provider_updated_at,
				     retrieved_at, source_url, reported_open_access, landing_page_url,
				     pdf_url, metadata_fragment, created_at, updated_at)
				VALUES (?, ?, 'OPENALEX', ?, ?, ?, ?, ?, ?, NULL,
				        CAST(? AS jsonb), ?, ?)
				""",
				paper.providerRecordId(),
				paper.paperId(),
				paper.recordId(),
				Timestamp.from(SEEDED_AT.minus(1, ChronoUnit.MINUTES)),
				Timestamp.from(SEEDED_AT),
				"https://fixtures.openscholar.test/source/" + pathKey,
				paper.reportedOpenAccess(),
				"https://fixtures.openscholar.test/papers/" + pathKey,
				"{\"synthetic\":true,\"evaluation\":\"related-topic-holdout-v1\"}",
				Timestamp.from(SEEDED_AT),
				Timestamp.from(SEEDED_AT));
		for (PlannedAuthor author : paper.authors()) {
			jdbcTemplate.update("""
					INSERT INTO author
					    (id, display_name, openalex_id, orcid, created_at, updated_at)
					VALUES (?, ?, ?, NULL, ?, ?)
					""",
					author.authorId(),
					author.displayName(),
					author.externalId(),
					Timestamp.from(SEEDED_AT),
					Timestamp.from(SEEDED_AT));
			jdbcTemplate.update("""
					INSERT INTO paper_author
					    (id, paper_id, provider_record_id, author_id, author_position,
					     corresponding, created_at, updated_at, credited_name)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
					""",
					author.paperAuthorId(),
					paper.paperId(),
					paper.providerRecordId(),
					author.authorId(),
					author.position(),
					author.position() == 0,
					Timestamp.from(SEEDED_AT),
					Timestamp.from(SEEDED_AT),
					author.displayName());
		}
	}

	private void insertVisibility(FixturePlan plan) {
		Map<String, List<PlannedPaper>> byLineage = new LinkedHashMap<>();
		for (PlannedPaper paper : plan.papers()) {
			byLineage.computeIfAbsent(paper.lineageKey(), ignored -> new ArrayList<>())
					.add(paper);
		}
		for (Map.Entry<String, List<PlannedPaper>> entry : byLineage.entrySet()) {
			RelatedTopicReuseHoldoutBundle.LineageKind kind =
					entry.getValue().getFirst().lineageKind();
			if (kind == RelatedTopicReuseHoldoutBundle.LineageKind.TARGET_OWNER_SEARCH
					|| kind == RelatedTopicReuseHoldoutBundle.LineageKind.OTHER_OWNER_SEARCH) {
				insertSearchLineage(
						plan,
						entry.getKey(),
						entry.getValue(),
						kind.targetVisible() ? plan.targetOwnerId() : plan.otherOwnerId());
			}
			else if (kind == RelatedTopicReuseHoldoutBundle.LineageKind.TARGET_OWNER_COLLECTION
					|| kind == RelatedTopicReuseHoldoutBundle.LineageKind.OTHER_OWNER_COLLECTION) {
				insertCollectionLineage(
						plan,
						entry.getKey(),
						entry.getValue(),
						kind.targetVisible() ? plan.targetOwnerId() : plan.otherOwnerId());
			}
		}
		jdbcTemplate.update("""
				INSERT INTO library_collection
				    (id, owner_id, name, description, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, ?, ?)
				""",
				plan.hiddenOtherCollectionId(),
				plan.otherOwnerId(),
				"Holdout hidden other-owner collection",
				"Evaluation-only perturbation visibility",
				Timestamp.from(SEEDED_AT),
				Timestamp.from(SEEDED_AT));
	}

	private void insertSearchLineage(
			FixturePlan plan,
			String lineageKey,
			List<PlannedPaper> papers,
			UUID ownerId) {
		for (int offset = 0, chunk = 0; offset < papers.size();
				offset += SEARCH_RESULTS_PER_SNAPSHOT, chunk++) {
			List<PlannedPaper> page = papers.subList(
					offset, Math.min(offset + SEARCH_RESULTS_PER_SNAPSHOT, papers.size()));
			String chunkKey = lineageKey + ":" + chunk;
			UUID snapshotId = deterministicId(
					plan.corpusSha256(), "search-snapshot", chunkKey);
			String query = "holdout prior " + shortHash(lineageKey, 16) + " " + chunk;
			String fingerprint = sha256Hex(
					(plan.corpusSha256() + "\0" + chunkKey).getBytes(StandardCharsets.UTF_8));
			jdbcTemplate.update("""
					INSERT INTO search_snapshot
					    (id, owner_id, original_query, normalized_query, fingerprint,
					     fingerprint_version, pipeline_version, filters, status, searched_at,
					     fresh_until, provider_coverage, warnings, total_provider_matches,
					     result_count, next_cursor, created_at, requested_mode, result_origin)
					VALUES (?, ?, ?, ?, ?, 1, 'openalex-v1', CAST(? AS jsonb),
					        'COMPLETED', ?, ?, CAST(? AS jsonb), '[]'::jsonb, ?, ?,
					        NULL, ?, 'ONLINE', 'PROVIDER')
					""",
					snapshotId,
					ownerId,
					query,
					query,
					fingerprint,
					"{\"documentTypes\":[],\"openAccessOnly\":false,"
							+ "\"minimumCitations\":0,\"languages\":[],"
							+ "\"pageSize\":50,\"cursor\":\"*\",\"mode\":\"ONLINE\"}",
					Timestamp.from(SEEDED_AT),
					Timestamp.from(SEEDED_AT.plus(1, ChronoUnit.DAYS)),
					"[{\"provider\":\"OPENALEX\",\"status\":\"SUCCESS\","
							+ "\"returnedCount\":" + page.size()
							+ ",\"totalMatches\":" + page.size() + "}]",
					page.size(),
					page.size(),
					Timestamp.from(SEEDED_AT));
			for (int index = 0; index < page.size(); index++) {
				PlannedPaper paper = page.get(index);
				insertSearchResult(plan, snapshotId, chunkKey, paper, index + 1);
			}
		}
	}

	private void insertSearchResult(
			FixturePlan plan,
			UUID snapshotId,
			String chunkKey,
			PlannedPaper paper,
			int rank) {
		UUID resultId = deterministicId(
				plan.corpusSha256(), "search-result", chunkKey + ":" + paper.externalKey());
		String pathKey = shortHash(paper.externalKey(), 24);
		jdbcTemplate.update("""
				INSERT INTO search_result
				    (id, search_id, paper_id, paper_snapshot, result_rank, total_score,
				     reported_open_access, landing_page_url, pdf_url, ranking_reasons,
				     provider_contributions, provider, provider_record_id, retrieved_at)
				VALUES (?, ?, ?, CAST(? AS jsonb), ?, 1.0, ?, ?, NULL,
				        '[]'::jsonb, CAST(? AS jsonb), 'OPENALEX', ?, ?)
				""",
				resultId,
				snapshotId,
				paper.paperId(),
				paperSnapshotJson(paper),
				rank,
				paper.reportedOpenAccess(),
				"https://fixtures.openscholar.test/papers/" + pathKey,
				"[{\"provider\":\"OPENALEX\",\"providerRecordId\":\""
						+ paper.recordId() + "\",\"retrievedAt\":\"" + SEEDED_AT + "\"}]",
				paper.recordId(),
				Timestamp.from(SEEDED_AT));
	}

	private String paperSnapshotJson(PlannedPaper paper) {
		SearchPaperSnapshot snapshot = new SearchPaperSnapshot(
				paper.paperId(),
				paper.title(),
				paper.abstractText(),
				null,
				paper.publicationYear(),
				paper.documentType(),
				paper.language(),
				paper.venueName(),
				paper.citationCount(),
				paper.citationCount() == null ? null : SEEDED_AT,
				List.of(new PaperIdentifier(
						PaperIdentifierType.OPENALEX,
						"",
						paper.recordId().toLowerCase(Locale.ROOT))),
				paper.authors().stream()
						.map(author -> new PaperAuthorView(
								author.authorId(),
								author.displayName(),
								null,
								author.externalId(),
								author.position(),
								author.position() == 0))
						.toList(),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				List.of(),
				List.of(),
				null);
		return objectMapper.writeValueAsString(snapshot);
	}

	private void insertCollectionLineage(
			FixturePlan plan,
			String lineageKey,
			List<PlannedPaper> papers,
			UUID ownerId) {
		UUID collectionId = deterministicId(
				plan.corpusSha256(), "collection", lineageKey);
		jdbcTemplate.update("""
				INSERT INTO library_collection
				    (id, owner_id, name, description, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, ?, ?)
				""",
				collectionId,
				ownerId,
				"Holdout collection " + shortHash(lineageKey, 16),
				"Evaluation-only corpus visibility",
				Timestamp.from(SEEDED_AT),
				Timestamp.from(SEEDED_AT));
		for (PlannedPaper paper : papers) {
			insertCollectionPaper(
					plan.corpusSha256(),
					collectionId,
					paper.paperId(),
					lineageKey + ":" + paper.externalKey());
		}
	}

	private void insertCollectionPaper(
			String corpusSha256,
			UUID collectionId,
			UUID paperId,
			String logicalKey) {
		jdbcTemplate.update("""
				INSERT INTO collection_paper
				    (id, collection_id, paper_id, reading_status, version, saved_at, updated_at)
				VALUES (?, ?, ?, 'UNREAD', 0, ?, ?)
				""",
				deterministicId(corpusSha256, "collection-paper", logicalKey),
				collectionId,
				paperId,
				Timestamp.from(SEEDED_AT),
				Timestamp.from(SEEDED_AT));
	}

	private PlannedPaper hiddenPaper(
			RelatedTopicReuseHoldoutBundle.RankingCorpus corpus,
			RelatedTopicReuseHoldoutBundle.Query query,
			HiddenRole role) {
		String key = hiddenKey(corpus.corpusSha256(), query.key(), role);
		RelatedTopicReuseHoldoutBundle.Filter filter = query.filters();
		Integer year = filter.yearTo() != null
				? filter.yearTo()
				: filter.yearFrom() != null ? filter.yearFrom() : 2026;
		DocumentType documentType = filter.documentTypes().isEmpty()
				? DocumentType.ARTICLE
				: filter.documentTypes().getFirst();
		String language = filter.languages().isEmpty()
				? "en"
				: filter.languages().getFirst();
		UUID paperId = deterministicId(corpus.corpusSha256(), "paper", key);
		String authorName = query.text();
		PlannedAuthor author = new PlannedAuthor(
				deterministicId(corpus.corpusSha256(), "author", key + ":0"),
				deterministicId(corpus.corpusSha256(), "paper-author", key + ":0"),
				authorExternalId(corpus.corpusSha256(), key, 0),
				authorName,
				0);
		return new PlannedPaper(
				key,
				paperId,
				deterministicId(corpus.corpusSha256(), "external-id", key),
				deterministicId(corpus.corpusSha256(), "provider-record", key),
				recordId(corpus.corpusSha256(), key),
				query.text(),
				"Maximum-match hidden perturbation for " + query.text(),
				year,
				documentType,
				language,
				query.text(),
				Math.max(filter.minimumCitations(), 1_000_000),
				true,
				role == HiddenRole.OTHER_OWNER
						? RelatedTopicReuseHoldoutBundle.LineageKind.OTHER_OWNER_COLLECTION
						: RelatedTopicReuseHoldoutBundle.LineageKind.CATALOG_ONLY,
				"hidden-" + role.name().toLowerCase(Locale.ROOT).replace('_', '-'),
				List.of(author));
	}

	private static String hiddenKey(
			String corpusSha256,
			String queryKey,
			HiddenRole role) {
		return "hidden-"
				+ (role == HiddenRole.OTHER_OWNER ? "other-" : "catalog-")
				+ shortHash(corpusSha256 + "\0" + queryKey + "\0" + role, 24);
	}

	private void assertNoIdCollision(FixturePlan plan) {
		List<UUID> ids = new ArrayList<>();
		ids.add(plan.targetOwnerId());
		ids.add(plan.otherOwnerId());
		ids.addAll(plan.cleanupPaperIds());
		ids.addAll(plan.cleanupAuthorIds());
		if (countIds("app_user", ids) > 0
				|| countIds("paper", ids) > 0
				|| countIds("author", ids) > 0) {
			throw new IllegalStateException(
					"deterministic holdout fixture identifiers collide with existing rows");
		}
	}

	private long countIds(String table, List<UUID> ids) {
		if (ids.isEmpty()) {
			return 0;
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM " + table + " WHERE id IN (" + placeholders + ")",
				Long.class,
				ids.toArray());
		return count == null ? 0 : count;
	}

	private void insertOwner(UUID ownerId, String displayName) {
		jdbcTemplate.update(
				"INSERT INTO app_user (id, display_name, created_at) VALUES (?, ?, ?)",
				ownerId,
				displayName,
				Timestamp.from(SEEDED_AT));
	}

	private <T> T inWriteTransaction(Supplier<T> action) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		T value = transaction.execute(status -> action.get());
		return Objects.requireNonNull(value, "holdout fixture transaction result");
	}

	private static BigDecimal metadataQuality(PlannedPaper paper) {
		int points = 25;
		points += paper.abstractText() == null ? 0 : 20;
		points += paper.publicationYear() == null ? 0 : 10;
		points += paper.language() == null ? 0 : 5;
		points += paper.venueName() == null ? 0 : 10;
		points += paper.citationCount() == null ? 0 : 5;
		points += 15;
		points += paper.authors().isEmpty() ? 0 : 10;
		return BigDecimal.valueOf(points, 2).setScale(4);
	}

	private static String normalizeTitle(String title) {
		return title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

	private static String recordId(String corpusSha256, String key) {
		return "rthv1-" + corpusSha256.substring(0, 12) + "-" + shortHash(key, 24);
	}

	private static String authorExternalId(String corpusSha256, String key, int index) {
		return "arthv1-" + corpusSha256.substring(0, 12) + "-"
				+ shortHash(key, 20) + "-" + index;
	}

	private static String shortHash(String value, int characters) {
		return sha256Hex(value.getBytes(StandardCharsets.UTF_8)).substring(0, characters);
	}

	private static byte[] sha256Bytes(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String sha256Hex(byte[] bytes) {
		return HexFormat.of().formatHex(sha256Bytes(bytes));
	}

	private static void requireDigest(String value, String field) {
		if (value == null || !value.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank() || !value.equals(value.strip())) {
			throw new IllegalArgumentException(field + " must be nonblank and trimmed");
		}
		return value;
	}

	private static void assertUniqueIds(
			UUID targetOwnerId,
			UUID otherOwnerId,
			UUID hiddenCollectionId,
			List<PlannedPaper> papers,
			Set<UUID> cleanupPaperIds,
			Set<UUID> cleanupAuthorIds) {
		Set<UUID> ids = new HashSet<>();
		for (UUID id : List.of(targetOwnerId, otherOwnerId, hiddenCollectionId)) {
			if (!ids.add(id)) {
				throw new IllegalStateException("deterministic holdout fixture UUID collision");
			}
		}
		for (PlannedPaper paper : papers) {
			for (UUID id : List.of(
					paper.paperId(), paper.externalId(), paper.providerRecordId())) {
				if (!ids.add(id)) {
					throw new IllegalStateException("deterministic holdout fixture UUID collision");
				}
			}
			for (PlannedAuthor author : paper.authors()) {
				for (UUID id : List.of(author.authorId(), author.paperAuthorId())) {
					if (!ids.add(id)) {
						throw new IllegalStateException("deterministic holdout fixture UUID collision");
					}
				}
			}
		}
		if (cleanupPaperIds.size() < papers.size()
				|| cleanupAuthorIds.size() < papers.stream()
						.mapToInt(paper -> paper.authors().size()).sum()) {
			throw new IllegalStateException("deterministic holdout cleanup identities collided");
		}
	}

	final class StagedCorpus implements AutoCloseable {

		private final RelatedTopicReuseHoldoutBundle.RankingCorpus input;
		private final FixturePlan plan;
		private final Map<UUID, String> activeHiddenKeys = new HashMap<>();
		private boolean hiddenLeaseActive;
		private boolean closed;

		private StagedCorpus(
				RelatedTopicReuseHoldoutBundle.RankingCorpus input,
				FixturePlan plan) {
			this.input = input;
			this.plan = plan;
		}

		UUID targetOwnerId() {
			ensureOpen();
			return plan.targetOwnerId();
		}

		UUID paperId(String externalKey) {
			ensureOpen();
			UUID paperId = plan.paperIds().get(externalKey);
			if (paperId == null) {
				throw new IllegalArgumentException("unknown holdout paper key: " + externalKey);
			}
			return paperId;
		}

		synchronized String externalKey(UUID paperId) {
			ensureOpen();
			String key = plan.keysByPaperId().get(paperId);
			if (key == null) {
				key = activeHiddenKeys.get(paperId);
			}
			if (key == null) {
				throw new IllegalStateException("ranker returned a paper outside the staged fixture");
			}
			return key;
		}

		Set<String> targetVisibleKeys() {
			ensureOpen();
			return plan.targetVisibleKeys();
		}

		long targetSnapshotCount() {
			ensureOpen();
			Long count = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM search_snapshot WHERE owner_id = ?",
					Long.class,
					plan.targetOwnerId());
			return count == null ? 0 : count;
		}

		synchronized HiddenLease injectMaximumMatch(
				RelatedTopicReuseHoldoutBundle.Query query) {
			ensureOpen();
			Objects.requireNonNull(query, "query");
			if (hiddenLeaseActive) {
				throw new IllegalStateException("only one hidden perturbation may be active");
			}
			PlannedPaper other = hiddenPaper(input, query, HiddenRole.OTHER_OWNER);
			PlannedPaper catalog = hiddenPaper(input, query, HiddenRole.CATALOG_ONLY);
			if (plan.paperIds().containsKey(other.externalKey())
					|| plan.paperIds().containsKey(catalog.externalKey())) {
				throw new IllegalStateException("hidden perturbation key collides with the corpus");
			}
			inWriteTransaction(() -> {
				insertPaper(input.corpusSha256(), other);
				insertPaper(input.corpusSha256(), catalog);
				insertCollectionPaper(
						input.corpusSha256(),
						plan.hiddenOtherCollectionId(),
						other.paperId(),
						"hidden:" + query.key());
				return Boolean.TRUE;
			});
			activeHiddenKeys.put(other.paperId(), other.externalKey());
			activeHiddenKeys.put(catalog.paperId(), catalog.externalKey());
			hiddenLeaseActive = true;
			return new HiddenLease(other, catalog);
		}

		@Override
		public synchronized void close() {
			if (closed) {
				return;
			}
			inWriteTransaction(() -> {
				jdbcTemplate.update(
						"DELETE FROM app_user WHERE id IN (?, ?)",
						plan.targetOwnerId(),
						plan.otherOwnerId());
				deleteIds("paper", plan.cleanupPaperIds());
				deleteIds("author", plan.cleanupAuthorIds());
				return Boolean.TRUE;
			});
			closed = true;
			activeHiddenKeys.clear();
			hiddenLeaseActive = false;
		}

		private void deleteIds(String table, Set<UUID> ids) {
			if (ids.isEmpty()) {
				return;
			}
			String placeholders = String.join(
					", ", java.util.Collections.nCopies(ids.size(), "?"));
			jdbcTemplate.update(
					"DELETE FROM " + table + " WHERE id IN (" + placeholders + ")",
					ids.toArray());
		}

		private void ensureOpen() {
			if (closed) {
				throw new IllegalStateException("the staged holdout corpus is closed");
			}
		}

		final class HiddenLease implements AutoCloseable {

			private final PlannedPaper other;
			private final PlannedPaper catalog;
			private boolean leaseClosed;

			private HiddenLease(PlannedPaper other, PlannedPaper catalog) {
				this.other = other;
				this.catalog = catalog;
			}

			String otherOwnerCandidateKey() {
				return other.externalKey();
			}

			String catalogOnlyCandidateKey() {
				return catalog.externalKey();
			}

			@Override
			public void close() {
				synchronized (StagedCorpus.this) {
					if (leaseClosed) {
						return;
					}
					ensureOpen();
					inWriteTransaction(() -> {
						jdbcTemplate.update(
								"DELETE FROM collection_paper WHERE collection_id = ? AND paper_id = ?",
								plan.hiddenOtherCollectionId(),
								other.paperId());
						deleteIds("paper", Set.of(other.paperId(), catalog.paperId()));
						Set<UUID> authorIds = Set.of(
								other.authors().getFirst().authorId(),
								catalog.authors().getFirst().authorId());
						deleteIds("author", authorIds);
						return Boolean.TRUE;
					});
					leaseClosed = true;
					activeHiddenKeys.remove(other.paperId());
					activeHiddenKeys.remove(catalog.paperId());
					hiddenLeaseActive = false;
				}
			}
		}
	}

	private record FixturePlan(
			String corpusSha256,
			UUID targetOwnerId,
			UUID otherOwnerId,
			UUID hiddenOtherCollectionId,
			List<PlannedPaper> papers,
			Map<String, UUID> paperIds,
			Map<UUID, String> keysByPaperId,
			Set<String> targetVisibleKeys,
			Set<UUID> cleanupPaperIds,
			Set<UUID> cleanupAuthorIds) {
	}

	private record PlannedPaper(
			String externalKey,
			UUID paperId,
			UUID externalId,
			UUID providerRecordId,
			String recordId,
			String title,
			String abstractText,
			Integer publicationYear,
			DocumentType documentType,
			String language,
			String venueName,
			Integer citationCount,
			boolean reportedOpenAccess,
			RelatedTopicReuseHoldoutBundle.LineageKind lineageKind,
			String lineageKey,
			List<PlannedAuthor> authors) {
	}

	private record PlannedAuthor(
			UUID authorId,
			UUID paperAuthorId,
			String externalId,
			String displayName,
			int position) {
	}

	private enum HiddenRole {
		OTHER_OWNER,
		CATALOG_ONLY
	}
}
