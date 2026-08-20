package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;

final class RelatedPaperEvaluationCorpus {

	private RelatedPaperEvaluationCorpus() {
	}

	static SeededCorpus seed(
			PaperCatalog paperCatalog,
			RelatedPaperEvaluationFixture fixture,
			Instant retrievedAt) {
		Map<String, PaperView> papers = new LinkedHashMap<>();
		int index = 1;
		for (RelatedPaperEvaluationFixture.FixturePaper fixturePaper : fixture.papers()) {
			String providerRecordId = "W-EVAL-%s-%03d".formatted(
					providerNamespace(fixture.fixtureId()), index++);
			CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(
					fixturePaper.title(),
					fixturePaper.abstractText(),
					null,
					fixturePaper.year(),
					fixturePaper.type(),
					fixturePaper.language(),
					"OpenScholar synthetic relevance fixture",
					fixturePaper.citationCount(),
					retrievedAt,
					List.of(new PaperIdentifier(
							PaperIdentifierType.OPENALEX, "", providerRecordId)),
					List.of());
			ProviderRecordCandidate providerRecord = new ProviderRecordCandidate(
					"OpenAlex",
					providerRecordId,
					retrievedAt,
					retrievedAt,
					URI.create("https://openalex.org/" + providerRecordId),
					true,
					URI.create("https://openalex.org/" + providerRecordId),
					null,
					Map.of("fixtureKey", fixturePaper.key()));
			PaperView paper = paperCatalog.upsert(candidate, providerRecord, retrievedAt);
			assertThat(papers.put(fixturePaper.key(), paper))
					.as("duplicate fixture paper key %s", fixturePaper.key())
					.isNull();
		}
		Map<UUID, String> keysByPaperId = new LinkedHashMap<>();
		papers.forEach((key, paper) -> keysByPaperId.put(paper.id(), key));
		return new SeededCorpus(papers, keysByPaperId);
	}

	private static String providerNamespace(String fixtureId) {
		return fixtureId.toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z0-9]+", "-")
				.replaceAll("^-|-$", "");
	}

	record SeededCorpus(Map<String, PaperView> papersByKey, Map<UUID, String> keysByPaperId) {

		SeededCorpus {
			papersByKey = Map.copyOf(papersByKey);
			keysByPaperId = Map.copyOf(keysByPaperId);
		}

		PaperView paper(String key) {
			return papersByKey.get(key);
		}

		String keyFor(UUID paperId, String queryKey) {
			String key = keysByPaperId.get(paperId);
			assertThat(key)
					.as("ranked paper %s for query %s belongs to the evaluation corpus", paperId, queryKey)
					.isNotNull();
			return key;
		}
	}
}
