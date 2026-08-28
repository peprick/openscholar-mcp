package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

/**
 * Builds the provenance-free packet used for independent comparative review.
 *
 * <p>This is deliberately a one-way projection from an already verified evidence bundle. It
 * validates the complete blinded shape and its query/count/order bindings before copying only the
 * reviewer-visible fields. Provenance, reconciliation, provider, ranking, identifiers, and other
 * capture internals are never read into the output model.</p>
 */
final class ProviderQualityComparativeReviewPacket {

	static final String REVIEW_PACKET_PROTOCOL_ID =
			"provider-quality-comparative-review-packet-v1";
	static final String WORKSHEET_PROTOCOL_ID =
			"provider-quality-comparative-review-worksheet-v1";
	static final long MAXIMUM_REVIEW_PACKET_BYTES = 72L * 1024L * 1024L;

	private static final String EVIDENCE_TYPE = "LIVE_COMPARATIVE_METADATA_CAPTURE";
	private static final String SOURCE_POLICY =
			"AUTHOR_WRITTEN_TOPICS_WITHOUT_RELEVANCE_LABELS";
	private static final String REVIEW_SESSION_DOMAIN =
			"provider-quality-comparative-review-session-v1";
	private static final String UNDETERMINED_LANGUAGE = "und";
	private static final Map<String, String> BIBLIOGRAPHIC_LANGUAGE_ALIASES = Map.ofEntries(
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
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern ISO_LANGUAGE = Pattern.compile("^[a-z]{2,3}$");
	private static final Pattern SAFE_SLUG =
			Pattern.compile("^[a-z0-9][a-z0-9-]{1,126}[a-z0-9]$");
	private static final Pattern QUERY_KEY =
			Pattern.compile("^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$");
	private static final Set<String> SUMMARY_FIELDS = Set.of(
			"schemaVersion", "evidenceType", "evidenceId", "measuredAt",
			"repositoryRevision", "querySet", "providerConfiguration", "boundaries",
			"qualityReviewEligible", "providerRequests", "providerFailures", "queries");
	private static final Set<String> QUERY_SET_FIELDS = Set.of(
			"id", "sha256", "sourcePolicy", "pageSize");
	private static final Set<String> SUMMARY_QUERY_FIELDS = Set.of(
			"queryKey", "complete", "rawCandidateCount", "providerCalls",
			"scenarioResultCounts");
	private static final Set<String> BLINDED_FIELDS = Set.of(
			"schemaVersion", "evidenceId", "qualityReviewEligible", "instructions",
			"candidates");
	private static final Set<String> BLINDED_CANDIDATE_FIELDS = Set.of(
			"reviewKey", "queryKey", "title", "abstractText", "publicationDate",
			"publicationYear", "documentType", "language", "venueName", "authors");
	private static final Set<String> BLINDED_AUTHOR_FIELDS = Set.of(
			"displayName", "position", "corresponding");

	private ProviderQualityComparativeReviewPacket() {
	}

	static Generated generate(
			ObjectMapper objectMapper,
			ProviderQualityComparativeEvidenceBundle bundle,
			ProviderQualityLiveQuerySet.BoundQuerySet boundQuerySet,
			ProviderQualityComparativeScoringPolicy.BoundPolicy boundPolicy)
			throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bundle, "bundle");
		Objects.requireNonNull(boundQuerySet, "boundQuerySet");
		Objects.requireNonNull(boundPolicy, "boundPolicy");
		if (!bundle.reviewReady()) {
			throw invalid("evidence bundle is not review-ready");
		}

