package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.ProviderRecordCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PaperDeduplicationQualityEvaluationTests {

	private static final String FIXTURE =
			"search/relevance/paper-deduplication-baseline-v1.json";
	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-22T12:00:00Z");

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void exactIdentifierPolicyMeetsTheFrozenPairwiseQualityGate() throws IOException {
		JsonNode fixture = objectMapper.readTree(requiredFixture());
		assertThat(fixture.required("version").asString())
				.isEqualTo("paper-deduplication-baseline-v1");
		assertThat(fixture.required("policy").asString())
				.isEqualTo("exact-identifiers-and-provider-records-only");

		Map<String, Observation> observations = new LinkedHashMap<>();
		for (JsonNode record : fixture.required("records")) {
			String key = record.required("key").asString();
			String provider = record.required("provider").asString();
			String providerRecordId = record.required("providerRecordId").asString();
			List<PaperIdentifier> identifiers = identifiers(record.required("identifiers"));
			UUID paperId = paperCatalog.upsert(
					new CanonicalPaperCandidate(
							record.required("title").asString(), null, null, 2026,
							DocumentType.OTHER, "en", null, null, null, identifiers, List.of()),
					new ProviderRecordCandidate(
							provider, providerRecordId, RETRIEVED_AT, RETRIEVED_AT,
							URI.create("https://fixtures.openscholar.test/" + key), false,
							null, null, Map.of("fixtureKey", key)),
					RETRIEVED_AT)
					.id();
			observations.put(key, new Observation(
					record.required("expectedCluster").asString(), paperId));
		}

		PairwiseMetrics metrics = evaluate(new ArrayList<>(observations.values()));
		System.out.printf(
				"dedup-fixture=%s records=%d tp=%d fp=%d fn=%d tn=%d precision=%.3f recall=%.3f f1=%.3f%n",
				fixture.required("version").asString(), observations.size(), metrics.truePositives(),
				metrics.falsePositives(), metrics.falseNegatives(), metrics.trueNegatives(),
				metrics.precision(), metrics.recall(), metrics.f1());

		assertThat(metrics.truePositives()).isPositive();
		assertThat(metrics.trueNegatives()).isPositive();
		assertThat(metrics.precision()).isEqualTo(1.0);
		assertThat(metrics.recall()).isEqualTo(1.0);
		assertThat(metrics.f1()).isEqualTo(1.0);
		assertThat(observations.get("common-title-one").paperId())
				.isNotEqualTo(observations.get("common-title-two").paperId());
		assertThat(observations.get("preprint-version").paperId())
				.isNotEqualTo(observations.get("published-version").paperId());
	}

	private java.io.InputStream requiredFixture() {
		java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(FIXTURE);
		if (input == null) {
			throw new IllegalStateException("Missing deduplication fixture: " + FIXTURE);
		}
		return input;
	}

	private static List<PaperIdentifier> identifiers(JsonNode values) {
		List<PaperIdentifier> identifiers = new ArrayList<>();
		values.forEach(value -> identifiers.add(new PaperIdentifier(
				PaperIdentifierType.valueOf(value.required("type").asString()),
				"",
				value.required("value").asString())));
		return List.copyOf(identifiers);
	}

	private static PairwiseMetrics evaluate(List<Observation> observations) {
		int truePositives = 0;
		int falsePositives = 0;
		int falseNegatives = 0;
		int trueNegatives = 0;
		for (int left = 0; left < observations.size(); left++) {
			for (int right = left + 1; right < observations.size(); right++) {
				Observation a = observations.get(left);
				Observation b = observations.get(right);
				boolean expectedSame = a.expectedCluster().equals(b.expectedCluster());
				boolean actualSame = a.paperId().equals(b.paperId());
				if (expectedSame && actualSame) {
					truePositives++;
				}
				else if (!expectedSame && actualSame) {
					falsePositives++;
				}
				else if (expectedSame) {
					falseNegatives++;
				}
				else {
					trueNegatives++;
				}
			}
		}
		double precision = ratio(truePositives, truePositives + falsePositives);
		double recall = ratio(truePositives, truePositives + falseNegatives);
		double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
		return new PairwiseMetrics(
				truePositives, falsePositives, falseNegatives, trueNegatives,
				precision, recall, f1);
	}

	private static double ratio(int numerator, int denominator) {
		return denominator == 0 ? 0 : (double) numerator / denominator;
	}

	private record Observation(String expectedCluster, UUID paperId) {
	}

	private record PairwiseMetrics(
			int truePositives,
			int falsePositives,
			int falseNegatives,
			int trueNegatives,
			double precision,
			double recall,
			double f1) {
	}
}
