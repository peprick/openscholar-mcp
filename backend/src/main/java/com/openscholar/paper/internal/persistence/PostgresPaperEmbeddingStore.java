package com.openscholar.paper.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingProfile;
import com.openscholar.paper.EmbeddingProfileConflictException;
import com.openscholar.paper.EmbeddingProfileNotFoundException;
import com.openscholar.paper.PaperEmbeddingCandidate;
import com.openscholar.paper.PaperEmbeddingMatch;
import com.openscholar.paper.PaperEmbeddingNotFoundException;
import com.openscholar.paper.PaperEmbeddingSource;
import com.openscholar.paper.PaperEmbeddingStore;
import com.openscholar.paper.PaperEmbeddingWorkPage;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.StalePaperEmbeddingException;
import com.openscholar.paper.StoreEmbeddingOutcome;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PostgresPaperEmbeddingStore implements PaperEmbeddingStore {

	private static final int MAX_NEAREST_RESULTS = 100;
	private static final int MAX_MISSING_RESULTS = 500;

	private static final String INSERT_PROFILE_SQL = """
			insert into embedding_profile (
			    profile_key,
			    provider,
			    model,
			    model_revision,
			    content_kind,
			    input_policy_version,
			    dimensions,
			    distance_metric,
			    created_at
			)
			values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
			on conflict (profile_key) do nothing
			""";

	private static final String FIND_PROFILE_SQL = """
			select
			    profile_key,
			    provider,
			    model,
			    model_revision,
			    content_kind,
			    input_policy_version,
			    dimensions,
			    distance_metric
			from embedding_profile
			where profile_key = ?
			""";

	private static final String SAVE_EMBEDDING_SQL = """
			insert into paper_embedding (
			    paper_id,
			    profile_key,
			    dimensions,
			    content_checksum,
			    embedding,
			    embedded_at
			)
			values (?, ?, ?, ?, cast(? as public.vector), ?)
			on conflict (paper_id, profile_key) do update
			set dimensions = excluded.dimensions,
			    content_checksum = excluded.content_checksum,
			    embedding = excluded.embedding,
			    embedded_at = excluded.embedded_at
			where paper_embedding.content_checksum <> excluded.content_checksum
			""";

	private static final String FIND_MISSING_FROM_START_SQL = """
			select paper.id
			from paper
			where not exists (
			    select 1
			    from paper_embedding embedding
			    where embedding.paper_id = paper.id
			      and embedding.profile_key = ?
			)
			order by paper.id
			limit ?
			""";

	private static final String FIND_MISSING_AFTER_SQL = """
			select paper.id
			from paper
			where paper.id > ?
			  and not exists (
			      select 1
			      from paper_embedding embedding
			      where embedding.paper_id = paper.id
			        and embedding.profile_key = ?
			  )
			order by paper.id
			limit ?
			""";

	private static final String FIND_NEAREST_SQL = """
			with source as (
			    select paper_id, profile_key, embedding
			    from paper_embedding
			    where paper_id = ?
			      and profile_key = ?
			)
			select
			    source.paper_id as source_paper_id,
			    candidate.paper_id,
			    candidate.cosine_similarity
			from source
			left join lateral (
			    select
			        candidate.paper_id,
			        1.0 - (candidate.embedding <=> source.embedding) as cosine_similarity
			    from paper_embedding candidate
			    where candidate.profile_key = source.profile_key
			      and candidate.paper_id <> source.paper_id
			    order by
			        candidate.embedding <=> source.embedding,
			        candidate.paper_id
			    limit ?
			) candidate on true
			""";

	private static final String FIND_APPROXIMATE_NEAREST_SQL = """
			with source as materialized (
			    select
			        paper_id,
			        embedding::public.vector(%d) as embedding
			    from paper_embedding
			    where paper_id = ?
			      and profile_key = '%s'
			)
			select
			    source.paper_id as source_paper_id,
			    candidate.paper_id,
			    1.0 - candidate.cosine_distance as cosine_similarity
			from source
			left join lateral (
			    select
			        candidate.paper_id,
			        candidate.embedding::public.vector(%d) <=> source.embedding
			            as cosine_distance
			    from paper_embedding candidate
			    where candidate.profile_key = '%s'
			      and candidate.paper_id <> source.paper_id
			    order by
			        candidate.embedding::public.vector(%d) <=> source.embedding
			    limit ?
			) candidate on true
			order by
			    candidate.cosine_distance,
			    candidate.paper_id
			limit ?
			""".formatted(
				PaperEmbeddingAnnPolicy.DIMENSIONS,
				PaperEmbeddingAnnPolicy.PROFILE_KEY,
				PaperEmbeddingAnnPolicy.DIMENSIONS,
				PaperEmbeddingAnnPolicy.PROFILE_KEY,
				PaperEmbeddingAnnPolicy.DIMENSIONS);

	private static final String READ_EXACT_PLANNER_SETTINGS_SQL = """
			select current_setting('enable_indexscan') as enable_indexscan
			""";

	private static final String APPLY_EXACT_PLANNER_SETTINGS_SQL = """
			select set_config('enable_indexscan', 'off', true)
			""";

	private static final String RESTORE_EXACT_PLANNER_SETTINGS_SQL = """
			select set_config('enable_indexscan', ?, true)
			""";

	private static final String READ_APPROXIMATE_PLANNER_SETTINGS_SQL = """
			select
			    current_setting('enable_indexscan') as enable_indexscan,
			    current_setting('enable_seqscan') as enable_seqscan,
			    current_setting('hnsw.ef_search') as hnsw_ef_search,
			    current_setting('hnsw.iterative_scan') as hnsw_iterative_scan,
			    current_setting('hnsw.max_scan_tuples') as hnsw_max_scan_tuples
			""";

	private static final String APPLY_APPROXIMATE_PLANNER_SETTINGS_SQL = """
			select
			    set_config('enable_indexscan', 'on', true),
			    set_config('enable_seqscan', 'off', true),
			    set_config('hnsw.ef_search', '%d', true),
			    set_config('hnsw.iterative_scan', 'strict_order', true),
			    set_config('hnsw.max_scan_tuples', '%d', true)
			""".formatted(
				PaperEmbeddingAnnPolicy.QUERY_EF_SEARCH,
				PaperEmbeddingAnnPolicy.QUERY_MAX_SCAN_TUPLES);

	private static final String RESTORE_APPROXIMATE_PLANNER_SETTINGS_SQL = """
			select
			    set_config('enable_indexscan', ?, true),
			    set_config('enable_seqscan', ?, true),
			    set_config('hnsw.ef_search', ?, true),
			    set_config('hnsw.iterative_scan', ?, true),
			    set_config('hnsw.max_scan_tuples', ?, true)
			""";

	private final JdbcTemplate jdbcTemplate;
	private final PaperEmbeddingInputRenderer inputRenderer;

	PostgresPaperEmbeddingStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.inputRenderer = new PaperEmbeddingInputRenderer();
	}

	@Override
	@Transactional
	public EmbeddingProfile registerProfile(EmbeddingProfile profile) {
		Objects.requireNonNull(profile, "profile");
		validateSupportedInputPolicy(profile);
		try {
			jdbcTemplate.update(
					INSERT_PROFILE_SQL,
					profile.profileKey(),
					profile.provider(),
					profile.model(),
					profile.modelRevision(),
					profile.contentKind().name(),
					profile.inputPolicyVersion(),
					profile.dimensions(),
					profile.distanceMetric().name());
		}
		catch (DataIntegrityViolationException exception) {
			throw new EmbeddingProfileConflictException(
					"Embedding profile definition is already registered under another key", exception);
		}

		EmbeddingProfile stored = findProfile(profile.profileKey());
		if (!stored.equals(profile)) {
			throw new EmbeddingProfileConflictException(
					"Embedding profile key is already registered with a different definition: "
							+ profile.profileKey());
		}
		return stored;
	}

	@Override
	@Transactional(readOnly = true)
	public PaperEmbeddingSource prepareSource(UUID paperId, String profileKey) {
		Objects.requireNonNull(paperId, "paperId");
		EmbeddingProfile profile = findProfile(profileKey);
		PaperSourceRow paper = findPaperSource(paperId, false);
		return inputRenderer.render(paperId, profile, paper.title(), paper.abstractText());
	}

	@Override
	@Transactional
	public StoreEmbeddingOutcome saveIfSourceCurrent(PaperEmbeddingCandidate candidate) {
		Objects.requireNonNull(candidate, "candidate");
		EmbeddingProfile profile = findProfile(candidate.profileKey());
		validateVector(candidate.vector(), profile.dimensions());

		PaperSourceRow currentPaper = findPaperSource(candidate.paperId(), true);
		PaperEmbeddingSource currentSource = inputRenderer.render(
				candidate.paperId(), profile, currentPaper.title(), currentPaper.abstractText());
		if (!currentSource.contentChecksum().equals(candidate.contentChecksum())) {
			throw new StalePaperEmbeddingException(candidate.paperId(), candidate.profileKey());
		}

		int changedRows = jdbcTemplate.update(
				SAVE_EMBEDDING_SQL,
				candidate.paperId(),
				candidate.profileKey(),
				profile.dimensions(),
				candidate.contentChecksum(),
				toVectorLiteral(candidate.vector()),
				Timestamp.from(candidate.generatedAt()));
		return changedRows == 0 ? StoreEmbeddingOutcome.UNCHANGED : StoreEmbeddingOutcome.STORED;
	}

	@Override
	@Transactional(readOnly = true)
	public PaperEmbeddingWorkPage findMissing(
			String profileKey, UUID afterExclusive, int limit) {
		if (limit < 1 || limit > MAX_MISSING_RESULTS) {
			throw new IllegalArgumentException(
					"Missing-embedding limit must be between 1 and " + MAX_MISSING_RESULTS);
		}
		EmbeddingProfile profile = findProfile(profileKey);
		int fetchLimit = limit + 1;
		List<UUID> fetchedPaperIds;
		if (afterExclusive == null) {
			fetchedPaperIds = jdbcTemplate.query(
					FIND_MISSING_FROM_START_SQL,
					(resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
					profile.profileKey(),
					fetchLimit);
		}
		else {
			fetchedPaperIds = jdbcTemplate.query(
					FIND_MISSING_AFTER_SQL,
					(resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
					afterExclusive,
					profile.profileKey(),
					fetchLimit);
		}

		boolean hasMore = fetchedPaperIds.size() > limit;
		List<UUID> paperIds = hasMore
				? List.copyOf(fetchedPaperIds.subList(0, limit))
				: List.copyOf(fetchedPaperIds);
		UUID nextCursor = hasMore ? paperIds.get(paperIds.size() - 1) : null;
		return new PaperEmbeddingWorkPage(paperIds, nextCursor, hasMore);
	}

	@Override
	@Transactional(readOnly = true)
	public List<PaperEmbeddingMatch> findNearestExact(
			UUID sourcePaperId, String profileKey, int limit) {
		EmbeddingProfile profile = validateNearestRequest(sourcePaperId, profileKey, limit);
		ExactPlannerSettings previousSettings = readExactPlannerSettings();
		List<ScoredEmbedding> rows;
		try {
			jdbcTemplate.queryForMap(APPLY_EXACT_PLANNER_SETTINGS_SQL);
			rows = jdbcTemplate.query(
					FIND_NEAREST_SQL,
					(resultSet, rowNumber) -> new ScoredEmbedding(
							resultSet.getObject("paper_id", UUID.class),
							resultSet.getObject("cosine_similarity", Double.class)),
					sourcePaperId,
					profile.profileKey(),
					limit);
		}
		finally {
			restoreExactPlannerSettings(previousSettings);
		}
		return toMatches(sourcePaperId, profile.profileKey(), rows);
	}

	@Override
	@Transactional(readOnly = true)
	public List<PaperEmbeddingMatch> findNearestApproximate(
			UUID sourcePaperId, String profileKey, int limit) {
		EmbeddingProfile profile = validateNearestRequest(sourcePaperId, profileKey, limit);
		PaperEmbeddingAnnPolicy.requireSupported(profile);
		ApproximatePlannerSettings previousSettings = readApproximatePlannerSettings();
		List<ScoredEmbedding> rows;
		try {
			jdbcTemplate.queryForMap(APPLY_APPROXIMATE_PLANNER_SETTINGS_SQL);
			rows = jdbcTemplate.query(
					FIND_APPROXIMATE_NEAREST_SQL,
					(resultSet, rowNumber) -> new ScoredEmbedding(
							resultSet.getObject("paper_id", UUID.class),
							resultSet.getObject("cosine_similarity", Double.class)),
					sourcePaperId,
					PaperEmbeddingAnnPolicy.candidatePool(limit),
					limit);
		}
		finally {
			restoreApproximatePlannerSettings(previousSettings);
		}
		return toMatches(sourcePaperId, profile.profileKey(), rows);
	}

	private EmbeddingProfile validateNearestRequest(
			UUID sourcePaperId, String profileKey, int limit) {
		Objects.requireNonNull(sourcePaperId, "sourcePaperId");
		if (limit < 1 || limit > MAX_NEAREST_RESULTS) {
			throw new IllegalArgumentException(
					"Nearest-embedding limit must be between 1 and " + MAX_NEAREST_RESULTS);
		}
		EmbeddingProfile profile = findProfile(profileKey);
		if (profile.distanceMetric() != EmbeddingDistanceMetric.COSINE) {
			throw new IllegalArgumentException(
					"Unsupported embedding distance metric: " + profile.distanceMetric());
		}
		return profile;
	}

	private List<PaperEmbeddingMatch> toMatches(
			UUID sourcePaperId, String profileKey, List<ScoredEmbedding> rows) {
		if (rows.isEmpty()) {
			throw new PaperEmbeddingNotFoundException(sourcePaperId, profileKey);
		}
		List<PaperEmbeddingMatch> matches = new ArrayList<>(rows.size());
		for (ScoredEmbedding embedding : rows) {
			if (embedding.paperId() == null) {
				continue;
			}
			matches.add(new PaperEmbeddingMatch(
					matches.size() + 1,
					embedding.paperId(),
					embedding.cosineSimilarity()));
		}
		return List.copyOf(matches);
	}

	private ExactPlannerSettings readExactPlannerSettings() {
		return jdbcTemplate.queryForObject(
				READ_EXACT_PLANNER_SETTINGS_SQL,
				(resultSet, rowNumber) -> new ExactPlannerSettings(
						resultSet.getString("enable_indexscan")));
	}

	private void restoreExactPlannerSettings(ExactPlannerSettings settings) {
		jdbcTemplate.queryForMap(
				RESTORE_EXACT_PLANNER_SETTINGS_SQL,
				settings.enableIndexScan());
	}

	private ApproximatePlannerSettings readApproximatePlannerSettings() {
		return jdbcTemplate.queryForObject(
				READ_APPROXIMATE_PLANNER_SETTINGS_SQL,
				(resultSet, rowNumber) -> new ApproximatePlannerSettings(
						resultSet.getString("enable_indexscan"),
						resultSet.getString("enable_seqscan"),
						resultSet.getString("hnsw_ef_search"),
						resultSet.getString("hnsw_iterative_scan"),
						resultSet.getString("hnsw_max_scan_tuples")));
	}

	private void restoreApproximatePlannerSettings(ApproximatePlannerSettings settings) {
		jdbcTemplate.queryForMap(
				RESTORE_APPROXIMATE_PLANNER_SETTINGS_SQL,
				settings.enableIndexScan(),
				settings.enableSeqScan(),
				settings.hnswEfSearch(),
				settings.hnswIterativeScan(),
				settings.hnswMaxScanTuples());
	}

	private EmbeddingProfile findProfile(String profileKey) {
		String requiredKey = Objects.requireNonNull(profileKey, "profileKey");
		return jdbcTemplate.query(FIND_PROFILE_SQL, this::mapProfile, requiredKey).stream()
				.findFirst()
				.orElseThrow(() -> new EmbeddingProfileNotFoundException(requiredKey));
	}

	private EmbeddingProfile mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
		return new EmbeddingProfile(
				resultSet.getString("profile_key"),
				resultSet.getString("provider"),
				resultSet.getString("model"),
				resultSet.getString("model_revision"),
				EmbeddingContentKind.valueOf(resultSet.getString("content_kind")),
				resultSet.getInt("input_policy_version"),
				resultSet.getInt("dimensions"),
				EmbeddingDistanceMetric.valueOf(resultSet.getString("distance_metric")));
	}

	private PaperSourceRow findPaperSource(UUID paperId, boolean lock) {
		String sql = "select title, abstract_text from paper where id = ?"
				+ (lock ? " for update" : "");
		return jdbcTemplate.query(
				sql,
				(resultSet, rowNumber) -> new PaperSourceRow(
						resultSet.getString("title"),
						resultSet.getString("abstract_text")),
				paperId).stream()
				.findFirst()
				.orElseThrow(() -> new PaperNotFoundException(paperId));
	}

	private void validateSupportedInputPolicy(EmbeddingProfile profile) {
		if (profile.contentKind() != EmbeddingContentKind.TITLE_ABSTRACT
				|| profile.inputPolicyVersion() != 1) {
			throw new IllegalArgumentException(
					"Unsupported embedding input policy: " + profile.contentKind()
							+ " v" + profile.inputPolicyVersion());
		}
	}

	private void validateVector(List<Float> vector, int expectedDimensions) {
		if (vector.size() != expectedDimensions) {
			throw new IllegalArgumentException(
					"Embedding vector must contain exactly " + expectedDimensions + " dimensions");
		}
		boolean hasNonZeroComponent = false;
		for (Float component : vector) {
			if (component == null || !Float.isFinite(component)) {
				throw new IllegalArgumentException("Embedding vector components must be finite");
			}
			hasNonZeroComponent |= component != 0.0f;
		}
		if (!hasNonZeroComponent) {
			throw new IllegalArgumentException("Embedding vector must not be the zero vector");
		}
	}

	private String toVectorLiteral(List<Float> vector) {
		StringJoiner literal = new StringJoiner(",", "[", "]");
		vector.forEach(component -> literal.add(Float.toString(component)));
		return literal.toString();
	}

	private record PaperSourceRow(String title, String abstractText) {
	}

	private record ScoredEmbedding(UUID paperId, Double cosineSimilarity) {
	}

	private record ExactPlannerSettings(String enableIndexScan) {
	}

	private record ApproximatePlannerSettings(
			String enableIndexScan,
			String enableSeqScan,
			String hnswEfSearch,
			String hnswIterativeScan,
			String hnswMaxScanTuples) {
	}
}
