package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ProviderQualityComparativeJudgmentsTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final String REVIEW_A = "a".repeat(64);
	private static final String REVIEW_B = "b".repeat(64);
	private static final String REVIEW_C = "c".repeat(64);
	private static final String REVIEW_D = "d".repeat(64);

	@TempDir
	private Path temporaryDirectory;

	@Test
	void loadsBoundedTypedJudgmentsAndExposesImmutableScorerIndexes() throws Exception {
		byte[] packetBytes = validJson().getBytes(StandardCharsets.UTF_8);
		ProviderQualityComparativeJudgments.BoundJudgments parsedBound =
				ProviderQualityComparativeJudgments.parseBound(OBJECT_MAPPER, packetBytes);
		ProviderQualityComparativeJudgments parsed = parsedBound.judgments();
		Path packet = temporaryDirectory.resolve("independent-judgments.json");
		Files.write(packet, packetBytes);

		ProviderQualityComparativeJudgments.BoundJudgments loadedBound =
				ProviderQualityComparativeJudgments.loadBound(OBJECT_MAPPER, packet);
		ProviderQualityComparativeJudgments loaded = loadedBound.judgments();

		assertThat(loaded).isEqualTo(parsed);
		assertThat(loadedBound).isEqualTo(parsedBound);
		assertThat(parsedBound.sha256()).isEqualTo(HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(packetBytes)));
		assertThat(ProviderQualityComparativeJudgments.load(OBJECT_MAPPER, packet))
				.isEqualTo(parsed);
		assertThat(parsed.schemaVersion()).isEqualTo(1);
		assertThat(parsed.protocolId())
				.isEqualTo(ProviderQualityComparativeJudgments.PROTOCOL_ID);
		assertThat(parsed.independenceAttestation())
				.isEqualTo(ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION);
		assertThat(parsed.queriesByKey()).containsOnlyKeys("cancer-ml-diagnosis");
		ProviderQualityComparativeJudgments.QueryJudgments query =
				parsed.queriesByKey().get("cancer-ml-diagnosis");
		assertThat(query.goldPapersByKey()).containsOnlyKeys("relevant-work", "hard-negative");
		assertThat(query.goldPaperKeyByReviewKey())
				.containsEntry(REVIEW_A, "relevant-work")
				.containsEntry(REVIEW_B, "hard-negative");
		assertThat(query.relevanceByGoldPaperKey())
				.containsEntry("relevant-work", 3)
				.containsEntry("hard-negative", 0);
		assertThat(query.goldPapersByKey().get("relevant-work").expectedFields())
				.containsExactly(MetadataField.ABSTRACT, MetadataField.TITLE);
		assertThat(query.mustSeparatePairs()).singleElement().satisfies(pair -> {
			assertThat(pair.leftReviewKey()).isEqualTo(REVIEW_A);
			assertThat(pair.rightReviewKey()).isEqualTo(REVIEW_B);
			assertThat(pair.canonicalKey()).isEqualTo(REVIEW_A + '\n' + REVIEW_B);
		});

		assertThatThrownBy(() -> parsed.queries().add(query))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> query.goldPapersByKey().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> query.goldPapersByKey()
				.get("relevant-work").expectedFieldSet().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsDuplicateFieldsTrailingDocumentsAndUnknownOrMissingKeys() throws Exception {
		String duplicateField = validJson().replaceFirst(
				"\\\"schemaVersion\\\"\\s*:\\s*1,",
				"\"schemaVersion\": 1, \"schemaVersion\": 1,");
		assertThatThrownBy(() -> ProviderQualityComparativeJudgments.parse(
				OBJECT_MAPPER, duplicateField.getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(JacksonException.class);
		assertThatThrownBy(() -> ProviderQualityComparativeJudgments.parse(
				OBJECT_MAPPER, (validJson() + "\n{}").getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(JacksonException.class);

		ObjectNode unknown = validTree();
		gold(unknown, 0).put("provider", "OPENALEX");
		assertInvalid(unknown, "Unknown keys");

		ObjectNode missing = validTree();
		query(missing, 0).remove("mustSeparatePairs");
		assertInvalid(missing, "Missing keys");
	}

	@Test
	void rejectsIncorrectProtocolBindingSyntaxAndAttestation() throws Exception {
		ObjectNode wrongSchema = validTree();
		wrongSchema.put("schemaVersion", 2);
		assertInvalid(wrongSchema, "schemaVersion must be 1");

		ObjectNode wrongProtocol = validTree();
		wrongProtocol.put("protocolId", "provider-quality-independent-judgments-v2");
		assertInvalid(wrongProtocol, "protocolId must be");

		ObjectNode unsafeEvidence = validTree();
		unsafeEvidence.put("evidenceId", "../capture");
		assertInvalid(unsafeEvidence, "evidenceId");

		ObjectNode invalidManifestDigest = validTree();
		invalidManifestDigest.put("evidenceManifestSha256", "A".repeat(64));
		assertInvalid(invalidManifestDigest, "evidenceManifestSha256");

		ObjectNode invalidPolicyId = validTree();
		invalidPolicyId.put("scoringPolicyId", "Provider Policy");
		assertInvalid(invalidPolicyId, "scoringPolicyId");

		ObjectNode invalidPolicyDigest = validTree();
		invalidPolicyDigest.put("scoringPolicySha256", "not-a-digest");
		assertInvalid(invalidPolicyDigest, "scoringPolicySha256");

		ObjectNode wrongAttestation = validTree();
		wrongAttestation.put("independenceAttestation", "AUTHORED_AFTER_REVIEWING_RESULTS");
		assertInvalid(wrongAttestation, "independenceAttestation");
	}

	@Test
	void rejectsDuplicateKeysWhileAllowingExplicitNoRelevantOutcomes() throws Exception {
		ObjectNode duplicateQuery = validTree();
		queries(duplicateQuery).add(query(duplicateQuery, 0).deepCopy());
		assertInvalid(duplicateQuery, "duplicate queryKey");

		ObjectNode duplicateGold = validTree();
		goldPapers(duplicateGold, 0).add(gold(duplicateGold, 0).deepCopy());
		assertInvalid(duplicateGold, "duplicate goldPaperKey");

		ObjectNode duplicateReviewAssignment = validTree();
		reviewKeys(gold(duplicateReviewAssignment, 1)).set(0, reviewKeys(
				gold(duplicateReviewAssignment, 0)).get(0).deepCopy());
		assertInvalid(duplicateReviewAssignment, "exactly one gold paper");

		ObjectNode duplicateReviewWithinGold = validTree();
		reviewKeys(gold(duplicateReviewWithinGold, 0)).add(REVIEW_A);
		assertInvalid(duplicateReviewWithinGold, "unique within a gold paper");

		ObjectNode noRelevantGold = validTree();
		gold(noRelevantGold, 0).put("relevanceGrade", 0);
		assertThat(ProviderQualityComparativeJudgments.parse(noRelevantGold)
				.queries().getFirst().relevanceByGoldPaperKey())
				.containsOnly(Map.entry("relevant-work", 0), Map.entry("hard-negative", 0));

		ObjectNode emptyResultSet = validTree();
		goldPapers(emptyResultSet, 0).removeAll();
		pairs(emptyResultSet, 0).removeAll();
		assertThat(ProviderQualityComparativeJudgments.parse(emptyResultSet)
				.queries().getFirst().goldPapers()).isEmpty();

		ObjectNode duplicateAcrossQueries = validTree();
		ObjectNode secondQuery = query(duplicateAcrossQueries, 0).deepCopy();
		secondQuery.put("queryKey", "second-query");
		goldInQuery(secondQuery, 0).put("goldPaperKey", "second-relevant");
		goldInQuery(secondQuery, 1).put("goldPaperKey", "second-negative");
		queries(duplicateAcrossQueries).add(secondQuery);
		assertInvalid(duplicateAcrossQueries, "unique across queries");
	}

	@Test
	void enforcesExpectedFieldAndJudgmentBounds() throws Exception {
		ObjectNode unsortedFields = validTree();
		ArrayNode fields = expectedFields(gold(unsortedFields, 0));
		fields.removeAll();
		fields.add("TITLE").add("ABSTRACT");
		assertInvalid(unsortedFields, "sorted unique");

		ObjectNode duplicateFields = validTree();
		expectedFields(gold(duplicateFields, 0)).add("TITLE");
		assertInvalid(duplicateFields, "sorted unique");

		ObjectNode unsupportedField = validTree();
		expectedFields(gold(unsupportedField, 0)).add("PDF_URL");
		assertInvalid(unsupportedField, "unsupported field");

		ObjectNode invalidReviewKey = validTree();
		ArrayNode invalidReviewKeys = reviewKeys(gold(invalidReviewKey, 0));
		invalidReviewKeys.removeAll();
		invalidReviewKeys.add("A".repeat(64));
		assertInvalid(invalidReviewKey, "lowercase SHA-256");

		ObjectNode invalidGrade = validTree();
		gold(invalidGrade, 0).put("relevanceGrade", 4);
		assertInvalid(invalidGrade, "0 through 3");
	}

	@Test
	void enforcesCanonicalUniqueCrossGoldMustSeparatePairs() throws Exception {
		ObjectNode reversed = validTree();
		ObjectNode reversedPair = pair(reversed, 0);
		reversedPair.put("leftReviewKey", REVIEW_B);
		reversedPair.put("rightReviewKey", REVIEW_A);
		assertInvalid(reversed, "canonical ascending order");

		ObjectNode duplicate = validTree();
		pairs(duplicate, 0).add(pair(duplicate, 0).deepCopy());
		assertInvalid(duplicate, "duplicate must-separate pair");

		ObjectNode missingReview = validTree();
		pair(missingReview, 0).put("rightReviewKey", REVIEW_D);
		assertInvalid(missingReview, "must both belong");

		ObjectNode sameGold = validTree();
		reviewKeys(gold(sameGold, 0)).add(REVIEW_C);
		pair(sameGold, 0).put("rightReviewKey", REVIEW_C);
		assertInvalid(sameGold, "different gold papers");

		ObjectNode unsafeReason = validTree();
		pair(unsafeReason, 0).put("reasonCode", "free form reason");
		assertInvalid(unsafeReason, "safe uppercase code");
	}

	@Test
	void enforcesPacketQueryAndCandidateLimitsBeforeScoring() throws Exception {
		assertThatThrownBy(() -> ProviderQualityComparativeJudgments.parse(
				OBJECT_MAPPER,
				new byte[ProviderQualityComparativeJudgments.MAX_INPUT_BYTES + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("1048576 bytes");
		assertThatThrownBy(() -> ProviderQualityComparativeJudgments.load(
				OBJECT_MAPPER, temporaryDirectory))
				.isInstanceOf(java.io.IOException.class)
				.hasMessageContaining("regular file");

		ObjectNode tooManyCandidates = validTree();
		ArrayNode candidateKeys = reviewKeys(gold(tooManyCandidates, 0));
		candidateKeys.removeAll();
		for (int index = 0; index <= ProviderQualityComparativeJudgments.MAX_CANDIDATES_PER_QUERY;
				index++) {
			candidateKeys.add(hex(index + 1));
		}
		pairs(tooManyCandidates, 0).removeAll();
		assertInvalid(tooManyCandidates, "exceeds 40 candidates");

		ObjectNode tooManyQueries = validTree();
		ArrayNode queryNodes = queries(tooManyQueries);
		queryNodes.removeAll();
		for (int index = 0; index <= ProviderQualityComparativeJudgments.MAX_QUERIES; index++) {
			ObjectNode query = OBJECT_MAPPER.createObjectNode();
			query.put("queryKey", "query-" + String.format(java.util.Locale.ROOT, "%02d", index));
			ArrayNode goldPapers = query.putArray("goldPapers");
			ObjectNode gold = goldPapers.addObject();
			gold.put("goldPaperKey", "relevant-work");
			gold.putArray("reviewKeys").add(hex(1_000 + index));
			gold.put("relevanceGrade", 1);
			gold.putArray("expectedFields");
			query.putArray("mustSeparatePairs");
			queryNodes.add(query);
		}
		assertInvalid(tooManyQueries, "1 through 50 entries");
	}

	private static void assertInvalid(ObjectNode root, String message) {
		assertThatThrownBy(() -> ProviderQualityComparativeJudgments.parse(
				OBJECT_MAPPER, OBJECT_MAPPER.writeValueAsBytes(root)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(message);
	}

	private static ObjectNode validTree() throws Exception {
		return (ObjectNode) OBJECT_MAPPER.readTree(validJson());
	}

	private static ArrayNode queries(ObjectNode root) {
		return (ArrayNode) root.required("queries");
	}

	private static ObjectNode query(ObjectNode root, int index) {
		return (ObjectNode) queries(root).get(index);
	}

	private static ArrayNode goldPapers(ObjectNode root, int queryIndex) {
		return (ArrayNode) query(root, queryIndex).required("goldPapers");
	}

	private static ObjectNode gold(ObjectNode root, int index) {
		return (ObjectNode) goldPapers(root, 0).get(index);
	}

	private static ObjectNode goldInQuery(ObjectNode query, int index) {
		return (ObjectNode) query.required("goldPapers").get(index);
	}

	private static ArrayNode reviewKeys(ObjectNode gold) {
		return (ArrayNode) gold.required("reviewKeys");
	}

	private static ArrayNode expectedFields(ObjectNode gold) {
		return (ArrayNode) gold.required("expectedFields");
	}

	private static ArrayNode pairs(ObjectNode root, int queryIndex) {
		return (ArrayNode) query(root, queryIndex).required("mustSeparatePairs");
	}

	private static ObjectNode pair(ObjectNode root, int index) {
		return (ObjectNode) pairs(root, 0).get(index);
	}

	private static String hex(int value) {
		return String.format(java.util.Locale.ROOT, "%064x", value);
	}

	private static String validJson() {
		return """
				{
				  "schemaVersion": 1,
				  "protocolId": "provider-quality-independent-judgments-v1",
				  "evidenceId": "europe-pmc-comparative-contract",
				  "evidenceManifestSha256": "%s",
				  "querySetId": "europe-pmc-live-queries-v1",
				  "querySetSha256": "%s",
				  "scoringPolicyId": "provider-comparative-scoring-policy-v1",
				  "scoringPolicySha256": "%s",
				  "independenceAttestation": "AUTHORED_WITHOUT_PROVENANCE_OR_SCENARIO_OUTPUT",
				  "queries": [
				    {
				      "queryKey": "cancer-ml-diagnosis",
				      "goldPapers": [
				        {
				          "goldPaperKey": "relevant-work",
				          "reviewKeys": ["%s"],
				          "relevanceGrade": 3,
				          "expectedFields": ["ABSTRACT", "TITLE"]
				        },
				        {
				          "goldPaperKey": "hard-negative",
				          "reviewKeys": ["%s"],
				          "relevanceGrade": 0,
				          "expectedFields": ["DOCUMENT_TYPE", "TITLE"]
				        }
				      ],
				      "mustSeparatePairs": [
				        {
				          "leftReviewKey": "%s",
				          "rightReviewKey": "%s",
				          "reasonCode": "DISTINCT_WORKS"
				        }
				      ]
				    }
				  ]
				}
				""".formatted(
				"1".repeat(64),
				"2".repeat(64),
				"3".repeat(64),
				REVIEW_A,
				REVIEW_B,
				REVIEW_A,
				REVIEW_B);
	}
}
