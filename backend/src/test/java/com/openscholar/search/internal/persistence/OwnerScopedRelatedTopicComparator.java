package com.openscholar.search.internal.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.search.SearchCommand;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Evaluation-only implementation of owner-scoped lexical feedback. This class is
 * deliberately kept under {@code src/test}: production LOCAL search remains the
 * control and no runtime path calls this comparator.
 */
final class OwnerScopedRelatedTopicComparator {

	static final int MAXIMUM_SEEDS = 2;
	static final int MAXIMUM_SEED_LEXEMES = 16;
	static final int MAXIMUM_RELATED_CANDIDATES_PER_SEED = 25;

	private static final String SCOPED_RELATED_SQL = """
			WITH eligible_paper AS (
			    SELECT result.paper_id
			    FROM search_result result
			    JOIN search_snapshot snapshot ON snapshot.id = result.search_id
			    WHERE snapshot.owner_id = :ownerId
			    UNION
			    SELECT saved.paper_id
			    FROM collection_paper saved
			    JOIN library_collection collection ON collection.id = saved.collection_id
			    WHERE collection.owner_id = :ownerId
			), filtered_paper AS (
			    SELECT paper.*
			    FROM eligible_paper eligible
			    JOIN paper ON paper.id = eligible.paper_id
			    WHERE TRUE
			    %s
			), requested_seed(seed_id, seed_order) AS (
			    VALUES %s
			), eligible_seed AS (
			    SELECT requested.seed_id,
			           requested.seed_order,
			           seed.title
			    FROM requested_seed requested
			    JOIN filtered_paper seed ON seed.id = requested.seed_id
			), raw_seed_lexeme AS (
			    SELECT seed.seed_id,
			           seed.seed_order,
			           token.lexeme,
			           (token.positions)[1] AS first_position,
			           row_number() OVER (
			               PARTITION BY seed.seed_id
			               ORDER BY (token.positions)[1], token.lexeme
			           ) AS lexeme_rank
			    FROM eligible_seed seed
			    CROSS JOIN LATERAL unnest(to_tsvector('english'::regconfig, seed.title))
			        AS token(lexeme, positions, weights)
			), seed_query AS (
			    SELECT seed_id,
			           seed_order,
			           string_agg(
			               quote_literal(lexeme),
			               ' | '
			               ORDER BY first_position, lexeme
			           )::tsquery AS related_query
			    FROM raw_seed_lexeme
			    WHERE lexeme_rank <= __MAXIMUM_SEED_LEXEMES__
			    GROUP BY seed_id, seed_order
			), scored AS (
			    SELECT seed.seed_id,
			           seed.seed_order,
			           candidate.id AS paper_id,
			           ts_rank_cd(
			               candidate.search_vector,
			               seed.related_query,
			               32
			           )::double precision AS lexical_score,
			           candidate.metadata_quality,
			           candidate.citation_count,
			           candidate.publication_year
			    FROM seed_query seed
			    JOIN filtered_paper candidate ON candidate.id <> seed.seed_id
			    WHERE candidate.search_vector @@ seed.related_query
			), ranked AS (
			    SELECT scored.*,
			           row_number() OVER (
			               PARTITION BY seed_id
			               ORDER BY lexical_score DESC,
			                        metadata_quality DESC,
			                        citation_count DESC NULLS LAST,
			                        publication_year DESC NULLS LAST,
			                        paper_id
			           ) AS related_rank
			    FROM scored
			)
			SELECT seed_id, seed_order, paper_id, lexical_score, related_rank
			FROM ranked
			WHERE related_rank <= __MAXIMUM_RELATED_CANDIDATES__
			ORDER BY seed_order, related_rank
			"""
			.replace("__MAXIMUM_SEED_LEXEMES__", Integer.toString(MAXIMUM_SEED_LEXEMES))
			.replace(
					"__MAXIMUM_RELATED_CANDIDATES__",
					Integer.toString(MAXIMUM_RELATED_CANDIDATES_PER_SEED));

	private final JdbcClient jdbcClient;

	OwnerScopedRelatedTopicComparator(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
	}

	List<FeedbackList> findFeedback(
			UUID ownerId, SearchCommand command, List<UUID> seedPaperIds) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(command, "command");
		List<UUID> seeds = List.copyOf(Objects.requireNonNull(seedPaperIds, "seedPaperIds"));
		if (seeds.size() > MAXIMUM_SEEDS) {
			throw new IllegalArgumentException(
					"Related-topic feedback accepts at most " + MAXIMUM_SEEDS + " seeds");
		}
		if (seeds.stream().anyMatch(Objects::isNull)
				|| new LinkedHashSet<>(seeds).size() != seeds.size()) {
			throw new IllegalArgumentException("Related-topic feedback seeds must be non-null and unique");
		}
		if (seeds.isEmpty()) {
			return List.of();
		}

