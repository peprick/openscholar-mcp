package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RelatedTopicReuseHoldoutBundleTests {

	private static final String CORPUS_FILENAME = "holdout-corpus.json";
	private static final String JUDGMENTS_FILENAME = "judgments.json";
	private static final String MANIFEST_FILENAME = "manifest.json";
	private static final String TARGET_SEARCH = "target-owner-search";
	private static final String TARGET_COLLECTION = "target-owner-collection";
	private static final String OTHER_SEARCH = "other-owner-search";
	private static final String OTHER_COLLECTION = "other-owner-collection";
	private static final String CATALOG = "catalog-only";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@TempDir
	private Path temporaryDirectory;

	private Path repositoryRoot;
	private RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy;
	private RelatedTopicReuseEvaluationFixture.BoundFixture developmentFixture;
	private int bundleSequence;

	@BeforeEach
	void loadFrozenInputs() throws Exception {
		temporaryDirectory = temporaryDirectory.toRealPath();
		repositoryRoot = findRepositoryRoot();
		boundPolicy = RelatedTopicReuseHoldoutPolicy.loadFrozen(objectMapper);
		developmentFixture = RelatedTopicReuseEvaluationFixture.loadFrozen(objectMapper);
	}

	@Test
	void validExternalBundleLoadsExactContentsAndExposesOnlyImmutableValues() throws Exception {
		BundleFiles files = validBundle();

		RelatedTopicReuseHoldoutBundle bundle = verify(files);

		assertThat(bundle.bundleId()).isEqualTo(files.bundleId());
		assertThat(bundle.corpusId()).isEqualTo(files.corpusId());
		assertThat(bundle.manifestSha256())
				.isEqualTo(sha256(Files.readAllBytes(files.manifestFile())));
		assertThat(bundle.corpus().corpusId()).isEqualTo(files.corpusId());
		assertThat(bundle.corpus().lineages()).hasSize(5);
		assertThat(bundle.corpus().candidates()).hasSize(40);
		assertThat(bundle.corpus().queries()).hasSize(8);
		assertThat(bundle.judgments().queries()).hasSize(8)
				.allSatisfy(query -> assertThat(query.grades()).hasSize(30));
		assertThat(bundle.corpus().candidates().stream()
				.filter(candidate -> candidate.lineageKey().startsWith("target-owner")))
				.hasSize(30);

		assertThatThrownBy(() -> bundle.corpus().candidates().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> bundle.corpus().candidates().get(0).authors().add("Mutation"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> bundle.corpus().queries().get(3).filters().languages().add("fr"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> bundle.judgments().queries().get(0).grades()
				.put(candidateKey(1), 0))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> bundle.judgments().queries().get(0).adversaries().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void pathsMustBeAbsoluteExternalRealDirectories() throws Exception {
		BundleFiles files = validBundle();

		assertRejected(Path.of("relative-holdout"), "HOLDOUT_PATH_MUST_BE_ABSOLUTE");
		assertRejected(repositoryRoot, "HOLDOUT_DIRECTORY_NOT_EXTERNAL");
		assertRejected(repositoryRoot.getParent(), "HOLDOUT_DIRECTORY_NOT_EXTERNAL");

		Path link = temporaryDirectory.resolve("holdout-directory-link");
		Files.createSymbolicLink(link, files.directory());
		assertRejected(link, "HOLDOUT_DIRECTORY_INVALID");

		Path ancestorLink = temporaryDirectory.resolve("holdout-ancestor-link");
		Files.createSymbolicLink(ancestorLink, temporaryDirectory);
		assertRejected(
				ancestorLink.resolve(files.bundleId()), "HOLDOUT_DIRECTORY_SYMLINKED");
	}

	@Test
	void exactLayoutRejectsExtraMissingAndSymlinkedFiles() throws Exception {
		BundleFiles extra = validBundle();
		Files.writeString(extra.directory().resolve("notes.txt"), "not part of the bundle");
		assertRejected(extra, "HOLDOUT_LAYOUT_INVALID");

		BundleFiles missing = validBundle();
		Files.delete(missing.judgmentsFile());
		assertRejected(missing, "HOLDOUT_LAYOUT_INVALID");

		BundleFiles linked = validBundle();
		Path externalJudgments = temporaryDirectory.resolve("external-judgments.json");
		Files.copy(linked.judgmentsFile(), externalJudgments);
		Files.delete(linked.judgmentsFile());
		Files.createSymbolicLink(linked.judgmentsFile(), externalJudgments);
		assertRejected(linked, "HOLDOUT_FILE_INVALID");
	}

	@Test
	void repositoryDiscoverySkipsNestedGitLookalikesWithoutProjectMarkers()
			throws Exception {
		Path syntheticRoot = temporaryDirectory.resolve("synthetic-worktree");
		Path nestedStart = syntheticRoot.resolve("backend/nested-run-directory");
		Files.createDirectories(syntheticRoot.resolve(".git"));
		Files.createDirectories(nestedStart.resolve(".git"));
		Files.createDirectories(syntheticRoot.resolve("frontend"));
		Files.writeString(syntheticRoot.resolve("backend/pom.xml"), "<project />");
		Files.writeString(syntheticRoot.resolve("frontend/package.json"), "{}");

		assertThat(RelatedTopicReuseHoldoutBundle.findRepositoryRoot(nestedStart))
				.isEqualTo(syntheticRoot.toRealPath());
	}

	@Test
	void strictJsonRejectsDuplicatesTrailingTokensUnknownFieldsAndOracleFields()
			throws Exception {
		BundleFiles duplicate = validBundle();
		String duplicateCorpus = Files.readString(duplicate.corpusFile())
				.replaceFirst("\\\"schemaVersion\\\":1", "\\\"schemaVersion\\\":1,\\\"schemaVersion\\\":1");
		duplicate.replacePayload(CORPUS_FILENAME, duplicateCorpus.getBytes(StandardCharsets.UTF_8));
		assertRejected(duplicate, "HOLDOUT_CORPUS_JSON_INVALID");

		BundleFiles trailing = validBundle();
		byte[] trailingBytes = (Files.readString(trailing.judgmentsFile()) + "\n{}").getBytes(
				StandardCharsets.UTF_8);
		trailing.replacePayload(JUDGMENTS_FILENAME, trailingBytes);
		assertRejected(trailing, "HOLDOUT_JUDGMENTS_JSON_INVALID");

		BundleFiles unknown = validBundle();
		unknown.manifest().put("unexpected", true);
		unknown.writeManifest();
		assertRejected(unknown, "HOLDOUT_SCHEMA_INVALID_AT_$");

		BundleFiles oracle = validBundle();
		candidate(oracle.corpus(), 0).put("relatedScore", 0.99d);
		oracle.writePayloadsAndManifest();
		assertRejected(oracle, "HOLDOUT_SCHEMA_INVALID_AT_$.candidates[0]");

		BundleFiles outputOracle = validBundle();
		queryJudgments(outputOracle.judgments(), 0).put("candidateRanking", "candidate-output");
		outputOracle.writePayloadsAndManifest();
		assertRejected(outputOracle, "HOLDOUT_SCHEMA_INVALID_AT_$.queries[0]");

		BundleFiles comments = validBundle();
		byte[] commentedCorpus = Files.readString(comments.corpusFile())
				.replaceFirst("\\{", "{/* caller-enabled comment */")
				.getBytes(StandardCharsets.UTF_8);
		comments.replacePayload(CORPUS_FILENAME, commentedCorpus);
		ObjectMapper permissiveMapper = JsonMapper.builder()
				.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
				.build();
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verify(
				permissiveMapper, comments.directory()))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_CORPUS_JSON_INVALID");

		BundleFiles singleQuotes = validBundle();
		byte[] singleQuotedCorpus = Files.readString(singleQuotes.corpusFile())
				.replaceFirst("\"schemaVersion\"", "'schemaVersion'")
				.getBytes(StandardCharsets.UTF_8);
		singleQuotes.replacePayload(CORPUS_FILENAME, singleQuotedCorpus);
		ObjectMapper singleQuoteMapper = JsonMapper.builder()
				.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
				.build();
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verify(
				singleQuoteMapper, singleQuotes.directory()))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_CORPUS_JSON_INVALID");

		BundleFiles trailingComma = validBundle();
		byte[] trailingCommaCorpus = Files.readString(trailingComma.corpusFile())
				.replaceFirst("\\}$", ",}")
				.getBytes(StandardCharsets.UTF_8);
		trailingComma.replacePayload(CORPUS_FILENAME, trailingCommaCorpus);
		ObjectMapper trailingCommaMapper = JsonMapper.builder()
				.enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
				.build();
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verify(
				trailingCommaMapper, trailingComma.directory()))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_CORPUS_JSON_INVALID");
	}

	@Test
	void manifestAndPayloadSizeDigestIdentityAndDeclarationDriftFailClosed()
			throws Exception {
		BundleFiles declaredSize = validBundle();
		declaredSize.manifest().put(
				"payloadBytes", declaredSize.manifest().required("payloadBytes").longValue() + 1);
		declaredSize.writeManifest();
		assertRejected(declaredSize, "HOLDOUT_MANIFEST_SIZE_INVALID");

		BundleFiles oversized = validBundle();
		oversized.replacePayloadWithoutManifest(
				CORPUS_FILENAME,
				new byte[boundPolicy.policy().bundle().maximumCorpusBytes() + 1]);
		assertRejected(oversized, "HOLDOUT_BUNDLE_TOO_LARGE");

		BundleFiles digest = validBundle();
		manifestFile(digest.manifest(), 0).put("sha256", "0".repeat(64));
		digest.writeManifest();
		assertRejected(digest, "HOLDOUT_PAYLOAD_DIGEST_MISMATCH");

		BundleFiles identity = validBundle();
		identity.corpus().put("corpusId", "different-corpus-identity");
		identity.writePayloadsAndManifest();
		assertRejected(identity, "HOLDOUT_DOCUMENT_IDENTITY_INVALID");

		BundleFiles policyIdentity = validBundle();
		policyIdentity.manifest().put("policySha256", "0".repeat(64));
		policyIdentity.writeManifest();
		assertRejected(policyIdentity, "HOLDOUT_MANIFEST_IDENTITY_INVALID");

		BundleFiles declarations = validBundle();
		((ObjectNode) declarations.manifest().required("declarations"))
				.put("firstRunRule", "A_DIFFERENT_FIRST_RUN_RULE");
		declarations.writeManifest();
		assertRejected(declarations, "HOLDOUT_DECLARATIONS_INVALID");
	}

	@Test
	void everyQueryMustGradeEveryTargetCandidateWithinTheFrozenRange() throws Exception {
		BundleFiles missingGrade = validBundle();
		grades(missingGrade.judgments(), 0).remove(candidateKey(30));
		missingGrade.writePayloadsAndManifest();
		assertRejected(missingGrade, "HOLDOUT_TARGET_JUDGMENTS_INCOMPLETE");

		BundleFiles extraGrade = validBundle();
		grades(extraGrade.judgments(), 0).put(candidateKey(31), 0);
		extraGrade.writePayloadsAndManifest();
		assertRejected(extraGrade, "HOLDOUT_TARGET_JUDGMENTS_INCOMPLETE");

		BundleFiles outOfRange = validBundle();
		grades(outOfRange.judgments(), 0).put(candidateKey(1), 4);
		outOfRange.writePayloadsAndManifest();
		assertRejected(outOfRange, "HOLDOUT_GRADE_OUT_OF_RANGE");
	}

	@Test
	void lineageQueryAndAdversaryKindsAndEveryFilterDimensionAreRequired()
			throws Exception {
		BundleFiles unusedLineage = validBundle();
		unusedLineage.corpus().withArray("lineages").addObject()
				.put("key", "unused-target-lineage")
				.put("kind", "TARGET_OWNER_SEARCH");
		unusedLineage.writePayloadsAndManifest();
		assertRejected(unusedLineage, "HOLDOUT_UNUSED_LINEAGE_INVALID");

		BundleFiles missingLineageKind = validBundle();
		lineage(missingLineageKind.corpus(), 3).put("kind", "OTHER_OWNER_SEARCH");
		missingLineageKind.writePayloadsAndManifest();
		assertRejected(missingLineageKind, "HOLDOUT_REQUIRED_LINEAGE_KIND_MISSING");

		BundleFiles missingQueryKind = validBundle();
		query(missingQueryKind.corpus(), 7).put("kind", "AUTHOR_NO_RELATED_SIGNAL_CONTROL");
		grades(missingQueryKind.judgments(), 7).put(candidateKey(1), 3);
		ArrayNode noSeedAdversaries = adversaries(missingQueryKind.judgments(), 7);
		noSeedAdversaries.removeAll();
		addAdversary(
				noSeedAdversaries,
				candidateKey(3),
				"AUTHOR_SUBSTRING_COLLISION",
				"Independent author substring collision annotation.");
		missingQueryKind.writePayloadsAndManifest();
		assertRejected(missingQueryKind, "HOLDOUT_QUERY_KIND_SHAPE_INVALID");

		BundleFiles missingAdversaryKind = validBundle();
		adversaries(missingAdversaryKind.judgments(), 0).remove(2);
		missingAdversaryKind.writePayloadsAndManifest();
		assertRejected(missingAdversaryKind, "HOLDOUT_ADVERSARY_KIND_MISSING");

		BundleFiles missingFilterDimension = validBundle();
		adversaries(missingFilterDimension.judgments(), 3).remove(5);
		missingFilterDimension.writePayloadsAndManifest();
		assertRejected(missingFilterDimension, "HOLDOUT_FILTER_DIMENSION_COVERAGE_INVALID");

		BundleFiles nonIsolatedFilterDimension = validBundle();
		candidate(nonIsolatedFilterDimension.corpus(), 9).put("reportedOpenAccess", false);
		nonIsolatedFilterDimension.writePayloadsAndManifest();
		assertRejected(
				nonIsolatedFilterDimension, "HOLDOUT_FILTER_ADVERSARY_NOT_ISOLATED");
	}

	@Test
	void candidateQueryKeyQueryTextAndTitleMustBeDisjointFromDevelopment()
			throws Exception {
		var development = developmentFixture.fixture();

		BundleFiles candidateKeyOverlap = validBundle();
		String oldCandidateKey = candidateKey(30);
		String developmentCandidateKey = development.candidates().get(0).key();
		candidate(candidateKeyOverlap.corpus(), 29).put("key", developmentCandidateKey);
		for (int index = 0; index < 8; index++) {
			ObjectNode queryGrades = grades(candidateKeyOverlap.judgments(), index);
			int grade = queryGrades.remove(oldCandidateKey).intValue();
			queryGrades.put(developmentCandidateKey, grade);
		}
		candidateKeyOverlap.writePayloadsAndManifest();
		assertRejected(candidateKeyOverlap, "HOLDOUT_CANDIDATE_KEY_OVERLAP");

		BundleFiles queryKeyOverlap = validBundle();
		String developmentQueryKey = development.queries().get(0).key();
		query(queryKeyOverlap.corpus(), 0).put("key", developmentQueryKey);
		queryJudgments(queryKeyOverlap.judgments(), 0).put("queryKey", developmentQueryKey);
		queryKeyOverlap.writePayloadsAndManifest();
		assertRejected(queryKeyOverlap, "HOLDOUT_QUERY_KEY_OVERLAP");

		BundleFiles queryTextOverlap = validBundle();
		query(queryTextOverlap.corpus(), 0).put("text", development.queries().get(0).text());
		queryTextOverlap.writePayloadsAndManifest();
		assertRejected(queryTextOverlap, "HOLDOUT_QUERY_TEXT_OVERLAP");

		BundleFiles titleOverlap = validBundle();
		candidate(titleOverlap.corpus(), 0).put("title", development.candidates().get(0).title());
		titleOverlap.writePayloadsAndManifest();
		assertRejected(titleOverlap, "HOLDOUT_TITLE_OVERLAP");
	}

	private BundleFiles validBundle() throws Exception {
		int sequence = ++bundleSequence;
		String bundleId = "external-holdout-bundle-" + sequence;
		String corpusId = "external-holdout-corpus-" + sequence;
		Path directory = temporaryDirectory.resolve(bundleId);
		Files.createDirectory(directory);
		BundleFiles files = new BundleFiles(
				directory,
				bundleId,
				corpusId,
				buildCorpus(bundleId, corpusId),
				buildJudgments(bundleId, corpusId));
		files.writePayloadsAndManifest();
		return files;
	}

	private ObjectNode buildCorpus(String bundleId, String corpusId) {
		ObjectNode root = identity(bundleId, corpusId);
		root.put("split", String.valueOf(boundPolicy.policy().corpus().split()));
		root.put("labelUnit", String.valueOf(boundPolicy.policy().labelUnit()));
		root.put("sourcePolicy", String.valueOf(boundPolicy.policy().sourcePolicy()));

		ArrayNode lineages = root.putArray("lineages");
		addLineage(lineages, TARGET_SEARCH, "TARGET_OWNER_SEARCH");
		addLineage(lineages, TARGET_COLLECTION, "TARGET_OWNER_COLLECTION");
		addLineage(lineages, OTHER_SEARCH, "OTHER_OWNER_SEARCH");
		addLineage(lineages, OTHER_COLLECTION, "OTHER_OWNER_COLLECTION");
		addLineage(lineages, CATALOG, "CATALOG_ONLY");

		ArrayNode candidates = root.putArray("candidates");
		for (int index = 1; index <= 40; index++) {
			addCandidate(candidates, index);
		}

		ArrayNode queries = root.putArray("queries");
		for (int index = 1; index <= 8; index++) {
			addQuery(queries, index);
		}
		return root;
	}

	private ObjectNode buildJudgments(String bundleId, String corpusId) {
		ObjectNode root = identity(bundleId, corpusId);
		root.put("labelUnit", String.valueOf(boundPolicy.policy().labelUnit()));
		ArrayNode queries = root.putArray("queries");
		for (int index = 1; index <= 8; index++) {
			ObjectNode query = queries.addObject();
			query.put("queryKey", queryKey(index));
			ObjectNode grades = query.putObject("grades");
			for (int candidate = 1; candidate <= 30; candidate++) {
				grades.put(candidateKey(candidate), grade(index, candidate));
			}
			ArrayNode adversaries = query.putArray("adversaries");
			addQueryAdversaries(adversaries, index);
		}
		return root;
	}

	private ObjectNode identity(String bundleId, String corpusId) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("schemaVersion", 1);
		root.put("protocolId", boundPolicy.policy().bundle().protocolId());
		root.put("bundleId", bundleId);
		root.put("policyId", boundPolicy.policy().policyId());
		root.put("policySha256", boundPolicy.sha256());
		root.put("corpusId", corpusId);
		return root;
	}

	private static void addLineage(ArrayNode lineages, String key, String kind) {
		lineages.addObject().put("key", key).put("kind", kind);
	}

	private static void addCandidate(ArrayNode candidates, int index) {
		ObjectNode candidate = candidates.addObject();
		candidate.put("key", candidateKey(index));
		candidate.put("lineageKey", lineageKey(index));
		candidate.put("title", "Independent Holdout Metadata Study " + index);
		candidate.put("abstractText", "Synthetic external metadata abstract number " + index + ".");
		candidate.put("venueName", "External Review Venue");
		candidate.put("publicationYear", publicationYear(index));
		candidate.put("documentType", index == 12 ? "PREPRINT" : "ARTICLE");
		candidate.put("language", index == 15 ? "fr" : "en");
		candidate.put("citationCount", index == 14 ? 1 : 50);
		candidate.put("reportedOpenAccess", index != 13);
		candidate.putArray("authors").add("External Author " + index);
	}

	private void addQuery(ArrayNode queries, int index) {
		ObjectNode query = queries.addObject();
		query.put("key", queryKey(index));
		query.put("text", "Independent external holdout research topic " + index);
		query.put("kind", queryKind(index));
		query.put("cutoff", boundPolicy.policy().gates().cutoff());
		ObjectNode filters = query.putObject("filters");
		if (index == 4) {
			filters.put("yearFrom", 2015);
			filters.put("yearTo", 2025);
			filters.putArray("documentTypes").add("ARTICLE");
			filters.put("openAccessOnly", true);
			filters.put("minimumCitations", 20);
			filters.putArray("languages").add("en");
		}
		else {
			filters.putNull("yearFrom");
			filters.putNull("yearTo");
			filters.putArray("documentTypes");
			filters.put("openAccessOnly", false);
			filters.put("minimumCitations", 0);
			filters.putArray("languages");
		}
	}

	private static int grade(int queryIndex, int candidateIndex) {
		if (queryIndex <= 4) {
			return candidateIndex == 1 ? 3 : candidateIndex == 2 ? 2 : 0;
		}
		if (queryIndex <= 7) {
			return candidateIndex == 1 ? 3 : 0;
		}
		return 0;
	}

	private static void addQueryAdversaries(ArrayNode adversaries, int queryIndex) {
		if (queryIndex == 1) {
			addAdversary(
					adversaries,
					candidateKey(3),
					"OWNER_VISIBLE_TOPIC_DRIFT",
					"Target-visible metadata is a deliberate topic-drift negative.");
			addAdversary(
					adversaries,
					candidateKey(31),
					"OTHER_OWNER_TOPIC_MATCH",
					"Topical metadata belongs exclusively to another owner.");
			addAdversary(
					adversaries,
					candidateKey(36),
					"CATALOG_ONLY_TOPIC_MATCH",
					"Topical metadata has no owner-visible lineage.");
		}
		else if (queryIndex <= 3) {
			addAdversary(
					adversaries,
					candidateKey(3),
					"OWNER_VISIBLE_TOPIC_DRIFT",
					"Target-visible metadata is a deliberate topic-drift negative.");
		}
		else if (queryIndex == 4) {
			for (int candidate = 10; candidate <= 15; candidate++) {
				addAdversary(
						adversaries,
						candidateKey(candidate),
						"FILTER_VIOLATION",
						"Candidate deliberately violates one frozen filter dimension.");
			}
		}
		else if (queryIndex <= 7) {
			addAdversary(
					adversaries,
					candidateKey(3),
					"AUTHOR_SUBSTRING_COLLISION",
					"Author text is a deliberate substring-collision negative.");
		}
		else {
			addAdversary(
					adversaries,
					candidateKey(31),
					"OTHER_OWNER_TOPIC_MATCH",
					"Topical metadata belongs exclusively to another owner.");
		}
	}

	private static void addAdversary(
			ArrayNode adversaries, String candidateKey, String kind, String reason) {
		adversaries.addObject()
				.put("candidateKey", candidateKey)
				.put("kind", kind)
				.put("reason", reason);
	}

	private RelatedTopicReuseHoldoutBundle verify(BundleFiles files) throws Exception {
		return RelatedTopicReuseHoldoutBundle.verify(
				objectMapper, files.directory());
	}

	private void assertRejected(BundleFiles files, String diagnostic) {
		assertRejected(files.directory(), diagnostic);
	}

	private void assertRejected(Path sourceDirectory, String diagnostic) {
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verify(
				objectMapper, sourceDirectory))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage(diagnostic);
	}

	private static Path findRepositoryRoot() throws IOException {
		Path candidate = Path.of("").toAbsolutePath().normalize();
		while (candidate != null && !Files.exists(candidate.resolve(".git"))) {
			candidate = candidate.getParent();
		}
		if (candidate == null) {
			throw new IOException("repository root is unavailable");
		}
		return candidate.toRealPath();
	}

	private static ObjectNode lineage(ObjectNode corpus, int index) {
		return (ObjectNode) corpus.required("lineages").get(index);
	}

	private static ObjectNode candidate(ObjectNode corpus, int index) {
		return (ObjectNode) corpus.required("candidates").get(index);
	}

	private static ObjectNode query(ObjectNode corpus, int index) {
		return (ObjectNode) corpus.required("queries").get(index);
	}

	private static ObjectNode queryJudgments(ObjectNode judgments, int index) {
		return (ObjectNode) judgments.required("queries").get(index);
	}

	private static ObjectNode grades(ObjectNode judgments, int index) {
		return (ObjectNode) queryJudgments(judgments, index).required("grades");
	}

	private static ArrayNode adversaries(ObjectNode judgments, int index) {
		return (ArrayNode) queryJudgments(judgments, index).required("adversaries");
	}

	private static ObjectNode manifestFile(ObjectNode manifest, int index) {
		return (ObjectNode) manifest.required("files").get(index);
	}

	private static int publicationYear(int candidateIndex) {
		return candidateIndex == 10 ? 2010 : candidateIndex == 11 ? 2030 : 2020;
	}

	private static String lineageKey(int candidateIndex) {
		if (candidateIndex <= 15) {
			return TARGET_SEARCH;
		}
		if (candidateIndex <= 30) {
			return TARGET_COLLECTION;
		}
		if (candidateIndex <= 33) {
			return OTHER_SEARCH;
		}
		if (candidateIndex <= 35) {
			return OTHER_COLLECTION;
		}
		return CATALOG;
	}

	private static String queryKind(int queryIndex) {
		if (queryIndex <= 3) {
			return "LEXICAL_BRIDGE_OPPORTUNITY";
		}
		if (queryIndex == 4) {
			return "FILTERED_LEXICAL_BRIDGE_OPPORTUNITY";
		}
		if (queryIndex <= 7) {
			return "AUTHOR_NO_RELATED_SIGNAL_CONTROL";
		}
		return "NO_SEED_FALLBACK_CONTROL";
	}

	private static String candidateKey(int index) {
		return "holdout-candidate-" + index;
	}

	private static String queryKey(int index) {
		return "holdout-query-" + index;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private final class BundleFiles {

		private final Path directory;
		private final String bundleId;
		private final String corpusId;
		private final ObjectNode corpus;
		private final ObjectNode judgments;
		private ObjectNode manifest;

		private BundleFiles(
				Path directory,
				String bundleId,
				String corpusId,
				ObjectNode corpus,
				ObjectNode judgments) {
			this.directory = directory;
			this.bundleId = bundleId;
			this.corpusId = corpusId;
			this.corpus = corpus;
			this.judgments = judgments;
		}

		private void writePayloadsAndManifest() throws IOException {
			Files.write(corpusFile(), objectMapper.writeValueAsBytes(corpus));
			Files.write(judgmentsFile(), objectMapper.writeValueAsBytes(judgments));
			rebuildManifestFromPayloads();
		}

		private void replacePayload(String filename, byte[] bytes) throws IOException {
			Files.write(directory.resolve(filename), bytes);
			rebuildManifestFromPayloads();
		}

		private void replacePayloadWithoutManifest(String filename, byte[] bytes)
				throws IOException {
			Files.write(directory.resolve(filename), bytes);
		}

		private void rebuildManifestFromPayloads() throws IOException {
			byte[] corpusBytes = Files.readAllBytes(corpusFile());
			byte[] judgmentBytes = Files.readAllBytes(judgmentsFile());
			manifest = objectMapper.createObjectNode();
			manifest.put("schemaVersion", 1);
			manifest.put("protocolId", boundPolicy.policy().bundle().protocolId());
			manifest.put("bundleId", bundleId);
			manifest.put("policyId", boundPolicy.policy().policyId());
			manifest.put("policySha256", boundPolicy.sha256());
			manifest.put("corpusId", corpusId);
			manifest.put("payloadBytes", corpusBytes.length + judgmentBytes.length);
			ArrayNode files = manifest.putArray("files");
			addManifestFile(files, CORPUS_FILENAME, corpusBytes);
			addManifestFile(files, JUDGMENTS_FILENAME, judgmentBytes);
			addDeclarations(manifest.putObject("declarations"));
			writeManifest();
		}

		private void addDeclarations(ObjectNode declarations) {
			var required = boundPolicy.policy().requiredDeclarations();
			declarations.put("corpusAuthorship", required.corpusAuthorship());
			declarations.put("judgmentAuthorship", required.judgmentAuthorship());
			declarations.put("firstRunRule", required.firstRunRule());
			declarations.put("noRetuningRule", required.noRetuningRule());
			declarations.put("externalCustodyRule", required.externalCustodyRule());
			declarations.put("evaluatorFreezeRule", required.evaluatorFreezeRule());
			ArrayNode limitations = declarations.putArray("limitations");
			required.requiredLimitations().forEach(limitations::add);
		}

		private void writeManifest() throws IOException {
			Files.write(manifestFile(), objectMapper.writeValueAsBytes(manifest));
		}

		private Path directory() {
			return directory;
		}

		private String bundleId() {
			return bundleId;
		}

		private String corpusId() {
			return corpusId;
		}

		private ObjectNode corpus() {
			return corpus;
		}

		private ObjectNode judgments() {
			return judgments;
		}

		private ObjectNode manifest() {
			return manifest;
		}

		private Path corpusFile() {
			return directory.resolve(CORPUS_FILENAME);
		}

		private Path judgmentsFile() {
			return directory.resolve(JUDGMENTS_FILENAME);
		}

		private Path manifestFile() {
			return directory.resolve(MANIFEST_FILENAME);
		}
	}

	private static void addManifestFile(ArrayNode files, String filename, byte[] bytes) {
		files.addObject()
				.put("filename", filename)
				.put("bytes", bytes.length)
				.put("sha256", sha256(bytes));
	}

}
