package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.openscholar.paper.DocumentType;
import com.openscholar.search.SearchCommand;
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

	private static SearchCommand command(
			String query,
			boolean forceRefresh,
			Set<DocumentType> types,
			Set<String> languages) {
		return new SearchCommand(query, null, null, types, false, 0, languages, 20, "*", forceRefresh);
	}
}