		StringBuilder filters = new StringBuilder();
		if (command.yearFrom() != null) {
			filters.append(" AND paper.publication_year >= :yearFrom\n");
		}
		if (command.yearTo() != null) {
			filters.append(" AND paper.publication_year <= :yearTo\n");
		}
		if (!command.documentTypes().isEmpty()) {
			filters.append(" AND paper.document_type IN (:documentTypes)\n");
		}
		if (!command.languages().isEmpty()) {
			filters.append(" AND lower(paper.language) IN (:languages)\n");
		}
		if (command.minimumCitations() > 0) {
			filters.append(" AND coalesce(paper.citation_count, 0) >= :minimumCitations\n");
		}
		if (command.openAccessOnly()) {
			filters.append("""
					 AND EXISTS (
					     SELECT 1
					     FROM provider_record open_filter
					     WHERE open_filter.paper_id = paper.id
					       AND open_filter.reported_open_access
					 )
					""");
		}

		List<String> seedRows = new ArrayList<>(seeds.size());
		for (int index = 0; index < seeds.size(); index++) {
			seedRows.add("(cast(:seed" + index + " as uuid), " + index + ")");
		}
		JdbcClient.StatementSpec statement = jdbcClient.sql(
				SCOPED_RELATED_SQL.formatted(filters, String.join(", ", seedRows)))
				.param("ownerId", ownerId);
		for (int index = 0; index < seeds.size(); index++) {
			statement = statement.param("seed" + index, seeds.get(index));
		}
		if (command.yearFrom() != null) {
			statement = statement.param("yearFrom", command.yearFrom());
		}
		if (command.yearTo() != null) {
			statement = statement.param("yearTo", command.yearTo());
		}
		if (!command.documentTypes().isEmpty()) {
			statement = statement.param(
					"documentTypes", command.documentTypes().stream().map(Enum::name).toList());
		}
		if (!command.languages().isEmpty()) {
			statement = statement.param("languages", command.languages());
		}
		if (command.minimumCitations() > 0) {
			statement = statement.param("minimumCitations", command.minimumCitations());
		}

		List<FeedbackRow> rows = statement.query((resultSet, rowNumber) -> new FeedbackRow(
				resultSet.getObject("seed_id", UUID.class),
				resultSet.getInt("seed_order"),
				resultSet.getObject("paper_id", UUID.class),
				resultSet.getDouble("lexical_score"),
				resultSet.getInt("related_rank")))
				.list();
		Map<UUID, List<RelatedCandidate>> bySeed = new LinkedHashMap<>();
		seeds.forEach(seed -> bySeed.put(seed, new ArrayList<>()));
		for (FeedbackRow row : rows) {
			if (row.seedOrder() < 0 || row.seedOrder() >= seeds.size()
					|| !seeds.get(row.seedOrder()).equals(row.seedPaperId())) {
				throw new IllegalStateException("Scoped related-topic feedback returned an unknown seed");
			}
			bySeed.get(row.seedPaperId()).add(
					new RelatedCandidate(row.paperId(), row.relatedRank(), row.lexicalScore()));
		}
		return seeds.stream()
				.map(seed -> new FeedbackList(seed, List.copyOf(bySeed.get(seed))))
				.toList();
	}

	static String sqlContract() {
		return SCOPED_RELATED_SQL;
	}

	record FeedbackList(UUID seedPaperId, List<RelatedCandidate> candidates) {

		FeedbackList {
			Objects.requireNonNull(seedPaperId, "seedPaperId");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			if (candidates.size() > MAXIMUM_RELATED_CANDIDATES_PER_SEED) {
				throw new IllegalArgumentException("Feedback list exceeds the frozen per-seed bound");
			}
		}
	}

	record RelatedCandidate(UUID paperId, int rank, double lexicalScore) {

		RelatedCandidate {
			Objects.requireNonNull(paperId, "paperId");
			if (rank < 1 || rank > MAXIMUM_RELATED_CANDIDATES_PER_SEED
					|| !Double.isFinite(lexicalScore) || lexicalScore <= 0.0d) {
				throw new IllegalArgumentException("Invalid related-topic feedback candidate");
			}
		}
	}

	private record FeedbackRow(
			UUID seedPaperId,
			int seedOrder,
			UUID paperId,
			double lexicalScore,
			int relatedRank) {
	}
}
