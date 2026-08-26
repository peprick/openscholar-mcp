package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ComparativeCapture;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ProviderCallEvidence;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.QueryCapture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class EuropePmcComparativeLiveEvaluationContractTests {

	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-26T13:00:00Z");
	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@Test
	void blindedArtifactOmitsProvenanceRankingIdentityAndDocumentHints() throws Exception {
		ProviderQualityRawCandidate candidate = ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1",
				"query-key",
				1,
				rawRecord());
		ComparativeCapture capture = capture(true, candidate);

		Map<String, Object> artifacts = EuropePmcComparativeLiveEvaluationTests.artifacts(
				"europe-pmc-comparative-contract",
				RETRIEVED_AT,
				"a".repeat(40),
				"b".repeat(64),
				capture);

		String blinded = OBJECT_MAPPER.writeValueAsString(artifacts.get("blinded-candidates.json"));
		assertThat(blinded)
				.contains("Visible title", "Visible abstract", "Visible Author")
				.doesNotContain(
						"OPENALEX",
						"W-PRIVATE-RECORD",
						"10.5555/private.identity",
						"0000-0000-0000-0001",
						"providerRank",
						"providerRecordId",
						"citationCount",
						"reportedOpenAccess",
						"identifiers",
						"sourceUrl",
						"providerUpdatedAt");

		String allArtifacts = OBJECT_MAPPER.writeValueAsString(artifacts);
		assertThat(allArtifacts)
				.doesNotContain(
						"pdf-secret-token",
						"landing-secret-token",
						"raw-private-secret",
						"provider-author-private-id",
						"pdfUrl",
						"landingPageUrl",
						"metadataFragment")
				.contains("W-PRIVATE-RECORD", "10.5555/private.identity");
	}

	@Test
	void incompleteCaptureCannotInstructAReviewerToLabelCandidates() throws Exception {
		Map<String, Object> artifacts = EuropePmcComparativeLiveEvaluationTests.artifacts(
				"europe-pmc-comparative-incomplete",
				RETRIEVED_AT,
				"a".repeat(40),
				"b".repeat(64),
				capture(false, ProviderQualityRawCandidate.from(
						"europe-pmc-live-queries-v1", "query-key", 1, rawRecord())));

		String blinded = OBJECT_MAPPER.writeValueAsString(artifacts.get("blinded-candidates.json"));
		assertThat(blinded)
				.contains("Do not label this incomplete capture")
				.doesNotContain("Assign one integer relevanceGrade");
	}

	@Test
	void blindedCandidatesUseEvidenceScopedPermutationRatherThanProviderOrSourceRankOrder()
			throws Exception {
		ProviderQualityRawCandidate openAlex = ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "query-key", 1,
				rawRecord(ProviderId.OPENALEX, "W-PRIVATE-RECORD"));
		ProviderQualityRawCandidate europePmc = ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "query-key", 2,
				rawRecord(ProviderId.EUROPE_PMC, "MED:PRIVATE-RECORD"));
		String evidenceId = IntStream.range(0, 10_000)
				.mapToObj(index -> "europe-pmc-comparative-blinding-" + index)
				.filter(candidateEvidenceId -> EuropePmcComparativeLiveEvaluationTests
						.blindedOrderingKey(candidateEvidenceId, openAlex.reviewKey())
						.compareTo(EuropePmcComparativeLiveEvaluationTests
								.blindedOrderingKey(candidateEvidenceId, europePmc.reviewKey())) > 0)
				.findFirst()
				.orElseThrow();
		Map<String, Object> artifacts = EuropePmcComparativeLiveEvaluationTests.artifacts(
				evidenceId,
				RETRIEVED_AT,
				"a".repeat(40),
				"b".repeat(64),
				capture(true, openAlex, europePmc));

		@SuppressWarnings("unchecked")
		Map<String, Object> blinded = (Map<String, Object>) artifacts.get("blinded-candidates.json");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> candidates =
				(List<Map<String, Object>>) blinded.get("candidates");
		List<String> actualKeys = candidates.stream()
				.map(candidate -> String.valueOf(candidate.get("reviewKey")))
				.toList();
		assertThat(actualKeys).containsExactly(europePmc.reviewKey(), openAlex.reviewKey());
	}

	@Test
	void evidenceRevisionMustMatchACleanCheckout() {
		String revision = "a".repeat(40);
		assertThat(EuropePmcComparativeLiveEvaluationTests.verifyRepositoryState(
				revision, revision, ""))
				.isEqualTo(revision);
		assertThatThrownBy(() -> EuropePmcComparativeLiveEvaluationTests.verifyRepositoryState(
				revision, "b".repeat(40), ""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must match");
		assertThatThrownBy(() -> EuropePmcComparativeLiveEvaluationTests.verifyRepositoryState(
				revision, revision, " M backend/pom.xml\n"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("clean Git worktree");
	}

	private static ComparativeCapture capture(
			boolean eligible, ProviderQualityRawCandidate... candidates) {
		List<ProviderCallEvidence> calls = List.of(
				new ProviderCallEvidence(
						ProviderId.OPENALEX, "SUCCESS", 12L, 1, 1L,
						RETRIEVED_AT, null, false),
				new ProviderCallEvidence(
						ProviderId.EUROPE_PMC,
						eligible ? "SUCCESS" : "FAILED",
						14L,
						eligible ? 1 : 0,
						eligible ? 1L : 0L,
						eligible ? RETRIEVED_AT : null,
						eligible ? null : "EUROPE_PMC_TIMEOUT",
						!eligible));
		QueryCapture query = new QueryCapture(
				"query-key",
				"bounded query text",
				eligible,
				calls,
				List.of(candidates),
				Map.of());
		return new ComparativeCapture(
				1,
				"europe-pmc-live-queries-v1",
				"AUTHOR_WRITTEN_TOPICS_WITHOUT_RELEVANCE_LABELS",
				20,
				eligible,
				List.of(query));
	}

	private static ProviderPaperRecord rawRecord() {
		return rawRecord(ProviderId.OPENALEX, "W-PRIVATE-RECORD");
	}

	private static ProviderPaperRecord rawRecord(
			ProviderId provider, String providerRecordId) {
		return new ProviderPaperRecord(
				provider,
				providerRecordId,
				"10.5555/private.identity",
				null,
				"Visible title",
				"Visible abstract",
				LocalDate.of(2026, 8, 1),
				2026,
				DocumentType.ARTICLE,
				"en",
				"Visible Journal",
				42,
				List.of(new ProviderAuthor(
						"provider-author-private-id",
						"Visible Author",
						"0000-0000-0000-0001",
						1,
						true)),
				true,
				URI.create("https://landing.test/work?landing-secret-token"),
				URI.create("https://documents.test/paper.pdf?pdf-secret-token"),
				0.99,
				RETRIEVED_AT,
				Map.of("raw-private-secret", "must-not-survive"),
				List.of(new PaperIdentifier(
						PaperIdentifierType.DOI, "", "10.5555/private.identity")),
				URI.create("https://source.test/work/W-PRIVATE-RECORD"));
	}
}
