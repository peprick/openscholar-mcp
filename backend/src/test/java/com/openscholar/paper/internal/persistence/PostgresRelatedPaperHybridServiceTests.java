package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingProfile;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperEmbeddingCandidate;
import com.openscholar.paper.PaperEmbeddingMatch;
import com.openscholar.paper.PaperEmbeddingSource;
import com.openscholar.paper.PaperEmbeddingStore;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.paper.RelatedPaperFallbackReason;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPaperRankingFeature;
import com.openscholar.paper.RelatedPaperRankingMode;
import com.openscholar.paper.RelatedPaperUseCase;
import com.openscholar.paper.RelatedPapersView;
import com.openscholar.paper.StoreEmbeddingOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
	"openscholar.related-papers.hybrid.enabled=true",
	"openscholar.related-papers.hybrid.candidate-pool-size=25"
})
@Transactional
class PostgresRelatedPaperHybridServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private PaperEmbeddingStore embeddingStore;

	@Autowired
	private RelatedPaperUseCase relatedPapers;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void ranksTheBoundedUnionWithTheFrozenScorerAndTypedFeatureValues() {
		PaperView source = save(
				"Graph neural networks for molecular discovery",
				"Representation learning for chemistry.",
				"W-HYBRID-SOURCE");
		PaperView lexical = save(
				"Graph neural networks improve molecular discovery",
				"A lexical control.",
				"W-HYBRID-LEXICAL");
		PaperView semantic = save(
				"Reef restoration soundscapes",
				"A semantically selected candidate.",
				"W-HYBRID-SEMANTIC");
		registerPinnedProfile();
		store(source, vector(1.0f, 0.0f));
		store(lexical, vector(-1.0f, 0.0f));
		store(semantic, vector(1.0f, 0.0f));

		RelatedPapersView first = relatedPapers.findRelated(source.id(), 2);
		RelatedPapersView repeated = relatedPapers.findRelated(source.id(), 2);

		assertThat(first.rankingMode()).isEqualTo(RelatedPaperRankingMode.HYBRID);
		assertThat(first.fallbackReason()).isNull();
		assertThat(first.results()).extracting(match -> match.paper().id())
			.containsExactly(semantic.id(), lexical.id());
		assertThat(repeated).isEqualTo(first);
		assertThat(first.results()).allSatisfy(match -> {
			assertThat(match.rankingReasons()).extracting(reason -> reason.feature())
					.containsExactly(
							RelatedPaperRankingFeature.POSTGRES_FULL_TEXT,
							RelatedPaperRankingFeature.CLAMPED_COSINE);
			double lexicalValue = match.rankingReasons().get(0).value();
			double semanticValue = match.rankingReasons().get(1).value();
			assertThat(match.score())
					.isEqualTo(0.5d * lexicalValue + 0.5d * semanticValue);
		});
		assertThat(first.results().getFirst().rankingReasons().get(0).value()).isZero();
		assertThat(first.results().getFirst().rankingReasons().get(1).value()).isEqualTo(1.0d);
	}

	@Test
	void sourceWithoutAPinnedVectorFallsBackToTheExactLexicalOrder() {
		PaperView source = save(
				"Clinical reinforcement learning",
				null,
				"W-HYBRID-MISSING-SOURCE");
		PaperView candidate = save(
				"Clinical reinforcement learning trials",
				null,
				"W-HYBRID-MISSING-SOURCE-CANDIDATE");
		registerPinnedProfile();
		store(candidate, vector(1.0f, 0.0f));

		RelatedPapersView result = relatedPapers.findRelated(source.id(), 10);

		assertLexicalFallback(
				result, RelatedPaperFallbackReason.SOURCE_VECTOR_MISSING, candidate.id());
	}

	@Test
	void incompleteCandidateVectorCoveragePreservesTheExactLexicalResults() {
		PaperView source = save(
				"Reliable explainable retrieval systems",
				null,
				"W-HYBRID-COVERAGE-SOURCE");
		PaperView lexicalCandidate = save(
				"Reliable explainable retrieval systems for research",
				null,
				"W-HYBRID-COVERAGE-LEXICAL");
		PaperView semanticCandidate = save(
				"Unrelated marine acoustics",
				null,
				"W-HYBRID-COVERAGE-SEMANTIC");
		registerPinnedProfile();
		store(source, vector(1.0f, 0.0f));
		store(semanticCandidate, vector(1.0f, 0.0f));
		RelatedPapersView lexicalControl = new PostgresRelatedPaperService(
				jdbcTemplate,
				paperCatalog,
				embeddingStore,
				new RelatedPaperHybridProperties(false, 25))
			.findRelated(source.id(), 10);

		RelatedPapersView fallback = relatedPapers.findRelated(source.id(), 10);

		assertLexicalFallback(
				fallback,
				RelatedPaperFallbackReason.CANDIDATE_VECTOR_COVERAGE_INCOMPLETE,
				lexicalCandidate.id());
		assertThat(fallback.results()).extracting(match -> match.paper().id())
			.containsExactlyElementsOf(lexicalControl.results().stream()
					.map(match -> match.paper().id())
					.toList());
		assertThat(fallback.results()).extracting(RelatedPaperMatch::score)
			.containsExactlyElementsOf(lexicalControl.results().stream()
					.map(RelatedPaperMatch::score)
					.toList());
	}

	@Test
	void missingPinnedProfileFallsBackExplicitly() {
		PaperView source = save(
				"Bounded database retrieval",
				null,
				"W-HYBRID-PROFILE-SOURCE");
		PaperView candidate = save(
				"Bounded database retrieval systems",
				null,
				"W-HYBRID-PROFILE-CANDIDATE");

		RelatedPapersView result = relatedPapers.findRelated(source.id(), 10);

		assertLexicalFallback(
				result, RelatedPaperFallbackReason.EMBEDDING_PROFILE_MISSING, candidate.id());
	}

	@Test
	void operationalEmbeddingFailuresAreNotConvertedIntoLexicalFallbacks() {
		PaperView source = save(
				"Operational error propagation",
				null,
				"W-HYBRID-FAILURE-SOURCE");
		DataAccessResourceFailureException failure =
				new DataAccessResourceFailureException("database unavailable");
		PaperEmbeddingStore failingStore = new PostgresPaperEmbeddingStore(jdbcTemplate) {
			@Override
			public List<PaperEmbeddingMatch> findNearestApproximate(
					java.util.UUID sourcePaperId, String profileKey, int limit) {
				throw failure;
			}
		};
		PostgresRelatedPaperService service = new PostgresRelatedPaperService(
				jdbcTemplate,
				paperCatalog,
				failingStore,
				new RelatedPaperHybridProperties(true, 25));

		assertThatThrownBy(() -> service.findRelated(source.id(), 10)).isSameAs(failure);
	}

	private void assertLexicalFallback(
			RelatedPapersView view,
			RelatedPaperFallbackReason fallbackReason,
			java.util.UUID expectedPaperId) {
		assertThat(view.rankingMode()).isEqualTo(RelatedPaperRankingMode.LEXICAL);
		assertThat(view.fallbackReason()).isEqualTo(fallbackReason);
		assertThat(view.results()).extracting(match -> match.paper().id())
			.containsExactly(expectedPaperId);
		assertThat(view.results()).allSatisfy(match -> {
			assertThat(match.rankingReasons()).singleElement().satisfies(reason -> {
				assertThat(reason.feature())
						.isEqualTo(RelatedPaperRankingFeature.POSTGRES_FULL_TEXT);
				assertThat(reason.value()).isEqualTo(match.score());
			});
		});
	}

	private void registerPinnedProfile() {
		embeddingStore.registerProfile(new EmbeddingProfile(
				PaperEmbeddingAnnPolicy.PROFILE_KEY,
				PaperEmbeddingAnnPolicy.PROVIDER,
				PaperEmbeddingAnnPolicy.MODEL,
				PaperEmbeddingAnnPolicy.MODEL_REVISION,
				PaperEmbeddingAnnPolicy.CONTENT_KIND,
				PaperEmbeddingAnnPolicy.INPUT_POLICY_VERSION,
				PaperEmbeddingAnnPolicy.DIMENSIONS,
				EmbeddingDistanceMetric.COSINE));
	}

	private void store(PaperView paper, List<Float> vector) {
		PaperEmbeddingSource source = embeddingStore.prepareSource(
				paper.id(), PaperEmbeddingAnnPolicy.PROFILE_KEY);
		assertThat(embeddingStore.saveIfSourceCurrent(new PaperEmbeddingCandidate(
				paper.id(),
				PaperEmbeddingAnnPolicy.PROFILE_KEY,
				source.contentChecksum(),
				vector,
				NOW))).isEqualTo(StoreEmbeddingOutcome.STORED);
	}

	private List<Float> vector(float first, float second) {
		List<Float> vector = new ArrayList<>(PaperEmbeddingAnnPolicy.DIMENSIONS);
		vector.add(first);
		vector.add(second);
		while (vector.size() < PaperEmbeddingAnnPolicy.DIMENSIONS) {
			vector.add(0.0f);
		}
		return List.copyOf(vector);
	}

	private PaperView save(String title, String abstractText, String providerRecordId) {
		CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(
				title,
				abstractText,
				LocalDate.of(2026, 8, 1),
				2026,
				com.openscholar.paper.DocumentType.ARTICLE,
				"en",
				"Journal of Machine Learning Research",
				0,
				NOW,
				List.of(new com.openscholar.paper.PaperIdentifier(
						com.openscholar.paper.PaperIdentifierType.OPENALEX,
						"",
						providerRecordId)),
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
