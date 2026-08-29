package com.openscholar.search.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class RelatedTopicReuseScaleFixture {

	static final UUID TARGET_OWNER =
			UUID.fromString("40000000-0000-0000-0000-000000000001");
	static final UUID OTHER_OWNER =
			UUID.fromString("40000000-0000-0000-0000-000000000002");
	static final Instant SEEDED_AT = Instant.parse("2026-08-29T00:00:00Z");
	static final int SEARCH_RESULTS_PER_SNAPSHOT = 50;
	static final String PROVIDER_RECORD_PREFIX = "RTSCALEV1-";
	static final String GENERATOR_VERSION = "related-topic-reuse-scale-corpus-v1";
	static final int GENERATOR_SEED = 20260829;

	private static final String PAPER_UUID_PREFIX = "41000000-0000-0000-0000-";
	private static final String INSERT_PAPERS = """
			WITH generated AS (
			    SELECT ordinal,
			           CASE
			               WHEN ordinal = 0 THEN 'Coastal Erosion Drone Mapping'
			               WHEN ordinal BETWEEN 1 AND 50
			                   THEN 'Coastal sediment dynamics survey ' || ordinal
			               WHEN ordinal BETWEEN 51 AND 99
			                   THEN 'Erosion shoreline monitoring ' || ordinal
			               WHEN ordinal = 100 THEN 'River Microplastic Community Sensors'
			               WHEN ordinal = 101
			                   THEN 'River Microplastic Community Sensors Field Study'
			               WHEN ordinal BETWEEN 102 AND 20101 AND ordinal % 4 = 0
			                   THEN 'River hydrology observation ' || ordinal
			               WHEN ordinal BETWEEN 102 AND 20101 AND ordinal % 4 = 1
			                   THEN 'Microplastic polymer analysis ' || ordinal
			               WHEN ordinal BETWEEN 102 AND 20101 AND ordinal % 4 = 2
			                   THEN 'Community participatory research ' || ordinal
			               WHEN ordinal BETWEEN 102 AND 20101
			                   THEN 'Sensors network calibration ' || ordinal
			               WHEN ordinal = 40000 THEN 'Community Wildfire Smoke Sensors'
			               WHEN ordinal BETWEEN 40001 AND 40100 AND ordinal % 4 = 0
			                   THEN 'Community wildfire resilience thesis ' || ordinal
			               WHEN ordinal BETWEEN 40001 AND 40100 AND ordinal % 4 = 1
			                   THEN 'Wildfire evacuation planning thesis ' || ordinal
			               WHEN ordinal BETWEEN 40001 AND 40100 AND ordinal % 4 = 2
			                   THEN 'Smoke exposure field thesis ' || ordinal
			               WHEN ordinal BETWEEN 40001 AND 40100
			                   THEN 'Sensors deployment methods thesis ' || ordinal
			               WHEN ordinal BETWEEN 40101 AND 40106
			                   THEN 'Community Wildfire Smoke Sensors boundary ' || ordinal
			               WHEN ordinal IN (50000, 75000) THEN 'Orbital Lichen Spectroscopy'
			               WHEN ordinal IN (50001, 75001)
			                   THEN 'River Microplastic Community Sensors invisible exact'
			               WHEN ordinal IN (50002, 75002)
			                   THEN 'Community Wildfire Smoke Sensors invisible exact'
			               ELSE 'Deterministic scholarly mechanics record ' || ordinal
			           END AS title,
			           CASE
			               WHEN ordinal = 40101 THEN 2021
			               WHEN ordinal = 40102 THEN 2027
			               WHEN ordinal BETWEEN 40000 AND 40106
			                    OR ordinal IN (50002, 75002) THEN 2024
			               ELSE 2020 + (ordinal % 7)
			           END AS publication_year,
			           CASE
			               WHEN ordinal = 40103 THEN 'ARTICLE'
			               WHEN ordinal BETWEEN 40000 AND 40106
			                    OR ordinal IN (50002, 75002) THEN 'THESIS'
			               ELSE 'ARTICLE'
			           END AS document_type,
			           CASE WHEN ordinal = 40106 THEN 'es' ELSE 'en' END AS language,
			           CASE WHEN ordinal = 40105 THEN 1
			                WHEN ordinal BETWEEN 40000 AND 40106
			                     OR ordinal IN (50002, 75002) THEN 20
			                ELSE ordinal % 500
			           END AS citation_count
			    FROM generate_series(0, ? - 1) AS series(ordinal)
			)
			INSERT INTO paper
			    (id, title, normalized_title, abstract_text, publication_year,
			     document_type, language, venue_name, citation_count,
			     metadata_quality, metadata_updated_at, version, created_at, updated_at)
			SELECT (('41000000-0000-0000-0000-' || lpad(to_hex(ordinal + 1), 12, '0'))::uuid),
			       title,
			       lower(title),
			       'Deterministic synthetic metadata for related-topic scale mechanics seed '
			           || ? || ' ordinal ' || ordinal,
			       publication_year,
			       document_type,
			       language,
			       'OpenScholar Scale Mechanics Archive',
			       citation_count,
			       CASE WHEN ordinal >= 50000 THEN 1.0000 ELSE 0.5000 END,
			       '2026-08-29T00:00:00Z'::timestamptz,
			       0,
			       '2026-08-29T00:00:00Z'::timestamptz,
			       '2026-08-29T00:00:00Z'::timestamptz
			FROM generated
			""";
	private static final String INSERT_PROVIDER_RECORDS = """
			INSERT INTO provider_record
			    (id, paper_id, provider, provider_record_id, provider_updated_at,
			     retrieved_at, source_url, reported_open_access, landing_page_url,
			     pdf_url, metadata_fragment, created_at, updated_at)
			SELECT (('42000000-0000-0000-0000-' || lpad(to_hex(ordinal + 1), 12, '0'))::uuid),
			       (('41000000-0000-0000-0000-' || lpad(to_hex(ordinal + 1), 12, '0'))::uuid),
			       'OPENALEX',
			       'RTSCALEV1-' || ordinal,
			       '2026-08-28T23:59:00Z'::timestamptz,
			       '2026-08-29T00:00:00Z'::timestamptz,
			       'https://fixtures.openscholar.test/source/' || ordinal,
			       CASE
			           WHEN ordinal = 40104 THEN FALSE
			           WHEN ordinal BETWEEN 40000 AND 40106
			                OR ordinal IN (50002, 75002) THEN TRUE
			           ELSE ordinal % 3 = 0
			       END,
			       'https://fixtures.openscholar.test/papers/' || ordinal,
			       NULL,
			       '{"synthetic":true,"benchmark":"related-topic-reuse-scale-v1"}'::jsonb,
			       '2026-08-29T00:00:00Z'::timestamptz,
			       '2026-08-29T00:00:00Z'::timestamptz
			FROM generate_series(0, ? - 1) AS series(ordinal)
			""";
	private static final String INSERT_TARGET_SNAPSHOTS = snapshotInsertSql(
			"43000000-0000-0000-0000-", "target", "a");
	private static final String INSERT_OTHER_SNAPSHOTS = snapshotInsertSql(
			"43100000-0000-0000-0000-", "other", "b");
	private static final String INSERT_TARGET_RESULTS = resultInsertSql(
			"44000000-0000-0000-0000-", "43000000-0000-0000-0000-");
	private static final String INSERT_OTHER_RESULTS = resultInsertSql(
			"44100000-0000-0000-0000-", "43100000-0000-0000-0000-");
	private static final String INSERT_COLLECTION = """
			INSERT INTO library_collection
			    (id, owner_id, name, description, version, created_at, updated_at)
			VALUES
			    ('45000000-0000-0000-0000-000000000001', ?,
			     'Related-topic scale collection', 'Deterministic test-only visibility cohort',
			     0, ?, ?)
			""";
	private static final String INSERT_COLLECTION_PAPERS = """
			INSERT INTO collection_paper
			    (id, collection_id, paper_id, reading_status, version, saved_at, updated_at)
			SELECT (('45100000-0000-0000-0000-' || lpad(to_hex(ordinal + 1), 12, '0'))::uuid),
			       '45000000-0000-0000-0000-000000000001'::uuid,
			       (('41000000-0000-0000-0000-' || lpad(to_hex(ordinal + 1), 12, '0'))::uuid),
			       'UNREAD',
			       0,
			       '2026-08-29T00:00:00Z'::timestamptz,
			       '2026-08-29T00:00:00Z'::timestamptz
			FROM generate_series(?, ? - 1) AS series(ordinal)
			""";

	private RelatedTopicReuseScaleFixture() {
	}

	static SeedTiming seed(
			JdbcTemplate jdbcTemplate,
			PlatformTransactionManager transactionManager,
			RelatedTopicReuseScalePolicy.Corpus corpus) {
		Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
		Objects.requireNonNull(transactionManager, "transactionManager");
		Objects.requireNonNull(corpus, "corpus");
		if (!GENERATOR_VERSION.equals(corpus.generatorVersion())
				|| corpus.generatorSeed() != GENERATOR_SEED
				|| !generatorSqlSha256().equals(corpus.generatorSqlSha256())) {
			throw new IllegalArgumentException("Scale corpus does not bind this fixture generator");
		}

		long seedStarted = System.nanoTime();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			cleanup(jdbcTemplate);
			insertOwner(jdbcTemplate, TARGET_OWNER, "Related-topic scale target owner");
			insertOwner(jdbcTemplate, OTHER_OWNER, "Related-topic scale other owner");
			jdbcTemplate.update(
					INSERT_PAPERS, corpus.totalPaperCount(), corpus.generatorSeed());
			jdbcTemplate.update(INSERT_PROVIDER_RECORDS, corpus.totalPaperCount());
			jdbcTemplate.update(
					INSERT_TARGET_SNAPSHOTS,
					TARGET_OWNER,
					corpus.targetSearchVisibleCount());
			jdbcTemplate.update(
					INSERT_TARGET_RESULTS,
					0,
					corpus.targetSearchVisibleCount());
			jdbcTemplate.update(
					INSERT_OTHER_SNAPSHOTS,
					OTHER_OWNER,
					corpus.otherOwnerVisibleCount());
			int otherStart = corpus.targetSearchVisibleCount()
					+ corpus.targetCollectionVisibleCount();
			jdbcTemplate.update(
					INSERT_OTHER_RESULTS,
					otherStart,
					corpus.otherOwnerVisibleCount());
			Timestamp seededAt = Timestamp.from(SEEDED_AT);
			jdbcTemplate.update(INSERT_COLLECTION, TARGET_OWNER, seededAt, seededAt);
			jdbcTemplate.update(
					INSERT_COLLECTION_PAPERS,
					corpus.targetSearchVisibleCount(),
					corpus.targetSearchVisibleCount()
							+ corpus.targetCollectionVisibleCount());
		});
		long seedNanos = System.nanoTime() - seedStarted;

		long analyzeStarted = System.nanoTime();
		for (String table : List.of(
				"paper", "provider_record", "search_snapshot", "search_result",
				"library_collection", "collection_paper")) {
			jdbcTemplate.execute("ANALYZE " + table);
		}
		long analyzeNanos = System.nanoTime() - analyzeStarted;
		return new SeedTiming(seedNanos, analyzeNanos);
	}

	static FixtureCounts counts(JdbcTemplate jdbcTemplate) {
		return new FixtureCounts(
				count(jdbcTemplate, "SELECT count(*) FROM paper WHERE id::text LIKE '41000000-%'"),
				count(jdbcTemplate, """
						SELECT count(DISTINCT result.paper_id)
						FROM search_result result
						JOIN search_snapshot snapshot ON snapshot.id = result.search_id
						WHERE snapshot.owner_id = '40000000-0000-0000-0000-000000000001'
						"""),
				count(jdbcTemplate, """
						SELECT count(DISTINCT saved.paper_id)
						FROM collection_paper saved
						JOIN library_collection collection ON collection.id = saved.collection_id
						WHERE collection.owner_id = '40000000-0000-0000-0000-000000000001'
						"""),
				count(jdbcTemplate, """
						SELECT count(DISTINCT result.paper_id)
						FROM search_result result
						JOIN search_snapshot snapshot ON snapshot.id = result.search_id
						WHERE snapshot.owner_id = '40000000-0000-0000-0000-000000000002'
						"""),
				count(jdbcTemplate, """
						SELECT count(*)
						FROM paper paper
						WHERE paper.id::text LIKE '41000000-%'
						  AND NOT EXISTS (SELECT 1 FROM search_result result WHERE result.paper_id = paper.id)
						  AND NOT EXISTS (SELECT 1 FROM collection_paper saved WHERE saved.paper_id = paper.id)
						"""));
	}

	static ProductTableCounts productTableCounts(JdbcTemplate jdbcTemplate) {
		return new ProductTableCounts(
				count(jdbcTemplate, "SELECT count(*) FROM paper"),
				count(jdbcTemplate, "SELECT count(*) FROM provider_record"),
				count(jdbcTemplate, "SELECT count(*) FROM search_snapshot"),
				count(jdbcTemplate, "SELECT count(*) FROM search_result"),
				count(jdbcTemplate, "SELECT count(*) FROM library_collection"),
				count(jdbcTemplate, "SELECT count(*) FROM collection_paper"));
	}

	static boolean targetVisible(UUID paperId, RelatedTopicReuseScalePolicy.Corpus corpus) {
		int ordinal = ordinal(paperId);
		return ordinal >= 0
				&& ordinal < corpus.targetSearchVisibleCount() + corpus.targetCollectionVisibleCount();
	}

	static int ordinal(UUID paperId) {
		Objects.requireNonNull(paperId, "paperId");
		String value = paperId.toString();
		if (!value.startsWith(PAPER_UUID_PREFIX)) {
			return -1;
		}
		try {
			return Math.toIntExact(Long.parseLong(value.substring(value.length() - 12), 16) - 1L);
		}
		catch (NumberFormatException | ArithmeticException exception) {
			return -1;
		}
	}

	static UUID paperId(int ordinal) {
		if (ordinal < 0 || ordinal > 0x7ffffffe) {
			throw new IllegalArgumentException("paper ordinal is out of range");
		}
		return UUID.fromString(PAPER_UUID_PREFIX + String.format(Locale.ROOT, "%012x", ordinal + 1));
	}

	static List<String> sqlContract() {
		return List.of(
				INSERT_PAPERS,
				INSERT_PROVIDER_RECORDS,
				INSERT_TARGET_SNAPSHOTS,
				INSERT_TARGET_RESULTS,
				INSERT_OTHER_SNAPSHOTS,
				INSERT_OTHER_RESULTS,
				INSERT_COLLECTION,
				INSERT_COLLECTION_PAPERS);
	}

	static String generatorSqlSha256() {
		String canonical = String.join("\n-- related-topic-scale-statement --\n", sqlContract());
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String snapshotInsertSql(
			String snapshotPrefix, String cohort, String fingerprintSalt) {
		return """
				INSERT INTO search_snapshot
				    (id, owner_id, original_query, normalized_query, fingerprint,
				     fingerprint_version, pipeline_version, filters, status, searched_at,
				     fresh_until, provider_coverage, warnings, total_provider_matches,
				     result_count, next_cursor, created_at, requested_mode, result_origin)
				SELECT (('%s' || lpad(to_hex(snapshot_ordinal + 1), 12, '0'))::uuid),
				       ?,
				       'related-topic scale prior %s ' || snapshot_ordinal,
				       'related-topic scale prior %s ' || snapshot_ordinal,
				       md5('%s-left-' || snapshot_ordinal) || md5('%s-right-' || snapshot_ordinal),
				       1,
				       'openalex-v1',
			       jsonb_build_object(
			           'documentTypes', '[]'::jsonb,
			           'openAccessOnly', FALSE,
			           'minimumCitations', 0,
			           'languages', '[]'::jsonb,
			           'pageSize', 50,
			           'cursor', '*',
			           'mode', 'ONLINE'
			       ),
				       'COMPLETED',
				       '2026-08-29T00:00:00Z'::timestamptz,
				       '2026-08-30T00:00:00Z'::timestamptz,
			       jsonb_build_array(jsonb_build_object(
			           'provider', 'OPENALEX',
			           'status', 'SUCCESS',
			           'returnedCount', 50,
			           'totalMatches', 50
			       )),
				       '[]'::jsonb,
				       50,
				       50,
				       NULL,
				       '2026-08-29T00:00:00Z'::timestamptz,
				       'ONLINE',
				       'PROVIDER'
				FROM generate_series(
				    0,
				    (cast(? as integer) / %d) - 1
				) AS series(snapshot_ordinal)
				""".formatted(
				snapshotPrefix,
				cohort,
				cohort,
				fingerprintSalt,
				fingerprintSalt,
				SEARCH_RESULTS_PER_SNAPSHOT);
	}

	private static String resultInsertSql(
			String resultPrefix, String snapshotPrefix) {
		return """
				INSERT INTO search_result
				    (id, search_id, paper_id, paper_snapshot, result_rank, total_score,
				     reported_open_access, landing_page_url, pdf_url, ranking_reasons,
				     provider_contributions, provider, provider_record_id, retrieved_at)
				SELECT (('%s' || lpad(to_hex(local_ordinal + 1), 12, '0'))::uuid),
				       (('%s' || lpad(to_hex((local_ordinal / %d) + 1), 12, '0'))::uuid),
				       (('41000000-0000-0000-0000-' || lpad(to_hex(paper_ordinal + 1), 12, '0'))::uuid),
			       jsonb_build_object(
			           'id', ('41000000-0000-0000-0000-'
			               || lpad(to_hex(paper_ordinal + 1), 12, '0'))::uuid,
			           'title', snapshot_paper.title,
			           'abstractText', snapshot_paper.abstract_text,
			           'publicationDate', NULL,
			           'publicationYear', snapshot_paper.publication_year,
			           'documentType', snapshot_paper.document_type,
			           'language', snapshot_paper.language,
			           'venueName', snapshot_paper.venue_name,
			           'citationCount', snapshot_paper.citation_count,
			           'citationCountAsOf', '2026-08-29T00:00:00Z',
			           'identifiers', '[]'::jsonb,
			           'authors', '[]'::jsonb,
			           'publisher', NULL,
			           'institution', NULL,
			           'volume', NULL,
			           'issue', NULL,
			           'pages', NULL,
			           'articleNumber', NULL,
			           'edition', NULL,
			           'isbn', '[]'::jsonb,
			           'issn', '[]'::jsonb,
			           'degree', NULL
			       ),
				       (local_ordinal %% %d) + 1,
				       1.0,
				       CASE
				           WHEN paper_ordinal = 40104 THEN FALSE
				           WHEN paper_ordinal BETWEEN 40000 AND 40106
				                OR paper_ordinal IN (50002, 75002) THEN TRUE
				           ELSE paper_ordinal %% 3 = 0
				       END,
				       'https://fixtures.openscholar.test/papers/' || paper_ordinal,
				       NULL,
				       '[]'::jsonb,
				       '[]'::jsonb,
				       'OPENALEX',
				       'RTSCALEV1-' || paper_ordinal,
				       '2026-08-29T00:00:00Z'::timestamptz
				FROM (
				    SELECT local_ordinal,
				           local_ordinal + cast(? as integer) AS paper_ordinal
				    FROM generate_series(0, ? - 1) AS series(local_ordinal)
			) generated
			JOIN paper snapshot_paper
			  ON snapshot_paper.id = (('41000000-0000-0000-0000-'
			      || lpad(to_hex(paper_ordinal + 1), 12, '0'))::uuid)
				""".formatted(
				resultPrefix,
				snapshotPrefix,
				SEARCH_RESULTS_PER_SNAPSHOT,
				SEARCH_RESULTS_PER_SNAPSHOT);
	}

	private static void insertOwner(
			JdbcTemplate jdbcTemplate, UUID ownerId, String displayName) {
		jdbcTemplate.update(
				"INSERT INTO app_user (id, display_name, created_at) VALUES (?, ?, ?)",
				ownerId,
				displayName,
				Timestamp.from(SEEDED_AT));
	}

	private static void cleanup(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.update(
				"DELETE FROM app_user WHERE id IN (?, ?)", TARGET_OWNER, OTHER_OWNER);
		jdbcTemplate.update(
				"""
				DELETE FROM paper
				WHERE id::text LIKE '41000000-%'
				   OR id IN (
						    SELECT paper_id
						    FROM provider_record
						    WHERE provider = 'OPENALEX'
						      AND provider_record_id LIKE 'RTSCALEV1-%'
						)
						""");
	}

	private static long count(JdbcTemplate jdbcTemplate, String sql) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class);
		return value == null ? 0L : value;
	}

	record SeedTiming(long seedNanos, long analyzeNanos) {

		SeedTiming {
			if (seedNanos < 0L || analyzeNanos < 0L) {
				throw new IllegalArgumentException("Seed timings must be non-negative");
			}
		}
	}

	record FixtureCounts(
			long papers,
			long targetSearchVisible,
			long targetCollectionVisible,
			long otherOwnerVisible,
			long catalogOnly) {
	}

	record ProductTableCounts(
			long papers,
			long providerRecords,
			long searchSnapshots,
			long searchResults,
			long collections,
			long collectionPapers) {
	}
}
