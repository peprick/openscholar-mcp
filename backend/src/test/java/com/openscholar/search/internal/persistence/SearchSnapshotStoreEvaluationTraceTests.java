package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SearchSnapshotStoreEvaluationTraceTests {

	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	private SearchSnapshotStore snapshotStore;

	@Test
	void tracesEveryRawContributionBeforePageLimitingAndSameProviderCollapse() {
		ProviderSearchResult openAlex = new ProviderSearchResult(
				ProviderId.OPENALEX,
				List.of(
						paper(ProviderId.OPENALEX, "W-DUPLICATE-A", "10.1000/trace.duplicate", "Duplicate A"),
						paper(ProviderId.OPENALEX, "W-DUPLICATE-B", "doi:10.1000/TRACE.DUPLICATE", "Duplicate B"),
						paper(ProviderId.OPENALEX, "W-IN-PAGE", "10.1000/trace.in-page", "In page"),
						paper(ProviderId.OPENALEX, "W-BELOW-PAGE", "10.1000/trace.below-page", "Below page")),
				4,
				null,
				NOW);

		SearchSnapshotStore.StoreTrace stored = snapshotStore.storeWithTrace(
				OWNER_ID,
				command(2),
				"evaluation trace",
				"e".repeat(64),
				1,
				"provider-fanout-v1",
				new ProviderSearchBatchResult(List.of(openAlex), List.of(), null, NOW),
				NOW.plus(Duration.ofHours(1)),
				CacheDisposition.MISS_FETCHED);

		assertThat(stored.view().results()).hasSize(2);
		assertThat(stored.rawContributions())
				.extracting(
						SearchSnapshotStore.RawContributionTrace::provider,
						SearchSnapshotStore.RawContributionTrace::providerRecordId,
						SearchSnapshotStore.RawContributionTrace::providerRank,
						SearchSnapshotStore.RawContributionTrace::includedInFirstPage)
				.containsExactly(
						tuple(ProviderId.OPENALEX, "W-DUPLICATE-A", 1, true),
						tuple(ProviderId.OPENALEX, "W-DUPLICATE-B", 2, true),
						tuple(ProviderId.OPENALEX, "W-IN-PAGE", 3, true),
						tuple(ProviderId.OPENALEX, "W-BELOW-PAGE", 4, false));

		Map<String, SearchSnapshotStore.RawContributionTrace> byProviderRecord =
				stored.rawContributions().stream().collect(Collectors.toMap(
						SearchSnapshotStore.RawContributionTrace::providerRecordId,
						Function.identity()));
		assertThat(byProviderRecord.get("W-DUPLICATE-A").canonicalPaperId())
				.isEqualTo(byProviderRecord.get("W-DUPLICATE-B").canonicalPaperId());
		assertThat(byProviderRecord.get("W-IN-PAGE").canonicalPaperId())
				.isNotEqualTo(byProviderRecord.get("W-DUPLICATE-A").canonicalPaperId())
				.isNotEqualTo(byProviderRecord.get("W-BELOW-PAGE").canonicalPaperId());
		assertThat(stored.view().results().getFirst().providerContributions())
				.singleElement()
				.satisfies(contribution -> assertThat(contribution.providerRecordId())
						.isEqualTo("W-DUPLICATE-A"));
	}

	@Test
	void publicStoreReturnsTheSameSearchViewAsTheSharedTracedImplementation() {
		ProviderSearchResult europePmc = new ProviderSearchResult(
				ProviderId.EUROPE_PMC,
				List.of(
						paper(ProviderId.EUROPE_PMC, "MED:TRACE-SHARED", "10.1000/trace.shared", "Shared Europe PMC"),
						paper(ProviderId.EUROPE_PMC, "MED:TRACE-ONLY", null, "Europe PMC only")),
				2,
				null,
				NOW);
		ProviderSearchResult openAlex = new ProviderSearchResult(
				ProviderId.OPENALEX,
				List.of(
						paper(ProviderId.OPENALEX, "W-TRACE-ONLY", "10.1000/trace.openalex", "OpenAlex only"),
						paper(ProviderId.OPENALEX, "W-TRACE-SHARED", "https://doi.org/10.1000/TRACE.SHARED", "Shared OpenAlex")),
				2,
				null,
				NOW.plusSeconds(1));
		ProviderSearchBatchResult batch = new ProviderSearchBatchResult(
				List.of(openAlex, europePmc), List.of(), "opaque-next", NOW.plusSeconds(1));

		SearchView publicView = snapshotStore.store(
				OWNER_ID,
				command(2),
				"evaluation trace",
				"a".repeat(64),
				1,
				"provider-fanout-v1",
				batch,
				NOW.plus(Duration.ofHours(1)),
				CacheDisposition.MISS_FETCHED);
		SearchSnapshotStore.StoreTrace traced = snapshotStore.storeWithTrace(
				OWNER_ID,
				command(2),
				"evaluation trace",
				"a".repeat(64),
				1,
				"provider-fanout-v1",
				batch,
				NOW.plus(Duration.ofHours(1)),
				CacheDisposition.MISS_FETCHED);

		assertThat(traced.view())
				.usingRecursiveComparison()
				.ignoringFields("searchId")
				.isEqualTo(publicView);
		assertThat(publicView.results())
				.extracting(result -> result.providerRecordId())
				.containsExactly("MED:TRACE-SHARED", "W-TRACE-ONLY");
		assertThat(publicView.results().getFirst().providerContributions())
				.extracting(contribution -> contribution.provider())
				.containsExactly(ProviderId.EUROPE_PMC, ProviderId.OPENALEX);
		assertThat(snapshotStore.findById(OWNER_ID, publicView.searchId()))
				.hasValueSatisfying(reloaded -> assertThat(reloaded.results())
						.isEqualTo(publicView.results()));
	}

	private static SearchCommand command(int pageSize) {
		return new SearchCommand(
				"Evaluation trace", null, null, Set.of(DocumentType.ARTICLE),
				false, 0, Set.of(), pageSize, "*", false);
	}

	private static ProviderPaperRecord paper(
			ProviderId provider,
			String providerRecordId,
			String doi,
			String title) {
		String providerSlug = provider.name().toLowerCase(java.util.Locale.ROOT);
		return new ProviderPaperRecord(
				provider,
				providerRecordId,
				doi,
				null,
				title,
				"Synthetic abstract for " + title,
				LocalDate.of(2026, 8, 1),
				2026,
				DocumentType.ARTICLE,
				"en",
				"Trace Journal",
				3,
				List.of(),
				false,
				URI.create("https://fixtures.openscholar.test/" + providerSlug + "/" + providerRecordId),
				null,
				0.5,
				NOW,
				Map.of(),
				List.of(),
				URI.create("https://fixtures.openscholar.test/source/" + providerSlug + "/" + providerRecordId));
	}
}