		ProviderQualityComparativeScoringPolicy policy = boundPolicy.policy();
		boundPolicy.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID,
				ProviderQualityComparativeScoringPolicy.POLICY_SHA256);
		ProviderQualityLiveQuerySet querySetDefinition = validateBoundQuerySet(
				objectMapper, boundQuerySet);
		SummaryBinding summary = parseSummary(
				bundle, boundQuerySet, querySetDefinition, policy.limits());
		BlindedProjection projection = parseBlinded(
				bundle, summary, policy.limits());
		String reviewSessionKey = reviewSessionKey(
				bundle.manifestSha256(), boundQuerySet.sha256(), boundPolicy.sha256());
		ReviewPacket packet = new ReviewPacket(
				1,
				REVIEW_PACKET_PROTOCOL_ID,
				reviewSessionKey,
				ProviderQualityComparativeEvidenceBundle.ELIGIBLE_REVIEW_INSTRUCTIONS,
				projection.queries());
		byte[] packetBytes = canonicalBytes(objectMapper, packet);
		String packetSha256 = sha256(packetBytes);

		List<WorksheetQuery> worksheetQueries = projection.queries().stream()
				.map(query -> new WorksheetQuery(
						query.queryKey(),
						query.candidates().stream()
								.map(candidate -> new WorksheetCandidate(
										candidate.candidateKey(), null, null, null))
								.toList(),
						false,
						List.of()))
				.toList();
		WorksheetSkeleton worksheet = new WorksheetSkeleton(
				1,
				WORKSHEET_PROTOCOL_ID,
				packetSha256,
				null,
				worksheetQueries);
		HiddenBindings hiddenBindings = new HiddenBindings(
				bundle.evidenceId(),
				bundle.manifestSha256(),
				querySetDefinition.querySetId(),
				boundQuerySet.sha256(),
				policy.policyId(),
				boundPolicy.sha256(),
				projection.hiddenQueries());
		return new Generated(
				packet, packetBytes, packetSha256, worksheet, hiddenBindings);
	}

	static String reviewSessionKey(
			String evidenceManifestSha256,
			String querySetSha256,
			String scoringPolicySha256) {
		requireSha256(evidenceManifestSha256, "evidenceManifestSha256");
		requireSha256(querySetSha256, "querySetSha256");
		requireSha256(scoringPolicySha256, "scoringPolicySha256");
		String binding = REVIEW_SESSION_DOMAIN + '\0'
				+ evidenceManifestSha256 + '\0'
				+ querySetSha256 + '\0'
				+ scoringPolicySha256;
		return sha256(binding.getBytes(StandardCharsets.US_ASCII));
	}

	static void verifyReviewedPacket(Path reviewPacketPath, Generated expected)
			throws IOException {
		Path path = Objects.requireNonNull(reviewPacketPath, "reviewPacketPath");
		Generated generated = Objects.requireNonNull(expected, "expected");
		if (Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("review packet must be a real regular file");
		}
		byte[] expectedBytes = generated.reviewPacketBytes();
		if (expectedBytes.length < 1 || expectedBytes.length > MAXIMUM_REVIEW_PACKET_BYTES) {
			throw new IllegalArgumentException(
					"expected review packet is outside the frozen byte limit");
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			long size = channel.size();
			if (size > MAXIMUM_REVIEW_PACKET_BYTES) {
				throw new IOException("review packet exceeds the frozen byte limit");
			}
			if (size != expectedBytes.length) {
				throw new IOException("review packet does not match the immutable generated packet");
			}
			byte[] actualBytes = input.readNBytes(expectedBytes.length + 1);
			if (channel.size() != expectedBytes.length
					|| actualBytes.length != expectedBytes.length
					|| !MessageDigest.isEqual(actualBytes, expectedBytes)
					|| !sameDigest(sha256(actualBytes), generated.reviewPacketSha256())) {
				throw new IOException("review packet does not match the immutable generated packet");
			}
		}
	}

	static byte[] canonicalBytes(ObjectMapper objectMapper, Object value) throws IOException {
		ObjectWriter writer = Objects.requireNonNull(objectMapper, "objectMapper")
				.writer()
				.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
				.without(SerializationFeature.INDENT_OUTPUT);
		byte[] json = writer.writeValueAsBytes(Objects.requireNonNull(value, "value"));
		if (json.length > 0 && json[json.length - 1] == '\n') {
			return json;
		}
		byte[] terminated = Arrays.copyOf(json, json.length + 1);
		terminated[terminated.length - 1] = '\n';
		return terminated;
	}

	private static SummaryBinding parseSummary(
			ProviderQualityComparativeEvidenceBundle bundle,
			ProviderQualityLiveQuerySet.BoundQuerySet boundQuerySet,
			ProviderQualityLiveQuerySet querySetDefinition,
			ProviderQualityComparativeScoringPolicy.Limits limits) {
		JsonNode summary = bundle.summary();
		requireExactObject(summary, "$summary", SUMMARY_FIELDS);
		requireInteger(summary.required("schemaVersion"), "$summary.schemaVersion", 2, 2);
		requireText(summary.required("evidenceType"), "$summary.evidenceType", EVIDENCE_TYPE);
		requireText(summary.required("evidenceId"), "$summary.evidenceId", bundle.evidenceId());
		requireBoolean(
				summary.required("qualityReviewEligible"),
				"$summary.qualityReviewEligible",
				true);

		JsonNode querySet = summary.required("querySet");
		requireExactObject(querySet, "$summary.querySet", QUERY_SET_FIELDS);
		String querySetId = requireSlug(
				querySet.required("id"), "$summary.querySet.id", SAFE_SLUG);
		if (!querySetDefinition.querySetId().equals(querySetId)) {
			throw invalid("summary query-set ID does not match the bound query set");
		}
		String querySetSha256 = requireSha256(
				querySet.required("sha256"), "$summary.querySet.sha256");
		if (!sameDigest(boundQuerySet.sha256(), querySetSha256)) {
			throw invalid("summary query-set SHA-256 does not match the bound query set");
		}
		requireText(
				querySet.required("sourcePolicy"),
				"$summary.querySet.sourcePolicy",
				querySetDefinition.sourcePolicy());
		requireInteger(
				querySet.required("pageSize"),
				"$summary.querySet.pageSize",
				querySetDefinition.pageSize(),
				querySetDefinition.pageSize());

		JsonNode queryNodes = summary.required("queries");
		requireArray(queryNodes, "$summary.queries", 1, limits.maximumQueries());
		List<SummaryQuery> queries = new ArrayList<>(queryNodes.size());
		Set<String> queryKeys = new LinkedHashSet<>();
		for (int index = 0; index < queryNodes.size(); index++) {
			JsonNode query = queryNodes.get(index);
			String path = "$summary.queries[" + index + ']';
			requireExactObject(query, path, SUMMARY_QUERY_FIELDS);
			String queryKey = requireSlug(
					query.required("queryKey"), path + ".queryKey", QUERY_KEY);
			if (!queryKeys.add(queryKey)) {
				throw invalid("summary query keys must be unique");
			}
			requireBoolean(query.required("complete"), path + ".complete", true);
			int candidateCount = requireInteger(
					query.required("rawCandidateCount"),
					path + ".rawCandidateCount",
					0,
					limits.maximumCandidatesPerQuery());
			if (!query.required("providerCalls").isArray()
					|| !query.required("scenarioResultCounts").isObject()) {
				throw invalid(path + " contains malformed capture summaries");
			}
			queries.add(new SummaryQuery(queryKey, candidateCount, null));
		}
		List<String> summaryKeys = queries.stream().map(SummaryQuery::queryKey).toList();
		List<String> boundKeys = querySetDefinition.queries().stream()
				.map(ProviderQualityLiveQuerySet.Query::key)
				.toList();
		if (!summaryKeys.equals(boundKeys)) {
			throw invalid("summary queries do not match the ordered bound query set");
		}
		List<SummaryQuery> boundQueries = new ArrayList<>(queries.size());
		for (int index = 0; index < queries.size(); index++) {
			SummaryQuery query = queries.get(index);
			boundQueries.add(new SummaryQuery(
					query.queryKey(),
					query.candidateCount(),
					querySetDefinition.queries().get(index).text()));
		}
		return new SummaryBinding(querySetId, querySetSha256, boundQueries);
	}

	private static ProviderQualityLiveQuerySet validateBoundQuerySet(
			ObjectMapper objectMapper,
			ProviderQualityLiveQuerySet.BoundQuerySet boundQuerySet) throws IOException {
		ProviderQualityLiveQuerySet querySet = Objects.requireNonNull(
				boundQuerySet.querySet(), "boundQuerySet.querySet");
		ProviderQualityLiveQuerySet.BoundQuerySet frozen =
				ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
		if (!querySet.equals(frozen.querySet())
				|| !sameDigest(boundQuerySet.sha256(), frozen.sha256())) {
			throw invalid("query set must be the exact frozen provider-quality query set");
		}
		if (querySet.schemaVersion() != 1
				|| !ProviderQualityLiveQuerySet.EXPECTED_QUERY_SET_ID.equals(querySet.querySetId())
				|| !SOURCE_POLICY.equals(querySet.sourcePolicy())
				|| querySet.pageSize() != 20
				|| !ProviderQualityLiveQuerySet.EXPECTED_RESOURCE_SHA256.equals(
						boundQuerySet.sha256())) {
			throw invalid("query set must be the exact frozen provider-quality query set");
		}
		List<String> keys = querySet.queries().stream()
				.map(ProviderQualityLiveQuerySet.Query::key)
				.toList();
		if (keys.size() != 8 || keys.stream().distinct().count() != keys.size()
				|| keys.stream().anyMatch(key -> !QUERY_KEY.matcher(key).matches())) {
			throw invalid("query set must contain the frozen ordered query keys");
		}
		return querySet;
	}

	private static BlindedProjection parseBlinded(
			ProviderQualityComparativeEvidenceBundle bundle,
			SummaryBinding summary,
			ProviderQualityComparativeScoringPolicy.Limits limits) {
		JsonNode blinded = bundle.blindedCandidates();
		requireExactObject(blinded, "$blinded", BLINDED_FIELDS);
		requireInteger(blinded.required("schemaVersion"), "$blinded.schemaVersion", 2, 2);
		requireText(blinded.required("evidenceId"), "$blinded.evidenceId", bundle.evidenceId());
		requireBoolean(
				blinded.required("qualityReviewEligible"),
				"$blinded.qualityReviewEligible",
				true);
		requireText(
				blinded.required("instructions"),
				"$blinded.instructions",
				ProviderQualityComparativeEvidenceBundle.ELIGIBLE_REVIEW_INSTRUCTIONS);

		int maximumCandidates = Math.multiplyExact(
				limits.maximumQueries(), limits.maximumCandidatesPerQuery());
		JsonNode candidateNodes = blinded.required("candidates");
		requireArray(candidateNodes, "$blinded.candidates", 0, maximumCandidates);
		Map<String, List<ReviewCandidate>> candidatesByQuery = new LinkedHashMap<>();
		Map<String, List<HiddenCandidate>> hiddenCandidatesByQuery = new LinkedHashMap<>();
		for (SummaryQuery query : summary.queries()) {
			candidatesByQuery.put(query.queryKey(), new ArrayList<>());
			hiddenCandidatesByQuery.put(query.queryKey(), new ArrayList<>());
		}
		Set<String> reviewKeys = new LinkedHashSet<>();
		int previousQueryIndex = -1;
		String previousOrderingKey = null;
		Map<String, Integer> queryOrder = new LinkedHashMap<>();
		for (int index = 0; index < summary.queries().size(); index++) {
			queryOrder.put(summary.queries().get(index).queryKey(), index);
		}

		for (int index = 0; index < candidateNodes.size(); index++) {
			JsonNode candidate = candidateNodes.get(index);
			String path = "$blinded.candidates[" + index + ']';
			requireExactObject(candidate, path, BLINDED_CANDIDATE_FIELDS);
			String reviewKey = requireSha256(candidate.required("reviewKey"), path + ".reviewKey");
			if (!reviewKeys.add(reviewKey)) {
				throw invalid("blinded review keys must be unique");
			}
			String queryKey = requireSlug(
					candidate.required("queryKey"), path + ".queryKey", QUERY_KEY);
			Integer currentQueryIndex = queryOrder.get(queryKey);
			if (currentQueryIndex == null || currentQueryIndex < previousQueryIndex) {
				throw invalid("blinded candidates do not follow the summary query order");
			}
			String orderingKey = ProviderQualityComparativeEvidenceBundle.blindedOrderingKey(
					bundle.evidenceId(), reviewKey);
			if (currentQueryIndex == previousQueryIndex
					&& previousOrderingKey.compareTo(orderingKey) > 0) {
				throw invalid("blinded candidates do not follow the evidence-scoped order");
			}
			if (currentQueryIndex != previousQueryIndex) {
				previousQueryIndex = currentQueryIndex;
			}
			previousOrderingKey = orderingKey;

			String candidateKey = "candidate-%04d".formatted(index + 1);
			ReviewCandidate sanitized = parseCandidate(candidate, path, candidateKey);
			candidatesByQuery.get(queryKey).add(sanitized);
			hiddenCandidatesByQuery.get(queryKey).add(
					new HiddenCandidate(candidateKey, reviewKey));
		}

		List<ReviewQuery> result = new ArrayList<>(summary.queries().size());
		List<HiddenQuery> hiddenQueries = new ArrayList<>(summary.queries().size());
		for (SummaryQuery query : summary.queries()) {
			List<ReviewCandidate> candidates = candidatesByQuery.get(query.queryKey());
			if (candidates.size() != query.candidateCount()) {
				throw invalid("summary candidate count does not match blinded candidates");
			}
			result.add(new ReviewQuery(query.queryKey(), query.queryText(), candidates));
			hiddenQueries.add(new HiddenQuery(
					query.queryKey(), hiddenCandidatesByQuery.get(query.queryKey())));
		}
		return new BlindedProjection(result, hiddenQueries);
	}

	private static ReviewCandidate parseCandidate(
			JsonNode candidate, String path, String candidateKey) {
		JsonNode authorNodes = candidate.required("authors");
		requireArray(authorNodes, path + ".authors", 0, 1_000);
		List<ReviewAuthor> authors = new ArrayList<>(authorNodes.size());
		for (int index = 0; index < authorNodes.size(); index++) {
			JsonNode author = authorNodes.get(index);
			String authorPath = path + ".authors[" + index + ']';
			requireExactObject(author, authorPath, BLINDED_AUTHOR_FIELDS);
			String displayName = requireProjectionText(
					author.required("displayName"),
					authorPath + ".displayName",
					1_000);
			requireInteger(author.required("position"), authorPath + ".position", 0, 1_000);
			requireBoolean(author.required("corresponding"), authorPath + ".corresponding");
			authors.add(new ReviewAuthor(displayName));
		}

		String publicationDate = requireOptionalProjectionText(
				candidate.required("publicationDate"), path + ".publicationDate", 32);
		if (publicationDate != null) {
			try {
				LocalDate.parse(publicationDate);
			}
			catch (RuntimeException exception) {
				throw invalid(path + ".publicationDate must be an ISO-8601 local date");
			}
		}
		Integer publicationYear = requireOptionalInteger(
				candidate.required("publicationYear"), path + ".publicationYear", 1_000, 9_999);
		String documentType = requireProjectionText(
				candidate.required("documentType"), path + ".documentType", 100);
		if (!"ARTICLE".equals(documentType)) {
			throw invalid(path + ".documentType must be ARTICLE");
		}
		return new ReviewCandidate(
				candidateKey,
				requireProjectionText(candidate.required("title"), path + ".title", 10_000),
				requireOptionalProjectionText(
						candidate.required("abstractText"), path + ".abstractText", 200_000),
				publicationDate,
				publicationYear,
				documentType,
				normalizeLanguage(requireOptionalProjectionText(
						candidate.required("language"), path + ".language", 100)),
				requireOptionalProjectionText(
						candidate.required("venueName"), path + ".venueName", 10_000),
				authors);
	}

	private static String normalizeLanguage(String supplied) {
		if (supplied == null) {
			return UNDETERMINED_LANGUAGE;
		}
		String languageTag = supplied.toLowerCase(Locale.ROOT).replace('_', '-');
		String primary = languageTag.split("-", 2)[0];
		if (!ISO_LANGUAGE.matcher(primary).matches()) {
			return UNDETERMINED_LANGUAGE;
		}
		if (primary.length() == 3) {
			String terminologicAlias = BIBLIOGRAPHIC_LANGUAGE_ALIASES.get(primary);
			if (terminologicAlias != null) {
				return terminologicAlias;
			}
			for (String iso2 : Locale.getISOLanguages()) {
				try {
					String iso3 = Locale.forLanguageTag(iso2)
							.getISO3Language().toLowerCase(Locale.ROOT);
					if (primary.equals(iso3)) {
						return iso3;
					}
				}
				catch (MissingResourceException ignored) {
					// Ignore incomplete runtime locale entries and continue fail-closed.
				}
			}
			return UNDETERMINED_LANGUAGE;
		}
		try {
			String iso3 = Locale.forLanguageTag(primary)
					.getISO3Language().toLowerCase(Locale.ROOT);
			return ISO_LANGUAGE.matcher(iso3).matches() && iso3.length() == 3
					? iso3
					: UNDETERMINED_LANGUAGE;
		}
		catch (MissingResourceException exception) {
			return UNDETERMINED_LANGUAGE;
		}
	}

	private static void requireExactObject(JsonNode node, String path, Set<String> expected) {
		if (node == null || !node.isObject()
				|| !new LinkedHashSet<>(node.propertyNames()).equals(expected)) {
			throw invalid(path + " must contain exactly the frozen fields");
		}
	}

	private static void requireArray(
			JsonNode node, String path, int minimum, int maximum) {
		if (node == null || !node.isArray() || node.size() < minimum || node.size() > maximum) {
			throw invalid(path + " has an invalid array length");
		}
	}

	private static String requireText(JsonNode node, String path) {
		if (node == null || !node.isString()) {
			throw invalid(path + " must be text");
		}
		String value = node.asString();
		if (!value.equals(value.strip()) || value.isEmpty()
				|| value.codePoints().anyMatch(Character::isISOControl)) {
			throw invalid(path + " must be bounded text without surrounding whitespace");
		}
		return value;
	}

	private static void requireText(JsonNode node, String path, String expected) {
		if (!expected.equals(requireText(node, path))) {
			throw invalid(path + " does not match the bound value");
		}
	}

	private static String requireSlug(JsonNode node, String path, Pattern pattern) {
		String value = requireText(node, path);
		if (!pattern.matcher(value).matches()) {
			throw invalid(path + " must be a safe lowercase slug");
		}
		return value;
	}

	private static String requireSha256(JsonNode node, String path) {
		String value = requireText(node, path);
		requireSha256(value, path);
		return value;
	}

	private static void requireSha256(String value, String path) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw invalid(path + " must be a lowercase SHA-256 value");
		}
	}

	private static String requireProjectionText(JsonNode node, String path, int maximum) {
		String value = requireText(node, path);
		if (value.length() > maximum) {
			throw invalid(path + " is outside the frozen text range");
		}
		return value;
	}

	private static String requireOptionalProjectionText(
			JsonNode node, String path, int maximum) {
		if (node != null && node.isNull()) {
			return null;
		}
		return requireProjectionText(node, path, maximum);
	}

	private static int requireInteger(
			JsonNode node, String path, int minimum, int maximum) {
		if (node == null || !node.isInt()) {
			throw invalid(path + " must be an integer");
		}
		int value = node.asInt();
		if (value < minimum || value > maximum) {
			throw invalid(path + " is outside the frozen range");
		}
		return value;
	}

	private static Integer requireOptionalInteger(
			JsonNode node, String path, int minimum, int maximum) {
		if (node != null && node.isNull()) {
			return null;
		}
		return requireInteger(node, path, minimum, maximum);
	}

	private static boolean requireBoolean(JsonNode node, String path) {
		if (node == null || !node.isBoolean()) {
			throw invalid(path + " must be boolean");
		}
		return node.asBoolean();
	}

	private static void requireBoolean(JsonNode node, String path, boolean expected) {
		if (requireBoolean(node, path) != expected) {
			throw invalid(path + " does not match the bound value");
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

	private static boolean sameDigest(String left, String right) {
		return left != null && right != null
				&& MessageDigest.isEqual(
						left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
						right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException(message);
	}

	private record SummaryBinding(
			String querySetId, String querySetSha256, List<SummaryQuery> queries) {

		private SummaryBinding {
			queries = List.copyOf(queries);
		}
	}

	private record SummaryQuery(String queryKey, int candidateCount, String queryText) {
	}

	static final class Generated {

		private final ReviewPacket reviewPacket;
		private final byte[] reviewPacketBytes;
		private final String reviewPacketSha256;
		private final WorksheetSkeleton worksheetSkeleton;
		private final HiddenBindings hiddenBindings;

		Generated(
				ReviewPacket reviewPacket,
				byte[] reviewPacketBytes,
				String reviewPacketSha256,
				WorksheetSkeleton worksheetSkeleton,
				HiddenBindings hiddenBindings) {
			this.reviewPacket = Objects.requireNonNull(reviewPacket, "reviewPacket");
			this.reviewPacketBytes = Objects.requireNonNull(
					reviewPacketBytes, "reviewPacketBytes").clone();
			this.reviewPacketSha256 = Objects.requireNonNull(
					reviewPacketSha256, "reviewPacketSha256");
			if (!SHA256.matcher(this.reviewPacketSha256).matches()) {
				throw new IllegalArgumentException(
						"reviewPacketSha256 must be a lowercase SHA-256 value");
			}
			if (this.reviewPacketBytes.length < 1
					|| this.reviewPacketBytes.length > MAXIMUM_REVIEW_PACKET_BYTES
					|| !sameDigest(sha256(this.reviewPacketBytes), this.reviewPacketSha256)) {
				throw new IllegalArgumentException(
						"review packet bytes and SHA-256 must agree within the frozen limit");
			}
			this.worksheetSkeleton = Objects.requireNonNull(
					worksheetSkeleton, "worksheetSkeleton");
			this.hiddenBindings = Objects.requireNonNull(hiddenBindings, "hiddenBindings");
		}

		ReviewPacket reviewPacket() {
			return reviewPacket;
		}

		String reviewPacketSha256() {
			return reviewPacketSha256;
		}

		WorksheetSkeleton worksheetSkeleton() {
			return worksheetSkeleton;
		}

		byte[] reviewPacketBytes() {
			return reviewPacketBytes.clone();
		}

		ProviderQualityComparativeReviewWorksheet.ExpectedReviewContext
				expectedReviewContext() {
			return new ProviderQualityComparativeReviewWorksheet.ExpectedReviewContext(
					reviewPacketSha256,
					hiddenBindings.evidenceId(),
					hiddenBindings.evidenceManifestSha256(),
					hiddenBindings.querySetId(),
					hiddenBindings.querySetSha256(),
					hiddenBindings.scoringPolicyId(),
					hiddenBindings.scoringPolicySha256(),
					hiddenBindings.queries().stream()
							.map(query ->
									new ProviderQualityComparativeReviewWorksheet.ExpectedQuery(
											query.queryKey(),
											query.candidates().stream()
													.map(candidate ->
															new ProviderQualityComparativeReviewWorksheet.ExpectedCandidate(
																	candidate.candidateKey(),
																	candidate.reviewKey()))
													.toList()))
							.toList());
		}
	}

	record ReviewPacket(
			int schemaVersion,
			String protocolId,
			String reviewSessionKey,
			String instructions,
			List<ReviewQuery> queries) {

		ReviewPacket {
			queries = List.copyOf(queries);
		}
	}

	record ReviewQuery(String queryKey, String queryText, List<ReviewCandidate> candidates) {

		ReviewQuery {
			candidates = List.copyOf(candidates);
		}
	}

	record ReviewCandidate(
			String candidateKey,
			String title,
			String abstractText,
			String publicationDate,
			Integer publicationYear,
			String documentType,
			String language,
			String venueName,
			List<ReviewAuthor> authors) {

		ReviewCandidate {
			authors = List.copyOf(authors);
		}
	}

	record ReviewAuthor(String displayName) {
	}

	record WorksheetSkeleton(
			int schemaVersion,
			String protocolId,
			String reviewPacketSha256,
			String independenceAttestation,
			List<WorksheetQuery> queries) {

		WorksheetSkeleton {
			queries = List.copyOf(queries);
		}
	}

	record WorksheetQuery(
			String queryKey,
			List<WorksheetCandidate> candidates,
			boolean mustSeparateReviewComplete,
			List<Object> mustSeparatePairs) {

		WorksheetQuery {
			candidates = List.copyOf(candidates);
			mustSeparatePairs = List.copyOf(mustSeparatePairs);
		}
	}

	record WorksheetCandidate(
			String candidateKey,
			String goldPaperKey,
			Integer relevanceGrade,
			List<String> expectedFields) {
	}

	private record BlindedProjection(
			List<ReviewQuery> queries, List<HiddenQuery> hiddenQueries) {

		private BlindedProjection {
			queries = List.copyOf(queries);
			hiddenQueries = List.copyOf(hiddenQueries);
		}
	}

	private record HiddenBindings(
			String evidenceId,
			String evidenceManifestSha256,
			String querySetId,
			String querySetSha256,
			String scoringPolicyId,
			String scoringPolicySha256,
			List<HiddenQuery> queries) {

		private HiddenBindings {
			queries = List.copyOf(queries);
		}
	}

	private record HiddenQuery(String queryKey, List<HiddenCandidate> candidates) {

		private HiddenQuery {
			candidates = List.copyOf(candidates);
		}
	}

	private record HiddenCandidate(String candidateKey, String reviewKey) {
	}
}
