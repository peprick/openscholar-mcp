package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPaperUseCase;
import com.openscholar.paper.RelatedPapersView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PostgresRelatedPaperServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private RelatedPaperUseCase relatedPapers;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void ranksTitleMatchesAheadOfAbstractMatchesAndExcludesTheSourcePaper() {
		PaperView source = save(
				"Graph neural networks for molecular discovery",
				"Learning useful representations for chemistry.",
				"W-FTS-SOURCE");
		PaperView titleMatch = save(
				"Graph neural networks improve molecular discovery",
				"A benchmark study.",
				"W-FTS-TITLE");
		PaperView abstractMatch = save(
				"Representation learning for chemistry",
				"Molecular discovery with graph neural networks.",
				"W-FTS-ABSTRACT");
		save(
				"A historical survey of medieval manuscripts",
				"An unrelated humanities paper.",
				"W-FTS-NOISE");

		RelatedPapersView result = relatedPapers.findRelated(source.id(), 10);

		assertThat(result.sourcePaperId()).isEqualTo(source.id());
		assertThat(result.results()).extracting(match -> match.paper().id())
				.containsExactly(titleMatch.id(), abstractMatch.id());
		assertThat(result.results()).extracting(RelatedPaperMatch::rank)
				.containsExactly(1, 2);
		assertThat(result.results()).allSatisfy(match -> {
			assertThat(match.paper().id()).isNotEqualTo(source.id());
			assertThat(match.score()).isPositive();
			assertThat(match.rankingReasons())
					.containsExactly(RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON);
		});
		assertThat(result.results().getFirst().score())
				.isGreaterThan(result.results().getLast().score());
	}

	@Test
	void appliesTheRequestedLimitAfterDeterministicRanking() {
		PaperView source = save("Clinical reinforcement learning", null, "W-FTS-LIMIT-SOURCE");
		save("Clinical reinforcement learning trials", null, "W-FTS-LIMIT-1");
		save("Reinforcement learning systems", null, "W-FTS-LIMIT-2");

		RelatedPapersView result = relatedPapers.findRelated(source.id(), 1);

		assertThat(result.results()).singleElement()
				.satisfies(match -> assertThat(match.rank()).isEqualTo(1));
	}

	@Test
	void returnsAnEmptyResultForAStopwordOnlyTitleWithoutQuerySyntaxErrors() {
		PaperView source = save("the and or", null, "W-FTS-STOPWORDS-SOURCE");
		save("The study and its findings", null, "W-FTS-STOPWORDS-CANDIDATE");

		RelatedPapersView result = relatedPapers.findRelated(source.id(), 10);

		assertThat(result.results()).isEmpty();
	}

	@Test
	void safelyParsesPunctuationApostrophesHyphensAndLongSeedTitles() {
		PaperView source = save(
				"Children's graph-based AI: R&D methods alpha beta gamma delta epsilon zeta eta theta "
						+ "iota kappa lambda mu nu xi omicron pi rho sigma tau",
				null,
				"W-FTS-PUNCTUATION-SOURCE");
		PaperView candidate = save(
				"Graph based AI methods for children's research",
				null,
				"W-FTS-PUNCTUATION-CANDIDATE");
		PaperView overflowOnly = save(
				"Sigma tau applications",
				null,
				"W-FTS-PUNCTUATION-OVERFLOW");

		RelatedPapersView result = relatedPapers.findRelated(source.id(), 10);

		assertThat(result.results()).extracting(match -> match.paper().id())
				.contains(candidate.id())
				.doesNotContain(overflowOnly.id());
	}

	@Test
	void includesVenueOnlyMatchesAtTheLowestDocumentWeight() {
		PaperView source = save("Quantum sensing protocols", null, "W-FTS-VENUE-SOURCE");
		PaperView candidate = save("Proceedings editorial", null, "W-FTS-VENUE-CANDIDATE");
		jdbcTemplate.update(
				"update paper set venue_name = ? where id = ?",
				"Journal of Quantum Sensing Protocols",
				candidate.id());

		RelatedPapersView result = relatedPapers.findRelated(source.id(), 10);

		assertThat(result.results()).extracting(match -> match.paper().id())
				.contains(candidate.id());
	}

	@Test
	void generatedSearchVectorTracksMetadataUpdatesAndTheGinIndexExists() {
		PaperView source = save("Adaptive trial matching", null, "W-FTS-UPDATE-SOURCE");
		PaperView candidate = save("Medieval manuscript catalogues", null, "W-FTS-UPDATE-CANDIDATE");

		assertThat(relatedPapers.findRelated(source.id(), 10).results())
				.extracting(match -> match.paper().id())
				.doesNotContain(candidate.id());

		jdbcTemplate.update(
				"update paper set title = ?, normalized_title = ? where id = ?",
				"Adaptive trial matching in oncology",
				"adaptive trial matching in oncology",
				candidate.id());

		assertThat(relatedPapers.findRelated(source.id(), 10).results())
				.extracting(match -> match.paper().id())
				.contains(candidate.id());
		String indexDefinition = jdbcTemplate.queryForObject(
				"select indexdef from pg_indexes where schemaname = current_schema() and indexname = ?",
				String.class,
				"idx_paper_search_vector_fts");
		assertThat(indexDefinition).containsIgnoringCase("using gin");
	}

	@Test
	void producesTheSameOrderedIdsAndScoresAcrossRepeatedReads() {
		PaperView source = save("Explainable retrieval systems", null, "W-FTS-STABLE-SOURCE");
		save("Explainable retrieval for research systems", null, "W-FTS-STABLE-1");
		save("Reliable systems with explainable decisions", null, "W-FTS-STABLE-2");

		RelatedPapersView first = relatedPapers.findRelated(source.id(), 10);
		RelatedPapersView repeated = relatedPapers.findRelated(source.id(), 10);

		assertThat(repeated.results()).extracting(match -> match.paper().id())
				.containsExactlyElementsOf(first.results().stream()
						.map(match -> match.paper().id())
						.toList());
		assertThat(repeated.results()).extracting(RelatedPaperMatch::score)
				.containsExactlyElementsOf(first.results().stream()
						.map(RelatedPaperMatch::score)
						.toList());
	}

	@Test
	void rejectsAnUnknownSourceAndOutOfRangeLimits() {
		UUID missingPaperId = UUID.randomUUID();

		assertThatThrownBy(() -> relatedPapers.findRelated(missingPaperId, 10))
				.isInstanceOf(PaperNotFoundException.class)
				.hasMessage("Paper not found: " + missingPaperId);

		PaperView source = save("Bounded related search", null, "W-FTS-BOUNDS");
		assertThatThrownBy(() -> relatedPapers.findRelated(source.id(), 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 1 and 25");
		assertThatThrownBy(() -> relatedPapers.findRelated(source.id(), 26))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 1 and 25");
	}

	private PaperView save(String title, String abstractText, String providerRecordId) {
		CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(
				title,
				abstractText,
				LocalDate.of(2026, 8, 1),
				2026,
				DocumentType.ARTICLE,
				"en",
				"Journal of Machine Learning Research",
				0,
				NOW,
				List.of(new PaperIdentifier(
						PaperIdentifierType.OPENALEX, "", providerRecordId)),
				List.of());
		ProviderRecordCandidate providerRecord = new ProviderRecordCandidate(
				"OpenAlex",
				providerRecordId,
				NOW,
				NOW,
				URI.create("https://api.openalex.org/works/" + providerRecordId),
				true,
				URI.create("https://openalex.org/" + providerRecordId),
				null,
				Map.of());
		return paperCatalog.upsert(candidate, providerRecord, NOW);
	}
}
