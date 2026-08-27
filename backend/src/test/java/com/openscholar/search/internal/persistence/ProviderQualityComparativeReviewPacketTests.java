package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ProviderQualityComparativeReviewPacketTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final String FIRST_KEY = sha256("first-candidate");
	private static final String SECOND_KEY = sha256("second-candidate");
	private static final String THIRD_KEY = sha256("third-candidate");

	@TempDir
	private Path temporaryDirectory;

	@Test
	void generatesDeterministicOpaquePacketsWithTheExactBlindedPartitionAndOrder()
			throws Exception {
		ProviderQualityComparativeEvidenceBundle bundle = writeAndVerify(
				"comparative-review-packet-valid", true, ignored -> { });
		ProviderQualityLiveQuerySet.BoundQuerySet querySet = boundQuerySet();
		ProviderQualityComparativeScoringPolicy.BoundPolicy policy = boundPolicy();

		ProviderQualityComparativeReviewPacket.Generated first =
				ProviderQualityComparativeReviewPacket.generate(
						OBJECT_MAPPER, bundle, querySet, policy);
		ProviderQualityComparativeReviewPacket.Generated second =
				ProviderQualityComparativeReviewPacket.generate(
						OBJECT_MAPPER, bundle, querySet, policy);

		assertThat(first.reviewPacketBytes()).isEqualTo(second.reviewPacketBytes());
		assertThat(first.reviewPacketSha256()).isEqualTo(second.reviewPacketSha256());
		assertThat(first.reviewPacketBytes()).endsWith(new byte[] {'\n'});
		assertThat(first.reviewPacketSha256()).isEqualTo(sha256(first.reviewPacketBytes()));

		JsonNode packet = OBJECT_MAPPER.readTree(first.reviewPacketBytes());
		assertThat(fields(packet)).containsExactlyInAnyOrder(
				"schemaVersion", "protocolId", "reviewSessionKey", "instructions", "queries");
		assertThat(packet.required("schemaVersion").asInt()).isEqualTo(1);
		assertThat(packet.required("protocolId").asString())
				.isEqualTo(ProviderQualityComparativeReviewPacket.REVIEW_PACKET_PROTOCOL_ID);
		assertThat(packet.required("reviewSessionKey").asString()).isEqualTo(
				ProviderQualityComparativeReviewPacket.reviewSessionKey(
						bundle.manifestSha256(), querySet.sha256(), policy.sha256()));
		assertThat(packet.required("instructions").asString())
				.isEqualTo(ProviderQualityComparativeEvidenceBundle.ELIGIBLE_REVIEW_INSTRUCTIONS);

		JsonNode queries = packet.required("queries");
		assertThat(queries).extracting(query -> query.required("queryKey").asString())
				.containsExactlyElementsOf(queryKeys(querySet));
		assertThat(queries.get(0).required("candidates"))
				.extracting(candidate -> candidate.required("candidateKey").asString())
				.containsExactly("candidate-0001", "candidate-0002");
		assertThat(queries.get(1).required("candidates"))
				.extracting(candidate -> candidate.required("candidateKey").asString())
				.containsExactly("candidate-0003");
		for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
			JsonNode query = queries.get(queryIndex);
			assertThat(fields(query)).containsExactlyInAnyOrder(
					"queryKey", "queryText", "candidates");
			assertThat(query.required("queryText").asString())
					.isEqualTo(querySet.querySet().queries().get(queryIndex).text());
			for (JsonNode candidate : query.required("candidates")) {
				assertThat(fields(candidate)).containsExactlyInAnyOrder(
						"candidateKey", "title", "abstractText", "publicationDate",
						"publicationYear", "documentType", "language", "venueName", "authors");
				for (JsonNode author : candidate.required("authors")) {
					assertThat(fields(author)).containsExactly("displayName");
				}
			}
		}
	}

	@Test
	void emitsOnlyReviewerProjectionAndFailClosedWorksheetPlaceholders() throws Exception {
		ProviderQualityComparativeEvidenceBundle bundle = writeAndVerify(
				"comparative-review-packet-sanitized", true, ignored -> { });
		ProviderQualityComparativeReviewPacket.Generated generated =
				ProviderQualityComparativeReviewPacket.generate(
						OBJECT_MAPPER, bundle, boundQuerySet(), boundPolicy());

		byte[] worksheetBytes = ProviderQualityComparativeReviewPacket.canonicalBytes(
				OBJECT_MAPPER, generated.worksheetSkeleton());
		String packetJson = new String(generated.reviewPacketBytes(), StandardCharsets.UTF_8);
		String worksheetJson = new String(worksheetBytes, StandardCharsets.UTF_8);
		for (String forbiddenValue : List.of(
				bundle.evidenceId(),
				boundQuerySet().querySet().querySetId(),
				boundQuerySet().sha256(),
				boundPolicy().policy().policyId(),
				boundPolicy().sha256(),
				bundle.manifestSha256(),
				FIRST_KEY,
				SECOND_KEY,
				THIRD_KEY,
				"SECRET-PROVIDER-RECORD-ID",
				"SECRET-DOI",
				"https://secret.invalid/source",
				"SECRET-RECONCILIATION-CLUSTER",
				"OPENALEX_ONLY_SENTINEL")) {
			assertThat(packetJson).doesNotContain(forbiddenValue);
			assertThat(worksheetJson).doesNotContain(forbiddenValue);
		}
		assertThat(packetJson).doesNotContain(
				"reviewKey", "corresponding", "position", "evidenceId",
				"evidenceManifestSha256",
				"querySetId", "querySetSha256", "scoringPolicyId", "scoringPolicySha256",
				"providerRank", "providerRecordId", "citationCount", "identifiers",
				"sourceUrl", "reportedOpenAccess", "scenarios", "europe-pmc");
		assertThat(worksheetJson).doesNotContain(
				"reviewKey", "corresponding", "evidenceId", "evidenceManifestSha256",
				"querySetId", "querySetSha256", "scoringPolicyId", "scoringPolicySha256",
				"title", "abstractText", "providerRank", "providerRecordId", "citationCount",
				"identifiers", "sourceUrl", "reportedOpenAccess", "scenarios", "europe-pmc");

		JsonNode worksheet = OBJECT_MAPPER.readTree(worksheetBytes);
		assertThat(fields(worksheet)).containsExactlyInAnyOrder(
				"schemaVersion", "protocolId", "reviewPacketSha256",
				"independenceAttestation", "queries");
		assertThat(worksheet.required("schemaVersion").asInt()).isEqualTo(1);
		assertThat(worksheet.required("protocolId").asString())
				.isEqualTo(ProviderQualityComparativeReviewPacket.WORKSHEET_PROTOCOL_ID);
		assertThat(worksheet.required("reviewPacketSha256").asString())
				.isEqualTo(generated.reviewPacketSha256());
		assertThat(worksheet.required("independenceAttestation").isNull()).isTrue();

		List<String> worksheetKeys = new ArrayList<>();
		for (JsonNode query : worksheet.required("queries")) {
			assertThat(fields(query)).containsExactlyInAnyOrder(
					"queryKey", "candidates", "mustSeparateReviewComplete",
					"mustSeparatePairs");
			assertThat(query.required("mustSeparateReviewComplete").asBoolean()).isFalse();
			assertThat(query.required("mustSeparatePairs")).isEmpty();
			for (JsonNode candidate : query.required("candidates")) {
				assertThat(fields(candidate)).containsExactlyInAnyOrder(
						"candidateKey", "goldPaperKey", "relevanceGrade", "expectedFields");
				worksheetKeys.add(candidate.required("candidateKey").asString());
				assertThat(candidate.required("goldPaperKey").isNull()).isTrue();
				assertThat(candidate.required("relevanceGrade").isNull()).isTrue();
				assertThat(candidate.required("expectedFields").isNull()).isTrue();
			}
		}
		assertThat(worksheetKeys).containsExactly(
				"candidate-0001", "candidate-0002", "candidate-0003");
		ProviderQualityComparativeReviewWorksheet.ExpectedReviewContext context =
				generated.expectedReviewContext();
		assertThat(context.reviewPacketSha256()).isEqualTo(generated.reviewPacketSha256());
		assertThat(context.queries()).extracting(
				ProviderQualityComparativeReviewWorksheet.ExpectedQuery::queryKey)
				.containsExactlyElementsOf(queryKeys(boundQuerySet()));
		assertThat(context.queries().stream()
				.flatMap(query -> query.orderedCandidates().stream())
				.map(ProviderQualityComparativeReviewWorksheet.ExpectedCandidate::candidateKey))
				.containsExactly("candidate-0001", "candidate-0002", "candidate-0003");
		assertThat(context.queries().stream()
				.flatMap(query -> query.orderedCandidates().stream())
				.map(ProviderQualityComparativeReviewWorksheet.ExpectedCandidate::reviewKey))
				.containsExactly(
						sortedReviewKeys(bundle.evidenceId(), List.of(FIRST_KEY, SECOND_KEY)).get(0),
						sortedReviewKeys(bundle.evidenceId(), List.of(FIRST_KEY, SECOND_KEY)).get(1),
						THIRD_KEY);
		assertThatThrownBy(() -> ProviderQualityComparativeReviewWorksheet.compile(
				OBJECT_MAPPER, worksheetBytes, context))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("independenceAttestation must be");

		ObjectNode completed = (ObjectNode) worksheet.deepCopy();
		completed.put(
				"independenceAttestation",
				ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION);
		int goldIndex = 0;
		for (JsonNode query : completed.required("queries")) {
			((ObjectNode) query).put("mustSeparateReviewComplete", true);
			for (JsonNode candidate : query.required("candidates")) {
				ObjectNode row = (ObjectNode) candidate;
				row.put("goldPaperKey", "reviewed-work-" + ++goldIndex);
				row.put("relevanceGrade", 0);
				row.set("expectedFields", OBJECT_MAPPER.createArrayNode());
			}
		}
		ProviderQualityComparativeReviewWorksheet.CompiledJudgments compiled =
				ProviderQualityComparativeReviewWorksheet.compile(
						OBJECT_MAPPER,
						ProviderQualityComparativeReviewPacket.canonicalBytes(
								OBJECT_MAPPER, completed),
						context);
		assertThat(compiled.boundJudgments().judgments().queries())
				.extracting(ProviderQualityComparativeJudgments.QueryJudgments::queryKey)
				.containsExactlyElementsOf(queryKeys(boundQuerySet()));
		assertThat(compiled.boundJudgments().judgments().queries().stream()
				.flatMap(query -> query.goldPapers().stream())
				.flatMap(goldPaper -> goldPaper.reviewKeys().stream()))
				.containsExactlyInAnyOrder(FIRST_KEY, SECOND_KEY, THIRD_KEY);
	}

	@Test
	void normalizesLanguageAliasesAndFallsBackWithoutLeakingSourceVocabulary()
			throws Exception {
		ProviderQualityComparativeEvidenceBundle alpha2 = writeAndVerify(
				"comparative-review-language-alpha2", true, ignored -> { });
		ProviderQualityComparativeEvidenceBundle alpha3 = writeAndVerify(
				"comparative-review-language-alpha3",
				true,
				artifacts -> setFirstCandidateLanguage(artifacts, "eng"));
		ProviderQualityComparativeEvidenceBundle unknown = writeAndVerify(
				"comparative-review-language-unknown",
				true,
				artifacts -> setFirstCandidateLanguage(artifacts, "English (US)"));

		assertThat(firstCandidateLanguage(alpha2)).isEqualTo("eng");
		assertThat(firstCandidateLanguage(alpha3)).isEqualTo(
				firstCandidateLanguage(alpha2));
		assertThat(firstCandidateLanguage(unknown)).isEqualTo("und");

		Map<String, String> bibliographicAliases = Map.ofEntries(
				Map.entry("alb", "sqi"),
				Map.entry("arm", "hye"),
				Map.entry("baq", "eus"),
				Map.entry("bur", "mya"),
				Map.entry("chi", "zho"),
				Map.entry("cze", "ces"),
				Map.entry("dut", "nld"),
				Map.entry("fre", "fra"),
				Map.entry("geo", "kat"),
				Map.entry("ger", "deu"),
				Map.entry("gre", "ell"),
				Map.entry("ice", "isl"),
				Map.entry("mac", "mkd"),
				Map.entry("mao", "mri"),
				Map.entry("may", "msa"),
				Map.entry("per", "fas"),
				Map.entry("rum", "ron"),
				Map.entry("slo", "slk"),
				Map.entry("tib", "bod"),
				Map.entry("wel", "cym"));
		for (Map.Entry<String, String> alias : bibliographicAliases.entrySet()) {
			ProviderQualityComparativeEvidenceBundle aliasBundle = writeAndVerify(
					"comparative-review-language-" + alias.getKey(),
					true,
					artifacts -> setFirstCandidateLanguage(artifacts, alias.getKey()));
			assertThat(firstCandidateLanguage(aliasBundle))
					.as("bibliographic language alias %s", alias.getKey())
					.isEqualTo(alias.getValue());
		}
	}

	@Test
	void derivesAnOpaqueDomainSeparatedSessionBinding() {
		String evidenceDigest = "1".repeat(64);
		String queryDigest = "2".repeat(64);
		String policyDigest = "3".repeat(64);
		String session = ProviderQualityComparativeReviewPacket.reviewSessionKey(
				evidenceDigest, queryDigest, policyDigest);

		assertThat(session).matches("[0-9a-f]{64}");
		assertThat(ProviderQualityComparativeReviewPacket.reviewSessionKey(
				evidenceDigest, queryDigest, policyDigest)).isEqualTo(session);
		assertThat(ProviderQualityComparativeReviewPacket.reviewSessionKey(
				"4".repeat(64), queryDigest, policyDigest)).isNotEqualTo(session);
		assertThat(session).doesNotContain(evidenceDigest, queryDigest, policyDigest);
	}

	@Test
	void verifiesOnlyTheExactImmutableRealReviewPacketFile() throws Exception {
		ProviderQualityComparativeEvidenceBundle bundle = writeAndVerify(
				"comparative-review-packet-file", true, ignored -> { });
		ProviderQualityComparativeReviewPacket.Generated generated =
				ProviderQualityComparativeReviewPacket.generate(
						OBJECT_MAPPER, bundle, boundQuerySet(), boundPolicy());
		Path exact = temporaryDirectory.resolve("review-packet-exact.json");
		Files.write(exact, generated.reviewPacketBytes());

		ProviderQualityComparativeReviewPacket.verifyReviewedPacket(exact, generated);

		byte[] modifiedBytes = generated.reviewPacketBytes();
		modifiedBytes[0] = modifiedBytes[0] == '{' ? (byte) '[' : (byte) '{';
		Path modified = temporaryDirectory.resolve("review-packet-modified.json");
		Files.write(modified, modifiedBytes);
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.verifyReviewedPacket(
				modified, generated))
				.isInstanceOf(IOException.class)
				.hasMessage("review packet does not match the immutable generated packet");

		Path symlink = temporaryDirectory.resolve("review-packet-link.json");
		Files.createSymbolicLink(symlink, exact.getFileName());
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.verifyReviewedPacket(
				symlink, generated))
				.isInstanceOf(IOException.class)
				.hasMessage("review packet must be a real regular file");

		Path oversized = temporaryDirectory.resolve("review-packet-oversized.json");
		try (FileChannel channel = FileChannel.open(
				oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			channel.position(ProviderQualityComparativeReviewPacket.MAXIMUM_REVIEW_PACKET_BYTES);
			channel.write(ByteBuffer.wrap(new byte[] {0}));
		}
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.verifyReviewedPacket(
				oversized, generated))
				.isInstanceOf(IOException.class)
				.hasMessage("review packet exceeds the frozen byte limit");
	}

	@Test
	void rejectsIncompleteBundlesAndAnyNonBlindedCandidateField() throws Exception {
		ProviderQualityComparativeEvidenceBundle incomplete = writeAndVerify(
				"comparative-review-packet-incomplete", false, ignored -> { });
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.generate(
				OBJECT_MAPPER, incomplete, boundQuerySet(), boundPolicy()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("evidence bundle is not review-ready");

		ProviderQualityComparativeEvidenceBundle contaminated = writeAndVerify(
				"comparative-review-packet-contaminated",
				true,
				artifacts -> {
					Map<String, Object> blinded = castMap(artifacts.get("blinded-candidates.json"));
					List<Map<String, Object>> candidates = castList(blinded.get("candidates"));
					candidates.get(0).put("providerRank", 1);
				});
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.generate(
				OBJECT_MAPPER, contaminated, boundQuerySet(), boundPolicy()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must contain exactly the frozen fields");
	}

	@Test
	void rejectsOrderAndSummaryPartitionMismatchesBeforeProducingAWorksheet()
			throws Exception {
		ProviderQualityComparativeEvidenceBundle wrongOrder = writeAndVerify(
				"comparative-review-packet-wrong-order",
				true,
				artifacts -> {
					Map<String, Object> blinded = castMap(artifacts.get("blinded-candidates.json"));
					List<Map<String, Object>> candidates = castList(blinded.get("candidates"));
					Map<String, Object> first = candidates.get(0);
					candidates.set(0, candidates.get(1));
					candidates.set(1, first);
				});
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.generate(
				OBJECT_MAPPER, wrongOrder, boundQuerySet(), boundPolicy()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("evidence-scoped order");

		ProviderQualityComparativeEvidenceBundle wrongCount = writeAndVerify(
				"comparative-review-packet-wrong-count",
				true,
				artifacts -> {
					Map<String, Object> summary = castMap(artifacts.get("summary.json"));
					List<Map<String, Object>> queries = castList(summary.get("queries"));
					queries.get(0).put("rawCandidateCount", 1);
				});
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.generate(
				OBJECT_MAPPER, wrongCount, boundQuerySet(), boundPolicy()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("summary candidate count does not match blinded candidates");
	}

	@Test
	void rejectsSubstitutedQuerySetObjectsAndStaleSummaryDigests() throws Exception {
		ProviderQualityComparativeEvidenceBundle valid = writeAndVerify(
				"comparative-review-packet-query-object", true, ignored -> { });
		ProviderQualityLiveQuerySet.BoundQuerySet frozen = boundQuerySet();
		List<ProviderQualityLiveQuerySet.Query> changedQueries = new ArrayList<>(
				frozen.querySet().queries());
		ProviderQualityLiveQuerySet.Query original = changedQueries.get(0);
		changedQueries.set(0, new ProviderQualityLiveQuerySet.Query(
				original.key(), original.text() + " substituted"));
		ProviderQualityLiveQuerySet substituted = new ProviderQualityLiveQuerySet(
				frozen.querySet().schemaVersion(),
				frozen.querySet().querySetId(),
				frozen.querySet().sourcePolicy(),
				frozen.querySet().pageSize(),
				changedQueries);
		ProviderQualityLiveQuerySet.BoundQuerySet forged =
				new ProviderQualityLiveQuerySet.BoundQuerySet(substituted, frozen.sha256());
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.generate(
				OBJECT_MAPPER, valid, forged, boundPolicy()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("query set must be the exact frozen provider-quality query set");

		ProviderQualityComparativeScoringPolicy.BoundPolicy frozenPolicy = boundPolicy();
		ProviderQualityComparativeScoringPolicy.BoundPolicy forgedPolicy =
				new ProviderQualityComparativeScoringPolicy.BoundPolicy(
						frozenPolicy.policy(), "3".repeat(64));
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.generate(
				OBJECT_MAPPER, valid, frozen, forgedPolicy))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("referenced scoring policy SHA-256 does not match");

		ProviderQualityComparativeEvidenceBundle staleDigest = writeAndVerify(
				"comparative-review-packet-stale-query-digest",
				true,
				artifacts -> {
					Map<String, Object> summary = castMap(artifacts.get("summary.json"));
					Map<String, Object> querySet = new LinkedHashMap<>(
							castMap(summary.get("querySet")));
					querySet.put("sha256", "2".repeat(64));
					summary.put("querySet", querySet);
				});
		assertThatThrownBy(() -> ProviderQualityComparativeReviewPacket.generate(
				OBJECT_MAPPER, staleDigest, frozen, boundPolicy()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("summary query-set SHA-256 does not match the bound query set");
	}

	private ProviderQualityComparativeEvidenceBundle writeAndVerify(
			String evidenceId,
			boolean eligible,
			Consumer<Map<String, Object>> mutation) throws Exception {
		Map<String, Object> artifacts = validArtifacts(evidenceId, eligible);
		mutation.accept(artifacts);
		ProviderQualityEvidenceWriter writer = ProviderQualityEvidenceWriter.forRepository(
				OBJECT_MAPPER,
				temporaryDirectory,
				ProviderQualityComparativeEvidenceBundle.MAXIMUM_PAYLOAD_BYTES);
		Path directory = writer.write(evidenceId, artifacts).directory();
		return ProviderQualityComparativeEvidenceBundle.verify(OBJECT_MAPPER, directory);
	}

	private static Map<String, Object> validArtifacts(String evidenceId, boolean eligible)
			throws Exception {
		ProviderQualityLiveQuerySet.BoundQuerySet boundQuerySet = boundQuerySet();
		ProviderQualityLiveQuerySet querySetDefinition = boundQuerySet.querySet();
		List<String> queryKeys = queryKeys(boundQuerySet);
		String firstQuery = queryKeys.get(0);
		String secondQuery = queryKeys.get(1);
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("schemaVersion", 2);
		summary.put("evidenceType", "LIVE_COMPARATIVE_METADATA_CAPTURE");
		summary.put("evidenceId", evidenceId);
		summary.put("measuredAt", "2026-08-26T00:00:00Z");
		summary.put("repositoryRevision", "a".repeat(40));
		summary.put("querySet", Map.of(
				"id", querySetDefinition.querySetId(),
				"sha256", boundQuerySet.sha256(),
				"sourcePolicy", querySetDefinition.sourcePolicy(),
				"pageSize", querySetDefinition.pageSize()));
		summary.put("providerConfiguration", Map.of(
				"providerSentinel", "OPENALEX_ONLY_SENTINEL"));
		summary.put("boundaries", Map.of("metadataOnly", true));
		summary.put("qualityReviewEligible", eligible);
		summary.put("providerRequests", Map.of("OPENALEX", 2));
		summary.put("providerFailures", Map.of("OPENALEX", 0));
		List<Map<String, Object>> summaryQueries = new ArrayList<>();
		for (int index = 0; index < queryKeys.size(); index++) {
			summaryQueries.add(querySummary(
					queryKeys.get(index), index == 0 ? 2 : index == 1 ? 1 : 0, eligible));
		}
		summary.put("queries", summaryQueries);

		List<Map<String, Object>> candidates = new ArrayList<>();
		for (String reviewKey : sortedReviewKeys(evidenceId, List.of(FIRST_KEY, SECOND_KEY))) {
			candidates.add(candidate(reviewKey, firstQuery));
		}
		candidates.add(candidate(THIRD_KEY, secondQuery));
		Map<String, Object> blinded = new LinkedHashMap<>();
		blinded.put("schemaVersion", 2);
		blinded.put("evidenceId", evidenceId);
		blinded.put("qualityReviewEligible", eligible);
		blinded.put(
				"instructions",
				eligible
						? ProviderQualityComparativeEvidenceBundle.ELIGIBLE_REVIEW_INSTRUCTIONS
						: "Do not label this incomplete capture; inspect summary.json and repeat the isolated run.");
		blinded.put("candidates", candidates);

		Map<String, Object> provenance = new LinkedHashMap<>();
		provenance.put("schemaVersion", 2);
		provenance.put("evidenceId", evidenceId);
		provenance.put("providerRecordId", "SECRET-PROVIDER-RECORD-ID");
		provenance.put("identifiers", List.of("SECRET-DOI"));
		provenance.put("sourceUrl", "https://secret.invalid/source");
		provenance.put("citationCount", 8_675_309);
		provenance.put("reportedOpenAccess", true);
		provenance.put("candidates", List.of());

		Map<String, Object> reconciliation = new LinkedHashMap<>();
		reconciliation.put("schemaVersion", 2);
		reconciliation.put("evidenceId", evidenceId);
		reconciliation.put("clusterKey", "SECRET-RECONCILIATION-CLUSTER");
		reconciliation.put("scenarios", List.of("OPENALEX_ONLY_SENTINEL"));
		reconciliation.put("queries", List.of());

		Map<String, Object> artifacts = new LinkedHashMap<>();
		artifacts.put("summary.json", summary);
		artifacts.put("blinded-candidates.json", blinded);
		artifacts.put("provenance-map.json", provenance);
		artifacts.put("reconciliation-trace.json", reconciliation);
		return artifacts;
	}

	private static Map<String, Object> querySummary(
			String queryKey, int candidateCount, boolean complete) {
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("queryKey", queryKey);
		query.put("complete", complete);
		query.put("rawCandidateCount", candidateCount);
		query.put("providerCalls", List.of());
		query.put("scenarioResultCounts", Map.of());
		return query;
	}

	private static Map<String, Object> candidate(String reviewKey, String queryKey) {
		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("reviewKey", reviewKey);
		candidate.put("queryKey", queryKey);
		candidate.put("title", "Reviewer-visible title");
		candidate.put("abstractText", "Reviewer-visible abstract");
		candidate.put("publicationDate", "2025-04-03");
		candidate.put("publicationYear", 2025);
		candidate.put("documentType", "ARTICLE");
		candidate.put("language", "en");
		candidate.put("venueName", "Journal of Visible Metadata");
		candidate.put("authors", List.of(Map.of(
				"displayName", "Ada Reviewer",
				"position", 0,
				"corresponding", true)));
		return candidate;
	}

	private static void setFirstCandidateLanguage(
			Map<String, Object> artifacts, String language) {
		Map<String, Object> blinded = castMap(artifacts.get("blinded-candidates.json"));
		castList(blinded.get("candidates")).get(0).put("language", language);
	}

	private static String firstCandidateLanguage(
			ProviderQualityComparativeEvidenceBundle bundle) throws Exception {
		ProviderQualityComparativeReviewPacket.Generated generated =
				ProviderQualityComparativeReviewPacket.generate(
						OBJECT_MAPPER, bundle, boundQuerySet(), boundPolicy());
		JsonNode queries = OBJECT_MAPPER.readTree(generated.reviewPacketBytes())
				.required("queries");
		for (JsonNode query : queries) {
			if (!query.required("candidates").isEmpty()) {
				return query.required("candidates").get(0).required("language").asString();
			}
		}
		throw new AssertionError("fixture must contain a review candidate");
	}

	private static List<String> sortedReviewKeys(String evidenceId, List<String> reviewKeys) {
		return reviewKeys.stream()
				.sorted(Comparator.comparing(reviewKey ->
						ProviderQualityComparativeEvidenceBundle.blindedOrderingKey(
								evidenceId, reviewKey)))
				.toList();
	}

	private static Set<String> fields(JsonNode node) {
		return new LinkedHashSet<>(node.propertyNames());
	}

	private static ProviderQualityComparativeScoringPolicy.BoundPolicy boundPolicy()
			throws Exception {
		return ProviderQualityComparativeScoringPolicy.loadBound(
				OBJECT_MAPPER, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);
	}

	private static ProviderQualityLiveQuerySet.BoundQuerySet boundQuerySet()
			throws Exception {
		return ProviderQualityLiveQuerySet.loadFrozen(OBJECT_MAPPER);
	}

	private static List<String> queryKeys(
			ProviderQualityLiveQuerySet.BoundQuerySet boundQuerySet) {
		return boundQuerySet.querySet().queries().stream()
				.map(ProviderQualityLiveQuerySet.Query::key)
				.toList();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> castList(Object value) {
		return (List<Map<String, Object>>) value;
	}

	private static String sha256(String value) {
		return sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

}
