package com.openscholar.paper.internal.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.openscholar.paper.EmbeddingProfileNotFoundException;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperEmbeddingMatch;
import com.openscholar.paper.PaperEmbeddingNotFoundException;
import com.openscholar.paper.PaperEmbeddingStore;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.RelatedPaperFallbackReason;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPaperRankingFeature;
import com.openscholar.paper.RelatedPaperRankingMode;
import com.openscholar.paper.RelatedPaperRankingReason;
import com.openscholar.paper.RelatedPaperUseCase;
import com.openscholar.paper.RelatedPapersView;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.HybridCandidateFeatures;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.HybridRankedPaper;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.VectorRankedPaper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PostgresRelatedPaperService implements RelatedPaperUseCase {

	private static final int MAX_RESULTS = 25;
	private static final int MAX_SEED_LEXEMES = 16;
	private static final int MAX_HYBRID_CANDIDATES =
			RelatedPaperHybridProperties.MAXIMUM_CANDIDATE_POOL_SIZE * 2;

	private static final String RELATED_PAPERS_SQL = """
			with seed_lexemes as (
			    select
			        seed.id,
			        token.lexeme,
			        (token.positions)[1] as first_position
			    from paper seed
			    cross join lateral unnest(to_tsvector('english'::regconfig, seed.title))
			        as token(lexeme, positions, weights)
			    where seed.id = ?
			    order by first_position, token.lexeme
			    limit %d
			), seed as (
			    select
			        id,
			        string_agg(
			            quote_literal(lexeme),
			            ' | '
			            order by first_position, lexeme
			        )::tsquery as related_query
			    from seed_lexemes
			    group by id
			), ranked as (
			    select
			        candidate.id as paper_id,
			        ts_rank_cd(
			            candidate.search_vector,
			            seed.related_query,
			            32
			        )::double precision as score,
			        candidate.metadata_quality,
			        candidate.citation_count,
			        candidate.publication_year
			    from seed
			    join paper candidate on candidate.id <> seed.id
			    where candidate.search_vector @@ seed.related_query
			)
			select paper_id, score
			from ranked
			order by
			    score desc,
			    metadata_quality desc,
			    citation_count desc nulls last,
			    publication_year desc nulls last,
			    paper_id
			limit ?
			""".formatted(MAX_SEED_LEXEMES);

	private static final String CANDIDATE_LEXICAL_SCORES_SQL = """
			with seed_lexemes as (
			    select
			        token.lexeme,
			        (token.positions)[1] as first_position
			    from paper seed
			    cross join lateral unnest(to_tsvector('english'::regconfig, seed.title))
			        as token(lexeme, positions, weights)
			    where seed.id = ?
			    order by first_position, token.lexeme
			    limit %d
			), seed as (
			    select string_agg(
			        quote_literal(lexeme),
			        ' | '
			        order by first_position, lexeme
			    )::tsquery as related_query
			    from seed_lexemes
			    having count(*) > 0
			), candidate_ids(paper_id) as (
			    values %s
			)
			select
			    candidate_ids.paper_id,
			    coalesce(
			        (select ts_rank_cd(
			            candidate.search_vector,
			            seed.related_query,
			            32
			        )::double precision from seed),
			        0.0::double precision
			    ) as score
			from candidate_ids
			join paper candidate on candidate.id = candidate_ids.paper_id
			order by candidate_ids.paper_id
			""";

	private final JdbcTemplate jdbcTemplate;
	private final PaperCatalog paperCatalog;
	private final PaperEmbeddingStore embeddingStore;
	private final RelatedPaperHybridProperties hybridProperties;

	PostgresRelatedPaperService(
			JdbcTemplate jdbcTemplate,
			PaperCatalog paperCatalog,
			PaperEmbeddingStore embeddingStore,
			RelatedPaperHybridProperties hybridProperties) {
		this.jdbcTemplate = jdbcTemplate;
		this.paperCatalog = paperCatalog;
		this.embeddingStore = embeddingStore;
		this.hybridProperties = hybridProperties;
	}

	@Override
	@Transactional(readOnly = true)
	public RelatedPapersView findRelated(UUID paperId, int limit) {
		Objects.requireNonNull(paperId, "paperId");
		if (limit < 1 || limit > MAX_RESULTS) {
			throw new IllegalArgumentException(
					"Related-paper limit must be between 1 and " + MAX_RESULTS);
		}
		paperCatalog.findById(paperId).orElseThrow(() -> new PaperNotFoundException(paperId));
		if (!hybridProperties.enabled()) {
			return lexicalView(
					paperId,
					findLexicalCandidates(paperId, limit),
					limit,
					RelatedPaperFallbackReason.HYBRID_DISABLED);
		}

		List<ScoredPaper> lexicalCandidates = findLexicalCandidates(
				paperId, hybridProperties.candidatePoolSize());
		try {
			return hybridView(paperId, lexicalCandidates, limit);
		}
		catch (EmbeddingProfileNotFoundException exception) {
			return lexicalView(
					paperId,
					lexicalCandidates,
					limit,
					RelatedPaperFallbackReason.EMBEDDING_PROFILE_MISSING);
		}
		catch (PaperEmbeddingNotFoundException exception) {
			return lexicalView(
					paperId,
					lexicalCandidates,
					limit,
					RelatedPaperFallbackReason.SOURCE_VECTOR_MISSING);
		}
	}

	private List<ScoredPaper> findLexicalCandidates(UUID paperId, int limit) {
		return jdbcTemplate.query(
				RELATED_PAPERS_SQL,
				(resultSet, rowNumber) -> new ScoredPaper(
						resultSet.getObject("paper_id", UUID.class),
						resultSet.getDouble("score")),
				paperId,
				limit);
	}

	private RelatedPapersView hybridView(
			UUID sourcePaperId, List<ScoredPaper> lexicalCandidates, int limit) {
		List<PaperEmbeddingMatch> approximate = embeddingStore.findNearestApproximate(
				sourcePaperId,
				PaperEmbeddingAnnPolicy.PROFILE_KEY,
				hybridProperties.candidatePoolSize());
		List<PaperEmbeddingMatch> lexicalSimilarities = lexicalCandidates.isEmpty()
				? List.of()
				: embeddingStore.findExactSimilarities(
						sourcePaperId,
						PaperEmbeddingAnnPolicy.PROFILE_KEY,
						lexicalCandidates.stream().map(ScoredPaper::paperId).toList());
		Set<UUID> coveredLexicalIds = lexicalSimilarities.stream()
			.map(PaperEmbeddingMatch::paperId)
			.collect(Collectors.toUnmodifiableSet());
		if (coveredLexicalIds.size() != lexicalCandidates.size()
				|| !coveredLexicalIds.containsAll(
						lexicalCandidates.stream().map(ScoredPaper::paperId).toList())) {
			return lexicalView(
					sourcePaperId,
					lexicalCandidates,
					limit,
					RelatedPaperFallbackReason.CANDIDATE_VECTOR_COVERAGE_INCOMPLETE);
		}

		Map<UUID, Double> cosineByPaperId = new LinkedHashMap<>();
		approximate.forEach(match -> cosineByPaperId.put(
				match.paperId(), match.cosineSimilarity()));
		lexicalSimilarities.forEach(match -> cosineByPaperId.put(
				match.paperId(), match.cosineSimilarity()));
		if (cosineByPaperId.size() > MAX_HYBRID_CANDIDATES) {
			throw new IllegalStateException(
					"Related-paper hybrid candidate pool exceeded its fixed bound");
		}
		if (cosineByPaperId.isEmpty()) {
			return new RelatedPapersView(
					sourcePaperId, RelatedPaperRankingMode.HYBRID, null, List.of());
		}

		Map<UUID, Double> lexicalScores = findCandidateLexicalScores(
				sourcePaperId, cosineByPaperId.keySet());
		if (lexicalScores.size() != cosineByPaperId.size()) {
			throw new IllegalStateException(
					"A related-paper hybrid candidate disappeared while it was being scored");
		}
		Map<String, UUID> paperIdByKey = new LinkedHashMap<>();
		List<VectorRankedPaper> vectorCandidates = cosineByPaperId.entrySet().stream()
			.sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder())
					.thenComparing(entry -> entry.getKey().toString()))
			.map(entry -> {
				String key = entry.getKey().toString();
				paperIdByKey.put(key, entry.getKey());
				return new VectorRankedPaper(key, entry.getValue());
			})
			.toList();
		Map<String, Double> lexicalScoresByKey = new LinkedHashMap<>();
		lexicalScores.forEach((paperId, score) -> lexicalScoresByKey.put(
				paperId.toString(), score));
		Map<String, Integer> lexicalRanksByKey = new LinkedHashMap<>();
		for (int index = 0; index < lexicalCandidates.size(); index++) {
			lexicalRanksByKey.put(
					lexicalCandidates.get(index).paperId().toString(), index + 1);
		}
		List<HybridCandidateFeatures> features = RelatedPaperHybridScorer.combine(
				vectorCandidates,
				lexicalScoresByKey,
				lexicalRanksByKey,
				vectorCandidates.size());
		List<HybridRankedPaper> ranked = RelatedPaperHybridScorer.rankHybridCandidates(
				features, RelatedPaperHybridScorer.FROZEN_SEMANTIC_WEIGHT, limit);
		List<UUID> rankedPaperIds = ranked.stream()
			.map(candidate -> paperIdByKey.get(candidate.paperKey()))
			.toList();
		Map<UUID, PaperView> papers = paperCatalog.findAllByIds(rankedPaperIds);
		List<RelatedPaperMatch> matches = new ArrayList<>(ranked.size());
		for (HybridRankedPaper candidate : ranked) {
			UUID paperId = paperIdByKey.get(candidate.paperKey());
			PaperView paper = papers.get(paperId);
			if (paper == null) {
				throw new IllegalStateException(
						"A ranked related paper disappeared before it could be loaded: "
								+ paperId);
			}
			matches.add(new RelatedPaperMatch(
					matches.size() + 1,
					paper,
					candidate.hybridScore(),
					List.of(
							new RelatedPaperRankingReason(
									RelatedPaperRankingFeature.POSTGRES_FULL_TEXT,
									candidate.lexicalScore()),
							new RelatedPaperRankingReason(
									RelatedPaperRankingFeature.CLAMPED_COSINE,
									candidate.semanticScore()))));
		}
		return new RelatedPapersView(
				sourcePaperId, RelatedPaperRankingMode.HYBRID, null, matches);
	}

	private Map<UUID, Double> findCandidateLexicalScores(
			UUID sourcePaperId, Set<UUID> candidatePaperIds) {
		List<UUID> candidates = new ArrayList<>(new LinkedHashSet<>(candidatePaperIds));
		if (candidates.isEmpty() || candidates.size() > MAX_HYBRID_CANDIDATES) {
			throw new IllegalArgumentException(
					"Hybrid lexical scoring requires between 1 and "
							+ MAX_HYBRID_CANDIDATES + " candidate paper IDs");
		}
		String requestedRows = candidates.stream()
			.map(ignored -> "(cast(? as uuid))")
			.collect(Collectors.joining(", "));
		List<Object> arguments = new ArrayList<>(candidates.size() + 1);
		arguments.add(sourcePaperId);
		arguments.addAll(candidates);
		Map<UUID, Double> scores = new LinkedHashMap<>();
		jdbcTemplate.query(
				CANDIDATE_LEXICAL_SCORES_SQL.formatted(
						MAX_SEED_LEXEMES, requestedRows),
				resultSet -> {
					UUID paperId = resultSet.getObject("paper_id", UUID.class);
					Double previous = scores.put(
							paperId, resultSet.getDouble("score"));
					if (previous != null) {
						throw new IllegalStateException(
								"Hybrid lexical scoring returned a duplicate paper: " + paperId);
					}
				},
				arguments.toArray());
		return Map.copyOf(scores);
	}

	private RelatedPapersView lexicalView(
			UUID sourcePaperId,
			List<ScoredPaper> lexicalCandidates,
			int limit,
			RelatedPaperFallbackReason fallbackReason) {
		List<ScoredPaper> scoredPapers = lexicalCandidates.stream().limit(limit).toList();
		Map<UUID, PaperView> papers = paperCatalog.findAllByIds(
				scoredPapers.stream().map(ScoredPaper::paperId).toList());

		List<RelatedPaperMatch> matches = new ArrayList<>(scoredPapers.size());
		for (ScoredPaper scoredPaper : scoredPapers) {
			PaperView paper = papers.get(scoredPaper.paperId());
			if (paper == null) {
				throw new IllegalStateException(
						"A ranked related paper disappeared before it could be loaded: "
								+ scoredPaper.paperId());
			}
			matches.add(new RelatedPaperMatch(
					matches.size() + 1,
					paper,
					scoredPaper.score(),
					List.of(new RelatedPaperRankingReason(
							RelatedPaperRankingFeature.POSTGRES_FULL_TEXT,
							scoredPaper.score()))));
		}
		return new RelatedPapersView(
				sourcePaperId, RelatedPaperRankingMode.LEXICAL, fallbackReason, matches);
	}

	private record ScoredPaper(UUID paperId, double score) {
	}
}
