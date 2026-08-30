package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.openscholar.paper.DocumentType;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Strict read-only intake boundary for an externally authored related-topic holdout.
 *
 * <p>The verifier proves local shape, integrity, and literal disjointness only. It cannot prove
 * authorship, authenticity, reviewer independence, or semantic disjointness. Real bundles remain
 * outside the repository and are never supplied by this class.</p>
 */
final class RelatedTopicReuseHoldoutBundle {

	private static final String MANIFEST_FILENAME = "manifest.json";
	private static final String CORPUS_FILENAME = "holdout-corpus.json";
	private static final String JUDGMENTS_FILENAME = "judgments.json";
	private static final List<String> PAYLOAD_FILENAMES = List.of(
			CORPUS_FILENAME, JUDGMENTS_FILENAME);
	private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,8}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	private static final Set<String> MANIFEST_FIELDS = Set.of(
			"schemaVersion", "protocolId", "bundleId", "policyId", "policySha256",
			"corpusId", "payloadBytes", "files", "declarations");
	private static final Set<String> MANIFEST_FILE_FIELDS = Set.of(
			"filename", "bytes", "sha256");
	private static final Set<String> DECLARATION_FIELDS = Set.of(
			"corpusAuthorship", "judgmentAuthorship", "firstRunRule", "noRetuningRule",
			"externalCustodyRule", "evaluatorFreezeRule", "limitations");
	private static final Set<String> CORPUS_FIELDS = Set.of(
			"schemaVersion", "protocolId", "bundleId", "policyId", "policySha256",
			"corpusId", "split", "labelUnit", "sourcePolicy", "lineages", "candidates",
			"queries");
	private static final Set<String> LINEAGE_FIELDS = Set.of("key", "kind");
	private static final Set<String> CANDIDATE_FIELDS = Set.of(
			"key", "lineageKey", "title", "abstractText", "venueName", "publicationYear",
			"documentType", "language", "citationCount", "reportedOpenAccess", "authors");
	private static final Set<String> QUERY_FIELDS = Set.of(
			"key", "text", "kind", "cutoff", "filters");
	private static final Set<String> FILTER_FIELDS = Set.of(
			"yearFrom", "yearTo", "documentTypes", "openAccessOnly", "minimumCitations",
			"languages");
	private static final Set<String> JUDGMENTS_FIELDS = Set.of(
			"schemaVersion", "protocolId", "bundleId", "policyId", "policySha256",
			"corpusId", "labelUnit", "queries");
	private static final Set<String> QUERY_JUDGMENTS_FIELDS = Set.of(
			"queryKey", "grades", "adversaries");
	private static final Set<String> ADVERSARY_FIELDS = Set.of(
			"candidateKey", "kind", "reason");

	private final String bundleId;
	private final String corpusId;
	private final String manifestSha256;
	private final Corpus corpus;
	private final Judgments judgments;

	private RelatedTopicReuseHoldoutBundle(
			String bundleId,
			String corpusId,
			String manifestSha256,
			Corpus corpus,
			Judgments judgments) {
		this.bundleId = bundleId;
		this.corpusId = corpusId;
		this.manifestSha256 = manifestSha256;
		this.corpus = corpus;
		this.judgments = judgments;
	}

	static VerifiedCorpus verifyCorpus(
			ObjectMapper objectMapper,
			Path sourceDirectory)
			throws IOException {
		if (objectMapper == null
				|| sourceDirectory == null) {
			throw failure("HOLDOUT_INPUT_INVALID");
		}
		FrozenInputs frozenInputs = loadFrozenInputs(objectMapper);
		RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy = frozenInputs.holdoutPolicy();
		RelatedTopicReuseEvaluationFixture.BoundFixture developmentFixture =
				frozenInputs.developmentFixture();
		RelatedTopicReuseHoldoutPolicy policy = boundPolicy.policy();
		Path directory = verifyExternalDirectory(sourceDirectory);
		Map<String, Long> sizes = verifyLayoutAndSizes(directory, policy);

		byte[] manifestBytes = readBounded(
				directory.resolve(MANIFEST_FILENAME),
				policy.bundle().maximumManifestBytes(),
				"HOLDOUT_MANIFEST_TOO_LARGE");
		if (manifestBytes.length != sizes.get(MANIFEST_FILENAME)) {
			throw failure("HOLDOUT_FILE_CHANGED");
		}
		Manifest manifest = parseManifest(
				parseStrict(objectMapper, manifestBytes, "HOLDOUT_MANIFEST_JSON_INVALID"),
				policy,
				boundPolicy.sha256());
		if (!manifest.bundleId().equals(directory.getFileName().toString())) {
			throw failure("HOLDOUT_BUNDLE_ID_INVALID");
		}
		verifyPayloadSizes(manifest, sizes);

		byte[] corpusBytes = readObservedFile(
				directory, CORPUS_FILENAME, sizes, policy);
		verifyPayloadDigest(manifest, CORPUS_FILENAME, corpusBytes);
		Corpus corpus = parseCorpus(
				parseStrict(
						objectMapper,
						corpusBytes,
						"HOLDOUT_CORPUS_JSON_INVALID"),
				manifest,
				policy,
				boundPolicy.sha256());
		validateCorpusSemantics(policy, corpus, developmentFixture.fixture());

		return new VerifiedCorpus(
				manifest,
				sha256(manifestBytes),
				manifestBytes.length,
				policy.evaluation().protocolId(),
				new RankingCorpus(
						manifest.protocolId(),
						manifest.bundleId(),
						manifest.corpusId(),
						manifest.policyId(),
						manifest.policySha256(),
						sha256(corpusBytes),
						corpus),
				corpusBytes.length);
	}

	static CompletedRanking completeRanking(
			VerifiedCorpus verifiedCorpus,
			RelatedTopicReuseHoldoutPostgresFirstRunLedger.CommittedFirstRun
					committedFirstRun,
			LabelFreeRankingPhase rankingPhase)
			throws IOException {
		if (verifiedCorpus == null
				|| committedFirstRun == null
				|| rankingPhase == null) {
			throw failure("HOLDOUT_INPUT_INVALID");
		}
		try {
			committedFirstRun.consumeForRanking(verifiedCorpus);
		}
		catch (RelatedTopicReuseHoldoutPostgresFirstRunLedger.LedgerException exception) {
			throw failure("HOLDOUT_FIRST_RUN_CLAIM_INVALID");
		}
		RelatedTopicReuseHoldoutRankingSnapshot.Observation observation =
				rankingPhase.rank(verifiedCorpus.rankingCorpus);
		if (observation == null) {
			throw failure("HOLDOUT_RANKING_OBSERVATION_INVALID");
		}
		ManifestFile judgmentsCommitment = manifestFile(
				verifiedCorpus.manifest, JUDGMENTS_FILENAME);
		RelatedTopicReuseHoldoutRankingSnapshot snapshot =
				RelatedTopicReuseHoldoutRankingSnapshot.seal(
						verifiedCorpus.rankingCorpus.bundleId(),
						verifiedCorpus.rankingCorpus.corpusId(),
						verifiedCorpus.rankingCorpus.policySha256(),
						verifiedCorpus.rankingCorpus.corpusSha256(),
						verifiedCorpus.manifestSha256,
						judgmentsCommitment.sha256(),
						judgmentsCommitment.bytes(),
						observation.candidateRevision(),
						observation.cutoff(),
						observation.queryOrder(),
						observation.queries(),
						observation.counters());
		return new CompletedRanking(
				verifiedCorpus,
				verifiedCorpus.completionAuthority,
				snapshot);
	}

	static VerifiedScoringInputs verifyAfterRanking(
			ObjectMapper objectMapper,
			Path sourceDirectory,
			CompletedRanking completedRanking)
			throws IOException {
		if (objectMapper == null
				|| sourceDirectory == null
				|| completedRanking == null
				|| !completedRanking.isAuthorized()) {
			throw failure("HOLDOUT_INPUT_INVALID");
		}
		VerifiedCorpus verifiedCorpus = completedRanking.verifiedCorpus;
		RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot =
				completedRanking.rankingSnapshot;

		FrozenInputs frozenInputs = loadFrozenInputs(objectMapper);
		RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy = frozenInputs.holdoutPolicy();
		RelatedTopicReuseEvaluationFixture.BoundFixture developmentFixture =
				frozenInputs.developmentFixture();
		RelatedTopicReuseHoldoutPolicy policy = boundPolicy.policy();
		validateRankingSnapshot(verifiedCorpus, rankingSnapshot, policy);
		Path directory = verifyExternalDirectory(sourceDirectory);
		Map<String, Long> sizes = verifyLayoutAndSizes(directory, policy);

		byte[] manifestBytes = readBounded(
				directory.resolve(MANIFEST_FILENAME),
				policy.bundle().maximumManifestBytes(),
				"HOLDOUT_MANIFEST_TOO_LARGE");
		if (manifestBytes.length != sizes.get(MANIFEST_FILENAME)) {
			throw failure("HOLDOUT_FILE_CHANGED");
		}
		Manifest manifest = parseManifest(
				parseStrict(objectMapper, manifestBytes, "HOLDOUT_MANIFEST_JSON_INVALID"),
				policy,
				boundPolicy.sha256());
		if (!manifest.bundleId().equals(directory.getFileName().toString())) {
			throw failure("HOLDOUT_BUNDLE_ID_INVALID");
		}
		verifyPayloadSizes(manifest, sizes);
		if (manifestBytes.length != verifiedCorpus.manifestBytes
				|| !sha256(manifestBytes).equals(verifiedCorpus.manifestSha256)
				|| !manifest.equals(verifiedCorpus.manifest)) {
			throw failure("HOLDOUT_STAGED_MANIFEST_CHANGED");
		}

		byte[] corpusBytes = readObservedFile(
				directory, CORPUS_FILENAME, sizes, policy);
		if (corpusBytes.length != verifiedCorpus.corpusBytes
				|| !sha256(corpusBytes).equals(verifiedCorpus.rankingCorpus.corpusSha256())) {
			throw failure("HOLDOUT_STAGED_CORPUS_CHANGED");
		}
		verifyPayloadDigest(manifest, CORPUS_FILENAME, corpusBytes);
		Corpus corpus = parseCorpus(
				parseStrict(objectMapper, corpusBytes, "HOLDOUT_CORPUS_JSON_INVALID"),
				manifest,
				policy,
				boundPolicy.sha256());
		if (!corpus.equals(verifiedCorpus.rankingCorpus.corpus())) {
			throw failure("HOLDOUT_STAGED_CORPUS_CHANGED");
		}
		validateCorpusSemantics(policy, corpus, developmentFixture.fixture());
		if (!verifiedCorpus.judgmentReleaseClaimed.compareAndSet(false, true)) {
			throw failure("HOLDOUT_FIRST_RUN_ALREADY_CONSUMED");
		}

		byte[] judgmentBytes = readObservedFile(
				directory, JUDGMENTS_FILENAME, sizes, policy);
		verifyPayloadDigest(manifest, JUDGMENTS_FILENAME, judgmentBytes);
		Judgments judgments = parseJudgments(
				parseStrict(
						objectMapper,
						judgmentBytes,
						"HOLDOUT_JUDGMENTS_JSON_INVALID"),
				manifest,
				policy,
				boundPolicy.sha256());
		validateJudgmentSemantics(policy, corpus, judgments);

		RelatedTopicReuseHoldoutBundle bundle = new RelatedTopicReuseHoldoutBundle(
				manifest.bundleId(),
				manifest.corpusId(),
				sha256(manifestBytes),
				corpus,
				judgments);
		return new VerifiedScoringInputs(
				completedRanking,
				completedRanking.completionAuthority,
				rankingSnapshot,
				boundPolicy,
				bundle);
	}

	private static void validateRankingSnapshot(
			VerifiedCorpus verifiedCorpus,
			RelatedTopicReuseHoldoutRankingSnapshot snapshot,
			RelatedTopicReuseHoldoutPolicy policy) throws IOException {
		RankingCorpus rankingCorpus = verifiedCorpus.rankingCorpus;
		List<String> queryOrder = rankingCorpus.corpus().queries().stream()
				.map(Query::key)
				.toList();
		if (!rankingCorpus.bundleId().equals(snapshot.bundleId())
				|| !rankingCorpus.corpusId().equals(snapshot.corpusId())
				|| !rankingCorpus.policySha256().equals(snapshot.policySha256())
				|| !rankingCorpus.corpusSha256().equals(snapshot.corpusSha256())
				|| !verifiedCorpus.manifestSha256.equals(snapshot.manifestSha256())
				|| !judgmentsCommitment(verifiedCorpus).sha256()
						.equals(snapshot.judgmentsSha256())
				|| judgmentsCommitment(verifiedCorpus).bytes() != snapshot.judgmentsBytes()
				|| !policy.candidateFreezeRevision().equals(snapshot.candidateRevision())
				|| policy.evaluation().cutoff() != snapshot.cutoff()
				|| !queryOrder.equals(snapshot.queryOrder())) {
			throw failure("HOLDOUT_RANKING_SEAL_IDENTITY_INVALID");
		}

		Set<String> corpusKeys = rankingCorpus.corpus().candidates().stream()
				.map(Candidate::key)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		for (RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking query : snapshot.queries()) {
			validateRankingRunKeys(query.initialRun(), corpusKeys);
			validateRankingRunKeys(query.repeatedRun(), corpusKeys);
			validateVisibleFeedbackKeys(
					query.hiddenPerturbation().visibleFeedbackPools(), corpusKeys);
			validateRankedKeys(
					query.hiddenPerturbation().visibleCandidateTop10(), corpusKeys);
			if (corpusKeys.contains(query.hiddenPerturbation().otherOwnerCandidateKey())
					|| corpusKeys.contains(query.hiddenPerturbation().catalogOnlyCandidateKey())) {
				throw failure("HOLDOUT_RANKING_SEAL_SCOPE_INVALID");
			}
		}
	}

	private static ManifestFile judgmentsCommitment(VerifiedCorpus verifiedCorpus)
			throws IOException {
		return manifestFile(verifiedCorpus.manifest, JUDGMENTS_FILENAME);
	}

	private static void validateRankingRunKeys(
			RelatedTopicReuseHoldoutRankingSnapshot.RankingRun run,
			Set<String> corpusKeys) throws IOException {
		validateRankedKeys(run.controlPool(), corpusKeys);
		validateRankedKeys(run.controlTop10(), corpusKeys);
		if (!corpusKeys.containsAll(run.eligibleSeedKeys())) {
			throw failure("HOLDOUT_RANKING_SEAL_SCOPE_INVALID");
		}
		validateVisibleFeedbackKeys(run.feedbackPools(), corpusKeys);
		validateRankedKeys(run.candidateTop10(), corpusKeys);
	}

	private static void validateVisibleFeedbackKeys(
			List<RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool> feedbackPools,
			Set<String> corpusKeys) throws IOException {
		for (RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool pool : feedbackPools) {
			if (!corpusKeys.contains(pool.seedPaperKey())) {
				throw failure("HOLDOUT_RANKING_SEAL_SCOPE_INVALID");
			}
			validateRankedKeys(pool.candidates(), corpusKeys);
		}
	}

	private static void validateRankedKeys(
			List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> papers,
			Set<String> corpusKeys) throws IOException {
		if (papers.stream()
				.map(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
				.anyMatch(key -> !corpusKeys.contains(key))) {
			throw failure("HOLDOUT_RANKING_SEAL_SCOPE_INVALID");
		}
	}

	private static FrozenInputs loadFrozenInputs(ObjectMapper objectMapper) throws IOException {
		try {
			RelatedTopicReuseHoldoutPolicy.BoundPolicy holdoutPolicy =
					RelatedTopicReuseHoldoutPolicy.loadFrozen(objectMapper);
			RelatedTopicReuseEvaluationPolicy.BoundPolicy candidatePolicy =
					RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper);
			RelatedTopicReuseEvaluationFixture.BoundFixture developmentFixture =
					RelatedTopicReuseEvaluationFixture.loadFrozen(objectMapper);
			holdoutPolicy.policy().validateFrozenInputs(candidatePolicy, developmentFixture);
			return new FrozenInputs(holdoutPolicy, developmentFixture);
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_FROZEN_INPUT_INVALID");
		}
	}

	String bundleId() {
		return bundleId;
	}

	String corpusId() {
		return corpusId;
	}

	String manifestSha256() {
		return manifestSha256;
	}

	Corpus corpus() {
		return corpus;
	}

	Judgments judgments() {
		return judgments;
	}

	private static Path verifyExternalDirectory(Path sourceDirectory)
			throws IOException {
		if (!sourceDirectory.isAbsolute()) {
			throw failure("HOLDOUT_PATH_MUST_BE_ABSOLUTE");
		}
		Path repositoryReal = findRepositoryRoot();
		Path sourceNormalized = sourceDirectory.normalize();
		Path sourceReal = realDirectory(sourceDirectory, "HOLDOUT_DIRECTORY_INVALID");
		if (!sourceNormalized.equals(sourceReal)) {
			throw failure("HOLDOUT_DIRECTORY_SYMLINKED");
		}
		if (sourceReal.startsWith(repositoryReal) || repositoryReal.startsWith(sourceReal)) {
			throw failure("HOLDOUT_DIRECTORY_NOT_EXTERNAL");
		}
		return sourceReal;
	}

	private static Path findRepositoryRoot() throws IOException {
		return findRepositoryRoot(Path.of(""));
	}

	static Path findRepositoryRoot(Path startingDirectory) throws IOException {
		if (startingDirectory == null) {
			throw failure("HOLDOUT_REPOSITORY_INVALID");
		}
		Path candidate = startingDirectory.toAbsolutePath().normalize();
		while (candidate != null) {
			try {
				if (Files.exists(candidate.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
						&& Files.isRegularFile(
								candidate.resolve("backend/pom.xml"), LinkOption.NOFOLLOW_LINKS)
						&& Files.isRegularFile(
								candidate.resolve("frontend/package.json"), LinkOption.NOFOLLOW_LINKS)) {
					return realDirectory(candidate, "HOLDOUT_REPOSITORY_INVALID");
				}
			}
			catch (SecurityException exception) {
				throw failure("HOLDOUT_REPOSITORY_INVALID");
			}
			candidate = candidate.getParent();
		}
		throw failure("HOLDOUT_REPOSITORY_INVALID");
	}

	private static Path realDirectory(Path path, String diagnostic) throws IOException {
		try {
			if (Files.isSymbolicLink(path)
					|| !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
				throw failure(diagnostic);
			}
			return path.toRealPath();
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | SecurityException exception) {
			throw failure(diagnostic);
		}
	}

	private static Map<String, Long> verifyLayoutAndSizes(
			Path directory, RelatedTopicReuseHoldoutPolicy policy) throws IOException {
		Set<String> expected = new LinkedHashSet<>(policy.bundle().requiredFiles());
		Set<String> actual = new LinkedHashSet<>();
		List<Path> entries;
		try (var paths = Files.list(directory)) {
			entries = paths.limit(expected.size() + 1L).toList();
		}
		catch (IOException | SecurityException exception) {
			throw failure("HOLDOUT_DIRECTORY_UNREADABLE");
		}
		for (Path entry : entries) {
			actual.add(entry.getFileName().toString());
		}
		if (entries.size() != expected.size() || !actual.equals(expected)) {
			throw failure("HOLDOUT_LAYOUT_INVALID");
		}
		Map<String, Long> sizes = new LinkedHashMap<>();
		long total = 0;
		for (String filename : expected) {
			Path file = directory.resolve(filename);
			BasicFileAttributes attributes;
			try {
				attributes = Files.readAttributes(
						file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			}
			catch (IOException | SecurityException exception) {
				throw failure("HOLDOUT_FILE_UNREADABLE");
			}
			if (Files.isSymbolicLink(file)
					|| !attributes.isRegularFile()
					|| attributes.size() < 1) {
				throw failure("HOLDOUT_FILE_INVALID");
			}
			long maximum = maximumBytes(filename, policy);
			if (attributes.size() > maximum
					|| total > policy.bundle().maximumTotalBytes() - attributes.size()) {
				throw failure("HOLDOUT_BUNDLE_TOO_LARGE");
			}
			total += attributes.size();
			sizes.put(filename, attributes.size());
		}
		return Collections.unmodifiableMap(sizes);
	}

	private static long maximumBytes(
			String filename, RelatedTopicReuseHoldoutPolicy policy) throws IOException {
		return switch (filename) {
			case MANIFEST_FILENAME -> policy.bundle().maximumManifestBytes();
			case CORPUS_FILENAME -> policy.bundle().maximumCorpusBytes();
			case JUDGMENTS_FILENAME -> policy.bundle().maximumJudgmentsBytes();
			default -> throw failure("HOLDOUT_LAYOUT_INVALID");
		};
	}

	private static byte[] readObservedFile(
			Path directory,
			String filename,
			Map<String, Long> observedSizes,
			RelatedTopicReuseHoldoutPolicy policy) throws IOException {
		byte[] bytes = readBounded(
				directory.resolve(filename),
				maximumBytes(filename, policy),
				"HOLDOUT_BUNDLE_TOO_LARGE");
		if (bytes.length != observedSizes.get(filename)) {
			throw failure("HOLDOUT_FILE_CHANGED");
		}
		return bytes;
	}

	private static byte[] readBounded(Path path, long maximum, String diagnostic)
			throws IOException {
		if (maximum < 1 || maximum >= Integer.MAX_VALUE) {
			throw failure("HOLDOUT_INTERNAL_LIMIT_INVALID");
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			byte[] bytes = input.readNBytes((int) maximum + 1);
			if (bytes.length < 1 || bytes.length > maximum) {
				throw failure(diagnostic);
			}
			return bytes;
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | SecurityException exception) {
			throw failure("HOLDOUT_FILE_UNREADABLE");
		}
	}

	private static JsonNode parseStrict(
			ObjectMapper objectMapper, byte[] bytes, String diagnostic) throws IOException {
		try {
			return objectMapper.reader()
					.withoutFeatures(JsonReadFeature.values())
					.without(StreamReadFeature.IGNORE_UNDEFINED)
					.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
					.with(
							DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
							DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.readTree(bytes);
		}
		catch (RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static Manifest parseManifest(
			JsonNode root,
			RelatedTopicReuseHoldoutPolicy policy,
			String policySha256) throws IOException {
		requireExactObject(root, "$", MANIFEST_FIELDS);
		String protocolId = requireText(root.required("protocolId"), "$.protocolId", 3, 100);
		String bundleId = requireKey(root.required("bundleId"), "$.bundleId");
		String policyId = requireText(root.required("policyId"), "$.policyId", 3, 100);
		String digest = requireDigest(root.required("policySha256"), "$.policySha256");
		String corpusId = requireKey(root.required("corpusId"), "$.corpusId");
		long payloadBytes = requireNonNegativeLong(
				root.required("payloadBytes"), "$.payloadBytes");
		if (requireInteger(root.required("schemaVersion"), "$.schemaVersion") != 1
				|| !protocolId.equals(policy.bundle().protocolId())
				|| !policyId.equals(policy.policyId())
				|| !digest.equals(policySha256)) {
			throw failure("HOLDOUT_MANIFEST_IDENTITY_INVALID");
		}
		List<ManifestFile> files = parseManifestFiles(root.required("files"));
		long declaredBytes = 0;
		for (ManifestFile file : files) {
			try {
				declaredBytes = Math.addExact(declaredBytes, file.bytes());
			}
			catch (ArithmeticException exception) {
				throw failure("HOLDOUT_MANIFEST_SIZE_INVALID");
			}
		}
		if (payloadBytes != declaredBytes
				|| payloadBytes > policy.bundle().maximumTotalBytes()) {
			throw failure("HOLDOUT_MANIFEST_SIZE_INVALID");
		}
		validateDeclarations(root.required("declarations"), policy.requiredDeclarations());
		return new Manifest(
				protocolId, bundleId, policyId, digest, corpusId, payloadBytes, files);
	}

	private static List<ManifestFile> parseManifestFiles(JsonNode node) throws IOException {
		JsonNode values = requireArray(node, "$.files");
		if (values.size() != PAYLOAD_FILENAMES.size()) {
			throw failure("HOLDOUT_MANIFEST_FILES_INVALID");
		}
		List<ManifestFile> files = new ArrayList<>();
		for (int index = 0; index < values.size(); index++) {
			String path = "$.files[" + index + "]";
			JsonNode value = values.get(index);
			requireExactObject(value, path, MANIFEST_FILE_FIELDS);
			String filename = requireText(value.required("filename"), path + ".filename", 1, 100);
			long bytes = requireNonNegativeLong(value.required("bytes"), path + ".bytes");
			String digest = requireDigest(value.required("sha256"), path + ".sha256");
			if (!filename.equals(PAYLOAD_FILENAMES.get(index)) || bytes < 1) {
				throw failure("HOLDOUT_MANIFEST_FILES_INVALID");
			}
			files.add(new ManifestFile(filename, bytes, digest));
		}
		return List.copyOf(files);
	}

	private static void validateDeclarations(
			JsonNode node, RelatedTopicReuseHoldoutPolicy.RequiredDeclarations declarations)
			throws IOException {
		requireExactObject(node, "$.declarations", DECLARATION_FIELDS);
		if (!requireText(node.required("corpusAuthorship"), "$.declarations.corpusAuthorship", 3, 200)
					.equals(declarations.corpusAuthorship())
				|| !requireText(
						node.required("judgmentAuthorship"),
						"$.declarations.judgmentAuthorship",
						3,
						200).equals(declarations.judgmentAuthorship())
				|| !requireText(node.required("firstRunRule"), "$.declarations.firstRunRule", 3, 200)
						.equals(declarations.firstRunRule())
				|| !requireText(node.required("noRetuningRule"), "$.declarations.noRetuningRule", 3, 200)
						.equals(declarations.noRetuningRule())
				|| !requireText(
						node.required("externalCustodyRule"),
						"$.declarations.externalCustodyRule",
						3,
						200).equals(declarations.externalCustodyRule())
				|| !requireText(
						node.required("evaluatorFreezeRule"),
						"$.declarations.evaluatorFreezeRule",
						3,
						200).equals(declarations.evaluatorFreezeRule())
				|| !requireTextArray(
						node.required("limitations"),
						"$.declarations.limitations",
						1,
						10,
						3,
						200).equals(declarations.requiredLimitations())) {
			throw failure("HOLDOUT_DECLARATIONS_INVALID");
		}
	}

	private static void verifyPayloadSizes(
			Manifest manifest, Map<String, Long> observedSizes) throws IOException {
		long observedPayloadBytes = 0;
		for (String filename : PAYLOAD_FILENAMES) {
			observedPayloadBytes += observedSizes.get(filename);
		}
		if (manifest.payloadBytes() != observedPayloadBytes) {
			throw failure("HOLDOUT_PAYLOAD_SIZE_MISMATCH");
		}
		for (ManifestFile file : manifest.files()) {
			if (!Objects.equals(observedSizes.get(file.filename()), file.bytes())) {
				throw failure("HOLDOUT_PAYLOAD_SIZE_MISMATCH");
			}
		}
	}

	private static void verifyPayloadDigest(
			Manifest manifest, String filename, byte[] bytes) throws IOException {
		if (!sha256(bytes).equals(manifestFile(manifest, filename).sha256())) {
			throw failure("HOLDOUT_PAYLOAD_DIGEST_MISMATCH");
		}
	}

	private static ManifestFile manifestFile(Manifest manifest, String filename)
			throws IOException {
		return manifest.files().stream()
				.filter(file -> file.filename().equals(filename))
				.findFirst()
				.orElseThrow(() -> failure("HOLDOUT_MANIFEST_FILES_INVALID"));
	}

	private static Corpus parseCorpus(
			JsonNode root,
			Manifest manifest,
			RelatedTopicReuseHoldoutPolicy policy,
			String policySha256) throws IOException {
		requireExactObject(root, "$", CORPUS_FIELDS);
		validateDocumentIdentity(root, manifest, policy, policySha256);
		if (!requireText(root.required("split"), "$.split", 3, 40)
					.equals(String.valueOf(policy.corpus().split()))
				|| !requireText(root.required("labelUnit"), "$.labelUnit", 3, 100)
						.equals(String.valueOf(policy.labelUnit()))
				|| !requireText(root.required("sourcePolicy"), "$.sourcePolicy", 3, 100)
						.equals(String.valueOf(policy.sourcePolicy()))) {
			throw failure("HOLDOUT_CORPUS_BOUNDARY_INVALID");
		}
		return new Corpus(
				manifest.corpusId(),
				parseLineages(root.required("lineages"), "$.lineages"),
				parseCandidates(root.required("candidates"), "$.candidates"),
				parseQueries(root.required("queries"), "$.queries"));
	}

	private static Judgments parseJudgments(
			JsonNode root,
			Manifest manifest,
			RelatedTopicReuseHoldoutPolicy policy,
			String policySha256) throws IOException {
		requireExactObject(root, "$", JUDGMENTS_FIELDS);
		validateDocumentIdentity(root, manifest, policy, policySha256);
		if (!requireText(root.required("labelUnit"), "$.labelUnit", 3, 100)
				.equals(String.valueOf(policy.labelUnit()))) {
			throw failure("HOLDOUT_JUDGMENTS_BOUNDARY_INVALID");
		}
		JsonNode values = requireArray(root.required("queries"), "$.queries");
		List<QueryJudgments> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			String path = "$.queries[" + index + "]";
			JsonNode value = values.get(index);
			requireExactObject(value, path, QUERY_JUDGMENTS_FIELDS);
			String queryKey = requireKey(value.required("queryKey"), path + ".queryKey");
			Map<String, Integer> grades = parseGrades(
					value.required("grades"), path + ".grades", policy.judgments());
			List<Adversary> adversaries = parseAdversaries(
					value.required("adversaries"), path + ".adversaries");
			result.add(new QueryJudgments(queryKey, grades, adversaries));
		}
		return new Judgments(manifest.corpusId(), result);
	}

	private static void validateDocumentIdentity(
			JsonNode root,
			Manifest manifest,
			RelatedTopicReuseHoldoutPolicy policy,
			String policySha256) throws IOException {
		if (requireInteger(root.required("schemaVersion"), "$.schemaVersion") != 1
				|| !requireText(root.required("protocolId"), "$.protocolId", 3, 100)
						.equals(manifest.protocolId())
				|| !requireText(root.required("bundleId"), "$.bundleId", 3, 100)
						.equals(manifest.bundleId())
				|| !requireText(root.required("policyId"), "$.policyId", 3, 100)
						.equals(policy.policyId())
				|| !requireDigest(root.required("policySha256"), "$.policySha256")
						.equals(policySha256)
				|| !requireText(root.required("corpusId"), "$.corpusId", 3, 100)
						.equals(manifest.corpusId())) {
			throw failure("HOLDOUT_DOCUMENT_IDENTITY_INVALID");
		}
	}

	private static List<Lineage> parseLineages(JsonNode node, String path) throws IOException {
		JsonNode values = requireArray(node, path);
		List<Lineage> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			String itemPath = path + "[" + index + "]";
			JsonNode value = values.get(index);
			requireExactObject(value, itemPath, LINEAGE_FIELDS);
			result.add(new Lineage(
					requireKey(value.required("key"), itemPath + ".key"),
					requireEnum(value.required("kind"), itemPath + ".kind", LineageKind.class)));
		}
		return List.copyOf(result);
	}

	private static List<Candidate> parseCandidates(JsonNode node, String path) throws IOException {
		JsonNode values = requireArray(node, path);
		List<Candidate> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			String itemPath = path + "[" + index + "]";
			JsonNode value = values.get(index);
			requireExactObject(value, itemPath, CANDIDATE_FIELDS);
			Integer publicationYear = requireNullableInteger(
					value.required("publicationYear"), itemPath + ".publicationYear");
			Integer citationCount = requireNullableInteger(
					value.required("citationCount"), itemPath + ".citationCount");
			if (publicationYear != null && (publicationYear < 1000 || publicationYear > 9999)
					|| citationCount != null && citationCount < 0) {
				throw failure("HOLDOUT_CANDIDATE_BOUNDARY_INVALID");
			}
			result.add(new Candidate(
					requireKey(value.required("key"), itemPath + ".key"),
					requireKey(value.required("lineageKey"), itemPath + ".lineageKey"),
					requireText(value.required("title"), itemPath + ".title", 3, 300),
					requireNullableText(value.required("abstractText"), itemPath + ".abstractText", 3, 4000),
					requireNullableText(value.required("venueName"), itemPath + ".venueName", 2, 300),
					publicationYear,
					requireEnum(value.required("documentType"), itemPath + ".documentType", DocumentType.class),
					requireLanguage(value.required("language"), itemPath + ".language"),
					citationCount,
					requireBoolean(value.required("reportedOpenAccess"), itemPath + ".reportedOpenAccess"),
					requireTextArray(value.required("authors"), itemPath + ".authors", 0, 10, 2, 200)));
		}
		return List.copyOf(result);
	}

	private static List<Query> parseQueries(JsonNode node, String path) throws IOException {
		JsonNode values = requireArray(node, path);
		List<Query> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			String itemPath = path + "[" + index + "]";
			JsonNode value = values.get(index);
			requireExactObject(value, itemPath, QUERY_FIELDS);
			result.add(new Query(
					requireKey(value.required("key"), itemPath + ".key"),
					requireText(value.required("text"), itemPath + ".text", 3, 500),
					requireEnum(value.required("kind"), itemPath + ".kind", QueryKind.class),
					requireInteger(value.required("cutoff"), itemPath + ".cutoff"),
					parseFilter(value.required("filters"), itemPath + ".filters")));
		}
		return List.copyOf(result);
	}

	private static Filter parseFilter(JsonNode node, String path) throws IOException {
		requireExactObject(node, path, FILTER_FIELDS);
		Integer yearFrom = requireNullableInteger(node.required("yearFrom"), path + ".yearFrom");
		Integer yearTo = requireNullableInteger(node.required("yearTo"), path + ".yearTo");
		int minimumCitations = requireInteger(
				node.required("minimumCitations"), path + ".minimumCitations");
		if (yearFrom != null && (yearFrom < 1000 || yearFrom > 9999)
				|| yearTo != null && (yearTo < 1000 || yearTo > 9999)
				|| yearFrom != null && yearTo != null && yearFrom > yearTo
				|| minimumCitations < 0) {
			throw failure("HOLDOUT_FILTER_BOUNDARY_INVALID");
		}
		return new Filter(
				yearFrom,
				yearTo,
				requireEnumArray(node.required("documentTypes"), path + ".documentTypes", DocumentType.class),
				requireBoolean(node.required("openAccessOnly"), path + ".openAccessOnly"),
				minimumCitations,
				requireLanguageArray(node.required("languages"), path + ".languages"));
	}

	private static Map<String, Integer> parseGrades(
			JsonNode node,
			String path,
			RelatedTopicReuseHoldoutPolicy.JudgmentsContract contract)
			throws IOException {
		if (node == null || !node.isObject()) {
			throw failure("HOLDOUT_GRADES_INVALID");
		}
		Map<String, Integer> result = new LinkedHashMap<>();
		for (String propertyName : node.propertyNames()) {
			String key = requireKeyValue(propertyName, path + " key");
			int grade = requireInteger(node.required(propertyName), path + "." + key);
			if (grade < contract.minimumRelevanceGrade()
					|| grade > contract.maximumRelevanceGrade()) {
				throw failure("HOLDOUT_GRADE_OUT_OF_RANGE");
			}
			result.put(key, grade);
		}
		return Map.copyOf(result);
	}

	private static List<Adversary> parseAdversaries(JsonNode node, String path)
			throws IOException {
		JsonNode values = requireArray(node, path);
		List<Adversary> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			String itemPath = path + "[" + index + "]";
			JsonNode value = values.get(index);
			requireExactObject(value, itemPath, ADVERSARY_FIELDS);
			result.add(new Adversary(
					requireKey(value.required("candidateKey"), itemPath + ".candidateKey"),
					requireEnum(value.required("kind"), itemPath + ".kind", AdversaryKind.class),
					requireText(value.required("reason"), itemPath + ".reason", 10, 300)));
		}
		return List.copyOf(result);
	}

	private static void validateCorpusSemantics(
			RelatedTopicReuseHoldoutPolicy policy,
			Corpus corpus,
			RelatedTopicReuseEvaluationFixture development) throws IOException {
		var boundary = policy.corpus();
		if (corpus.queries().size() < boundary.minimumQueryCount()
				|| corpus.queries().size() > boundary.maximumQueryCount()
				|| corpus.candidates().size() < boundary.minimumCandidateCount()
				|| corpus.candidates().size() > boundary.maximumCandidateCount()) {
			throw failure("HOLDOUT_CORPUS_SHAPE_INVALID");
		}
		Map<String, Lineage> lineages = uniqueByKey(corpus.lineages(), "lineage");
		Map<String, Candidate> candidates = uniqueByKey(corpus.candidates(), "candidate");
		Map<String, Query> queries = uniqueByKey(corpus.queries(), "query");

		Map<LineageKind, Integer> lineageCandidateCounts = new EnumMap<>(LineageKind.class);
		for (Candidate candidate : candidates.values()) {
			Lineage lineage = lineages.get(candidate.lineageKey());
			if (lineage == null) {
				throw failure("HOLDOUT_CANDIDATE_LINEAGE_INVALID");
			}
			lineageCandidateCounts.merge(lineage.kind(), 1, Integer::sum);
		}
		Set<String> usedLineageKeys = candidates.values().stream()
				.map(Candidate::lineageKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (!usedLineageKeys.equals(lineages.keySet())) {
			throw failure("HOLDOUT_UNUSED_LINEAGE_INVALID");
		}
		Set<String> requiredLineages = names(boundary.requiredLineageKinds());
		if (!lineageCandidateCounts.keySet().stream()
					.map(Enum::name)
					.collect(java.util.stream.Collectors.toUnmodifiableSet())
					.containsAll(requiredLineages)) {
			throw failure("HOLDOUT_REQUIRED_LINEAGE_KIND_MISSING");
		}

		long targetVisibleCount = candidates.values().stream()
				.filter(candidate -> lineages.get(candidate.lineageKey()).kind().targetVisible())
				.count();
		long otherOwnerCount = candidates.values().stream()
				.filter(candidate -> lineages.get(candidate.lineageKey()).kind().otherOwner())
				.count();
		long catalogOnlyCount = candidates.values().stream()
				.filter(candidate -> lineages.get(candidate.lineageKey()).kind() == LineageKind.CATALOG_ONLY)
				.count();
		if (targetVisibleCount < boundary.minimumTargetVisibleCandidateCount()
				|| otherOwnerCount < boundary.minimumOtherOwnerCandidateCount()
				|| catalogOnlyCount < boundary.minimumCatalogOnlyCandidateCount()) {
			throw failure("HOLDOUT_VISIBILITY_SHAPE_INVALID");
		}

		Map<QueryKind, Integer> queryKindCounts = new EnumMap<>(QueryKind.class);
		int fullyFiltered = 0;
		int opportunities = 0;
		int controls = 0;
		int noSeedControls = 0;
		Set<String> normalizedQueries = new LinkedHashSet<>();
		for (Query query : queries.values()) {
			queryKindCounts.merge(query.kind(), 1, Integer::sum);
			if (!normalizedQueries.add(normalize(query.text()))) {
				throw failure("HOLDOUT_QUERY_TEXT_DUPLICATE");
			}
			if (query.cutoff() != policy.gates().cutoff()) {
				throw failure("HOLDOUT_QUERY_CUTOFF_INVALID");
			}
			if (query.kind().opportunity()) {
				opportunities++;
			}
			else {
				controls++;
			}
			if (query.kind() == QueryKind.NO_SEED_FALLBACK_CONTROL) {
				noSeedControls++;
			}
			if (query.kind() == QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY) {
				if (!query.filters().exercisesEveryDimension()) {
					throw failure("HOLDOUT_FILTERED_QUERY_INCOMPLETE");
				}
				fullyFiltered++;
			}
			else if (!query.filters().isEmpty()) {
				throw failure("HOLDOUT_UNEXPECTED_QUERY_FILTER");
			}
		}
		Set<String> requiredQueries = names(boundary.requiredQueryKinds());
		Set<String> actualQueries = queryKindCounts.keySet().stream()
				.map(Enum::name)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (!actualQueries.containsAll(requiredQueries)
				|| opportunities < boundary.minimumOpportunityQueryCount()
				|| controls < boundary.minimumControlQueryCount()
				|| fullyFiltered < boundary.minimumFullyFilteredQueryCount()
				|| noSeedControls < boundary.minimumNoSeedControlCount()) {
			throw failure("HOLDOUT_QUERY_KIND_SHAPE_INVALID");
		}
		validateDevelopmentDisjointness(corpus, development);
	}

	private static void validateJudgmentSemantics(
			RelatedTopicReuseHoldoutPolicy policy,
			Corpus corpus,
			Judgments judgments) throws IOException {
		Map<String, Lineage> lineages = uniqueByKey(corpus.lineages(), "lineage");
		Map<String, Candidate> candidates = uniqueByKey(corpus.candidates(), "candidate");
		Map<String, Query> queries = uniqueByKey(corpus.queries(), "query");
		Map<String, QueryJudgments> queryJudgments = uniqueByKey(
				judgments.queries(), "judgments query");
		if (!queries.keySet().equals(queryJudgments.keySet())) {
			throw failure("HOLDOUT_QUERY_JUDGMENTS_INCOMPLETE");
		}

		Set<String> targetVisible = candidates.values().stream()
				.filter(candidate -> lineages.get(candidate.lineageKey()).kind().targetVisible())
				.map(Candidate::key)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		EnumSet<AdversaryKind> representedAdversaries = EnumSet.noneOf(AdversaryKind.class);
		for (Query query : queries.values()) {
			QueryJudgments labels = queryJudgments.get(query.key());
			if (!labels.grades().keySet().equals(targetVisible)) {
				throw failure("HOLDOUT_TARGET_JUDGMENTS_INCOMPLETE");
			}
			validateQueryJudgments(query, labels.grades());
			validateAdversaries(
					query, labels, candidates, lineages, representedAdversaries);
		}

		Set<String> requiredAdversaries = names(policy.judgments().requiredAdversaryKinds());
		Set<String> actualAdversaries = representedAdversaries.stream()
				.map(Enum::name)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (!actualAdversaries.containsAll(requiredAdversaries)) {
			throw failure("HOLDOUT_ADVERSARY_KIND_MISSING");
		}
	}

	private static void validateQueryJudgments(Query query, Map<String, Integer> grades)
			throws IOException {
		long relevant = grades.values().stream().filter(grade -> grade > 0).count();
		switch (query.kind()) {
			case LEXICAL_BRIDGE_OPPORTUNITY, FILTERED_LEXICAL_BRIDGE_OPPORTUNITY -> {
				if (relevant < 2
						|| !grades.containsValue(3)
						|| grades.values().stream().noneMatch(grade -> grade == 1 || grade == 2)) {
					throw failure("HOLDOUT_OPPORTUNITY_JUDGMENTS_INVALID");
				}
			}
			case AUTHOR_NO_RELATED_SIGNAL_CONTROL -> {
				if (relevant != 1 || !grades.containsValue(3)) {
					throw failure("HOLDOUT_AUTHOR_CONTROL_JUDGMENTS_INVALID");
				}
			}
			case NO_SEED_FALLBACK_CONTROL -> {
				if (relevant != 0) {
					throw failure("HOLDOUT_NO_SEED_JUDGMENTS_INVALID");
				}
			}
		}
	}

	private static void validateAdversaries(
			Query query,
			QueryJudgments labels,
			Map<String, Candidate> candidates,
			Map<String, Lineage> lineages,
			EnumSet<AdversaryKind> represented) throws IOException {
		if (labels.adversaries().isEmpty()) {
			throw failure("HOLDOUT_QUERY_ADVERSARY_MISSING");
		}
		Set<String> keys = new LinkedHashSet<>();
		EnumSet<FilterDimension> filterDimensions = EnumSet.noneOf(FilterDimension.class);
		for (Adversary adversary : labels.adversaries()) {
			if (!keys.add(adversary.candidateKey())) {
				throw failure("HOLDOUT_QUERY_ADVERSARY_DUPLICATE");
			}
			Candidate candidate = candidates.get(adversary.candidateKey());
			if (candidate == null) {
				throw failure("HOLDOUT_ADVERSARY_CANDIDATE_UNKNOWN");
			}
			LineageKind lineageKind = lineages.get(candidate.lineageKey()).kind();
			switch (adversary.kind()) {
				case OWNER_VISIBLE_TOPIC_DRIFT -> {
					requireTargetGradeZero(labels, candidate, lineageKind);
					if (!query.kind().opportunity()) {
						throw failure("HOLDOUT_TOPIC_DRIFT_BOUNDARY_INVALID");
					}
				}
				case FILTER_VIOLATION -> {
					requireTargetGradeZero(labels, candidate, lineageKind);
					EnumSet<FilterDimension> violations = violatedDimensions(
							candidate, query.filters());
					if (query.kind() != QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY
							|| violations.isEmpty()) {
						throw failure("HOLDOUT_FILTER_ADVERSARY_INVALID");
					}
					if (violations.size() != 1) {
						throw failure("HOLDOUT_FILTER_ADVERSARY_NOT_ISOLATED");
					}
					filterDimensions.addAll(violations);
				}
				case AUTHOR_SUBSTRING_COLLISION -> {
					requireTargetGradeZero(labels, candidate, lineageKind);
					if (query.kind() != QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL) {
						throw failure("HOLDOUT_AUTHOR_ADVERSARY_INVALID");
					}
				}
				case OTHER_OWNER_TOPIC_MATCH -> {
					if (!lineageKind.otherOwner()) {
						throw failure("HOLDOUT_OTHER_OWNER_ADVERSARY_INVALID");
					}
				}
				case CATALOG_ONLY_TOPIC_MATCH -> {
					if (lineageKind != LineageKind.CATALOG_ONLY) {
						throw failure("HOLDOUT_CATALOG_ADVERSARY_INVALID");
					}
				}
			}
			represented.add(adversary.kind());
		}
		if (query.kind() == QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY) {
			for (Map.Entry<String, Integer> grade : labels.grades().entrySet()) {
				if (grade.getValue() > 0
						&& !RelatedTopicReuseHoldoutCandidateFilters.matches(
								candidates.get(grade.getKey()), query.filters())) {
					throw failure("HOLDOUT_FILTERED_RELEVANT_INVALID");
				}
			}
			if (!filterDimensions.equals(EnumSet.allOf(FilterDimension.class))) {
				throw failure("HOLDOUT_FILTER_DIMENSION_COVERAGE_INVALID");
			}
		}
	}

	private static void requireTargetGradeZero(
			QueryJudgments labels, Candidate candidate, LineageKind lineageKind)
			throws IOException {
		if (!lineageKind.targetVisible()
				|| labels.grades().getOrDefault(candidate.key(), -1) != 0) {
			throw failure("HOLDOUT_TARGET_GRADE_ZERO_REQUIRED");
		}
	}

	private static EnumSet<FilterDimension> violatedDimensions(
			Candidate candidate, Filter filter) {
		EnumSet<FilterDimension> dimensions = EnumSet.noneOf(FilterDimension.class);
		if (filter.yearFrom() != null && (candidate.publicationYear() == null
				|| candidate.publicationYear() < filter.yearFrom())) {
			dimensions.add(FilterDimension.YEAR_FROM);
		}
		if (filter.yearTo() != null && (candidate.publicationYear() == null
				|| candidate.publicationYear() > filter.yearTo())) {
			dimensions.add(FilterDimension.YEAR_TO);
		}
		if (!filter.documentTypes().isEmpty()
				&& !filter.documentTypes().contains(candidate.documentType())) {
			dimensions.add(FilterDimension.DOCUMENT_TYPE);
		}
		if (filter.openAccessOnly() && !candidate.reportedOpenAccess()) {
			dimensions.add(FilterDimension.OPEN_ACCESS);
		}
		if ((candidate.citationCount() == null ? 0 : candidate.citationCount())
				< filter.minimumCitations()) {
			dimensions.add(FilterDimension.MINIMUM_CITATIONS);
		}
		if (!filter.languages().isEmpty() && !filter.languages().contains(candidate.language())) {
			dimensions.add(FilterDimension.LANGUAGE);
		}
		return dimensions;
	}

	private static void validateDevelopmentDisjointness(
			Corpus corpus, RelatedTopicReuseEvaluationFixture development) throws IOException {
		Set<String> developmentKeys = development.candidates().stream()
				.map(RelatedTopicReuseEvaluationFixture.Candidate::key)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (corpus.candidates().stream().map(Candidate::key).anyMatch(developmentKeys::contains)) {
			throw failure("HOLDOUT_CANDIDATE_KEY_OVERLAP");
		}
		Set<String> developmentQueryKeys = development.queries().stream()
				.map(RelatedTopicReuseEvaluationFixture.Query::key)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (corpus.queries().stream().map(Query::key).anyMatch(developmentQueryKeys::contains)) {
			throw failure("HOLDOUT_QUERY_KEY_OVERLAP");
		}
		Set<String> developmentQueries = development.queries().stream()
				.map(RelatedTopicReuseEvaluationFixture.Query::text)
				.map(RelatedTopicReuseHoldoutBundle::normalize)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (corpus.queries().stream().map(Query::text).map(RelatedTopicReuseHoldoutBundle::normalize)
				.anyMatch(developmentQueries::contains)) {
			throw failure("HOLDOUT_QUERY_TEXT_OVERLAP");
		}
		Set<String> developmentTitles = development.candidates().stream()
				.map(RelatedTopicReuseEvaluationFixture.Candidate::title)
				.map(RelatedTopicReuseHoldoutBundle::normalize)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		Set<String> holdoutTitles = new LinkedHashSet<>();
		for (Candidate candidate : corpus.candidates()) {
			String normalized = normalize(candidate.title());
			if (!holdoutTitles.add(normalized)) {
				throw failure("HOLDOUT_TITLE_DUPLICATE");
			}
			if (developmentTitles.contains(normalized)) {
				throw failure("HOLDOUT_TITLE_OVERLAP");
			}
		}
	}

	private static String normalize(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC)
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ")
				.strip();
	}

	private static <T extends Keyed> Map<String, T> uniqueByKey(
			List<T> values, String kind) throws IOException {
		Map<String, T> result = new LinkedHashMap<>();
		for (T value : values) {
			if (result.put(value.key(), value) != null) {
				throw failure("HOLDOUT_" + kind.toUpperCase(Locale.ROOT).replace(' ', '_')
						+ "_KEY_DUPLICATE");
			}
		}
		return Collections.unmodifiableMap(result);
	}

	private static Set<String> names(List<?> values) {
		return values.stream()
				.map(String::valueOf)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static void requireExactObject(JsonNode node, String path, Set<String> fields)
			throws IOException {
		if (node == null || !node.isObject()
				|| !new LinkedHashSet<>(node.propertyNames()).equals(fields)) {
			throw failure("HOLDOUT_SCHEMA_INVALID_AT_" + path);
		}
	}

	private static JsonNode requireArray(JsonNode node, String path) throws IOException {
		if (node == null || !node.isArray()) {
			throw failure("HOLDOUT_ARRAY_REQUIRED_AT_" + path);
		}
		return node;
	}

	private static int requireInteger(JsonNode node, String path) throws IOException {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
			throw failure("HOLDOUT_INTEGER_REQUIRED_AT_" + path);
		}
		return node.intValue();
	}

	private static long requireNonNegativeLong(JsonNode node, String path) throws IOException {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
				|| node.longValue() < 0) {
			throw failure("HOLDOUT_NON_NEGATIVE_INTEGER_REQUIRED_AT_" + path);
		}
		return node.longValue();
	}

	private static Integer requireNullableInteger(JsonNode node, String path)
			throws IOException {
		return node != null && node.isNull() ? null : requireInteger(node, path);
	}

	private static boolean requireBoolean(JsonNode node, String path) throws IOException {
		if (node == null || !node.isBoolean()) {
			throw failure("HOLDOUT_BOOLEAN_REQUIRED_AT_" + path);
		}
		return node.booleanValue();
	}

	private static String requireText(JsonNode node, String path, int minimum, int maximum)
			throws IOException {
		if (node == null || !node.isTextual()) {
			throw failure("HOLDOUT_TEXT_REQUIRED_AT_" + path);
		}
		return requireTextValue(node.textValue(), path, minimum, maximum);
	}

	private static String requireNullableText(
			JsonNode node, String path, int minimum, int maximum) throws IOException {
		return node != null && node.isNull()
				? null
				: requireText(node, path, minimum, maximum);
	}

	private static String requireTextValue(
			String value, String path, int minimum, int maximum) throws IOException {
		if (value == null
				|| !value.equals(value.strip())
				|| value.length() < minimum
				|| value.length() > maximum
				|| !Normalizer.isNormalized(value, Normalizer.Form.NFC)
				|| value.codePoints().anyMatch(Character::isISOControl)) {
			throw failure("HOLDOUT_TEXT_BOUNDARY_INVALID_AT_" + path);
		}
		return value;
	}

	private static String requireKey(JsonNode node, String path) throws IOException {
		return requireKeyValue(requireText(node, path, 3, 100), path);
	}

	private static String requireKeyValue(String value, String path) throws IOException {
		String key = requireTextValue(value, path, 3, 100);
		if (!SAFE_KEY.matcher(key).matches()) {
			throw failure("HOLDOUT_KEY_INVALID_AT_" + path);
		}
		return key;
	}

	private static String requireDigest(JsonNode node, String path) throws IOException {
		String digest = requireText(node, path, 64, 64);
		if (!SHA256.matcher(digest).matches()) {
			throw failure("HOLDOUT_SHA256_INVALID_AT_" + path);
		}
		return digest;
	}

	private static String requireLanguage(JsonNode node, String path) throws IOException {
		String language = requireText(node, path, 2, 8);
		if (!LANGUAGE.matcher(language).matches()) {
			throw failure("HOLDOUT_LANGUAGE_INVALID_AT_" + path);
		}
		return language;
	}

	private static List<String> requireLanguageArray(JsonNode node, String path)
			throws IOException {
		JsonNode values = requireArray(node, path);
		List<String> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			result.add(requireLanguage(values.get(index), path + "[" + index + "]"));
		}
		requireUnique(result, path);
		return List.copyOf(result);
	}

	private static List<String> requireTextArray(
			JsonNode node,
			String path,
			int minimumSize,
			int maximumSize,
			int minimumLength,
			int maximumLength) throws IOException {
		JsonNode values = requireArray(node, path);
		if (values.size() < minimumSize || values.size() > maximumSize) {
			throw failure("HOLDOUT_ARRAY_SIZE_INVALID_AT_" + path);
		}
		List<String> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			result.add(requireText(
					values.get(index), path + "[" + index + "]", minimumLength, maximumLength));
		}
		requireUnique(result, path);
		return List.copyOf(result);
	}

	private static <E extends Enum<E>> List<E> requireEnumArray(
			JsonNode node, String path, Class<E> enumType) throws IOException {
		JsonNode values = requireArray(node, path);
		List<E> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			result.add(requireEnum(values.get(index), path + "[" + index + "]", enumType));
		}
		requireUnique(result, path);
		return List.copyOf(result);
	}

	private static void requireUnique(List<?> values, String path) throws IOException {
		if (new LinkedHashSet<>(values).size() != values.size()) {
			throw failure("HOLDOUT_DUPLICATE_VALUE_AT_" + path);
		}
	}

	private static <E extends Enum<E>> E requireEnum(
			JsonNode node, String path, Class<E> enumType) throws IOException {
		String value = requireText(node, path, 1, 100);
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException exception) {
			throw failure("HOLDOUT_ENUM_INVALID_AT_" + path);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static VerificationException failure(String diagnostic) {
		return new VerificationException(diagnostic);
	}

	private interface Keyed {
		String key();
	}

	private record FrozenInputs(
			RelatedTopicReuseHoldoutPolicy.BoundPolicy holdoutPolicy,
			RelatedTopicReuseEvaluationFixture.BoundFixture developmentFixture) {
	}

	@FunctionalInterface
	interface LabelFreeRankingPhase {

		RelatedTopicReuseHoldoutRankingSnapshot.Observation rank(RankingCorpus corpus)
				throws IOException;
	}

	static final class VerifiedCorpus {

		private final Object completionAuthority = new Object();
		private final AtomicBoolean judgmentReleaseClaimed = new AtomicBoolean();
		private final Manifest manifest;
		private final String manifestSha256;
		private final long manifestBytes;
		private final RankingCorpus rankingCorpus;
		private final long corpusBytes;
		private final VerifiedFirstRunCommitment firstRunCommitment;

		private VerifiedCorpus(
				Manifest manifest,
				String manifestSha256,
				long manifestBytes,
				String evaluationProtocolId,
				RankingCorpus rankingCorpus,
				long corpusBytes) {
			this.manifest = Objects.requireNonNull(manifest);
			this.manifestSha256 = Objects.requireNonNull(manifestSha256);
			this.manifestBytes = manifestBytes;
			this.rankingCorpus = Objects.requireNonNull(rankingCorpus);
			this.corpusBytes = corpusBytes;
			ManifestFile judgmentCommitment = manifest.files().get(1);
			if (!JUDGMENTS_FILENAME.equals(judgmentCommitment.filename())) {
				throw new IllegalArgumentException("verified corpus judgment commitment is invalid");
			}
			this.firstRunCommitment = new VerifiedFirstRunCommitment(
					completionAuthority,
					evaluationProtocolId,
					manifest.protocolId(),
					manifest.bundleId(),
					manifest.corpusId(),
					manifest.policyId(),
					manifest.policySha256(),
					manifestSha256,
					manifestBytes,
					rankingCorpus.corpusSha256(),
					corpusBytes,
					judgmentCommitment.sha256(),
					judgmentCommitment.bytes());
		}

		RankingCorpus rankingCorpus() {
			return rankingCorpus;
		}

		VerifiedFirstRunCommitment firstRunCommitment() {
			return firstRunCommitment;
		}
	}

	/** Label-free immutable commitment used only to claim the durable first run. */
	static final class VerifiedFirstRunCommitment {

		private final String evaluationProtocolId;
		private final Object corpusAuthority;
		private final String bundleProtocolId;
		private final String bundleId;
		private final String corpusId;
		private final String policyId;
		private final String policySha256;
		private final String manifestSha256;
		private final long manifestBytes;
		private final String corpusSha256;
		private final long corpusBytes;
		private final String judgmentsSha256;
		private final long judgmentsBytes;

		private VerifiedFirstRunCommitment(
				Object corpusAuthority,
				String evaluationProtocolId,
				String bundleProtocolId,
				String bundleId,
				String corpusId,
				String policyId,
				String policySha256,
				String manifestSha256,
				long manifestBytes,
				String corpusSha256,
				long corpusBytes,
				String judgmentsSha256,
				long judgmentsBytes) {
			this.corpusAuthority = Objects.requireNonNull(corpusAuthority, "corpusAuthority");
			this.evaluationProtocolId = Objects.requireNonNull(
					evaluationProtocolId, "evaluationProtocolId");
			this.bundleProtocolId = Objects.requireNonNull(
					bundleProtocolId, "bundleProtocolId");
			this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
			this.corpusId = Objects.requireNonNull(corpusId, "corpusId");
			this.policyId = Objects.requireNonNull(policyId, "policyId");
			this.policySha256 = Objects.requireNonNull(policySha256, "policySha256");
			this.manifestSha256 = Objects.requireNonNull(
					manifestSha256, "manifestSha256");
			if (manifestBytes < 1) {
				throw new IllegalArgumentException("invalid manifest commitment size");
			}
			this.manifestBytes = manifestBytes;
			this.corpusSha256 = Objects.requireNonNull(corpusSha256, "corpusSha256");
			if (corpusBytes < 1) {
				throw new IllegalArgumentException("invalid corpus commitment size");
			}
			this.corpusBytes = corpusBytes;
			this.judgmentsSha256 = Objects.requireNonNull(
					judgmentsSha256, "judgmentsSha256");
			if (judgmentsBytes < 1) {
				throw new IllegalArgumentException("invalid judgment commitment size");
			}
			this.judgmentsBytes = judgmentsBytes;
		}

		String evaluationProtocolId() {
			return evaluationProtocolId;
		}

		String bundleProtocolId() {
			return bundleProtocolId;
		}

		String bundleId() {
			return bundleId;
		}

		String corpusId() {
			return corpusId;
		}

		String policyId() {
			return policyId;
		}

		String policySha256() {
			return policySha256;
		}

		String manifestSha256() {
			return manifestSha256;
		}

		long manifestBytes() {
			return manifestBytes;
		}

		String corpusSha256() {
			return corpusSha256;
		}

		long corpusBytes() {
			return corpusBytes;
		}

		String judgmentsSha256() {
			return judgmentsSha256;
		}

		long judgmentsBytes() {
			return judgmentsBytes;
		}

		boolean authorizes(VerifiedCorpus verifiedCorpus) {
			return verifiedCorpus != null
					&& verifiedCorpus.completionAuthority == corpusAuthority;
		}
	}

	static final class CompletedRanking {

		private final VerifiedCorpus verifiedCorpus;
		private final Object completionAuthority;
		private final RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot;

		private CompletedRanking(
				VerifiedCorpus verifiedCorpus,
				Object completionAuthority,
				RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot) {
			this.verifiedCorpus = Objects.requireNonNull(verifiedCorpus);
			this.completionAuthority = Objects.requireNonNull(completionAuthority);
			this.rankingSnapshot = Objects.requireNonNull(rankingSnapshot);
		}

		private boolean isAuthorized() {
			return verifiedCorpus.completionAuthority == completionAuthority;
		}

		RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot() {
			return rankingSnapshot;
		}
	}

	/**
	 * Opaque post-ranking capability that binds the exact sealed ranking to the
	 * revalidated corpus, judgments, and frozen policy used for scoring.
	 */
	static final class VerifiedScoringInputs {

		private final CompletedRanking completedRanking;
		private final Object scoringAuthority;
		private final RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot;
		private final RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy;
		private final RelatedTopicReuseHoldoutBundle bundle;

		private VerifiedScoringInputs(
				CompletedRanking completedRanking,
				Object scoringAuthority,
				RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot,
				RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy,
				RelatedTopicReuseHoldoutBundle bundle) {
			this.completedRanking = Objects.requireNonNull(completedRanking);
			this.scoringAuthority = Objects.requireNonNull(scoringAuthority);
			this.rankingSnapshot = Objects.requireNonNull(rankingSnapshot);
			this.boundPolicy = Objects.requireNonNull(boundPolicy);
			this.bundle = Objects.requireNonNull(bundle);
		}

		boolean isAuthorized() {
			return completedRanking.isAuthorized()
					&& completedRanking.completionAuthority == scoringAuthority
					&& completedRanking.rankingSnapshot == rankingSnapshot;
		}

		RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot() {
			return rankingSnapshot;
		}

		RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy() {
			return boundPolicy;
		}

		RelatedTopicReuseHoldoutBundle bundle() {
			return bundle;
		}
	}

	record RankingCorpus(
			String protocolId,
			String bundleId,
			String corpusId,
			String policyId,
			String policySha256,
			String corpusSha256,
			Corpus corpus) {

		RankingCorpus {
			protocolId = Objects.requireNonNull(protocolId, "protocolId");
			bundleId = Objects.requireNonNull(bundleId, "bundleId");
			corpusId = Objects.requireNonNull(corpusId, "corpusId");
			policyId = Objects.requireNonNull(policyId, "policyId");
			policySha256 = Objects.requireNonNull(policySha256, "policySha256");
			corpusSha256 = Objects.requireNonNull(corpusSha256, "corpusSha256");
			corpus = Objects.requireNonNull(corpus, "corpus");
		}
	}

	private record Manifest(
			String protocolId,
			String bundleId,
			String policyId,
			String policySha256,
			String corpusId,
			long payloadBytes,
			List<ManifestFile> files) {

		private Manifest {
			files = List.copyOf(files);
		}
	}

	private record ManifestFile(String filename, long bytes, String sha256) {
	}

	record Corpus(
			String corpusId, List<Lineage> lineages, List<Candidate> candidates, List<Query> queries) {

		Corpus {
			lineages = List.copyOf(lineages);
			candidates = List.copyOf(candidates);
			queries = List.copyOf(queries);
		}
	}

	record Judgments(String corpusId, List<QueryJudgments> queries) {

		Judgments {
			queries = List.copyOf(queries);
		}
	}

	record Lineage(String key, LineageKind kind) implements Keyed {
	}

	record Candidate(
			String key,
			String lineageKey,
			String title,
			String abstractText,
			String venueName,
			Integer publicationYear,
			DocumentType documentType,
			String language,
			Integer citationCount,
			boolean reportedOpenAccess,
			List<String> authors) implements Keyed {

		Candidate {
			authors = List.copyOf(authors);
		}
	}

	record Query(String key, String text, QueryKind kind, int cutoff, Filter filters)
			implements Keyed {
	}

	record Filter(
			Integer yearFrom,
			Integer yearTo,
			List<DocumentType> documentTypes,
			boolean openAccessOnly,
			int minimumCitations,
			List<String> languages) {

		Filter {
			documentTypes = List.copyOf(documentTypes);
			languages = List.copyOf(languages);
		}

		boolean exercisesEveryDimension() {
			return yearFrom != null
					&& yearTo != null
					&& !documentTypes.isEmpty()
					&& openAccessOnly
					&& minimumCitations > 0
					&& !languages.isEmpty();
		}

		boolean isEmpty() {
			return yearFrom == null
					&& yearTo == null
					&& documentTypes.isEmpty()
					&& !openAccessOnly
					&& minimumCitations == 0
					&& languages.isEmpty();
		}
	}

	record QueryJudgments(
			String queryKey, Map<String, Integer> grades, List<Adversary> adversaries)
			implements Keyed {

		QueryJudgments {
			grades = Map.copyOf(grades);
			adversaries = List.copyOf(adversaries);
		}

		@Override
		public String key() {
			return queryKey;
		}
	}

	record Adversary(String candidateKey, AdversaryKind kind, String reason) {
	}

	enum LineageKind {
		TARGET_OWNER_SEARCH,
		TARGET_OWNER_COLLECTION,
		OTHER_OWNER_SEARCH,
		OTHER_OWNER_COLLECTION,
		CATALOG_ONLY;

		boolean targetVisible() {
			return this == TARGET_OWNER_SEARCH || this == TARGET_OWNER_COLLECTION;
		}

		boolean otherOwner() {
			return this == OTHER_OWNER_SEARCH || this == OTHER_OWNER_COLLECTION;
		}
	}

	enum QueryKind {
		LEXICAL_BRIDGE_OPPORTUNITY,
		FILTERED_LEXICAL_BRIDGE_OPPORTUNITY,
		AUTHOR_NO_RELATED_SIGNAL_CONTROL,
		NO_SEED_FALLBACK_CONTROL;

		boolean opportunity() {
			return this == LEXICAL_BRIDGE_OPPORTUNITY
					|| this == FILTERED_LEXICAL_BRIDGE_OPPORTUNITY;
		}
	}

	enum AdversaryKind {
		OWNER_VISIBLE_TOPIC_DRIFT,
		OTHER_OWNER_TOPIC_MATCH,
		CATALOG_ONLY_TOPIC_MATCH,
		FILTER_VIOLATION,
		AUTHOR_SUBSTRING_COLLISION
	}

	private enum FilterDimension {
		YEAR_FROM,
		YEAR_TO,
		DOCUMENT_TYPE,
		OPEN_ACCESS,
		MINIMUM_CITATIONS,
		LANGUAGE
	}

	static final class VerificationException extends IOException {

		private VerificationException(String diagnostic) {
			super(diagnostic);
		}
	}

}
