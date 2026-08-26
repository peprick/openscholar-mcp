package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProviderQualityRawCandidateTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@Test
	void createsStableOpaqueKeysWithDistinctCandidateIdentities() {
		String first = ProviderQualityRawReviewKey.create(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis",
				ProviderId.OPENALEX, "W123");
		String replay = ProviderQualityRawReviewKey.create(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis",
				ProviderId.OPENALEX, "W123");
		Set<String> distinct = Set.of(
				first,
				ProviderQualityRawReviewKey.create(
						"europe-pmc-live-queries-v1", "cancer-ml-diagnosis",
						ProviderId.EUROPE_PMC, "W123"),
				ProviderQualityRawReviewKey.create(
						"europe-pmc-live-queries-v1", "crispr-off-target",
						ProviderId.OPENALEX, "W123"),
				ProviderQualityRawReviewKey.create(
						"europe-pmc-live-queries-v1", "cancer-ml-diagnosis",
						ProviderId.OPENALEX, "W124"));

		assertThat(first).isEqualTo(replay).matches("^[0-9a-f]{64}$");
		assertThat(first).doesNotContain("OPENALEX", "W123", "cancer-ml-diagnosis");
		assertThat(distinct).hasSize(4);
	}

	@Test
	void projectsOnlyClosedBoundedMetadataAndRedactsRawTransportFields() throws Exception {
		ProviderQualityRawCandidate candidate = ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1",
				"cancer-ml-diagnosis",
				1,
				record(DocumentType.ARTICLE, "Synthetic abstract"));

		String json = OBJECT_MAPPER.writeValueAsString(candidate);

		assertThat(candidate.schemaVersion()).isEqualTo(1);
		assertThat(candidate.provider()).isEqualTo(ProviderId.OPENALEX);
		assertThat(candidate.providerRank()).isEqualTo(1);
		assertThat(candidate.authors()).singleElement().satisfies(author -> {
			assertThat(author.displayName()).isEqualTo("Synthetic Author");
			assertThat(author.orcid()).isEqualTo("https://orcid.test/author");
		});
		assertThat(candidate.identifiers())
				.extracting(ProviderQualityRawCandidate.Identifier::type)
				.contains(PaperIdentifierType.DOI, PaperIdentifierType.OPENALEX);
		assertThat(candidate.sourceUrl()).isEqualTo("https://source.test/work/W123");
		assertThat(json)
				.doesNotContain(
						"pdfUrl", "landingPageUrl", "metadataFragment", "relevanceScore",
						"providerAuthorId", "secret-pdf", "secret-landing", "secret-config",
						"secret-cause", "secret-author-id")
				.contains("Synthetic abstract", "10.5555/raw.candidate");
	}

	@Test
	void rejectsCandidatesOutsideTheArticleRankAndMetadataBounds() {
		ProviderPaperRecord article = record(DocumentType.ARTICLE, "Synthetic abstract");
		assertThatThrownBy(() -> ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis", 21, article))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("providerRank");

		ProviderPaperRecord thesis = record(DocumentType.THESIS, "Synthetic abstract");
		assertThatThrownBy(() -> ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis", 1, thesis))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ARTICLE metadata");

		ProviderPaperRecord oversizedAbstract = record(
				DocumentType.ARTICLE, "x".repeat(200_001));
		assertThatThrownBy(() -> ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis", 1, oversizedAbstract))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("abstractText");

		ProviderPaperRecord signedSourceUrl = record(
				DocumentType.ARTICLE,
				"Synthetic abstract",
				URI.create("https://source.test/work/W123?token=secret#fragment"));
		assertThatThrownBy(() -> ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis", 1, signedSourceUrl))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("without credentials, query, or fragment");

		ProviderPaperRecord opaqueSourceUrl = record(
				DocumentType.ARTICLE,
				"Synthetic abstract",
				URI.create("https:opaque-source"));
		assertThatThrownBy(() -> ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis", 1, opaqueSourceUrl))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("with a host");

		ProviderPaperRecord paddedIdentity = record(
				DocumentType.ARTICLE,
				"Synthetic abstract",
				URI.create("https://source.test/work/W123"),
				" W123 ");
		assertThatThrownBy(() -> ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis", 1, paddedIdentity))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("providerRecordId");

		ProviderPaperRecord controlIdentity = record(
				DocumentType.ARTICLE,
				"Synthetic abstract",
				URI.create("https://source.test/work/W123"),
				"W123\nforged");
		assertThatThrownBy(() -> ProviderQualityRawCandidate.from(
				"europe-pmc-live-queries-v1", "cancer-ml-diagnosis", 1, controlIdentity))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("control characters");
	}

	private static ProviderPaperRecord record(DocumentType documentType, String abstractText) {
		return record(
				documentType, abstractText, URI.create("https://source.test/work/W123"));
	}

	private static ProviderPaperRecord record(
			DocumentType documentType, String abstractText, URI sourceUrl) {
		return record(documentType, abstractText, sourceUrl, "W123");
	}

	private static ProviderPaperRecord record(
			DocumentType documentType,
			String abstractText,
			URI sourceUrl,
			String providerRecordId) {
		return new ProviderPaperRecord(
				ProviderId.OPENALEX,
				providerRecordId,
				"10.5555/raw.candidate",
				null,
				"Synthetic title",
				abstractText,
				LocalDate.parse("2026-01-02"),
				2026,
				documentType,
				"en",
				"Synthetic Journal",
				7,
				List.of(new ProviderAuthor(
						"secret-author-id", "Synthetic Author",
						"https://orcid.test/author", 1, true)),
				true,
				URI.create("https://landing.test/secret-landing"),
				URI.create("https://pdf.test/secret-pdf"),
				0.987654321d,
				Instant.parse("2026-08-26T10:00:00Z"),
				Map.of("providerConfig", "secret-config", "cause", "secret-cause"),
				List.of(new PaperIdentifier(
						PaperIdentifierType.DOI, "", "10.5555/raw.candidate")),
				sourceUrl);
	}
}
