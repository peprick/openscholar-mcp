package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SearchSnapshotStoreMultiProviderTests {

	private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");
	private static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	private SearchSnapshotStore snapshotStore;

	@Test
	void mergesExactIdentifiersAndRanksStableCrossProviderResultsWithProvenance() {
		ProviderSearchResult core = new ProviderSearchResult(
				ProviderId.CORE,
				List.of(
						paper(ProviderId.CORE, "core-shared", "10.1000/shared", "Shared from CORE", true),
						paper(ProviderId.CORE, "core-only", "10.1000/core", "CORE only", false)),
				21,
				"core-next",
				NOW);
		ProviderSearchResult openAlex = new ProviderSearchResult(
				ProviderId.OPENALEX,
				List.of(
						paper(ProviderId.OPENALEX, "W-OA-ONLY", "10.1000/openalex", "OpenAlex only", false),
						paper(ProviderId.OPENALEX, "W-SHARED", "https://doi.org/10.1000/SHARED", "Shared enriched", false)),
				34,
				"oa-next",
				NOW.plusSeconds(1));
		ProviderSearchBatchResult batch = new ProviderSearchBatchResult(
				List.of(openAlex, core),
				List.of(),
				"opaque-next",
				NOW.plusSeconds(1));

		SearchView stored = snapshotStore.store(
				LOCAL_USER_ID,
				command(),
				"graph models",
				"a".repeat(64),
				1,
				"provider-fanout-v1",
				batch,
				NOW.plus(Duration.ofHours(1)),
				CacheDisposition.MISS_FETCHED);

		assertThat(stored.providerCoverage())
				.extracting(coverage -> coverage.provider())
				.containsExactly(ProviderId.CORE, ProviderId.OPENALEX);
		assertThat(stored.warnings()).isEmpty();
		assertThat(stored.results()).hasSize(3);
		SearchResultView shared = stored.results().getFirst();
		assertThat(shared.paper().identifiers())
				.extracting(identifier -> identifier.type())
				.contains(PaperIdentifierType.DOI, PaperIdentifierType.CORE, PaperIdentifierType.OPENALEX);
		assertThat(shared.providerContributions())
				.extracting(contribution -> contribution.provider())
				.containsExactly(ProviderId.CORE, ProviderId.OPENALEX);
		assertThat(shared.rankingReasons()).singleElement()
				.satisfies(reason -> {
					assertThat(reason.feature()).isEqualTo("PROVIDER_RECIPROCAL_RANK_FUSION");
					assertThat(reason.value()).isEqualTo(1.0 / 61.0 + 1.0 / 62.0);
				});
		assertThat(stored.results()).extracting(result -> result.paper().title())
				.containsExactly("Shared enriched", "OpenAlex only", "CORE only");
		assertThat(snapshotStore.findById(LOCAL_USER_ID, stored.searchId())).hasValueSatisfying(reloaded -> {
			assertThat(reloaded.cacheDisposition()).isEqualTo(CacheDisposition.EXACT_HIT);
			assertThat(reloaded.results()).isEqualTo(stored.results());
		});
	}

	@Test
	void mergesEuropePmcAndOpenAlexProvenanceAcrossDoiPmidAndPmcidBridges() {
		ProviderSearchResult europePmc = new ProviderSearchResult(
				ProviderId.EUROPE_PMC,
				List.of(
						identifiedPaper(
								ProviderId.EUROPE_PMC, "PMC-DOI", "Europe PMC DOI bridge",
								PaperIdentifierType.DOI, "https://doi.org/10.1000/EPMC.BRIDGE"),
						identifiedPaper(
								ProviderId.EUROPE_PMC, "PMC-PMID", "Europe PMC PMID bridge",
								PaperIdentifierType.PMID, "12345678"),
						identifiedPaper(
								ProviderId.EUROPE_PMC, "PMC-PMCID", "Europe PMC PMCID bridge",
								PaperIdentifierType.PMCID, "PMC87654321")),
				3,
				null,
				NOW);
		ProviderSearchResult openAlex = new ProviderSearchResult(
				ProviderId.OPENALEX,
				List.of(
						identifiedPaper(
								ProviderId.OPENALEX, "W-EPMC-DOI", "OpenAlex DOI bridge",
								PaperIdentifierType.DOI, "doi:10.1000/epmc.bridge"),
						identifiedPaper(
								ProviderId.OPENALEX, "W-EPMC-PMID", "OpenAlex PMID bridge",
								PaperIdentifierType.PMID, "12345678"),
						identifiedPaper(
								ProviderId.OPENALEX, "W-EPMC-PMCID", "OpenAlex PMCID bridge",
								PaperIdentifierType.PMCID, "pmc87654321")),
				3,
				null,
				NOW.plusSeconds(1));

		SearchView stored = snapshotStore.store(
				LOCAL_USER_ID,
				command(),
				"graph models",
				"d".repeat(64),
				1,
				"provider-fanout-v1",
				new ProviderSearchBatchResult(
						List.of(openAlex, europePmc), List.of(), null, NOW.plusSeconds(1)),
				NOW.plus(Duration.ofHours(1)),
				CacheDisposition.MISS_FETCHED);

		assertThat(stored.providerCoverage())
				.extracting(coverage -> coverage.provider())
				.containsExactly(ProviderId.EUROPE_PMC, ProviderId.OPENALEX);
		assertThat(stored.results()).hasSize(3);
		for (PaperIdentifierType bridgeType : List.of(
				PaperIdentifierType.DOI, PaperIdentifierType.PMID, PaperIdentifierType.PMCID)) {
			SearchResultView bridged = stored.results().stream()
					.filter(result -> result.paper().identifiers().stream()
							.anyMatch(identifier -> identifier.type() == bridgeType))
					.findFirst()
					.orElseThrow();
			assertThat(bridged.paper().identifiers())
					.extracting(identifier -> identifier.type())
					.contains(bridgeType, PaperIdentifierType.OPENALEX);
			assertThat(bridged.providerContributions())
					.extracting(contribution -> contribution.provider())
					.containsExactly(ProviderId.EUROPE_PMC, ProviderId.OPENALEX);
		}
		assertThat(snapshotStore.findById(LOCAL_USER_ID, stored.searchId()))
				.hasValueSatisfying(reloaded -> assertThat(reloaded.results()).isEqualTo(stored.results()));
	}

	@Test
	void persistsPartialCoverageAndDeterministicProviderWarnings() {
		ProviderSearchResult openAlex = new ProviderSearchResult(
				ProviderId.OPENALEX,
				List.of(paper(ProviderId.OPENALEX, "W-PARTIAL", null, "Partial result", true)),
				1,
				null,
				NOW);
		ProviderException coreFailure = new ProviderException(
				ProviderId.CORE,
				"CORE_UNAVAILABLE",
				"CORE unavailable",
				true,
				Duration.ofSeconds(5),
				null);

		SearchView stored = snapshotStore.store(
				LOCAL_USER_ID,
				command(),
				"graph models",
				"b".repeat(64),
				1,
				"provider-fanout-v1",
				new ProviderSearchBatchResult(List.of(openAlex), List.of(coreFailure), null, NOW),
				NOW.plus(Duration.ofHours(1)),
				CacheDisposition.MISS_FETCHED);

		assertThat(stored.providerCoverage())
				.extracting(coverage -> coverage.provider(), coverage -> coverage.status())
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(ProviderId.CORE, "FAILED"),
						org.assertj.core.groups.Tuple.tuple(ProviderId.OPENALEX, "SUCCESS"));
		assertThat(stored.warnings()).containsExactly("CORE_UNAVAILABLE");
		assertThat(stored.results()).singleElement()
				.satisfies(result -> assertThat(result.rankingReasons()).singleElement()
						.satisfies(reason -> assertThat(reason.feature())
								.isEqualTo("PROVIDER_RECIPROCAL_RANK_FUSION")));
	}

	@Test
	void preservesSingleOpenAlexOrderingCursorAndNullableRelevance() {
		ProviderSearchResult openAlex = new ProviderSearchResult(
				ProviderId.OPENALEX,
				List.of(
						paper(ProviderId.OPENALEX, "W-FIRST", null, "First provider result", true, null),
						paper(ProviderId.OPENALEX, "W-SECOND", null, "Second provider result", false, null)),
				2,
				"raw-openalex-next",
				NOW);

		SearchView stored = snapshotStore.store(
				LOCAL_USER_ID,
				command(),
				"graph models",
				"c".repeat(64),
				1,
				"openalex-v1",
				new ProviderSearchBatchResult(List.of(openAlex), List.of(), "raw-openalex-next", NOW),
				NOW.plus(Duration.ofHours(1)),
				CacheDisposition.MISS_FETCHED);

		assertThat(stored.nextCursor()).isEqualTo("raw-openalex-next");
		assertThat(stored.results()).extracting(result -> result.paper().title())
				.containsExactly("First provider result", "Second provider result");
		assertThat(stored.results()).allSatisfy(result -> {
			assertThat(result.score()).isNull();
			assertThat(result.rankingReasons()).isEmpty();
			assertThat(result.providerContributions()).singleElement()
					.satisfies(contribution -> assertThat(contribution.provider())
							.isEqualTo(ProviderId.OPENALEX));
		});
	}

	private static SearchCommand command() {
		return new SearchCommand(
				"Graph Models", null, null, Set.of(), false, 0, Set.of("en"), 20, "*", false);
	}

	private static ProviderPaperRecord paper(
			ProviderId provider,
			String providerRecordId,
			String doi,
			String title,
			boolean openAccess) {
		return paper(provider, providerRecordId, doi, title, openAccess, 0.75);
	}

	private static ProviderPaperRecord paper(
			ProviderId provider,
			String providerRecordId,
			String doi,
			String title,
			boolean openAccess,
			Double relevanceScore) {
		return paper(provider, providerRecordId, doi, title, openAccess, relevanceScore, List.of());
	}

	private static ProviderPaperRecord identifiedPaper(
			ProviderId provider,
			String providerRecordId,
			String title,
			PaperIdentifierType identifierType,
			String identifierValue) {
		return paper(
				provider,
				providerRecordId,
				null,
				title,
				false,
				null,
				List.of(new PaperIdentifier(identifierType, "", identifierValue)));
	}

	private static ProviderPaperRecord paper(
			ProviderId provider,
			String providerRecordId,
			String doi,
			String title,
			boolean openAccess,
			Double relevanceScore,
			List<PaperIdentifier> identifiers) {
		String providerSlug = provider.name().toLowerCase(java.util.Locale.ROOT);
		return new ProviderPaperRecord(
				provider,
				providerRecordId,
				doi,
				null,
				title,
				"Abstract for " + title,
				LocalDate.of(2026, 8, 1),
				2026,
				DocumentType.ARTICLE,
				"en",
				"Test Journal",
				5,
				List.of(),
				openAccess,
				URI.create("https://example.org/" + providerSlug + "/" + providerRecordId),
				openAccess ? URI.create("https://example.org/" + providerSlug + "/" + providerRecordId + ".pdf") : null,
				relevanceScore,
				NOW,
				Map.of("provider", providerSlug),
				identifiers,
				URI.create("https://api.example.org/" + providerSlug + "/" + providerRecordId));
	}
}
