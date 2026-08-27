package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeJudgments.BoundJudgments;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeJudgments.MustSeparatePair;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict compiler from a completed, alias-only review worksheet to the canonical
 * independent-judgment packet consumed by the offline comparative scorer.
 * Hidden evidence bindings and review keys come only from the trusted expected
 * context and are never required in the reviewer-authored file.
 */
final class ProviderQualityComparativeReviewWorksheet {

	static final int MAX_INPUT_BYTES = ProviderQualityComparativeJudgments.MAX_INPUT_BYTES;
	static final String PROTOCOL_ID = "provider-quality-comparative-review-worksheet-v1";

	private static final Pattern SAFE_SLUG =
			Pattern.compile("^[a-z0-9][a-z0-9-]{1,126}[a-z0-9]$");
	private static final Pattern QUERY_LOCAL_SLUG =
			Pattern.compile("^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$");
	private static final Pattern CANDIDATE_KEY =
			Pattern.compile("^candidate-[0-9]{4}$");
	private static final Pattern REASON_CODE =
			Pattern.compile("^[A-Z][A-Z0-9_]{1,78}[A-Z0-9]$");
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion",
			"protocolId",
			"reviewPacketSha256",
			"independenceAttestation",
			"queries");
	private static final Set<String> QUERY_FIELDS = Set.of(
			"queryKey", "candidates", "mustSeparateReviewComplete", "mustSeparatePairs");
	private static final Set<String> CANDIDATE_FIELDS = Set.of(
			"candidateKey", "goldPaperKey", "relevanceGrade", "expectedFields");
	private static final Set<String> MUST_SEPARATE_FIELDS = Set.of(
			"leftCandidateKey", "rightCandidateKey", "reasonCode");

	private ProviderQualityComparativeReviewWorksheet() {
	}

	static CompiledJudgments compile(
			ObjectMapper objectMapper,
			Path worksheetPath,
			ExpectedReviewContext expectedContext) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Path path = Objects.requireNonNull(worksheetPath, "worksheetPath");
		Objects.requireNonNull(expectedContext, "expectedContext");
		if (Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("review worksheet must be a real regular file");
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			return compile(
					objectMapper, input.readNBytes(MAX_INPUT_BYTES + 1), expectedContext);
		}
	}

	static CompiledJudgments compile(
			ObjectMapper objectMapper,
			byte[] worksheetBytes,
			ExpectedReviewContext expectedContext) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(worksheetBytes, "worksheetBytes");
		ExpectedReviewContext expected = Objects.requireNonNull(
				expectedContext, "expectedContext");
		if (worksheetBytes.length < 1 || worksheetBytes.length > MAX_INPUT_BYTES) {
			throw new IllegalArgumentException(
					"review worksheet must contain 1 through " + MAX_INPUT_BYTES + " bytes");
		}

		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(worksheetBytes);
		Worksheet worksheet = parse(root);
		validateBindingsAndOrder(worksheet, expected);

		ObjectNode canonicalTree = buildJudgmentTree(objectMapper, worksheet, expected);
		ObjectWriter canonicalWriter = objectMapper.writer()
				.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
				.without(SerializationFeature.INDENT_OUTPUT);
		byte[] serialized = canonicalWriter.writeValueAsBytes(canonicalTree);
		byte[] canonicalBytes = Arrays.copyOf(serialized, serialized.length + 1);
		canonicalBytes[canonicalBytes.length - 1] = '\n';
		BoundJudgments bound = ProviderQualityComparativeJudgments.parseBound(
				objectMapper, canonicalBytes);
		return new CompiledJudgments(
				canonicalBytes,
				bound.sha256(),
				sha256(worksheetBytes),
				bound);
	}

	private static Worksheet parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		int schemaVersion = requireInteger(root.required("schemaVersion"), "$.schemaVersion");
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("$.schemaVersion must be 1");
		}
		String protocolId = requireString(root.required("protocolId"), "$.protocolId");
		if (!PROTOCOL_ID.equals(protocolId)) {
			throw new IllegalArgumentException("$.protocolId must be " + PROTOCOL_ID);
		}
		String attestation = requireString(
				root.required("independenceAttestation"), "$.independenceAttestation");
		if (!ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION.equals(attestation)) {
			throw new IllegalArgumentException(
					"$.independenceAttestation must be "
							+ ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION);
		}

		JsonNode queryNodes = requireArray(root.required("queries"), "$.queries");
		if (queryNodes.isEmpty()
				|| queryNodes.size() > ProviderQualityComparativeJudgments.MAX_QUERIES) {
			throw new IllegalArgumentException(
					"$.queries must contain 1 through "
							+ ProviderQualityComparativeJudgments.MAX_QUERIES + " entries");
		}
		List<WorksheetQuery> queries = new ArrayList<>(queryNodes.size());
		for (int index = 0; index < queryNodes.size(); index++) {
			queries.add(parseQuery(queryNodes.get(index), index));
		}
		return new Worksheet(
				requireString(root.required("reviewPacketSha256"), "$.reviewPacketSha256"),
				attestation,
				queries);
	}

	private static WorksheetQuery parseQuery(JsonNode node, int queryIndex) {
		String path = "$.queries[" + queryIndex + "]";
		requireExactObject(node, path, QUERY_FIELDS);
		JsonNode candidateNodes = requireArray(node.required("candidates"), path + ".candidates");
		if (candidateNodes.size() > ProviderQualityComparativeJudgments.MAX_CANDIDATES_PER_QUERY) {
			throw new IllegalArgumentException(
					path + ".candidates exceeds "
							+ ProviderQualityComparativeJudgments.MAX_CANDIDATES_PER_QUERY
							+ " candidates");
		}
		List<Candidate> candidates = new ArrayList<>(candidateNodes.size());
		for (int index = 0; index < candidateNodes.size(); index++) {
			candidates.add(parseCandidate(
					candidateNodes.get(index), path + ".candidates[" + index + "]"));
		}

		boolean mustSeparateReviewComplete = requireBoolean(
				node.required("mustSeparateReviewComplete"),
				path + ".mustSeparateReviewComplete");
		if (!mustSeparateReviewComplete) {
			throw new IllegalArgumentException(
					path + ".mustSeparateReviewComplete must be true before compilation");
		}
		JsonNode pairNodes = requireArray(
				node.required("mustSeparatePairs"), path + ".mustSeparatePairs");
		int maximumPairs = candidates.size() * (candidates.size() - 1) / 2;
		if (pairNodes.size() > maximumPairs) {
			throw new IllegalArgumentException(
					path + ".mustSeparatePairs exceeds the number of candidate pairs");
		}
		List<WorksheetMustSeparatePair> pairs = new ArrayList<>(pairNodes.size());
		for (int index = 0; index < pairNodes.size(); index++) {
			pairs.add(parseMustSeparatePair(
					pairNodes.get(index), path + ".mustSeparatePairs[" + index + "]"));
		}
		return new WorksheetQuery(
				requireString(node.required("queryKey"), path + ".queryKey"),
				candidates,
				pairs);
	}

	private static Candidate parseCandidate(JsonNode node, String path) {
		requireExactObject(node, path, CANDIDATE_FIELDS);
		JsonNode expectedFieldNodes = requireArray(
				node.required("expectedFields"), path + ".expectedFields");
		List<MetadataField> expectedFields = new ArrayList<>(expectedFieldNodes.size());
		for (int index = 0; index < expectedFieldNodes.size(); index++) {
			String name = requireString(
					expectedFieldNodes.get(index), path + ".expectedFields[" + index + "]");
			try {
				expectedFields.add(MetadataField.valueOf(name));
			}
			catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException(
						path + ".expectedFields contains unsupported field " + name,
						exception);
			}
		}
		return new Candidate(
				requireString(node.required("candidateKey"), path + ".candidateKey"),
				requireString(node.required("goldPaperKey"), path + ".goldPaperKey"),
				requireInteger(node.required("relevanceGrade"), path + ".relevanceGrade"),
				expectedFields);
	}

	private static WorksheetMustSeparatePair parseMustSeparatePair(
			JsonNode node, String path) {
		requireExactObject(node, path, MUST_SEPARATE_FIELDS);
		return new WorksheetMustSeparatePair(
				requireString(
						node.required("leftCandidateKey"), path + ".leftCandidateKey"),
				requireString(
						node.required("rightCandidateKey"), path + ".rightCandidateKey"),
				requireString(node.required("reasonCode"), path + ".reasonCode"));
	}

	private static void validateBindingsAndOrder(
			Worksheet worksheet, ExpectedReviewContext expected) {
		requireEqual(worksheet.reviewPacketSha256(), expected.reviewPacketSha256(),
				"reviewPacketSha256");
		if (worksheet.queries().size() != expected.queries().size()) {
			throw new IllegalArgumentException(
					"worksheet query count does not match the expected review context");
		}
		for (int queryIndex = 0; queryIndex < worksheet.queries().size(); queryIndex++) {
			WorksheetQuery query = worksheet.queries().get(queryIndex);
			ExpectedQuery expectedQuery = expected.queries().get(queryIndex);
			if (!query.queryKey().equals(expectedQuery.queryKey())) {
				throw new IllegalArgumentException(
						"worksheet queries must retain expected order at index " + queryIndex);
			}
			List<String> actualCandidateKeys = query.candidates().stream()
					.map(Candidate::candidateKey)
					.toList();
			List<String> expectedCandidateKeys = expectedQuery.orderedCandidates().stream()
					.map(ExpectedCandidate::candidateKey)
					.toList();
			if (!actualCandidateKeys.equals(expectedCandidateKeys)) {
				throw new IllegalArgumentException(
						"query " + query.queryKey()
								+ " candidates must exactly retain the expected candidate-key order");
			}
		}
	}

	private static ObjectNode buildJudgmentTree(
			ObjectMapper objectMapper,
			Worksheet worksheet,
			ExpectedReviewContext expected) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("schemaVersion", 2);
		root.put("protocolId", ProviderQualityComparativeJudgments.PROTOCOL_ID);
		root.put("reviewPacketSha256", worksheet.reviewPacketSha256());
		root.put("evidenceId", expected.evidenceId());
		root.put("evidenceManifestSha256", expected.evidenceManifestSha256());
		root.put("querySetId", expected.querySetId());
		root.put("querySetSha256", expected.querySetSha256());
		root.put("scoringPolicyId", expected.scoringPolicyId());
		root.put("scoringPolicySha256", expected.scoringPolicySha256());
		root.put("independenceAttestation", worksheet.independenceAttestation());
		ArrayNode queryNodes = root.putArray("queries");
		for (int queryIndex = 0; queryIndex < worksheet.queries().size(); queryIndex++) {
			WorksheetQuery query = worksheet.queries().get(queryIndex);
			ExpectedQuery expectedQuery = expected.queries().get(queryIndex);
			Map<String, String> reviewKeyByCandidateKey = new LinkedHashMap<>();
			expectedQuery.orderedCandidates().forEach(candidate ->
					reviewKeyByCandidateKey.put(
							candidate.candidateKey(), candidate.reviewKey()));
			ObjectNode queryNode = queryNodes.addObject();
			queryNode.put("queryKey", query.queryKey());
			ArrayNode goldNodes = queryNode.putArray("goldPapers");
			for (GoldGroup group : query.groupsByKey(reviewKeyByCandidateKey).values()) {
				ObjectNode goldNode = goldNodes.addObject();
				goldNode.put("goldPaperKey", group.goldPaperKey());
				ArrayNode reviewKeyNodes = goldNode.putArray("reviewKeys");
				group.reviewKeys().forEach(reviewKeyNodes::add);
				goldNode.put("relevanceGrade", group.relevanceGrade());
				ArrayNode expectedFieldNodes = goldNode.putArray("expectedFields");
				group.expectedFields().stream()
						.map(MetadataField::name)
						.forEach(expectedFieldNodes::add);
			}
			ArrayNode pairNodes = queryNode.putArray("mustSeparatePairs");
			for (MustSeparatePair pair : query.translatedPairs(reviewKeyByCandidateKey)) {
				ObjectNode pairNode = pairNodes.addObject();
				pairNode.put("leftReviewKey", pair.leftReviewKey());
				pairNode.put("rightReviewKey", pair.rightReviewKey());
				pairNode.put("reasonCode", pair.reasonCode());
			}
		}
		return root;
	}

	private static void requireEqual(String actual, String expected, String field) {
		if (!actual.equals(expected)) {
			throw new IllegalArgumentException(
					"worksheet " + field + " does not match the expected review context");
		}
	}

	private static void requireExactObject(JsonNode node, String path, Set<String> expected) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		Set<String> unknown = new LinkedHashSet<>(actual);
		unknown.removeAll(expected);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown keys at " + path + ": " + unknown);
		}
		Set<String> missing = new LinkedHashSet<>(expected);
		missing.removeAll(actual);
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Missing keys at " + path + ": " + missing);
		}
	}

	private static JsonNode requireArray(JsonNode node, String path) {
		if (node == null || !node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return node;
	}

	private static String requireString(JsonNode node, String path) {
		if (node == null || !node.isString()) {
			throw new IllegalArgumentException(path + " must be a string");
		}
		return node.asString();
	}

	private static int requireInteger(JsonNode node, String path) {
		if (node == null || !node.isInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
		return node.asInt();
	}

	private static boolean requireBoolean(JsonNode node, String path) {
		if (node == null || !node.isBoolean()) {
			throw new IllegalArgumentException(path + " must be a boolean");
		}
		return node.asBoolean();
	}

	private static void requireSlug(String value, String field, Pattern pattern) {
		if (value == null || !pattern.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a safe lowercase slug");
		}
	}

	private static void requireSha256(String value, String field) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	record ExpectedReviewContext(
			String reviewPacketSha256,
			String evidenceId,
			String evidenceManifestSha256,
			String querySetId,
			String querySetSha256,
			String scoringPolicyId,
			String scoringPolicySha256,
			List<ExpectedQuery> queries) {

		ExpectedReviewContext {
			requireSha256(reviewPacketSha256, "reviewPacketSha256");
			requireSlug(evidenceId, "evidenceId", SAFE_SLUG);
			requireSha256(evidenceManifestSha256, "evidenceManifestSha256");
			requireSlug(querySetId, "querySetId", SAFE_SLUG);
			requireSha256(querySetSha256, "querySetSha256");
			requireSlug(scoringPolicyId, "scoringPolicyId", SAFE_SLUG);
			requireSha256(scoringPolicySha256, "scoringPolicySha256");
			queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
			if (queries.isEmpty()
					|| queries.size() > ProviderQualityComparativeJudgments.MAX_QUERIES) {
				throw new IllegalArgumentException(
						"queries must contain 1 through "
								+ ProviderQualityComparativeJudgments.MAX_QUERIES + " entries");
			}
			Set<String> queryKeys = new HashSet<>();
			Set<String> candidateKeys = new HashSet<>();
			Set<String> reviewKeys = new HashSet<>();
			int expectedCandidateOrdinal = 1;
			for (ExpectedQuery query : queries) {
				Objects.requireNonNull(query, "queries must not contain null");
				if (!queryKeys.add(query.queryKey())) {
					throw new IllegalArgumentException("duplicate expected queryKey: " + query.queryKey());
				}
				for (ExpectedCandidate candidate : query.orderedCandidates()) {
					String expectedCandidateKey =
							"candidate-%04d".formatted(expectedCandidateOrdinal++);
					if (!expectedCandidateKey.equals(candidate.candidateKey())) {
						throw new IllegalArgumentException(
								"expected candidateKeys must be globally sequential in query order");
					}
					if (!candidateKeys.add(candidate.candidateKey())) {
						throw new IllegalArgumentException(
								"expected candidateKeys must be unique across queries");
					}
					if (!reviewKeys.add(candidate.reviewKey())) {
						throw new IllegalArgumentException(
								"expected reviewKeys must be unique across queries");
					}
				}
			}
		}
	}

	record ExpectedQuery(String queryKey, List<ExpectedCandidate> orderedCandidates) {

		ExpectedQuery {
			requireSlug(queryKey, "queryKey", QUERY_LOCAL_SLUG);
			orderedCandidates = List.copyOf(
					Objects.requireNonNull(orderedCandidates, "orderedCandidates"));
			if (orderedCandidates.size()
					> ProviderQualityComparativeJudgments.MAX_CANDIDATES_PER_QUERY) {
				throw new IllegalArgumentException(
						"orderedCandidates exceeds "
								+ ProviderQualityComparativeJudgments.MAX_CANDIDATES_PER_QUERY);
			}
			Set<String> unique = new HashSet<>();
			for (ExpectedCandidate candidate : orderedCandidates) {
				Objects.requireNonNull(candidate, "orderedCandidates must not contain null");
				if (!unique.add(candidate.candidateKey())) {
					throw new IllegalArgumentException(
							"orderedCandidates must have unique candidateKeys within a query");
				}
			}
		}
	}

	record ExpectedCandidate(String candidateKey, String reviewKey) {

		ExpectedCandidate {
			if (candidateKey == null || !CANDIDATE_KEY.matcher(candidateKey).matches()) {
				throw new IllegalArgumentException(
						"candidateKey must use the candidate-0001 alias format");
			}
			requireSha256(reviewKey, "reviewKey");
		}
	}

	record CompiledJudgments(
			byte[] canonicalBytes,
			String sha256,
			String worksheetSha256,
			BoundJudgments boundJudgments) {

		CompiledJudgments {
			canonicalBytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
			requireSha256(sha256, "sha256");
			requireSha256(worksheetSha256, "worksheetSha256");
			Objects.requireNonNull(boundJudgments, "boundJudgments");
			if (!sha256.equals(ProviderQualityComparativeReviewWorksheet.sha256(canonicalBytes))
					|| !sha256.equals(boundJudgments.sha256())) {
				throw new IllegalArgumentException(
						"canonical bytes, digest, and bound judgments must agree");
			}
		}

		@Override
		public byte[] canonicalBytes() {
			return canonicalBytes.clone();
		}
	}

	private record Worksheet(
			String reviewPacketSha256,
			String independenceAttestation,
			List<WorksheetQuery> queries) {

		private Worksheet {
			requireSha256(reviewPacketSha256, "reviewPacketSha256");
			queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
		}
	}

	private record Candidate(
			String candidateKey,
			String goldPaperKey,
			int relevanceGrade,
			List<MetadataField> expectedFields) {

		private Candidate {
			if (candidateKey == null || !CANDIDATE_KEY.matcher(candidateKey).matches()) {
				throw new IllegalArgumentException(
						"candidateKey must use the candidate-0001 alias format");
			}
			requireSlug(goldPaperKey, "goldPaperKey", QUERY_LOCAL_SLUG);
			if (relevanceGrade < 0 || relevanceGrade > 3) {
				throw new IllegalArgumentException("relevanceGrade must be from 0 through 3");
			}
			expectedFields = List.copyOf(
					Objects.requireNonNull(expectedFields, "expectedFields"));
			List<String> names = expectedFields.stream()
					.map(field -> Objects.requireNonNull(
							field, "expectedFields must not contain null").name())
					.toList();
			if (!names.equals(names.stream().distinct().sorted().toList())) {
				throw new IllegalArgumentException(
						"expectedFields must contain sorted unique MetadataField names");
			}
		}
	}

	private record GoldGroup(
			String goldPaperKey,
			int relevanceGrade,
			List<MetadataField> expectedFields,
			List<String> reviewKeys) {

		private GoldGroup {
			expectedFields = List.copyOf(expectedFields);
			reviewKeys = reviewKeys.stream().sorted().toList();
		}
	}

	private record WorksheetQuery(
			String queryKey,
			List<Candidate> candidates,
			List<WorksheetMustSeparatePair> mustSeparatePairs) {

		private WorksheetQuery {
			requireSlug(queryKey, "queryKey", QUERY_LOCAL_SLUG);
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			mustSeparatePairs = List.copyOf(
					Objects.requireNonNull(mustSeparatePairs, "mustSeparatePairs"));
			Map<String, Candidate> candidatesByCandidateKey = new LinkedHashMap<>();
			Map<String, Candidate> representativeByGoldKey = new LinkedHashMap<>();
			for (Candidate candidate : candidates) {
				Objects.requireNonNull(candidate, "candidates must not contain null");
				if (candidatesByCandidateKey.putIfAbsent(
						candidate.candidateKey(), candidate) != null) {
					throw new IllegalArgumentException(
							"duplicate candidateKey in query " + queryKey + ": "
									+ candidate.candidateKey());
				}
				Candidate representative = representativeByGoldKey.putIfAbsent(
						candidate.goldPaperKey(), candidate);
				if (representative != null
						&& (representative.relevanceGrade() != candidate.relevanceGrade()
								|| !representative.expectedFields().equals(candidate.expectedFields()))) {
					throw new IllegalArgumentException(
							"all rows for goldPaperKey " + candidate.goldPaperKey()
									+ " must use identical relevanceGrade and expectedFields");
				}
			}

			Set<String> pairKeys = new HashSet<>();
			String previousPairKey = null;
			for (WorksheetMustSeparatePair pair : mustSeparatePairs) {
				Objects.requireNonNull(pair, "mustSeparatePairs must not contain null");
				String pairKey = pair.canonicalKey();
				if (!pairKeys.add(pairKey)) {
					throw new IllegalArgumentException(
							"duplicate must-separate pair in query " + queryKey);
				}
				if (previousPairKey != null && previousPairKey.compareTo(pairKey) >= 0) {
					throw new IllegalArgumentException(
							"mustSeparatePairs must use canonical ascending order");
				}
				previousPairKey = pairKey;
				Candidate left = candidatesByCandidateKey.get(pair.leftCandidateKey());
				Candidate right = candidatesByCandidateKey.get(pair.rightCandidateKey());
				if (left == null || right == null) {
					throw new IllegalArgumentException(
							"must-separate keys must both belong to query " + queryKey);
				}
				if (left.goldPaperKey().equals(right.goldPaperKey())) {
					throw new IllegalArgumentException(
							"must-separate keys must belong to different gold papers");
				}
			}
		}

		private Map<String, GoldGroup> groupsByKey(
				Map<String, String> reviewKeyByCandidateKey) {
			Map<String, List<Candidate>> candidatesByGoldKey = new TreeMap<>();
			for (Candidate candidate : candidates) {
				candidatesByGoldKey.computeIfAbsent(
						candidate.goldPaperKey(), ignored -> new ArrayList<>()).add(candidate);
			}
			Map<String, GoldGroup> result = new LinkedHashMap<>();
			for (Map.Entry<String, List<Candidate>> entry : candidatesByGoldKey.entrySet()) {
				Candidate first = entry.getValue().getFirst();
				result.put(entry.getKey(), new GoldGroup(
						entry.getKey(),
						first.relevanceGrade(),
						first.expectedFields(),
						entry.getValue().stream()
								.map(candidate -> requireMappedReviewKey(
										reviewKeyByCandidateKey, candidate.candidateKey()))
								.toList()));
			}
			return Collections.unmodifiableMap(result);
		}

		private List<MustSeparatePair> translatedPairs(
				Map<String, String> reviewKeyByCandidateKey) {
			return mustSeparatePairs.stream()
					.map(pair -> {
						String left = requireMappedReviewKey(
								reviewKeyByCandidateKey, pair.leftCandidateKey());
						String right = requireMappedReviewKey(
								reviewKeyByCandidateKey, pair.rightCandidateKey());
						if (left.compareTo(right) > 0) {
							String swap = left;
							left = right;
							right = swap;
						}
						return new MustSeparatePair(left, right, pair.reasonCode());
					})
					.sorted((left, right) -> left.canonicalKey().compareTo(right.canonicalKey()))
					.toList();
		}
	}

	private record WorksheetMustSeparatePair(
			String leftCandidateKey,
			String rightCandidateKey,
			String reasonCode) {

		private WorksheetMustSeparatePair {
			if (leftCandidateKey == null || !CANDIDATE_KEY.matcher(leftCandidateKey).matches()
					|| rightCandidateKey == null
					|| !CANDIDATE_KEY.matcher(rightCandidateKey).matches()) {
				throw new IllegalArgumentException(
						"must-separate candidate keys must use the candidate-0001 alias format");
			}
			if (leftCandidateKey.compareTo(rightCandidateKey) >= 0) {
				throw new IllegalArgumentException(
						"must-separate candidate keys must use canonical ascending order");
			}
			if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
				throw new IllegalArgumentException("reasonCode must be a safe uppercase code");
			}
		}

		private String canonicalKey() {
			return leftCandidateKey + '\n' + rightCandidateKey;
		}
	}

	private static String requireMappedReviewKey(
			Map<String, String> reviewKeyByCandidateKey, String candidateKey) {
		String reviewKey = reviewKeyByCandidateKey.get(candidateKey);
		if (reviewKey == null) {
			throw new IllegalArgumentException(
					"candidate alias is absent from the expected review context: " + candidateKey);
		}
		return reviewKey;
	}
}
