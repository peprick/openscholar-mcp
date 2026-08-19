package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
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
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.StalePaperEmbeddingException;
import com.openscholar.paper.StoreEmbeddingOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PostgresPaperEmbeddingStoreTests {

	private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
	private static final String PROFILE_KEY = "fixture-title-abstract-v1";

	@Autowired
	private PaperEmbeddingStore embeddingStore;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void registersTheSameProfileIdempotentlyAndRejectsProfileKeyRedefinition() {
		EmbeddingProfile profile = profile(PROFILE_KEY, "fixture-model", "revision-1", 3);

		assertThat(embeddingStore.registerProfile(profile)).isEqualTo(profile);
		assertThat(embeddingStore.registerProfile(profile)).isEqualTo(profile);
		assertThat(profileCount(PROFILE_KEY)).isEqualTo(1);

		EmbeddingProfile redefinition = profile(
				PROFILE_KEY, "different-model", "revision-2", 3);
		assertThatThrownBy(() -> embeddingStore.registerProfile(redefinition))
				.isInstanceOf(EmbeddingProfileConflictException.class)
				.hasMessageContaining(PROFILE_KEY);
		assertThat(profileCount(PROFILE_KEY)).isEqualTo(1);
	}

	@Test
	void rejectsRegisteringTheSameImmutableDefinitionUnderAnotherKey() {
		EmbeddingProfile profile = profile(PROFILE_KEY, "fixture-model", "revision-1", 3);
		embeddingStore.registerProfile(profile);

		EmbeddingProfile alias = profile(
				"fixture-title-abstract-v1-alias", "fixture-model", "revision-1", 3);

		assertThatThrownBy(() -> embeddingStore.registerProfile(alias))
				.isInstanceOf(EmbeddingProfileConflictException.class)
				.hasMessageContaining("already registered under another key");
	}

	@Test
	void sameChecksumIsANoopThatPreservesTheStoredVectorAndTimestamp() {
		UUID paperId = uuid(100);
		insertPaper(paperId, "Stable embedding input", "A stable abstract");
		embeddingStore.registerProfile(profile(PROFILE_KEY, "fixture-model", "revision-1", 3));
		PaperEmbeddingSource source = embeddingStore.prepareSource(paperId, PROFILE_KEY);

		assertThat(embeddingStore.saveIfSourceCurrent(candidate(
				paperId, PROFILE_KEY, source, List.of(1.0f, 0.0f, 0.0f), NOW)))
				.isEqualTo(StoreEmbeddingOutcome.STORED);
		assertThat(embeddingStore.saveIfSourceCurrent(candidate(
				paperId,
				PROFILE_KEY,
				source,
				List.of(0.0f, 1.0f, 0.0f),
				NOW.plusSeconds(60))))
				.isEqualTo(StoreEmbeddingOutcome.UNCHANGED);

		assertThat(embeddingCount(paperId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"select embedding::text from paper_embedding where paper_id = ? and profile_key = ?",
				String.class,
				paperId,
				PROFILE_KEY)).isEqualTo("[1,0,0]");
		assertThat(jdbcTemplate.queryForObject(
				"select embedded_at from paper_embedding where paper_id = ? and profile_key = ?",
				Instant.class,
				paperId,
				PROFILE_KEY)).isEqualTo(NOW);
	}

	@Test
	void rejectsAnEmbeddingGeneratedFromContentThatHasSinceChanged() {
		UUID paperId = uuid(110);
		insertPaper(paperId, "Initial title", "Initial abstract");
		embeddingStore.registerProfile(profile(PROFILE_KEY, "fixture-model", "revision-1", 3));
		PaperEmbeddingSource staleSource = embeddingStore.prepareSource(paperId, PROFILE_KEY);

		jdbcTemplate.update(
				"update paper set abstract_text = ?, updated_at = ? where id = ?",
				"Replacement abstract",
				Timestamp.from(NOW.plusSeconds(1)),
				paperId);

		assertThatThrownBy(() -> embeddingStore.saveIfSourceCurrent(candidate(
				paperId, PROFILE_KEY, staleSource, List.of(1.0f, 0.0f, 0.0f), NOW)))
				.isInstanceOf(StalePaperEmbeddingException.class)
				.hasMessageContaining(paperId.toString())
				.hasMessageContaining(PROFILE_KEY);
		assertThat(embeddingCount(paperId)).isZero();
	}

	@Test
	void titleAndAbstractChangesInvalidateEmbeddingsButVenueAndCitationsDoNot() {
		UUID paperId = uuid(120);
		insertPaper(paperId, "Policy title", "Policy abstract");
		embeddingStore.registerProfile(profile(PROFILE_KEY, "fixture-model", "revision-1", 3));
		PaperEmbeddingSource originalSource = embeddingStore.prepareSource(paperId, PROFILE_KEY);
		store(paperId, PROFILE_KEY, originalSource, List.of(1.0f, 0.0f, 0.0f), NOW);

		jdbcTemplate.update(
				"update paper set venue_name = ?, citation_count = ?, updated_at = ? where id = ?",
				"A changed venue",
				42,
				Timestamp.from(NOW.plusSeconds(1)),
				paperId);
		assertThat(embeddingCount(paperId)).isEqualTo(1);
		assertThat(embeddingStore.prepareSource(paperId, PROFILE_KEY).contentChecksum())
				.isEqualTo(originalSource.contentChecksum());

		jdbcTemplate.update(
				"update paper set abstract_text = ?, updated_at = ? where id = ?",
				"A changed abstract",
				Timestamp.from(NOW.plusSeconds(2)),
				paperId);
		assertThat(embeddingCount(paperId)).isZero();

		PaperEmbeddingSource abstractSource = embeddingStore.prepareSource(paperId, PROFILE_KEY);
		store(paperId, PROFILE_KEY, abstractSource, List.of(1.0f, 0.0f, 0.0f), NOW.plusSeconds(2));
		jdbcTemplate.update(
				"update paper set title = ?, normalized_title = ?, updated_at = ? where id = ?",
				"A changed title",
				"a changed title",
				Timestamp.from(NOW.plusSeconds(3)),
				paperId);
		assertThat(embeddingCount(paperId)).isZero();
	}

	@Test
	void validatesDimensionFiniteComponentsAndNonZeroNormBeforeWriting() {
		UUID paperId = uuid(130);
		insertPaper(paperId, "Vector validation", "Vector validation abstract");
		embeddingStore.registerProfile(profile(PROFILE_KEY, "fixture-model", "revision-1", 3));
		PaperEmbeddingSource source = embeddingStore.prepareSource(paperId, PROFILE_KEY);

		assertThatThrownBy(() -> embeddingStore.saveIfSourceCurrent(candidate(
				paperId, PROFILE_KEY, source, List.of(1.0f, 0.0f), NOW)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly 3 dimensions");
		assertThatThrownBy(() -> embeddingStore.saveIfSourceCurrent(candidate(
				paperId, PROFILE_KEY, source, List.of(Float.NaN, 0.0f, 1.0f), NOW)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("components must be finite");
		assertThatThrownBy(() -> embeddingStore.saveIfSourceCurrent(candidate(
				paperId, PROFILE_KEY, source, List.of(Float.POSITIVE_INFINITY, 0.0f, 1.0f), NOW)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("components must be finite");
		assertThatThrownBy(() -> embeddingStore.saveIfSourceCurrent(candidate(
				paperId, PROFILE_KEY, source, List.of(0.0f, -0.0f, 0.0f), NOW)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must not be the zero vector");
		assertThat(embeddingCount(paperId)).isZero();
	}

	@Test
	void keepsDifferentProfilesAndDimensionsInSeparateVectorSpaces() {
		String profileA = "fixture-space-a-v1";
		String profileB = "fixture-space-b-v1";
		UUID sourceId = uuid(140);
		UUID candidateA = uuid(141);
		UUID candidateB = uuid(142);
		insertPaper(sourceId, "Isolation source", "Shared source");
		insertPaper(candidateA, "Space A candidate", "Candidate in space A");
		insertPaper(candidateB, "Space B candidate", "Candidate in space B");
		embeddingStore.registerProfile(profile(profileA, "matryoshka-model", "revision-a", 3));
		embeddingStore.registerProfile(profile(profileB, "matryoshka-model", "revision-a", 2));

		storeCurrent(sourceId, profileA, List.of(1.0f, 0.0f, 0.0f), NOW);
		storeCurrent(candidateA, profileA, List.of(0.9f, 0.1f, 0.0f), NOW);
		storeCurrent(sourceId, profileB, List.of(1.0f, 0.0f), NOW);
		storeCurrent(candidateB, profileB, List.of(0.8f, 0.2f), NOW);

		assertThat(embeddingStore.findNearest(sourceId, profileA, 10))
				.extracting(PaperEmbeddingMatch::paperId)
				.containsExactly(candidateA);
		assertThat(embeddingStore.findNearest(sourceId, profileB, 10))
				.extracting(PaperEmbeddingMatch::paperId)
				.containsExactly(candidateB);
	}

	@Test
	void ordersExactCosineMatchesAndBreaksEqualDistancesByUuidBeforeApplyingLimit() {
		UUID sourceId = uuid(200);
		UUID nearId = uuid(210);
		UUID lowerTieId = uuid(1);
		UUID higherTieId = uuid(2);
		UUID orthogonalId = uuid(220);
		UUID oppositeId = uuid(230);
		List.of(sourceId, nearId, lowerTieId, higherTieId, orthogonalId, oppositeId)
				.forEach(id -> insertPaper(id, "Paper " + id, "A deterministic vector fixture"));
		embeddingStore.registerProfile(profile(PROFILE_KEY, "fixture-model", "revision-1", 3));

		storeCurrent(sourceId, PROFILE_KEY, List.of(1.0f, 0.0f, 0.0f), NOW);
		storeCurrent(nearId, PROFILE_KEY, List.of(0.9f, 0.1f, 0.0f), NOW);
		storeCurrent(lowerTieId, PROFILE_KEY, List.of(0.6f, 0.8f, 0.0f), NOW);
		storeCurrent(higherTieId, PROFILE_KEY, List.of(0.6f, 0.8f, 0.0f), NOW);
		storeCurrent(orthogonalId, PROFILE_KEY, List.of(0.0f, 1.0f, 0.0f), NOW);
		storeCurrent(oppositeId, PROFILE_KEY, List.of(-1.0f, 0.0f, 0.0f), NOW);

		List<PaperEmbeddingMatch> first = embeddingStore.findNearest(sourceId, PROFILE_KEY, 4);
		List<PaperEmbeddingMatch> repeated = embeddingStore.findNearest(sourceId, PROFILE_KEY, 4);

		assertThat(first).extracting(PaperEmbeddingMatch::paperId)
				.containsExactly(nearId, lowerTieId, higherTieId, orthogonalId)
				.doesNotContain(sourceId, oppositeId);
		assertThat(first).extracting(PaperEmbeddingMatch::rank)
				.containsExactly(1, 2, 3, 4);
		assertThat(first.get(0).cosineSimilarity()).isCloseTo(0.9938837d, within(0.000001d));
		assertThat(first.get(1).cosineSimilarity()).isCloseTo(0.6d, within(0.000001d));
		assertThat(first.get(2).cosineSimilarity()).isCloseTo(0.6d, within(0.000001d));
		assertThat(first.get(3).cosineSimilarity()).isCloseTo(0.0d, within(0.000001d));
		assertThat(repeated).isEqualTo(first);
		assertThat(embeddingStore.findNearest(sourceId, PROFILE_KEY, 2))
				.extracting(PaperEmbeddingMatch::paperId)
				.containsExactly(nearId, lowerTieId);
	}

	@Test
	void reportsMissingPapersAndEmbeddingsAndRejectsOutOfRangeLimits() {
		UUID missingPaperId = uuid(300);
		UUID unembeddedPaperId = uuid(301);
		embeddingStore.registerProfile(profile(PROFILE_KEY, "fixture-model", "revision-1", 3));
		insertPaper(unembeddedPaperId, "No embedding", null);

		assertThatThrownBy(() -> embeddingStore.prepareSource(missingPaperId, PROFILE_KEY))
				.isInstanceOf(PaperNotFoundException.class)
				.hasMessageContaining(missingPaperId.toString());
		assertThatThrownBy(() -> embeddingStore.prepareSource(
				unembeddedPaperId, "unknown-profile-v1"))
				.isInstanceOf(EmbeddingProfileNotFoundException.class)
				.hasMessageContaining("unknown-profile-v1");
		assertThatThrownBy(() -> embeddingStore.findNearest(unembeddedPaperId, PROFILE_KEY, 10))
				.isInstanceOf(PaperEmbeddingNotFoundException.class)
				.hasMessageContaining(unembeddedPaperId.toString())
				.hasMessageContaining(PROFILE_KEY);
		assertThatThrownBy(() -> embeddingStore.findNearest(unembeddedPaperId, PROFILE_KEY, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 1 and 100");
		assertThatThrownBy(() -> embeddingStore.findNearest(unembeddedPaperId, PROFILE_KEY, 101))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 1 and 100");
	}

	private static org.assertj.core.data.Offset<Double> within(double value) {
		return org.assertj.core.data.Offset.offset(value);
	}

	private EmbeddingProfile profile(
			String profileKey, String model, String revision, int dimensions) {
		return new EmbeddingProfile(
				profileKey,
				"TEST",
				model,
				revision,
				EmbeddingContentKind.TITLE_ABSTRACT,
				1,
				dimensions,
				EmbeddingDistanceMetric.COSINE);
	}

	private PaperEmbeddingCandidate candidate(
			UUID paperId,
			String profileKey,
			PaperEmbeddingSource source,
			List<Float> vector,
			Instant generatedAt) {
		return new PaperEmbeddingCandidate(
				paperId,
				profileKey,
				source.contentChecksum(),
				vector,
				generatedAt);
	}

	private void storeCurrent(
			UUID paperId, String profileKey, List<Float> vector, Instant generatedAt) {
		PaperEmbeddingSource source = embeddingStore.prepareSource(paperId, profileKey);
		store(paperId, profileKey, source, vector, generatedAt);
	}

	private void store(
			UUID paperId,
			String profileKey,
			PaperEmbeddingSource source,
			List<Float> vector,
			Instant generatedAt) {
		assertThat(embeddingStore.saveIfSourceCurrent(candidate(
				paperId, profileKey, source, vector, generatedAt)))
				.isEqualTo(StoreEmbeddingOutcome.STORED);
	}

	private void insertPaper(UUID paperId, String title, String abstractText) {
		jdbcTemplate.update("""
				insert into paper
				    (id, title, normalized_title, abstract_text, document_type,
				     metadata_quality, metadata_updated_at, version, created_at, updated_at)
				values (?, ?, lower(?), ?, 'ARTICLE', 0, ?, 0, ?, ?)
				""",
				paperId,
				title,
				title,
				abstractText,
				Timestamp.from(NOW),
				Timestamp.from(NOW),
				Timestamp.from(NOW));
	}

	private int embeddingCount(UUID paperId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from paper_embedding where paper_id = ?",
				Integer.class,
				paperId);
	}

	private int profileCount(String profileKey) {
		return jdbcTemplate.queryForObject(
				"select count(*) from embedding_profile where profile_key = ?",
				Integer.class,
				profileKey);
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}
}
