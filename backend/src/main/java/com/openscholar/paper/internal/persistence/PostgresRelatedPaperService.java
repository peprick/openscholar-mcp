package com.openscholar.paper.internal.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPaperUseCase;
import com.openscholar.paper.RelatedPapersView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PostgresRelatedPaperService implements RelatedPaperUseCase {

	private static final int MAX_RESULTS = 25;
	private static final int MAX_SEED_LEXEMES = 16;

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

	private final JdbcTemplate jdbcTemplate;
	private final PaperCatalog paperCatalog;

	PostgresRelatedPaperService(JdbcTemplate jdbcTemplate, PaperCatalog paperCatalog) {
		this.jdbcTemplate = jdbcTemplate;
		this.paperCatalog = paperCatalog;
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

		List<ScoredPaper> scoredPapers = jdbcTemplate.query(
				RELATED_PAPERS_SQL,
				(resultSet, rowNumber) -> new ScoredPaper(
						resultSet.getObject("paper_id", UUID.class),
						resultSet.getDouble("score")),
				paperId,
				limit);
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
					List.of(RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON)));
		}
		return new RelatedPapersView(paperId, matches);
	}

	private record ScoredPaper(UUID paperId, double score) {
	}
}
