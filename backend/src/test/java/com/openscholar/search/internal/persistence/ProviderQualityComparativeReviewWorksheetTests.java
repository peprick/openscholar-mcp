package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewWorksheet.CompiledJudgments;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewWorksheet.ExpectedCandidate;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewWorksheet.ExpectedQuery;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewWorksheet.ExpectedReviewContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ProviderQualityComparativeReviewWorksheetTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final String REVIEW_A = "a".repeat(64);
	private static final String REVIEW_B = "b".repeat(64);
	private static final String REVIEW_C = "c".repeat(64);
	private static final String REVIEW_D = "d".repeat(64);
	private static final String CANDIDATE_1 = "candidate-0001";
	private static final String CANDIDATE_2 = "candidate-0002";
	private static final String CANDIDATE_3 = "candidate-0003";
	private static final String CANDIDATE_4 = "candidate-0004";
	private static final String REVIEW_PACKET_SHA = "1".repeat(64);
	private static final String MANIFEST_SHA = "2".repeat(64);
	private static final String QUERY_SET_SHA = "3".repeat(64);
	private static final String POLICY_SHA = "4".repeat(64);

	@TempDir
	private Path temporaryDirectory;

	@Test
	void compilesCompletedRowsIntoCanonicalDigestBoundJudgments() throws Exception {
		ObjectNode worksheet = validTree();
		CompiledJudgments result = compile(worksheet);
		byte[] canonical = result.canonicalBytes();
		JsonNode root = OBJECT_MAPPER.readTree(canonical);

		assertThat(root.propertyNames()).containsExactlyInAnyOrder(
				"schemaVersion",
				"protocolId",
				"reviewPacketSha256",
				"evidenceId",
				"evidenceManifestSha256",
				"querySetId",
				"querySetSha256",
				"scoringPolicyId",
				"scoringPolicySha256",
				"independenceAttestation",
				"queries");
		assertThat(root.required("reviewPacketSha256").asString())
				.isEqualTo(REVIEW_PACKET_SHA);
		assertThat(root.required("schemaVersion").asInt()).isEqualTo(2);
		assertThat(root.required("protocolId").asString())
				.isEqualTo(ProviderQualityComparativeJudgments.PROTOCOL_ID);
		assertThat(root.required("independenceAttestation").asString())
				.isEqualTo(ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION);

		JsonNode query = root.required("queries").get(0);
		assertThat(query.propertyNames()).containsExactlyInAnyOrder(
				"queryKey", "goldPapers", "mustSeparatePairs");
		JsonNode goldPapers = query.required("goldPapers");
		assertThat(goldPapers.get(0).required("goldPaperKey").asString())
				.isEqualTo("hard-negative");
		assertThat(goldPapers.get(0).required("expectedFields")).isEmpty();
		assertThat(goldPapers.get(1).required("goldPaperKey").asString())
				.isEqualTo("relevant-work");
		assertThat(strings(goldPapers.get(1).required("reviewKeys")))
				.containsExactly(REVIEW_A, REVIEW_C);
		assertThat(strings(goldPapers.get(1).required("expectedFields")))
				.containsExactly("ABSTRACT", "TITLE");

		assertThat(result.boundJudgments().judgments().queries().getFirst().goldPapers())
				.hasSize(2);
		assertThat(result.boundJudgments().judgments().queries().getFirst()
				.mustSeparatePairs()).singleElement().satisfies(pair -> {
					assertThat(pair.leftReviewKey()).isEqualTo(REVIEW_A);
					assertThat(pair.rightReviewKey()).isEqualTo(REVIEW_B);
				});
		assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(canonical)));
		assertThat(result.boundJudgments().sha256()).isEqualTo(result.sha256());
		assertThat(canonical[canonical.length - 1]).isEqualTo((byte) '\n');

		byte originalFirstByte = canonical[0];
		canonical[0] = (byte) '!';
		assertThat(result.canonicalBytes()[0]).isEqualTo(originalFirstByte);
	}

	@Test
	void worksheetNeedsOnlyPacketLocalAliasesAndCompiledOutputRestoresHiddenBindings()
			throws Exception {
		ObjectNode worksheet = validTree();
		String serializedWorksheet = OBJECT_MAPPER.writeValueAsString(worksheet);

		assertThat(worksheet.propertyNames()).containsExactlyInAnyOrder(
				"schemaVersion",
				"protocolId",
				"reviewPacketSha256",
				"independenceAttestation",
				"queries");
		assertThat(serializedWorksheet)
				.contains(CANDIDATE_1, CANDIDATE_2, CANDIDATE_3)
				.doesNotContain(
						REVIEW_A,
						REVIEW_B,
						REVIEW_C,
						"europe-pmc-comparative-contract",
						MANIFEST_SHA,
						"europe-pmc-live-queries-v1",
						QUERY_SET_SHA,
						"provider-comparative-scoring-policy-v1",
						POLICY_SHA);

		JsonNode compiled = OBJECT_MAPPER.readTree(compile(worksheet).canonicalBytes());
		String serializedCompiled = OBJECT_MAPPER.writeValueAsString(compiled);
		assertThat(serializedCompiled)
				.contains(
						REVIEW_A,
						REVIEW_B,
						REVIEW_C,
						"europe-pmc-comparative-contract",
						"europe-pmc-live-queries-v1",
						"provider-comparative-scoring-policy-v1",
						REVIEW_PACKET_SHA)
				.doesNotContain(CANDIDATE_1, CANDIDATE_2, CANDIDATE_3);
	}

	@Test
	void producesIdenticalCanonicalBytesForEquivalentWorksheetFormatting() throws Exception {
		ObjectNode worksheet = validTree();
		byte[] compact = OBJECT_MAPPER.writeValueAsBytes(worksheet);
		byte[] pretty = OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
				.writeValueAsBytes(worksheet);

		CompiledJudgments first = ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, compact, expectedContext());
		CompiledJudgments second = ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, pretty, expectedContext());

		assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());
		assertThat(first.sha256()).isEqualTo(second.sha256());
	}

	@Test
	void compilesOnlyBoundedRealWorksheetFilesWithoutFollowingSymlinks() throws Exception {
		Path worksheet = temporaryDirectory.resolve("completed-worksheet.json");
		Files.write(worksheet, validBytes());

		CompiledJudgments fromFile = ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, worksheet, expectedContext());
		CompiledJudgments fromBytes = ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, validBytes(), expectedContext());
		assertThat(fromFile.canonicalBytes()).isEqualTo(fromBytes.canonicalBytes());
		assertThat(fromFile.sha256()).isEqualTo(fromBytes.sha256());

		Path symbolicLink = temporaryDirectory.resolve("worksheet-link.json");
		Files.createSymbolicLink(symbolicLink, worksheet.getFileName());
		assertThatThrownBy(() -> ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, symbolicLink, expectedContext()))
				.isInstanceOf(java.io.IOException.class)
				.hasMessageContaining("real regular file");
		assertThatThrownBy(() -> ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, temporaryDirectory, expectedContext()))
				.isInstanceOf(java.io.IOException.class)
				.hasMessageContaining("real regular file");

		Path oversized = temporaryDirectory.resolve("oversized-worksheet.json");
		Files.write(
				oversized,
				new byte[ProviderQualityComparativeReviewWorksheet.MAX_INPUT_BYTES + 1]);
		assertThatThrownBy(() -> ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, oversized, expectedContext()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("1048576 bytes");
	}

	@Test
	void requiresExplicitCompletedFieldsAndMustSeparateReview() throws Exception {
		ObjectNode nullGold = validTree();
		candidate(nullGold, 0).putNull("goldPaperKey");
		assertInvalid(nullGold, "goldPaperKey must be a string");

		ObjectNode nullGrade = validTree();
		candidate(nullGrade, 0).putNull("relevanceGrade");
		assertInvalid(nullGrade, "relevanceGrade must be an integer");

		ObjectNode nullExpectedFields = validTree();
		candidate(nullExpectedFields, 0).putNull("expectedFields");
		assertInvalid(nullExpectedFields, "expectedFields must be an array");

		ObjectNode missingExpectedFields = validTree();
		candidate(missingExpectedFields, 0).remove("expectedFields");
		assertInvalid(missingExpectedFields, "Missing keys");

		ObjectNode incompletePairs = validTree();
		query(incompletePairs).put("mustSeparateReviewComplete", false);
		assertInvalid(incompletePairs, "must be true before compilation");

		ObjectNode nullPairCompletion = validTree();
		query(nullPairCompletion).putNull("mustSeparateReviewComplete");
		assertInvalid(nullPairCompletion, "must be a boolean");

		ObjectNode explicitEmptyFieldsAndPairs = validTree();
		pairs(explicitEmptyFieldsAndPairs).removeAll();
		CompiledJudgments result = compile(explicitEmptyFieldsAndPairs);
		assertThat(result.boundJudgments().judgments().queries().getFirst()
				.mustSeparatePairs()).isEmpty();
	}

	@Test
	void rejectsUnknownMissingDuplicateMalformedTrailingAndOversizedInput() throws Exception {
		ObjectNode unknownRoot = validTree();
		unknownRoot.put("provider", "EUROPE_PMC");
		assertInvalid(unknownRoot, "Unknown keys");

		ObjectNode missingRoot = validTree();
		missingRoot.remove("reviewPacketSha256");
		assertInvalid(missingRoot, "Missing keys");

		ObjectNode unknownQuery = validTree();
		query(unknownQuery).put("complete", true);
		assertInvalid(unknownQuery, "Unknown keys");

		ObjectNode unknownCandidate = validTree();
		candidate(unknownCandidate, 0).put("title", "Hidden provider clue");
		assertInvalid(unknownCandidate, "Unknown keys");

		ObjectNode unknownPair = validTree();
		pair(unknownPair, 0).put("notes", "free text");
		assertInvalid(unknownPair, "Unknown keys");

		String json = new String(validBytes(), StandardCharsets.UTF_8);
		String duplicate = json.replaceFirst(
				"\\\"schemaVersion\\\":1",
				"\"schemaVersion\":1,\"schemaVersion\":1");
		assertThatThrownBy(() -> ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, duplicate.getBytes(StandardCharsets.UTF_8), expectedContext()))
				.isInstanceOf(JacksonException.class);
		assertThatThrownBy(() -> ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER,
				(json + "\n{}").getBytes(StandardCharsets.UTF_8),
				expectedContext()))
				.isInstanceOf(JacksonException.class);
		assertThatThrownBy(() -> ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, "{".getBytes(StandardCharsets.UTF_8), expectedContext()))
				.isInstanceOf(JacksonException.class);
		assertThatThrownBy(() -> ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER,
				new byte[ProviderQualityComparativeReviewWorksheet.MAX_INPUT_BYTES + 1],
				expectedContext()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("1048576 bytes");
	}

	@Test
	void rejectsStalePacketAndAnyAttemptToAddHiddenBindings() throws Exception {
		ObjectNode stale = validTree();
		stale.put("reviewPacketSha256", "9".repeat(64));
		assertInvalid(stale, "does not match the expected review context");

		for (String forbidden : List.of(
				"evidenceId",
				"evidenceManifestSha256",
				"querySetId",
				"querySetSha256",
				"scoringPolicyId",
				"scoringPolicySha256")) {
			ObjectNode leaked = validTree();
			leaked.put(forbidden, "forbidden-hidden-binding");
			assertInvalid(leaked, "Unknown keys");
		}
	}

	@Test
	void rejectsMissingExtraDuplicateAndReorderedCandidateRows() throws Exception {
		ObjectNode missing = validTree();
		candidates(missing).remove(0);
		assertInvalid(missing, "exactly retain the expected candidate-key order");

		ObjectNode extra = validTree();
		ObjectNode extraCandidate = candidate(extra, 0).deepCopy();
		extraCandidate.put("candidateKey", CANDIDATE_4);
		candidates(extra).add(extraCandidate);
		assertInvalid(extra, "exactly retain the expected candidate-key order");

		ObjectNode duplicate = validTree();
		candidate(duplicate, 2).put("candidateKey", CANDIDATE_1);
		assertInvalid(duplicate, "duplicate candidateKey");

		ObjectNode reordered = validTree();
		ArrayNode rows = candidates(reordered);
		JsonNode first = rows.get(0).deepCopy();
		JsonNode second = rows.get(1).deepCopy();
		JsonNode third = rows.get(2).deepCopy();
		rows.removeAll();
		rows.add(second).add(first).add(third);
		assertInvalid(reordered, "exactly retain the expected candidate-key order");

		ObjectNode invalidAlias = validTree();
		candidate(invalidAlias, 0).put("candidateKey", REVIEW_C);
		assertInvalid(invalidAlias, "candidate-0001 alias format");
	}

	@Test
	void enforcesGoldKeyGradeAndExpectedFieldContracts() throws Exception {
		ObjectNode unsafeGold = validTree();
		candidate(unsafeGold, 0).put("goldPaperKey", "Relevant Work");
		assertInvalid(unsafeGold, "safe lowercase slug");

		ObjectNode invalidGrade = validTree();
		candidate(invalidGrade, 0).put("relevanceGrade", 4);
		assertInvalid(invalidGrade, "0 through 3");

		ObjectNode nonIntegerGrade = validTree();
		candidate(nonIntegerGrade, 0).put("relevanceGrade", 2.5d);
		assertInvalid(nonIntegerGrade, "must be an integer");

		ObjectNode unsortedFields = validTree();
		ArrayNode unsorted = expectedFields(candidate(unsortedFields, 0));
		unsorted.removeAll();
		unsorted.add("TITLE").add("ABSTRACT");
		assertInvalid(unsortedFields, "sorted unique");

		ObjectNode duplicateFields = validTree();
		expectedFields(candidate(duplicateFields, 0)).add("TITLE");
		assertInvalid(duplicateFields, "sorted unique");

		ObjectNode unknownField = validTree();
		expectedFields(candidate(unknownField, 0)).add("PDF_URL");
		assertInvalid(unknownField, "unsupported field");

		ObjectNode inconsistentGrade = validTree();
		candidate(inconsistentGrade, 2).put("relevanceGrade", 2);
		assertInvalid(inconsistentGrade, "identical relevanceGrade and expectedFields");

		ObjectNode inconsistentFields = validTree();
		expectedFields(candidate(inconsistentFields, 2)).removeAll();
		assertInvalid(inconsistentFields, "identical relevanceGrade and expectedFields");
	}

	@Test
	void enforcesCanonicalUniqueCrossGoldMustSeparatePairs() throws Exception {
		ObjectNode reversed = validTree();
		pair(reversed, 0).put("leftCandidateKey", CANDIDATE_3);
		pair(reversed, 0).put("rightCandidateKey", CANDIDATE_2);
		assertInvalid(reversed, "canonical ascending order");

		ObjectNode missingReview = validTree();
		pair(missingReview, 0).put("rightCandidateKey", CANDIDATE_4);
		assertInvalid(missingReview, "must both belong");

		ObjectNode sameGold = validTree();
		pair(sameGold, 0).put("leftCandidateKey", CANDIDATE_1);
		pair(sameGold, 0).put("rightCandidateKey", CANDIDATE_3);
		assertInvalid(sameGold, "different gold papers");

		ObjectNode duplicate = validTree();
		pairs(duplicate).add(pair(duplicate, 0).deepCopy());
		assertInvalid(duplicate, "duplicate must-separate pair");

		ObjectNode unsafeReason = validTree();
		pair(unsafeReason, 0).put("reasonCode", "free form reason");
		assertInvalid(unsafeReason, "safe uppercase code");

		ObjectNode unordered = validTree();
		ObjectNode later = OBJECT_MAPPER.createObjectNode();
		later.put("leftCandidateKey", CANDIDATE_2);
		later.put("rightCandidateKey", CANDIDATE_3);
		later.put("reasonCode", "DISTINCT_WORKS");
		ObjectNode earlier = OBJECT_MAPPER.createObjectNode();
		earlier.put("leftCandidateKey", CANDIDATE_1);
		earlier.put("rightCandidateKey", CANDIDATE_2);
		earlier.put("reasonCode", "DISTINCT_WORKS");
		pairs(unordered).removeAll();
		pairs(unordered).add(later).add(earlier);
		assertInvalid(unordered, "canonical ascending order");

		ObjectNode translated = validTree();
		pairs(translated).removeAll();
		pairs(translated).add(earlier).add(later);
		List<ProviderQualityComparativeJudgments.MustSeparatePair> compiledPairs =
				compile(translated).boundJudgments().judgments().queries().getFirst()
						.mustSeparatePairs();
		assertThat(compiledPairs).extracting(
				ProviderQualityComparativeJudgments.MustSeparatePair::leftReviewKey,
				ProviderQualityComparativeJudgments.MustSeparatePair::rightReviewKey)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(REVIEW_A, REVIEW_B),
						org.assertj.core.groups.Tuple.tuple(REVIEW_B, REVIEW_C));
	}

	@Test
	void rejectsWrongAttestationAndInvalidExpectedContext() throws Exception {
		ObjectNode wrongAttestation = validTree();
		wrongAttestation.put(
				"independenceAttestation", "AUTHORED_AFTER_REVIEWING_RESULTS");
		assertInvalid(wrongAttestation, "independenceAttestation must be");

		assertThatThrownBy(() -> new ExpectedReviewContext(
				REVIEW_PACKET_SHA,
				"europe-pmc-comparative-contract",
				MANIFEST_SHA,
				"europe-pmc-live-queries-v1",
				QUERY_SET_SHA,
				"provider-comparative-scoring-policy-v1",
				POLICY_SHA,
				List.of(
						new ExpectedQuery(
								"cancer-ml-diagnosis",
								List.of(new ExpectedCandidate(CANDIDATE_1, REVIEW_A))),
						new ExpectedQuery(
								"second-query",
								List.of(new ExpectedCandidate(CANDIDATE_2, REVIEW_A))))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unique across queries");

		assertThatThrownBy(() -> new ExpectedCandidate("candidate-one", REVIEW_A))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidate-0001 alias format");

		assertThatThrownBy(() -> new ExpectedReviewContext(
				REVIEW_PACKET_SHA,
				"europe-pmc-comparative-contract",
				MANIFEST_SHA,
				"europe-pmc-live-queries-v1",
				QUERY_SET_SHA,
				"provider-comparative-scoring-policy-v1",
				POLICY_SHA,
				List.of(new ExpectedQuery(
						"cancer-ml-diagnosis",
						List.of(new ExpectedCandidate(CANDIDATE_2, REVIEW_A))))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("globally sequential");
	}

	private static CompiledJudgments compile(ObjectNode root) throws Exception {
		return ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, OBJECT_MAPPER.writeValueAsBytes(root), expectedContext());
	}

	private static void assertInvalid(ObjectNode root, String message) {
		assertThatThrownBy(() -> compile(root))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(message);
	}

	private static ExpectedReviewContext expectedContext() {
		return new ExpectedReviewContext(
				REVIEW_PACKET_SHA,
				"europe-pmc-comparative-contract",
				MANIFEST_SHA,
				"europe-pmc-live-queries-v1",
				QUERY_SET_SHA,
				"provider-comparative-scoring-policy-v1",
				POLICY_SHA,
				List.of(new ExpectedQuery(
						"cancer-ml-diagnosis",
						List.of(
								new ExpectedCandidate(CANDIDATE_1, REVIEW_C),
								new ExpectedCandidate(CANDIDATE_2, REVIEW_B),
								new ExpectedCandidate(CANDIDATE_3, REVIEW_A)))));
	}

	private static byte[] validBytes() throws Exception {
		return OBJECT_MAPPER.writeValueAsBytes(validTree());
	}

	private static ObjectNode validTree() {
		ObjectNode root = OBJECT_MAPPER.createObjectNode();
		root.put("schemaVersion", 1);
		root.put("protocolId", ProviderQualityComparativeReviewWorksheet.PROTOCOL_ID);
		root.put("reviewPacketSha256", REVIEW_PACKET_SHA);
		root.put(
				"independenceAttestation",
				ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION);
		ObjectNode query = root.putArray("queries").addObject();
		query.put("queryKey", "cancer-ml-diagnosis");
		ArrayNode candidates = query.putArray("candidates");
		addCandidate(candidates, CANDIDATE_1, "relevant-work", 3, "ABSTRACT", "TITLE");
		addCandidate(candidates, CANDIDATE_2, "hard-negative", 0);
		addCandidate(candidates, CANDIDATE_3, "relevant-work", 3, "ABSTRACT", "TITLE");
		query.put("mustSeparateReviewComplete", true);
		ObjectNode pair = query.putArray("mustSeparatePairs").addObject();
		pair.put("leftCandidateKey", CANDIDATE_2);
		pair.put("rightCandidateKey", CANDIDATE_3);
		pair.put("reasonCode", "DISTINCT_WORKS");
		return root;
	}

	private static void addCandidate(
			ArrayNode candidates,
			String candidateKey,
			String goldPaperKey,
			int relevanceGrade,
			String... expectedFieldNames) {
		ObjectNode candidate = candidates.addObject();
		candidate.put("candidateKey", candidateKey);
		candidate.put("goldPaperKey", goldPaperKey);
		candidate.put("relevanceGrade", relevanceGrade);
		ArrayNode expectedFields = candidate.putArray("expectedFields");
		for (String field : expectedFieldNames) {
			expectedFields.add(field);
		}
	}

	private static ObjectNode query(ObjectNode root) {
		return (ObjectNode) root.required("queries").get(0);
	}

	private static ArrayNode candidates(ObjectNode root) {
		return (ArrayNode) query(root).required("candidates");
	}

	private static ObjectNode candidate(ObjectNode root, int index) {
		return (ObjectNode) candidates(root).get(index);
	}

	private static ArrayNode expectedFields(ObjectNode candidate) {
		return (ArrayNode) candidate.required("expectedFields");
	}

	private static ArrayNode pairs(ObjectNode root) {
		return (ArrayNode) query(root).required("mustSeparatePairs");
	}

	private static ObjectNode pair(ObjectNode root, int index) {
		return (ObjectNode) pairs(root).get(index);
	}

	private static List<String> strings(JsonNode array) {
		return array.valueStream().map(JsonNode::asString).toList();
	}
}
