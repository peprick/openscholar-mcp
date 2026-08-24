package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderId;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchMode;
import org.junit.jupiter.api.Test;

class QueryFingerprinterTests {

	private final QueryFingerprinter fingerprinter = new QueryFingerprinter(new QueryNormalizer());

	@Test
	void normalizesUnicodeCaseAndWhitespace() {
		SearchCommand command = command("  Ｇraph\u3000NEURAL\nNetworks  ", false, Set.of(), Set.of());

		assertThat(fingerprinter.normalizedQuery(command)).isEqualTo("graph neural networks");
	}

	@Test
	void producesStableFingerprintForEquivalentSetOrderingAndRefreshFlag() {
		SearchCommand first = command(
				"Graph Neural Networks",
				false,
				Set.of(DocumentType.PREPRINT, DocumentType.ARTICLE),
				Set.of("EN", "fr"));
		SearchCommand second = command(
				" graph   neural networks ",
				true,
				Set.of(DocumentType.ARTICLE, DocumentType.PREPRINT),
				Set.of("fr", "en"));

		assertThat(fingerprinter.fingerprint(first)).isEqualTo(fingerprinter.fingerprint(second));
	}

	@Test
	void changesFingerprintForMaterialFiltersAndCursor() {
		SearchCommand base = command("Graph Neural Networks", false, Set.of(), Set.of());
		SearchCommand filtered = new SearchCommand(
				base.query(), 2020, 2026, Set.of(), true, 5, Set.of("en"), 20, "next", false);

		assertThat(fingerprinter.fingerprint(base)).isNotEqualTo(fingerprinter.fingerprint(filtered));
		assertThat(fingerprinter.fingerprint(base)).hasSize(64);
	}

	@Test
	void includesTheStableEnabledProviderSetInTheFingerprint() {
		SearchCommand command = command("Graph Neural Networks", false, Set.of(), Set.of());
		QueryFingerprinter reversedProviders = new QueryFingerprinter(
				new QueryNormalizer(), List.of(ProviderId.OPENALEX, ProviderId.CORE));
		QueryFingerprinter orderedProviders = new QueryFingerprinter(
				new QueryNormalizer(), List.of(ProviderId.CORE, ProviderId.OPENALEX));

		assertThat(reversedProviders.fingerprint(command))
				.isEqualTo(orderedProviders.fingerprint(command))
				.isNotEqualTo(fingerprinter.fingerprint(command));
		assertThat(fingerprinter.pipelineVersion()).isEqualTo("openalex-v1");
		assertThat(orderedProviders.pipelineVersion()).isEqualTo("provider-fanout-v1");
	}

	@Test
	void isolatesExecutionModesAndTheLocalPipeline() {
		SearchCommand auto = command("Graph Neural Networks", false, Set.of(), Set.of());
		SearchCommand online = new SearchCommand(
				auto.query(), null, null, Set.of(), false, 0, Set.of(), 20, "*", false,
				SearchMode.ONLINE);

		assertThat(fingerprinter.onlineFingerprint(auto))
				.isNotEqualTo(fingerprinter.onlineFingerprint(online));
		assertThat(fingerprinter.localFingerprint(auto))
				.isNotEqualTo(fingerprinter.onlineFingerprint(auto));
		assertThat(fingerprinter.fingerprintVersion()).isEqualTo(2);
		assertThat(fingerprinter.localPipelineVersion()).isEqualTo("local-catalog-v1");
	}

	@Test
	void localScopeFingerprintStaysStableAcrossPagesWhileSnapshotFingerprintsDoNot() {
		SearchCommand first = new SearchCommand(
				"Graph Neural Networks", null, null, Set.of(), false, 0, Set.of(), 20, "*", false,
				SearchMode.LOCAL);
		SearchCommand next = new SearchCommand(
				first.query(), null, null, Set.of(), false, 0, Set.of(), 20,
				"oslocal1.placeholder", false, SearchMode.LOCAL);

		assertThat(fingerprinter.localScopeFingerprint(first))
				.isEqualTo(fingerprinter.localScopeFingerprint(next));
		assertThat(fingerprinter.localFingerprint(first))
				.isNotEqualTo(fingerprinter.localFingerprint(next));
	}

	private static SearchCommand command(
			String query,
			boolean forceRefresh,
			Set<DocumentType> types,
			Set<String> languages) {
		return new SearchCommand(query, null, null, types, false, 0, languages, 20, "*", forceRefresh);
	}
}
